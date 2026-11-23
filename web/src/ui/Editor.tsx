import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { BlurRenderer, ellipseFor, type Ellipse } from '../lib/renderer'
import { DEFAULT_LOOK, frameAt, trackBox, type Analysis, type BlurMode, type Look, type Track } from '../lib/types'
import type { ExportJob } from './Exporting'
import { AddIcon, BackIcon, CloseIcon, CutIcon, FaceIcon, PictureIcon, MarkIcon, PauseIcon, PlayIcon, RedoIcon, SaveIcon, SoundIcon, SubtitlesIcon, UndoIcon } from './icons'
import { FRAME, Timeline } from './Timeline'
import { BlurBar } from './BlurBar'
import { CutTab, wordSpan } from './CutTab'
import { addCuts, CutMap, cutTotal, type Range } from '../lib/cutmap'
import { isFiller } from '../lib/silence'
import { applyFollow, boxNear, newTrack, nextTrackId, retime, setEnd, setStart, shift, split, type Box } from '../lib/tracks'
import type { FollowMessage, FollowRequest } from '../workers/follow.worker'
import { SfxScheduler, type SfxClip } from '../lib/sfx'
import { drawWatermark, watermarkOn, type Watermark } from '../lib/watermark'
import { CleanupCard, SoundTab, SubtitlesTab, WatermarkTab, loadWatermark } from './EditorTabs'
import { ExportSheet } from './ExportSheet'
import { BackgroundCard, FrameCard, StabilizeCard, type PictureBusy } from './PictureTab'
import { maskAt, maskIndex, NO_BACKGROUND, type Background, type PeopleMasks } from '../lib/background'
import { ASPECT_RATIO, cropFraction, reframePath, steady, viewRect } from '../lib/camera'
import type { PictureMessage, PictureRequest } from '../workers/picture.worker'
import { createProject, saveProject, type ProjectPicture, type ProjectState } from '../lib/projects'
import { reserveCueIds } from '../lib/subtitles'
import { reserveClipIds } from './EditorTabs'
import { cleanupOn, type Cleanup } from '../lib/cleanup'
import { loadExportOptions, saveExportOptions } from '../lib/exportopts'
import { Analyzing } from './Analyzing'
import { bleepRanges, cueAt, DEFAULT_SUB_STYLE, drawSubtitle, maskCue, shownText, type Cue, type SubStyle, type Word } from '../lib/subtitles'
import { loadSfx, makeBuffer, SFX_NAMES } from '../lib/sfx'
import type { VoiceFx } from '../lib/voice'
import type { AudioMessage, AudioRequest } from '../workers/audio.worker'

type Props = {
  file: File
  analysis: Analysis
  thumbs: (string | null)[]
  info: string
  onExport: (job: ExportJob) => void
  onClose: () => void
  initialTab?: EditorTab
  hidden?: boolean
  /** A project opened again (its id and what was saved), or none: a new one is made. */
  project?: { id: string; state: ProjectState | null; picture: ProjectPicture | null }
}

function loadLook(): Look {
  try {
    const l = JSON.parse(localStorage.getItem('onit.look') ?? 'null')
    if (l && ['blur', 'pixelate', 'solid'].includes(l.mode)) return { ...DEFAULT_LOOK, ...l }
  } catch {}
  return DEFAULT_LOOK
}

export type EditorTab = 'faces' | 'edit' | 'picture' | 'subs' | 'sound' | 'mark'

/** The groups of tools, as in the app: each in its color, keys 1 to 6. */
const GROUPS: [EditorTab, string, (p: { size?: number }) => any, string][] = [
  ['faces', 'פרטיות', FaceIcon, 'var(--coral)'],
  ['edit', 'עריכה', CutIcon, 'var(--primary)'],
  ['picture', 'תמונה', PictureIcon, 'var(--violet)'],
  ['subs', 'כתוביות', SubtitlesIcon, 'var(--amber)'],
  ['sound', 'סאונד', SoundIcon, 'var(--cyan)'],
  ['mark', 'מיתוג', MarkIcon, 'var(--primary)'],
]

/** What undo and redo move between: the blurs and the ones turned off, the sounds, the subtitles, the cuts. */
type Snap = { off: Set<number>; sfx: SfxClip[]; cues: Cue[]; tracks: Track[]; cuts: Range[] }

/** A key for frame [k]'s mask of [m] (a new pass gives new keys): the renderer uploads a mask once. */
const maskGens = new WeakMap<PeopleMasks, number>()
let maskGen = 0
function maskKey(m: PeopleMasks, k: number): string {
  let g = maskGens.get(m)
  if (g === undefined) { g = ++maskGen; maskGens.set(m, g) }
  return g + ':' + k
}

/** Width of a new blur when there's no face under the tap, in frame widths (as in the app). */
const NEW_BLUR = 0.12

export function Editor({ file, analysis, thumbs: thumbs0, info, onExport, onClose, initialTab = 'faces', hidden = false, project }: Props) {
  // A project opened again starts as it was left.
  const saved = project?.state ?? null
  useMemo(() => {
    if (saved) { reserveCueIds(saved.cues); reserveClipIds(saved.sfx) }
  }, [])
  // Opened from a tool on the home screen, faces aren't found yet; they can be, from here.
  const [an, setAn] = useState(saved?.analysis ?? analysis)
  const [tracks, setTracks] = useState<Track[]>(saved?.tracks ?? analysis.tracks)
  const [thumbOf, setThumbOf] = useState<Map<number, string | null>>(() => new Map(saved?.thumbs ?? analysis.tracks.map((tr, k): [number, string | null] => [tr.id, thumbs0[k] ?? null])))
  const [detecting, setDetecting] = useState(false)
  const [detectInfo, setDetectInfo] = useState(saved?.detectInfo ?? info)
  // Faces searched for (a video opened from a tool has frame times only once a blur is added).
  const [analyzed, setAnalyzed] = useState(saved?.analyzed ?? analysis.times.length > 0)
  const { times, width: W, height: H } = an
  const [selected, setSelected] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)
  const [following, setFollowing] = useState<{ id: number; progress: number } | null>(null)
  const [cuts, setCuts] = useState<Range[]>(saved?.cuts ?? [])
  const [cutStart, setCutStart] = useState<number | null>(null)
  const [cutBusy, setCutBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  // The sound's cleanup: noise, a clearer voice, the networks' loudness (measured once).
  const [denoise, setDenoise] = useState(saved?.denoise ?? 0)
  const [enhance, setEnhance] = useState(saved?.enhance ?? false)
  const [loudOn, setLoudOn] = useState(saved?.loud ?? false)
  /** The gain to the networks' loudness, as measured on the cleaned sound (null until it is). */
  const [loudGain, setLoudGain] = useState<number | null>(null)
  const clean: Cleanup = useMemo(() => ({ denoise, enhance, loud: loudOn }), [denoise, enhance, loudOn])
  const [exportOpen, setExportOpen] = useState(false)
  const [exportOpts, setExportOpts] = useState(() => ({ ...loadExportOptions(), aspect: saved?.aspect ?? 'original' }))
  const [srcInfo, setSrcInfo] = useState<{ codec: string; fps: number } | null>(null)
  // The picture tools: the camera's motion, the cuts and the people (from one pass over the video).
  const [picture, setPicture] = useState<ProjectPicture | null>(project?.picture ?? null)
  const [picBusy, setPicBusy] = useState<PictureBusy>(null)
  const [background, setBackground] = useState<Background>(saved?.background ?? NO_BACKGROUND)
  const [steadyLevel, setSteadyLevel] = useState(saved?.steady ?? 0)
  const bgImage = useRef<HTMLImageElement | null>(null)
  const steadied = useMemo(() => (picture && steadyLevel > 0 ? steady(picture.motion, picture.cuts, H / W, an.fps, steadyLevel) : null), [picture, steadyLevel, an.fps])
  const crop = useMemo(() => cropFraction(W, H, ASPECT_RATIO[exportOpts.aspect]), [exportOpts.aspect])
  const shotCuts = an.cuts ?? picture?.cuts ?? []
  const framePath = useMemo(
    () => (exportOpts.aspect !== 'original' && times.length ? reframePath(times.length, tracks, shotCuts, an.fps, crop[0], crop[1]) : null),
    [exportOpts.aspect, times, tracks, shotCuts, an.fps, crop],
  )
  const cutMap = useMemo(() => new CutMap(cuts), [cuts])
  const url = useMemo(() => URL.createObjectURL(file), [file])
  useEffect(() => () => URL.revokeObjectURL(url), [url])
  const video = useRef<HTMLVideoElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const overlay = useRef<HTMLCanvasElement>(null)
  const rings = useRef<HTMLCanvasElement>(null)
  const renderer = useRef<BlurRenderer | null>(null)
  const [off, setOff] = useState<Set<number>>(() => new Set(saved?.off ?? []))
  const [look, setLook] = useState<Look>(() => saved?.look ?? loadLook())
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [dur, setDur] = useState(times.length ? times[times.length - 1] : 0)
  const [tab, setTab] = useState<EditorTab>(initialTab)
  const [cues, setCues] = useState<Cue[]>(saved?.cues ?? [])
  const [words, setWords] = useState<Word[]>(saved?.words ?? [])
  const [bleeped, setBleeped] = useState<Set<number>>(() => new Set(saved?.bleeped ?? []))
  const [tone, setTone] = useState(saved?.tone ?? true)
  const [voice, setVoice] = useState<VoiceFx>(saved?.voice ?? 'none')
  const [voiceAudio, setVoiceAudio] = useState<{ rate: number; data: Float32Array; buffer: AudioBuffer } | null>(null)
  const [voiceBusy, setVoiceBusy] = useState(false)
  const [selectedSfx, setSelectedSfx] = useState<number | null>(null)
  const [sfxLength, setSfxLength] = useState<Map<string, number>>(new Map())
  const [subStyle, setSubStyle] = useState<SubStyle>(() => {
    if (saved) return saved.subStyle
    try { return { ...DEFAULT_SUB_STYLE, ...JSON.parse(localStorage.getItem('onit.substyle') ?? '{}') } } catch { return DEFAULT_SUB_STYLE }
  })
  const [sfx, setSfx] = useState<SfxClip[]>(saved?.sfx ?? [])
  const [mark, setMark] = useState<Watermark>(() => saved?.mark ?? loadWatermark())
  const logo = useRef<HTMLImageElement | null>(null)
  const scheduler = useMemo(() => new SfxScheduler(), [])
  const state = useRef({ off, look, mediaTime: 0, sfx, mark, cues, subStyle, words, bleeped, voiceBuf: null as AudioBuffer | null, tracks, selected, cuts, cutMap, fps: an.fps, times, steadied, crop, framePath, masks: null as PeopleMasks | null, background })
  // (The drawing runs in callbacks made once: it reads the frame times from here, as they can
  // arrive later, from a search for faces or a first blur by hand.)
  state.current.times = times
  state.current.steadied = steadied
  state.current.crop = crop
  state.current.framePath = framePath
  state.current.masks = picture?.masks ?? null
  state.current.background = background
  state.current.tracks = tracks
  state.current.selected = selected
  state.current.cuts = cuts
  state.current.cutMap = cutMap
  state.current.fps = an.fps
  state.current.off = off
  state.current.look = look
  state.current.sfx = sfx
  state.current.mark = mark
  state.current.cues = cues
  state.current.subStyle = subStyle
  state.current.words = words
  state.current.bleeped = bleeped
  state.current.voiceBuf = voiceAudio?.buffer ?? null

  useEffect(() => {
    try { localStorage.setItem('onit.look', JSON.stringify(look)) } catch {}
  }, [look])

  const ellipseOf = (tr: Track, i: number): Ellipse | null => {
    const b = trackBox(tr, i)
    return b ? ellipseFor(b, W, H, state.current.look.padding * (tr.scale ?? 1)) : null
  }
  const facesAt = (i: number, wantOff: boolean): Ellipse[] => {
    const out: Ellipse[] = []
    for (const tr of state.current.tracks) {
      if (state.current.off.has(tr.id) !== wantOff) continue
      const e = ellipseOf(tr, i)
      if (e) out.push(e)
    }
    return out
  }

  const draw = () => {
    const v = video.current, c = canvas.current
    if (!v || !c || v.readyState < 2) return
    if (!renderer.current) {
      try { renderer.current = new BlurRenderer(c) } catch { return }
    }
    const i = frameAt(state.current.times, state.current.mediaTime)
    const r = renderer.current
    // The background (behind the people, or a screen's color).
    const bg = state.current.background
    r.setBackground(bg)
    const masks = state.current.masks
    if (bg.key === 'person' && masks) {
      const k = maskIndex(masks, i)
      r.setMask(masks.w, masks.h, k >= 0 ? maskAt(masks, k) : null, maskKey(masks, k))
    } else r.setMask(1, 1, null, null)
    r.setBackgroundImage(bg.fill === 'image' ? bgImage.current : null, bg.image)
    r.draw(v, W, H, facesAt(i, false), state.current.look)
    // The steadied picture (and the blurs' rings with it): moved and enlarged, as exported.
    const st = state.current.steadied
    const k = st ? Math.min(i, st.sx.length - 1) : 0
    const move = st ? `scale(${st.zoom}) translate(${(st.sx[k] * 100).toFixed(3)}%, ${(st.sy[k] * 100).toFixed(3)}%)` : ''
    c.style.transform = move
    const rg = rings.current
    if (rg) {
      rg.style.transform = move
      if (rg.width !== W || rg.height !== H) { rg.width = W; rg.height = H }
    }
    // Faces the user un-blurred: a dashed ring, so they can be tapped back on.
    const o = rings.current
    const top = overlay.current
    if (o && top) {
      if (top.width !== W || top.height !== H) { top.width = W; top.height = H }
      const ctx = o.getContext('2d')!
      ctx.clearRect(0, 0, W, H)
      ctx.setLineDash([W / 120, W / 160])
      ctx.lineWidth = Math.max(2, W / 400)
      ctx.strokeStyle = 'rgba(255,255,255,0.85)'
      for (const e of facesAt(i, true)) {
        ctx.beginPath()
        ctx.ellipse(e.cx, e.cy, e.rx, e.ry, 0, 0, Math.PI * 2)
        ctx.stroke()
      }
      ctx.setLineDash([])
      // The selected blur: a solid ring.
      const sel = state.current.tracks.find((tr) => tr.id === state.current.selected)
      const se = sel ? ellipseOf(sel, i) : null
      if (se) {
        ctx.lineWidth = Math.max(3, W / 260)
        ctx.strokeStyle = '#ffc53d'
        ctx.beginPath()
        ctx.ellipse(se.cx, se.cy, se.rx, se.ry, 0, 0, Math.PI * 2)
        ctx.stroke()
      }
      // Over the picture: the exported frame (when it's cut to a shape), and in it the watermark
      // and the subtitle, where they'll be.
      const tc = top.getContext('2d')!
      tc.clearRect(0, 0, W, H)
      const [cw, ch] = state.current.crop
      let fx = 0, fy = 0, fw = W, fh = H
      if (cw < 0.999 || ch < 0.999) {
        const fp = state.current.framePath
        const q = fp ? Math.min(i, fp.x.length - 1) : 0
        const [x, y, w, h] = viewRect(W, H, [cw, ch], fp ? [fp.x[q], fp.y[q]] : [0.5, 0.5], 0, 0, 1)
        fx = x; fy = y; fw = w; fh = h
        tc.fillStyle = 'rgba(0,0,0,0.6)'
        tc.fillRect(0, 0, W, H)
        tc.clearRect(fx, fy, fw, fh)
        tc.strokeStyle = 'rgba(255,255,255,0.9)'
        tc.lineWidth = Math.max(2, W / 500)
        tc.strokeRect(fx, fy, fw, fh)
      }
      tc.save()
      tc.translate(fx, fy)
      const m = state.current.mark
      if (watermarkOn(m)) drawWatermark(tc, m, fw, fh, m.image ? logo.current : null)
      const cue = cueAt(state.current.cues, state.current.mediaTime)
      if (cue) drawSubtitle(tc, shownText(cue, maskCue(cue, state.current.words, state.current.bleeped), state.current.subStyle.show), state.current.subStyle, fw, fh)
      tc.restore()
    }
  }

  // Draw every new frame while playing, and after each seek.
  useEffect(() => {
    const v = video.current
    if (!v) return
    let stop = false
    const hasVfc = 'requestVideoFrameCallback' in v
    // Playing, the cut stretches are jumped over.
    const skipCut = (at: number) => {
      const cm = state.current.cutMap
      if (cm.empty || v.paused) return
      const end = cm.cutEnd(at + 0.5 / (state.current.fps || 30))
      if (end > 0) v.currentTime = isFinite(v.duration) ? Math.min(end + 1e-3, v.duration) : end + 1e-3
    }
    const onFrame = (_: number, meta: { mediaTime: number }) => {
      if (stop) return
      skipCut(meta.mediaTime)
      state.current.mediaTime = meta.mediaTime
      draw()
      setTime(meta.mediaTime)
      ;(v as any).requestVideoFrameCallback(onFrame)
    }
    let raf = 0
    const loop = () => {
      if (stop) return
      skipCut(v.currentTime)
      state.current.mediaTime = v.currentTime
      draw()
      setTime(v.currentTime)
      raf = requestAnimationFrame(loop)
    }
    if (hasVfc) (v as any).requestVideoFrameCallback(onFrame)
    else raf = requestAnimationFrame(loop)
    const onSeeked = () => {
      state.current.mediaTime = v.currentTime
      draw()
      setTime(v.currentTime)
    }
    const onMeta = () => {
      if (isFinite(v.duration)) setDur(v.duration)
      onSeeked()
    }
    v.addEventListener('seeked', onSeeked)
    v.addEventListener('loadeddata', onMeta)
    // The sound effects play along, from wherever the video starts.
    const onPlay = () => {
      setPlaying(true)
      scheduler.start(state.current.sfx, v.currentTime, state.current.voiceBuf)
    }
    const onPause = () => {
      setPlaying(false)
      scheduler.stop()
    }
    const onSeeking = () => scheduler.stop()
    const onSeekedPlaying = () => { if (!v.paused) scheduler.start(state.current.sfx, v.currentTime, state.current.voiceBuf) }
    v.addEventListener('play', onPlay)
    v.addEventListener('pause', onPause)
    v.addEventListener('ended', onPause)
    v.addEventListener('seeking', onSeeking)
    v.addEventListener('seeked', onSeekedPlaying)
    return () => {
      stop = true
      scheduler.stop()
      v.removeEventListener('play', onPlay)
      v.removeEventListener('pause', onPause)
      v.removeEventListener('ended', onPause)
      v.removeEventListener('seeking', onSeeking)
      v.removeEventListener('seeked', onSeekedPlaying)
      cancelAnimationFrame(raf)
      v.removeEventListener('seeked', onSeeked)
      v.removeEventListener('loadeddata', onMeta)
    }
  }, [url])

  // Redraw the paused frame when the look, the faces or the watermark change.
  useEffect(() => {
    if (video.current?.paused) draw()
  }, [off, look, mark, cues, subStyle, tracks, selected, background, picture, steadied, framePath, crop])

  // The background's picture, loaded once for the preview.
  useEffect(() => {
    if (!background.image) { bgImage.current = null; return }
    const img = new Image()
    img.onload = () => { bgImage.current = img; if (video.current?.paused) draw() }
    img.src = background.image
  }, [background.image])

  // ---------------------------------------------------------------- the picture pass

  const pictureWorker = useRef<Worker | null>(null)
  useEffect(() => () => pictureWorker.current?.terminate(), [])
  /** One pass over the video: the camera's motion and the cuts, and ([people]) where the people are. */
  const runPicture = (people: boolean) => {
    pictureWorker.current?.terminate()
    video.current?.pause()
    const w = new Worker(new URL('../workers/picture.worker.ts', import.meta.url), { type: 'module' })
    pictureWorker.current = w
    setPicBusy({ what: people ? 'people' : 'motion', progress: 0 })
    const end = () => { w.terminate(); if (pictureWorker.current === w) pictureWorker.current = null; setPicBusy(null) }
    w.onmessage = (e: MessageEvent<PictureMessage>) => {
      const m = e.data
      if (m.type === 'progress') { setPicBusy((b) => (b ? { ...b, progress: m.progress } : b)); return }
      end()
      if (m.type === 'error') { setNotice(t('משהו השתבש') + ': ' + m.message); return }
      if (!state.current.times.length) setAn((a) => (a.times.length ? a : { ...a, times: m.times, fps: m.fps }))
      setPicture((p) => ({ motion: m.motion, cuts: m.cuts, masks: m.masks ?? (people ? null : p?.masks ?? null) }))
    }
    w.onerror = (e) => { end(); setNotice(t('משהו השתבש') + ': ' + (e.message || '')) }
    const forced = new URLSearchParams(location.search).get('backend')
    const req: PictureRequest = { file, times: state.current.times, people, model: new URL('models/people.onnx', document.baseURI).href, backend: forced === 'wasm' || forced === 'webgpu' ? forced : undefined }
    w.postMessage(req)
  }
  const cancelPicture = () => {
    pictureWorker.current?.terminate()
    pictureWorker.current = null
    setPicBusy(null)
  }
  const chooseSteady = (level: number) => {
    setSteadyLevel(level)
    if (level > 0 && !picture && !picBusy) runPicture(false)
  }

  // ---------------------------------------------------------------- the project, kept as it's edited

  const projectId = useRef<string | null>(project?.id ?? null)
  const savedPicture = useRef<ProjectPicture | null | undefined>(project ? project.picture : undefined)
  const storageFull = useRef(false)
  useEffect(() => {
    if (projectId.current) return
    createProject(file, { name: file.name.replace(/\.[^.]+$/, ''), duration: 0, blurs: tracks.length, cues: cues.length, thumb: null, detected: analyzed })
      .then((id) => { projectId.current = id; setProjectTick((n) => n + 1) })
      .catch(() => { storageFull.current = true; setNotice(t('אין מקום לשמור את הפרויקט בדפדפן. אפשר להמשיך לערוך ולייצא.')) })
  }, [])
  const [projectTick, setProjectTick] = useState(0)
  /** A small picture of the video as edited (faces blurred), for the projects' list. */
  const snapshot = (): string | null => {
    const c = canvas.current
    if (!c || !c.width) return null
    try {
      const small = document.createElement('canvas')
      small.width = 160
      small.height = Math.max(1, Math.round((160 * c.height) / c.width))
      small.getContext('2d')!.drawImage(c, 0, 0, small.width, small.height)
      return small.toDataURL('image/jpeg', 0.7)
    } catch {
      return null
    }
  }
  useEffect(() => {
    const id = projectId.current
    if (!id || storageFull.current || detecting) return
    const timer = setTimeout(() => {
      const state: ProjectState = {
        analysis: an, analyzed, detectInfo, tracks, thumbs: [...thumbOf.entries()], off: [...off], look, cues, words, bleeped: [...bleeped], tone, voice,
        sfx, mark, subStyle, cuts, denoise, enhance, loud: loudOn, background, steady: steadyLevel, aspect: exportOpts.aspect,
      }
      const pic = savedPicture.current !== picture ? picture : undefined
      savedPicture.current = picture
      saveProject(id, { duration: dur, blurs: tracks.length, cues: cues.length, thumb: snapshot(), detected: analyzed }, state, pic).catch(() => {
        storageFull.current = true
        setNotice(t('אין מקום לשמור את הפרויקט בדפדפן. אפשר להמשיך לערוך ולייצא.'))
      })
    }, 1200)
    return () => clearTimeout(timer)
  }, [projectTick, an, analyzed, tracks, thumbOf, off, look, cues, words, bleeped, tone, voice, sfx, mark, subStyle, cuts, denoise, enhance, loudOn, background, steadyLevel, exportOpts.aspect, picture, dur, detecting])

  // A short message over the video (what a tool did, or why it couldn't).
  useEffect(() => {
    if (!notice) return
    const id = setTimeout(() => setNotice(null), 4500)
    return () => clearTimeout(id)
  }, [notice])

  useEffect(() => {
    try { localStorage.setItem('onit.substyle', JSON.stringify(subStyle)) } catch {}
  }, [subStyle])

  // The logo, decoded once for the preview.
  useEffect(() => {
    if (!mark.image) { logo.current = null; return }
    const img = new Image()
    img.onload = () => { logo.current = img; if (video.current?.paused) draw() }
    img.src = mark.image
  }, [mark.image])

  // A changed sound list (or voice) is heard right away if the video is playing; with a changed
  // voice the video's own sound is muted and the processed one plays instead.
  useEffect(() => {
    const v = video.current
    if (!v) return
    v.muted = voiceAudio !== null
    if (!v.paused) scheduler.start(sfx, v.currentTime, voiceAudio?.buffer ?? null)
  }, [sfx, voiceAudio])

  // The voice change and the bleeps, processed in a worker (the sound is decoded once).
  const audioWorker = useMemo(() => new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' }), [file])
  useEffect(() => () => audioWorker.terminate(), [audioWorker])
  useEffect(() => {
    if (voice === 'none' && bleeped.size === 0 && !cleanupOn(clean)) {
      setVoiceAudio(null)
      setVoiceBusy(false)
      return
    }
    setVoiceBusy(true)
    const timer = setTimeout(() => {
      audioWorker.onmessage = (e: MessageEvent<AudioMessage>) => {
        const m = e.data
        setVoiceBusy(false)
        if (m.type === 'done') {
          setVoiceAudio({ rate: m.rate, data: m.data, buffer: makeBuffer(m.data, m.rate) })
          if (clean.loud) {
            setLoudGain(m.lufs === null ? null : m.gainDb)
            if (m.lufs === null) { setLoudOn(false); setNotice(t('לא נמצא דיבור למדידה')) }
            else if (loudGain === null) setNotice(t('העוצמה: %s ← %s', `\u2066${m.lufs.toFixed(1)} LUFS\u2069`, `\u2066${(m.lufs + m.gainDb).toFixed(1)} LUFS\u2069`))
          }
        } else {
          setVoiceAudio(null)
          if (m.type === 'error' && cleanupOn(clean)) setNotice(t('אין סאונד בסרטון'))
        }
      }
      const req: AudioRequest = { file, voice, bleeps: bleepRanges(words, bleeped), tone, clean }
      audioWorker.postMessage(req)
    }, 250)
    return () => clearTimeout(timer)
  }, [voice, bleeped, tone, words, clean])


  // Undo and redo. Quick changes in a row (typing, dragging a sound) are one step.
  const past = useRef<Snap[]>([])
  const future = useRef<Snap[]>([])
  const lastChange = useRef(0)
  const [, setHistoryVersion] = useState(0)
  const snap = (): Snap => ({ off: state.current.off, sfx: state.current.sfx, cues: state.current.cues, tracks: state.current.tracks, cuts: state.current.cuts })
  /** Before a change: a new undo step, or ([merge]) the same one as a change just before it. */
  const record = (merge = true) => {
    const now = performance.now()
    const burst = merge && now - lastChange.current < 800
    lastChange.current = merge ? now : 0
    future.current = []
    if (!burst) {
      past.current.push(snap())
      if (past.current.length > 100) past.current.shift()
    }
    setHistoryVersion((n) => n + 1)
  }
  const put = (s: Snap) => {
    setOff(s.off)
    setSfx(s.sfx)
    setCues(s.cues)
    setTracks(s.tracks)
    setCuts(s.cuts)
    if (s.tracks.every((tr) => tr.id !== state.current.selected)) setSelected(null)
    lastChange.current = 0
    setHistoryVersion((n) => n + 1)
  }
  const undo = () => {
    if (following) return
    const s = past.current.pop()
    if (!s) return
    future.current.push(snap())
    put(s)
  }
  const redo = () => {
    if (following) return
    const s = future.current.pop()
    if (!s) return
    past.current.push(snap())
    put(s)
  }
  const setOffH = (v: Set<number> | ((s: Set<number>) => Set<number>)) => { record(false); setOff(v) }
  const setSfxH = (v: SfxClip[] | ((s: SfxClip[]) => SfxClip[])) => { record(); setSfx(v) }
  const setCuesH = (v: Cue[], step = false) => { record(!step); setCues(v) }
  const setTracksH = (v: Track[] | ((s: Track[]) => Track[]), merge = false) => { record(merge); setTracks(v) }
  const setCutsH = (v: Range[]) => { record(false); setCuts(v) }
  const seek = (at: number) => { const v = video.current; if (v) v.currentTime = at }
  const frameNow = () => frameAt(state.current.times, state.current.mediaTime)
  const timelineControls = useRef<{ zoom?: (f: number) => void }>({}).current

  const toggle = (id: number) => {
    setOffH((s) => {
      const n = new Set(s)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })
  }

  const updateSfx = (id: number, patch: Partial<SfxClip>) => setSfxH((list) => list.map((c) => (c.id === id ? { ...c, ...patch } : c)).sort((a, b) => a.at - b.at))
  const selectedClip = sfx.find((c) => c.id === selectedSfx) ?? null

  // How long each placed sound is (for its block on the timeline).
  useEffect(() => {
    for (const c of sfx) {
      if (sfxLength.has(c.sound)) continue
      loadSfx(c.sound).then((b) => setSfxLength((m) => new Map(m).set(c.sound, b.duration))).catch(() => {})
    }
  }, [sfx])

  // ---------------------------------------------------------------- blurs by hand

  /** The blur under a point of the video (fractions), if any: the nearest center. */
  const blurAt = (fx: number, fy: number): number | null => {
    const x = fx * W, y = fy * H
    const i = frameAt(times, state.current.mediaTime)
    let hit: number | null = null, best = Infinity
    for (const tr of state.current.tracks) {
      const el = ellipseOf(tr, i)
      if (!el) continue
      const d = Math.hypot((x - el.cx) / el.rx, (y - el.cy) / el.ry)
      if (d < 1.1 && d < best) { best = d; hit = tr.id }
    }
    return hit
  }

  /** A square picture of [box] on the frame shown, for the blurs' list. */
  const grabThumb = (box: Box): string | null => {
    const v = video.current
    if (!v || v.readyState < 2 || !v.videoWidth) return null
    const c = document.createElement('canvas')
    c.width = c.height = 160
    const ctx = c.getContext('2d')!
    const vw = v.videoWidth, vh = v.videoHeight
    const r = Math.max(box[2] * vw, box[3] * vh) * 0.8
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, 160, 160)
    ctx.drawImage(v, box[0] * vw - r, box[1] * vh - r, 2 * r, 2 * r, 0, 0, 160, 160)
    try { return c.toDataURL('image/jpeg', 0.8) } catch { return null }
  }

  const followWorker = useRef<Worker | null>(null)
  useEffect(() => () => followWorker.current?.terminate(), [])
  const runFollow = (req: FollowRequest, onMsg: (m: FollowMessage) => void) => {
    followWorker.current?.terminate()
    const w = new Worker(new URL('../workers/follow.worker.ts', import.meta.url), { type: 'module' })
    followWorker.current = w
    const end = () => { w.terminate(); if (followWorker.current === w) followWorker.current = null }
    w.onmessage = (e: MessageEvent<FollowMessage>) => { if (e.data.type !== 'progress') end(); onMsg(e.data) }
    w.onerror = (e) => { end(); onMsg({ type: 'error', message: e.message || 'error' }) }
    w.postMessage(req)
  }

  /** Every frame's time (from the packets), for a video whose faces weren't searched yet. */
  const frameTimes = (): Promise<{ times: Float64Array; fps: number }> => {
    if (times.length) return Promise.resolve({ times, fps: an.fps })
    return new Promise((resolve, reject) => runFollow({ type: 'times', file }, (m) => {
      if (m.type === 'times') { setAn((a) => ({ ...a, times: m.times, fps: m.fps })); resolve(m) }
      else if (m.type === 'error') reject(new Error(m.message))
    }))
  }

  /** The source's codec and frame rate (and its frame times, if they weren't known), for the export's choices. */
  const probe = () => {
    const w = new Worker(new URL('../workers/follow.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<FollowMessage>) => {
      w.terminate()
      const m = e.data
      if (m.type !== 'times') return
      setSrcInfo({ codec: m.codec, fps: m.fps })
      if (!state.current.times.length) setAn((a) => (a.times.length ? a : { ...a, times: m.times, fps: m.fps }))
    }
    w.onerror = () => w.terminate()
    w.postMessage({ type: 'times', file } satisfies FollowRequest)
  }

  /** Follows blur [tr] from frame [from]: 0 both ways (a new one), 1 forward, -1 back. */
  const follow = (tr: Track, from: number, dir: number, tm: Float64Array, fps: number) => {
    const sc = tr.scale ?? 1
    const b = boxNear(tr, from)
    setFollowing({ id: tr.id, progress: 0 })
    runFollow({
      type: 'follow', file, times: tm, fps, from, dir,
      box: [b[0], b[1], b[2] * sc, b[3] * sc],
      guides: state.current.tracks.filter((x) => !x.manual && x.id !== tr.id).map((x) => ({ first: x.first, boxes: x.boxes })),
    }, (m) => {
      if (m.type === 'progress') { setFollowing({ id: tr.id, progress: m.progress }); return }
      setFollowing(null)
      if (m.type !== 'done') { setNotice(t('לא הצלחתי לעקוב מכאן. אפשר להזיז את הטשטוש ולקבוע התחלה וסוף ידנית.')); return }
      const n = m.boxes.length / 4
      if (n <= 1) { setNotice(t('לא הצלחתי לעקוב מכאן. אפשר להזיז את הטשטוש ולקבוע התחלה וסוף ידנית.')); return }
      // Followed at the size shown; kept without the user's size.
      for (let k = 0; k < n; k++) { m.boxes[k * 4 + 2] /= sc; m.boxes[k * 4 + 3] /= sc }
      const apply = (ts: Track[]) => ts.map((x) => (x.id === tr.id ? applyFollow(x, from, m, dir) : x))
      // A new blur's following is part of adding it (one undo step).
      if (dir === 0) setTracks(apply)
      else setTracksH(apply)
      setNotice(t('הטשטוש עוקב %s שניות. בדוק וקצר אם צריך.', (n / Math.max(1, fps)).toFixed(1)))
    })
  }
  const cancelFollow = () => {
    followWorker.current?.terminate()
    followWorker.current = null
    setFollowing(null)
  }

  /** A new blur where the video was tapped (sized from a face found there, if any), then followed both ways. */
  const addAt = async (fx: number, fy: number) => {
    setAdding(false)
    video.current?.pause()
    let tm = times, fps = an.fps
    try {
      if (!tm.length) ({ times: tm, fps } = await frameTimes())
    } catch {
      setNotice(t('מעקב אוטומטי לא זמין לסרטון הזה'))
      return
    }
    const i = frameAt(tm, state.current.mediaTime)
    let box: Box | null = null
    for (const tr of state.current.tracks) {
      if (tr.manual) continue
      const b = trackBox(tr, i)
      if (b && Math.abs(fx - b[0]) < b[2] / 2 && Math.abs(fy - b[1]) < b[3] / 2) box = [b[0], b[1], b[2], b[3]]
    }
    if (!box) {
      const h = Math.min(0.6, NEW_BLUR * 1.25 * W / H)
      box = [fx, fy + 0.06 * h, NEW_BLUR, h]
    }
    const tr = newTrack(nextTrackId(state.current.tracks), i, box)
    setTracksH((ts) => [...ts, tr])
    setThumbOf((m) => new Map(m).set(tr.id, grabThumb(box!)))
    setSelected(tr.id)
    setSelectedSfx(null)
    follow(tr, i, 0, tm, fps)
  }

  const selTrack = tracks.find((tr) => tr.id === selected) ?? null
  const changeSel = (f: (tr: Track) => Track | null, merge = false) => {
    if (!selTrack) return
    const next = f(selTrack)
    if (next) setTracksH((ts) => ts.map((x) => (x.id === selTrack.id ? next : x)), merge)
  }
  const splitSel = () => {
    if (!selTrack) return
    const id = nextTrackId(tracks)
    const parts = split(selTrack, frameNow(), id)
    if (!parts) { setNotice(t('כדי לפצל, הזז את הסמן לתוך טווח הטשטוש')); return }
    setTracksH((ts) => ts.flatMap((x) => (x.id === selTrack.id ? parts : [x])))
    setThumbOf((m) => new Map(m).set(id, m.get(selTrack.id) ?? null))
    if (off.has(selTrack.id)) setOff((s) => new Set(s).add(id))
    setSelected(id)
  }
  const deleteSel = () => {
    if (!selTrack) return
    setTracksH((ts) => ts.filter((x) => x.id !== selTrack.id))
    setSelected(null)
  }

  // A tap on a blur in the video selects it (dragging the selected one moves it); with "add a
  // blur" on, a tap places one; elsewhere a tap plays or pauses.
  const press = useRef<{ x: number; y: number; fx: number; fy: number; hit: number | null; base: Track | null; moved: boolean } | null>(null)
  const toFrac = (e: PointerEvent): [number, number] => {
    const r = canvas.current!.getBoundingClientRect()
    return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]
  }
  const onScreenDown = (e: PointerEvent) => {
    if (!canvas.current) return
    const [fx, fy] = toFrac(e)
    const hit = adding ? null : blurAt(fx, fy)
    const base = hit !== null && hit === selected && !following ? state.current.tracks.find((x) => x.id === hit) ?? null : null
    press.current = { x: e.clientX, y: e.clientY, fx, fy, hit, base, moved: false }
    if (base) (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }
  const onScreenMove = (e: PointerEvent) => {
    const d = press.current
    if (!d?.base) return
    if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) < 6) return
    if (!d.moved) { d.moved = true; record(false) }
    const [fx, fy] = toFrac(e)
    const moved = shift(d.base, fx - d.fx, fy - d.fy)
    setTracks((ts) => ts.map((x) => (x.id === moved.id ? moved : x)))
  }
  const onScreenUp = () => {
    const d = press.current
    press.current = null
    if (!d || d.moved) return
    if (adding) { void addAt(d.fx, d.fy); return }
    if (d.hit !== null) { setSelected(d.hit === selected ? null : d.hit); setSelectedSfx(null) }
    else if (selected !== null) setSelected(null)
    else playPause()
  }

  // ---------------------------------------------------------------- cuts

  const cutSilences = (minPause: number) => {
    setCutBusy(true)
    const w = new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<AudioMessage>) => {
      w.terminate()
      setCutBusy(false)
      const m = e.data
      if (m.type !== 'silences') { setNotice(t('אין סאונד בסרטון')); return }
      const before = cutTotal(state.current.cuts)
      const { ranges, added } = addCuts(state.current.cuts, m.ranges)
      if (added === 0) { setNotice(t('לא נמצאו שתיקות ארוכות')); return }
      setCutsH(ranges)
      setNotice(t('נחתכו %d שתיקות · %s שנ׳', added, (cutTotal(ranges) - before).toFixed(1)))
    }
    w.onerror = () => { w.terminate(); setCutBusy(false); setNotice(t('אין סאונד בסרטון')) }
    const req: AudioRequest = { type: 'silences', file, minPause, words: words.map(wordSpan) }
    w.postMessage(req)
  }
  const cutFillers = () => {
    const { ranges, added } = addCuts(cuts, words.filter((w) => isFiller(w.text)).map(wordSpan))
    if (added === 0) { setNotice(t('לא נמצאו מילות מילוי בכתוביות')); return }
    setCutsH(ranges)
    setNotice(t('נחתכו %d מילות מילוי', added))
  }

  const playPause = () => {
    const v = video.current
    if (!v) return
    if (v.paused) v.play().catch(() => {})
    else v.pause()
  }

  // The keyboard, on a computer: Space, J K L, the arrows (a frame), Ctrl+Z / Ctrl+Shift+Z,
  // − and + (the timeline's zoom), 1 to 4 (the groups), Delete (the selected sound).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) return
      if (hidden || detecting) return
      if (e.key === 'Escape') { setAdding(false); setSelected(null); setSelectedSfx(null); setCutStart(null); return }
      const v = video.current
      if (!v) return
      const k = e.key
      const end = isFinite(v.duration) ? v.duration : dur
      if ((e.ctrlKey || e.metaKey) && (k === 'z' || k === 'Z')) { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return }
      if ((e.ctrlKey || e.metaKey) && (k === 'y' || k === 'Y')) { e.preventDefault(); redo(); return }
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const frame = 1 / (an.fps || 30)
      switch (k) {
        case ' ': e.preventDefault(); playPause(); break
        case 'k': case 'K': v.pause(); break
        case 'j': case 'J': v.currentTime = Math.max(0, v.currentTime - 2); break
        case 'l': case 'L': if (v.paused) v.play().catch(() => {}); else v.currentTime = Math.min(end, v.currentTime + 2); break
        case 'ArrowLeft': e.preventDefault(); v.pause(); v.currentTime = Math.max(0, v.currentTime - frame); break
        case 'ArrowRight': e.preventDefault(); v.pause(); v.currentTime = Math.min(end, v.currentTime + frame); break
        case '-': case '_': timelineControls.zoom?.(1 / 1.5); break
        case '+': case '=': timelineControls.zoom?.(1.5); break
        case '1': case '2': case '3': case '4': case '5': case '6': setTab(GROUPS[Number(k) - 1][0]); break
        case 'Delete': case 'Backspace':
          if (selectedSfx !== null) { setSfxH((list) => list.filter((c) => c.id !== selectedSfx)); setSelectedSfx(null) }
          else if (selected !== null && !following) deleteSel()
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [hidden, detecting, selectedSfx, selected, following, tracks, dur, an])

  const enabled = tracks.filter((tr) => !off.has(tr.id))
  const setMode = (mode: BlurMode) => setLook({ ...look, mode })

  return (
    <>
    {detecting && (
      <Analyzing
        file={file}
        sensitive={false}
        onCancel={() => setDetecting(false)}
        onError={() => setDetecting(false)}
        onDone={(a, th, ms, backend) => {
          // Blurs added by hand stay, on the new frame times.
          let id = nextTrackId(a.tracks)
          const kept = tracks.filter((tr) => tr.manual).map((tr) => ({ old: tr.id, tr: { ...retime(tr, times, (x) => frameAt(a.times, x)), id: id++ } }))
          setAn(a)
          setTracks([...a.tracks, ...kept.map((k) => k.tr)])
          setThumbOf(new Map([...a.tracks.map((tr, k): [number, string | null] => [tr.id, th[k] ?? null]), ...kept.map((k): [number, string | null] => [k.tr.id, thumbOf.get(k.old) ?? null])]))
          setOff(new Set(kept.filter((k) => off.has(k.old)).map((k) => k.tr.id)))
          setSelected(null)
          setAnalyzed(true)
          past.current = []
          future.current = []
          setDetectInfo(t('זמן עיבוד: %s', duration(ms / 1000)) + ' · ' + t('הזיהוי רץ על %s', backend === 'webgpu' ? t('המעבד הגרפי') : t('המעבד')))
          setDetecting(false)
        }}
      />
    )}
    <main class="editor" style={detecting || hidden ? 'display:none' : ''}>
      <div class="player">
        <div class="row">
          <button class="btn ghost" onClick={onClose} style="padding-inline:6px"><span class="flip"><BackIcon /></span> {t('סרטון חדש')}</button>
          <div class="grow" />
          <span class="small muted detect-info">{detectInfo}</span>
          <div class="edit-tools">
            <button class="icon-btn" disabled={past.current.length === 0 || !!following} aria-label={t('בטל')} title={t('בטל') + ' (Ctrl+Z)'} onClick={undo}><span class="flip"><UndoIcon /></span></button>
            <button class="icon-btn" disabled={future.current.length === 0 || !!following} aria-label={t('בצע שוב')} title={t('בצע שוב') + ' (Ctrl+Shift+Z)'} onClick={redo}><span class="flip"><RedoIcon /></span></button>
          </div>
        </div>
        <div class={'screen' + (adding ? ' adding' : '') + (selected !== null ? ' sel' : '')} onPointerDown={onScreenDown} onPointerMove={onScreenMove} onPointerUp={onScreenUp} onPointerCancel={() => { press.current = null }}>
          <div class="view" style={{ aspectRatio: `${W} / ${H}`, width: `min(100%, calc(var(--screen-h) * ${(W / H).toFixed(4)}))` }}>
            <canvas ref={canvas} width={W} height={H} />
            <canvas ref={rings} class="layer" width={W} height={H} />
            <canvas ref={overlay} class="layer" width={W} height={H} />
          </div>
          <video ref={video} src={url} playsInline preload="auto" />
          {adding && <div class="screen-hint">{t('הקש על הסרטון במקום שצריך לטשטש')}</div>}
          {notice && <div class="toast" role="status">{notice}</div>}
        </div>
        <div class="transport">
          <button class="round" onClick={playPause} aria-label={playing ? t('השהה') : t('נגן')}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
          <span class="time">{duration(time)} / {duration(dur)}</span>
          <div class="grow" />
          {selectedClip && (
            <div class="sel-sfx">
              <strong>{t(SFX_NAMES.get(selectedClip.sound) ?? selectedClip.sound)}</strong>
              <input type="range" min={0} max={2} step={0.05} value={selectedClip.gain} aria-label={t('עוצמה')} onInput={(e) => updateSfx(selectedClip.id, { gain: Number((e.currentTarget as HTMLInputElement).value) })} />
              <span class="muted small" dir="ltr">{Math.round(selectedClip.gain * 100)}%</span>
              <button class="btn ghost small-btn" onClick={() => updateSfx(selectedClip.id, { at: time })}>{t('העבר לכאן')}</button>
              <button class="icon-btn" aria-label={t('מחק')} onClick={() => { setSfxH(sfx.filter((c) => c.id !== selectedClip.id)); setSelectedSfx(null) }}><CloseIcon size={18} /></button>
            </div>
          )}
        </div>
        {selTrack && (
          <BlurBar
            track={selTrack}
            label={selTrack.manual ? t('טשטוש %d', selTrack.id + 1) : t('פנים %d', selTrack.id + 1)}
            off={off.has(selTrack.id)}
            following={following?.id === selTrack.id ? following.progress : null}
            onToggle={() => toggle(selTrack.id)}
            onSplit={splitSel}
            onStart={() => changeSel((tr) => setStart(tr, frameNow()))}
            onEnd={() => changeSel((tr) => setEnd(tr, frameNow()))}
            onFollow={(dir) => follow(selTrack, frameNow(), dir, times, an.fps)}
            onScale={(sc) => changeSel((tr) => ({ ...tr, scale: sc }), true)}
            onDelete={deleteSel}
            onCancelFollow={cancelFollow}
            onClose={() => setSelected(null)}
          />
        )}
        <Timeline
          file={file}
          duration={dur}
          time={time}
          times={times}
          tracks={tracks}
          off={off}
          cues={cues.map((c) => ({ ...c, text: shownText(c, maskCue(c, words, bleeped), subStyle.show) }))}
          sfx={sfx}
          sfxLength={sfxLength}
          selectedSfx={selectedSfx}
          onScrub={(at) => { const v = video.current; if (v) v.currentTime = at }}
          onScrubStart={() => video.current?.pause()}
          selectedTrack={selected}
          onSelectTrack={(id) => { setSelected(id === selected ? null : id); setSelectedSfx(null) }}
          onTrimStart={() => record(false)}
          onTrim={(id, start, at) => {
            const i = frameAt(times, start ? at : at - FRAME)
            setTracks((ts) => ts.map((x) => (x.id === id ? (start ? setStart(x, i) : setEnd(x, i)) : x)))
            seek(times[i] ?? at)
          }}
          cuts={cuts}
          cutStart={cutStart}
          onSelectSfx={(id) => { setSelectedSfx(id); if (id !== null) setSelected(null) }}
          onMoveSfx={(id, at) => updateSfx(id, { at })}
          controls={timelineControls}
        />
      </div>

      <aside class="side">
        <div class="tabs" role="tablist">
          {GROUPS.map(([id, label, Icon, color], k) => (
            <button role="tab" data-g={id} class={tab === id ? 'on' : ''} aria-selected={tab === id} style={{ '--g': color } as any} title={`${t(label)} (${k + 1})`} onClick={() => setTab(id)}>
              <span class="pill-ic"><Icon size={22} /></span>
              <span>{t(label)}{id === 'sound' && sfx.length > 0 ? <span class="count"> · {sfx.length}</span> : id === 'edit' && cuts.length > 0 ? <span class="count"> · {cuts.length}</span> : null}</span>
            </button>
          ))}
        </div>
        {tab === 'faces' && !analyzed && (
          <section class="card">
            <h3 class="section-title">{t('פנים')}</h3>
            <p class="small muted" style="margin:0 0 12px">{t('הפנים בסרטון עוד לא זוהו. אפשר לזהות ולטשטש אותן עכשיו; הכתוביות, הסאונד וסימן המים יישארו.')}</p>
            <button class="btn primary" style="width:100%" disabled={!!following} onClick={() => { video.current?.pause(); setSelected(null); setDetecting(true) }}>{t('זהה פנים')}</button>
          </section>
        )}
        {tab === 'faces' && (
          <section class="card">
            <h3 class="section-title">{t('טשטוש ידני')}</h3>
            <p class="small muted" style="margin:0 0 12px">{t('לפנים שלא נמצאו, לוחית רישוי או כל דבר אחר: הקש במקום, והטשטוש יעקוב אחריו.')}</p>
            <button class={'btn ' + (adding ? 'ghost' : 'tonal') + ' add-blur'} style="width:100%" disabled={!!following} onClick={() => { video.current?.pause(); setAdding(!adding) }}>
              {adding ? t('ביטול') : <><AddIcon size={20} /> {t('הוסף טשטוש')}</>}
            </button>
          </section>
        )}
        {tab === 'faces' && (analyzed || tracks.length > 0) && (
          <>
        <section class="card">
          <h3 class="section-title">{t('טשטוש פנים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{t('%d מתוך %d מטושטשות', enabled.length, tracks.length)}</span></h3>
          {tracks.length === 0 ? (
            <p class="muted small" style="margin:0">{t('לא נמצאו פנים בסרטון.')}</p>
          ) : (
            <>
              <div class="faces">
                {tracks.map((tr, k) => (
                  <button class={'face' + (off.has(tr.id) ? ' off' : '') + (tr.id === selected ? ' sel' : '')} onClick={() => toggle(tr.id)} aria-pressed={!off.has(tr.id)} title={tr.manual ? t('טשטוש %d', tr.id + 1) : t('פנים %d', k + 1)}>
                    {thumbOf.get(tr.id) ? <img src={thumbOf.get(tr.id)!} alt={t('פנים %d', k + 1)} /> : <div class="ph" />}
                    <span class="badge">{off.has(tr.id) ? '–' : '✓'}</span>
                    <span class="lbl">{off.has(tr.id) ? t('גלוי') : tr.manual ? t('ידני') : t('מטושטש')}</span>
                  </button>
                ))}
              </div>
              <p class="small muted" style="margin:12px 0 8px">{t('הקש על פנים כדי לבטל או להחזיר את הטשטוש שלהן. הקש על טשטוש בסרטון או בציר הזמן כדי לערוך אותו, וגרור אותו בסרטון כדי להזיז.')}</p>
              <div class="actions">
                <button class="btn tonal" onClick={() => setOffH(new Set())}>{t('הכל')}</button>
                <button class="btn tonal" onClick={() => setOffH(new Set(tracks.map((x) => x.id)))}>{t('אף אחד')}</button>
              </div>
            </>
          )}
        </section>

        <section class="card">
          <h3 class="section-title">{t('מראה')}</h3>
          <div class="seg" role="radiogroup">
            {(['blur', 'pixelate', 'solid'] as BlurMode[]).map((m) => (
              <button class={look.mode === m ? 'on' : ''} role="radio" aria-checked={look.mode === m} onClick={() => setMode(m)}>
                {m === 'blur' ? t('טשטוש') : m === 'pixelate' ? t('פיקסלים') : t('כיסוי')}
              </button>
            ))}
          </div>
          {look.mode !== 'solid' && (
            <div class="slider-row">
              <label>{t('עוצמה')}</label>
              <input type="range" min={0} max={1} step={0.01} value={look.strength} onInput={(e) => setLook({ ...look, strength: Number((e.currentTarget as HTMLInputElement).value) })} />
            </div>
          )}
          <div class="slider-row">
            <label>{t('גודל')}</label>
            <input type="range" min={1.1} max={2.4} step={0.01} value={look.padding} onInput={(e) => setLook({ ...look, padding: Number((e.currentTarget as HTMLInputElement).value) })} />
          </div>
        </section>

          </>
        )}
        {tab === 'sound' && (
          <CleanupCard
            clean={clean}
            onClean={(c) => { setDenoise(c.denoise); setEnhance(c.enhance) }}
            loud={{ on: loudOn, gainDb: loudGain }}
            loudBusy={loudOn && voiceBusy}
            onLoud={(on) => { setLoudOn(on); if (!on) setLoudGain(null) }}
          />
        )}
        {tab === 'sound' && (
          <SoundTab
            voice={voice}
            onVoice={setVoice}
            voiceBusy={voiceBusy}
            clips={sfx}
            time={time}
            onChange={setSfxH}
            onSeek={(at) => { const v = video.current; if (v) v.currentTime = at }}
          />
        )}
        {tab === 'edit' && (
          <CutTab
            cuts={cuts}
            onCuts={setCutsH}
            duration={dur}
            time={time}
            cues={cues}
            words={words}
            cutStart={cutStart}
            onCutStart={setCutStart}
            busy={cutBusy}
            onSilences={cutSilences}
            onFillers={cutFillers}
            onMakeSubtitles={() => setTab('subs')}
            onPause={() => video.current?.pause()}
          />
        )}
        {tab === 'edit' && <StabilizeCard level={steadyLevel} onLevel={chooseSteady} busy={picBusy} zoom={steadied ? Math.round((steadied.zoom - 1) * 100) : null} onCancel={() => { cancelPicture(); if (!picture) setSteadyLevel(0) }} />}
        {tab === 'picture' && (
          <>
            <BackgroundCard bg={background} onChange={setBackground} masksReady={!!picture?.masks} busy={picBusy} onFindPeople={() => runPicture(true)} onCancel={cancelPicture} />
            <FrameCard aspect={exportOpts.aspect} onChange={(a) => setExportOpts((o) => ({ ...o, aspect: a }))} hasFaces={tracks.length > 0} />
          </>
        )}
        {tab === 'mark' && <WatermarkTab mark={mark} onChange={setMark} />}
        {tab === 'subs' && (
          <SubtitlesTab
            file={file}
            cues={cues}
            onCues={setCuesH}
            words={words}
            onWords={setWords}
            bleeped={bleeped}
            onBleeped={setBleeped}
            tone={tone}
            onTone={setTone}
            style={subStyle}
            onStyle={setSubStyle}
            time={time}
            onSeek={(at) => { const v = video.current; if (v) v.currentTime = at }}
          />
        )}

        <details class="keys">
          <summary>{t('קיצורי מקלדת')}</summary>
          <dl>
            <dt><kbd>Space</kbd></dt><dd>{t('ניגון ועצירה')}</dd>
            <dt dir="ltr"><kbd>J</kbd> <kbd>K</kbd> <kbd>L</kbd></dt><dd>{t('אחורה, עצירה, קדימה')}</dd>
            <dt dir="ltr"><kbd>←</kbd> <kbd>→</kbd></dt><dd>{t('פריים אחורה או קדימה')}</dd>
            <dt dir="ltr"><kbd>Ctrl+Z</kbd> <kbd>Ctrl+Shift+Z</kbd></dt><dd>{t('בטל ובצע שוב')}</dd>
            <dt dir="ltr"><kbd>−</kbd> <kbd>+</kbd></dt><dd>{t('זום בציר הזמן')}</dd>
            <dt dir="ltr"><kbd>1</kbd>–<kbd>6</kbd></dt><dd>{t('קבוצות הכלים')}</dd>
            <dt><kbd>Delete</kbd></dt><dd>{t('מחיקת הטשטוש או האפקט הנבחר')}</dd>
            <dt><kbd>Esc</kbd></dt><dd>{t('ביטול הבחירה')}</dd>
          </dl>
        </details>
        <div class="export-bar">
          <button
            class="btn primary"
            disabled={!!following}
            onClick={() => {
              video.current?.pause()
              setExportOpen(true)
              if (!srcInfo) probe()
            }}
          >
            <SaveIcon /> {t('ייצוא')}
          </button>
        </div>
      </aside>
    </main>
    {exportOpen && (
      <ExportSheet
        width={W}
        height={H}
        fps={srcInfo?.fps ?? (analyzed ? an.fps : null)}
        sourceHevc={srcInfo?.codec === 'hevc'}
        hasCues={cues.length > 0}
        options={exportOpts}
        onChange={setExportOpts}
        onClose={() => setExportOpen(false)}
        onGo={() => {
          setExportOpen(false)
          saveExportOptions(exportOpts)
          onExport({ file, times, tracks: enabled, look, width: W, height: H, sfx, watermark: mark, cues: cues.map((c) => ({ ...c, text: shownText(c, maskCue(c, words, bleeped), subStyle.show) })), subStyle, voice: voiceAudio ? { rate: voiceAudio.rate, data: voiceAudio.data } : null, cuts, options: exportOpts, picture: { background: background.fill !== 'none' && (background.key !== 'person' || picture?.masks) ? background : null, masks: background.key === 'person' && background.fill !== 'none' ? picture?.masks ?? null : null, steady: steadied, path: framePath } })
        }}
      />
    )}
    </>
  )
}
