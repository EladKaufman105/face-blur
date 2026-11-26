import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { BETA, storageKey } from '../lib/channel'
import { t, duration, lang } from '../i18n'
import { BlurRenderer, ellipseFor, type Ellipse } from '../lib/renderer'
import { frameAt, loadLook, saveLook, trackBox, type Analysis, type BlurMode, type Look, type Track } from '../lib/types'
import type { ExportJob } from './Exporting'
import { AddIcon, BackIcon, ChevronLeftIcon, ChevronRightIcon, CloseIcon, CutIcon, EyeOffIcon, FaceIcon, TuneIcon, VisibleIcon, PictureIcon, MarkIcon, SafeAreaIcon, SettingsIcon, TemplateIcon, PauseIcon, PlayIcon, RedoIcon, SaveIcon, SoundIcon, SubtitlesIcon, UndoIcon } from './icons'
import { FRAME, Timeline } from './Timeline'
import { BlurBar } from './BlurBar'
import { CueBar, CueSidePanel } from './CueBar'
import { SafeZoneBar } from './SafeZoneBar'
import { appliesTo, easeOut, GLIDE_MS, mixZone, zoneOf, type Platform, type Zone } from '../lib/safezone'
import { drawSafeZone } from '../lib/safezonemock'
import { CutTab, wordSpan } from './CutTab'
import { addCuts, CutMap, cutAt, cutTotal, skipTarget, uncut, type Range } from '../lib/cutmap'
import { isFiller } from '../lib/silence'
import { applyFollow, boxNear, MAX_SCALE, MIN_SCALE, newTrack, nextTrackId, retime, setEnd, setStart, shift, split, type Box } from '../lib/tracks'
import type { FollowMessage, FollowRequest } from '../workers/follow.worker'
import { SfxScheduler, type SfxClip } from '../lib/sfx'
import { drawWatermark, normalizeWatermark, watermarkOn, type Watermark } from '../lib/watermark'
import { CleanupCard, SoundTab, SubtitlesTab, WatermarkTab, loadWatermark, type LostBleeps } from './EditorTabs'
import { ExportSheet } from './ExportSheet'
import { BackgroundCard, FrameCard, StabilizeCard, type PictureBusy } from './PictureTab'
import { maskAt, maskIndex, NO_BACKGROUND, type Background, type PeopleMasks } from '../lib/background'
import { ASPECT_RATIO, cropFraction, reframePath, steady, viewRect } from '../lib/camera'
import type { PictureMessage, PictureRequest } from '../workers/picture.worker'
import { createProject, saveProject, summarize, type ProjectPicture, type ProjectState } from '../lib/projects'
import { reserveCueIds, wordsToCues } from '../lib/subtitles'
import { speakerColor } from '../lib/speakers'
import { reserveClipIds } from './EditorTabs'
import { speechLine } from './SpeechUi'
import { cleanupOn, type Cleanup } from '../lib/cleanup'
import { loadExportOptions, saveExportOptions } from '../lib/exportopts'
import { Analyzing } from './Analyzing'
import { ChipTabs, PANEL_ID, STYLE_PARTS, subsOf, useNarrow, useTablet } from './panels'
import { bleepRanges, cueAt, cueRoom, cueWi, maskCue, maskWarning, mergeCues, migrateCues, orphansOf, retimeCue, shownText, splitCueAtTime, trVisible, type Cue, type SubStyle, type Word } from '../lib/subtitles'
import { DEFAULT_SUB_STYLE, loadSubFont, normalizeStyle } from '../lib/substyle'
import { cueWordTimes, drawCue } from '../lib/subrender'
import { getFont } from '../lib/projects'
import { loadSfx, makeBuffer, sfxName } from '../lib/sfx'
import type { VoiceFx } from '../lib/voice'
import type { AudioMessage, AudioRequest } from '../workers/audio.worker'
import { exportOptionsOf, hasProcessing, savedLanguage, styleOf, type TemplateSettings } from '../lib/template'
import { Aborted, findPauses, transcribe } from '../lib/templaterun'
import { translateCues, translateNote } from '../lib/translate'
import { releaseSpeechForExport } from '../lib/speech'
import { SaveTemplate } from './SaveTemplate'
import { DetectionNotice } from './DetectionNotice'

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
  /** A template chosen for this new video: its settings fill in the editor and its processing runs (subtitles, voice, cuts, background, steadying). */
  template?: TemplateSettings | null
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
type Snap = { off: Set<number>; sfx: SfxClip[]; cues: Cue[]; words: Word[]; bleeped: Set<number>; tracks: Track[]; cuts: Range[] }

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

export function Editor({ file, analysis, thumbs: thumbs0, info, onExport, onClose, initialTab: initTab, hidden = false, project, template: tpl0 }: Props) {
  const initialTab: EditorTab = initTab ?? 'faces'
  // A project opened again starts as it was left.
  const saved = project?.state ?? null
  // A template applies to a new video only (a project opened again is as it was left).
  const tpl = !project ? tpl0 ?? null : null
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
  const [denoise, setDenoise] = useState(saved?.denoise ?? tpl?.denoise ?? 0)
  const [enhance, setEnhance] = useState(saved?.enhance ?? tpl?.enhance ?? false)
  const [loudOn, setLoudOn] = useState(saved?.loud ?? tpl?.loud ?? false)
  /** The gain to the networks' loudness, as measured on the cleaned sound (null until it is). */
  const [loudGain, setLoudGain] = useState<number | null>(null)
  const clean: Cleanup = useMemo(() => ({ denoise, enhance, loud: loudOn }), [denoise, enhance, loudOn])
  const [exportOpen, setExportOpen] = useState(false)
  const [exportOpts, setExportOpts] = useState(() => (tpl ? exportOptionsOf(tpl, loadExportOptions()) : { ...loadExportOptions(), aspect: saved?.aspect ?? 'original' }))
  const [srcInfo, setSrcInfo] = useState<{ codec: string; fps: number; bitrate: number } | null>(null)
  // The safe zone, a view aid: where a short-video platform's own screens cover a vertical frame (drawn here only, never exported).
  const safeAvailable = appliesTo(exportOpts.aspect, W, H)
  const [safeOn, setSafeOn] = useState(false)
  /** The blur view (as the app's): the real blur in the player, or outlines only (to see the faces under it). Only here, never in the export. */
  const [blurView, setBlurView] = useState(true)
  // With no blur left the switch goes (it shows only with blurs): the next blur starts on the real blur again.
  useEffect(() => { if (tracks.length === 0 && !blurView) setBlurView(true) }, [tracks.length, blurView])
  const [safePlatform, setSafePlatform] = useState<Platform>(() => { try { const p = localStorage.getItem(storageKey('safezone.platform')); return p === 'reels' || p === 'shorts' ? p : 'tiktok' } catch { return 'tiktok' } })
  const safe = useRef<{ platform: Platform; from: Zone; at: number }>({ platform: safePlatform, from: zoneOf(safePlatform), at: -1e9 })
  // The picture tools: the camera's motion, the cuts and the people (from one pass over the video).
  const [picture, setPicture] = useState<ProjectPicture | null>(project?.picture ?? null)
  const [picBusy, setPicBusy] = useState<PictureBusy>(null)
  const [background, setBackground] = useState<Background>(saved?.background ?? tpl?.background ?? NO_BACKGROUND)
  const [steadyLevel, setSteadyLevel] = useState(saved?.steady ?? tpl?.stabilize ?? 0)
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
  // How wide the exported frame is on screen: the safe zone's chooser is as wide as it.
  const viewBox = useRef<HTMLDivElement>(null)
  const [viewW, setViewW] = useState(0)
  useEffect(() => {
    const el = viewBox.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => setViewW(el.clientWidth))
    ro.observe(el)
    setViewW(el.clientWidth)
    return () => ro.disconnect()
  }, [])
  const frameW = viewW * crop[0]
  useEffect(() => () => URL.revokeObjectURL(url), [url])
  const video = useRef<HTMLVideoElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const overlay = useRef<HTMLCanvasElement>(null)
  const rings = useRef<HTMLCanvasElement>(null)
  const renderer = useRef<BlurRenderer | null>(null)
  const [off, setOff] = useState<Set<number>>(() => new Set(saved?.off ?? []))
  const [look, setLook] = useState<Look>(() => saved?.look ?? tpl?.look ?? loadLook())
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [dur, setDur] = useState(times.length ? times[times.length - 1] : 0)
  const [tab, setTab] = useState<EditorTab>(initialTab)
  // On a phone the timeline and a group's panel share one place (as in the app): the timeline shows, and a group opens its panel in its place (a tool picked from the home screen opens its panel).
  // On a tablet a panel is always open beside the video (until its X); on a phone it opens in the timeline's place.
  const tabletNow = useTablet()
  const [panelOpen, setPanelOpen] = useState(initTab !== undefined || tabletNow)
  /** Speech, speakers or translation is running in the subtitles tab: it stays mounted (hidden) when another group is open, so the job goes on. */
  const [subsBusy, setSubsBusy] = useState(false)
  /** The subtitles tab was opened once: from then on it stays mounted (hidden when another group is open), so a job, its notice or its error is never lost. */
  const subsSeen = useRef(initTab === 'subs')
  /** Bleeped words the new subtitles had no word for: an alert in every group and a line in the export sheet, until dismissed or undone. */
  const [lost, setLost] = useState<LostBleeps | null>(null)
  /** An error came in the subtitles tab while another group was open: its group says so. */
  const [subsAlert, setSubsAlert] = useState(false)
  const subsTime = useRef(0)
  // A phone shows one sub-tool of the open group at a time (as the app does); a wide screen shows them all.
  const phone = useNarrow()
  const tablet = useTablet()
  /** One group's panel at a time, its tools as chips, one top bar: a phone and a tablet (the page as before on a wider screen). */
  const narrow = phone || tablet
  const [subTab, setSubTab] = useState<Record<string, string>>({})
  const [styleTab, setStyleTab] = useState('looks')
  const bodyRef = useRef<HTMLDivElement>(null)
  const [cues, setCues] = useState<Cue[]>(() => migrateCues(saved?.cues ?? [], saved?.words ?? []))
  const [words, setWords] = useState<Word[]>(saved?.words ?? [])
  const [bleeped, setBleeped] = useState<Set<number>>(() => new Set(saved?.bleeped ?? []))
  const [tone, setTone] = useState(saved?.tone ?? true)
  const [voice, setVoice] = useState<VoiceFx>(saved?.voice ?? tpl?.voice ?? 'none')
  const [voiceAudio, setVoiceAudio] = useState<{ rate: number; data: Float32Array; buffer: AudioBuffer } | null>(null)
  // True from the moment the wanted sound (voice change, bleeps, cleanup) differs from the
  // processed one until the matching result arrives: it starts true, and the effect below clears it.
  const [voiceBusy, setVoiceBusy] = useState(true)
  /** The sound step failed: [hard] when a voice change or bleeps are asked for (then nothing can be exported). */
  const [soundFail, setSoundFail] = useState<{ hard: boolean } | null>(null)
  /** The worker has the current settings' request and has not answered or failed (so a late answer can still come). */
  const [soundInflight, setSoundInflight] = useState(false)
  /** The video has no sound: nothing to change or anonymise. */
  const [noAudio, setNoAudio] = useState(false)
  const [audioTry, setAudioTry] = useState(0)
  const [selectedSfx, setSelectedSfx] = useState<number | null>(null)
  /** The subtitle selected on the timeline (its bar and its edges' handles), and whether its properties are open. */
  const [selectedCue, setSelectedCue] = useState<number | null>(null)
  const [cueOpen, setCueOpen] = useState(false)
  const [sfxLength, setSfxLength] = useState<Map<string, number>>(new Map())
  const [subStyle, setSubStyle] = useState<SubStyle>(() => {
    if (saved) return normalizeStyle(saved.subStyle)
    if (tpl) return styleOf(tpl)
    try { return normalizeStyle(JSON.parse(localStorage.getItem(storageKey('substyle')) ?? 'null')) } catch { return normalizeStyle(null) }
  })
  const [sfx, setSfx] = useState<SfxClip[]>(saved?.sfx ?? [])
  const [mark, setMark] = useState<Watermark>(() => (saved?.mark ? normalizeWatermark(saved.mark) : tpl ? normalizeWatermark(tpl.watermark) : loadWatermark()))
  const logo = useRef<HTMLImageElement | null>(null)
  const scheduler = useMemo(() => new SfxScheduler(), [])
  const orphans = useMemo(() => orphansOf(cues, bleeped), [cues, bleeped])
  const state = useRef({ off, look, mediaTime: 0, sfx, mark, cues, subStyle, words, bleeped, orphans, voiceBuf: null as AudioBuffer | null, tracks, selected, cuts, cutMap, fps: an.fps, times, steadied, crop, framePath, masks: null as PeopleMasks | null, background, safeOn: false, blurView: true })
  // (The drawing runs in callbacks made once: it reads the frame times from here, as they can
  // arrive later, from a search for faces or a first blur by hand.)
  state.current.times = times
  state.current.steadied = steadied
  state.current.crop = crop
  state.current.framePath = framePath
  state.current.masks = picture?.masks ?? null
  state.current.background = background
  state.current.safeOn = safeOn && safeAvailable
  state.current.blurView = blurView
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
  state.current.orphans = orphans
  state.current.voiceBuf = voiceAudio?.buffer ?? null

  useEffect(() => {
    saveLook(look)
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

  /** The safe zone's margins now: gliding from the last platform's to the chosen one's (260 ms, easing out). */
  const zoneNow = (): Zone => {
    const s = safe.current
    return mixZone(s.from, zoneOf(s.platform), easeOut(Math.min(1, (performance.now() - s.at) / GLIDE_MS)))
  }
  const choosePlatform = (p: Platform) => {
    if (p === safe.current.platform) return
    const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    safe.current = { platform: p, from: zoneNow(), at: reduced ? -1e9 : performance.now() }
    setSafePlatform(p)
    try { localStorage.setItem(storageKey('safezone.platform'), p) } catch {}
    // A paused video is redrawn through the glide (a playing one is drawn every frame anyway).
    const step = () => {
      if (video.current?.paused) draw()
      if (performance.now() - safe.current.at < GLIDE_MS + 30) requestAnimationFrame(step)
    }
    requestAnimationFrame(step)
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
    r.draw(v, W, H, state.current.blurView ? facesAt(i, false) : [], state.current.look)
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
      // Outlines only (the blur view off): each blur a light shape with its number, the faces seen through it (as the app's).
      if (!state.current.blurView) {
        ctx.lineWidth = Math.max(2, W / 400)
        ctx.font = `bold ${Math.max(12, Math.round(W / 45))}px system-ui, sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'bottom'
        for (const tr of state.current.tracks) {
          if (state.current.off.has(tr.id)) continue
          const e = ellipseOf(tr, i)
          if (!e) continue
          // (A blur by hand in the timeline's pink; the selected one's ring, drawn after, is yellow.)
          const c = tr.manual ? '255,140,190' : '255,255,255'
          ctx.fillStyle = `rgba(${c},0.16)`
          ctx.strokeStyle = `rgb(${c})`
          ctx.beginPath()
          ctx.ellipse(e.cx, e.cy, e.rx, e.ry, 0, 0, Math.PI * 2)
          ctx.fill()
          ctx.stroke()
          ctx.fillStyle = '#fff'
          ctx.shadowColor = '#000'
          ctx.shadowBlur = 5
          ctx.fillText(String(tr.id + 1), e.cx, Math.max(Math.round(W / 45) + 2, e.cy - e.ry - 4))
          ctx.shadowBlur = 0
        }
      }
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
      if (cue) {
        const st = state.current.subStyle
        const text = state.current.subStyle.maskBleeped === false ? cue.text : maskCue(cue, state.current.words, state.current.bleeped, state.current.orphans)
        const blocks = shownText(cue, text, st.show, st.maskBleeped !== false).split('\n')
        const times = st.show === 'tr' ? [] : [cueWordTimes(text, cue.wi, state.current.words)]
        drawCue(tc, blocks, cue.start, cue.end, state.current.mediaTime, { ...st, color: speakerColor(st.color, cue.speaker, st.speakerColors) }, fw, fh, times)
      }
      tc.restore()
      // The safe zone's mock of a platform's screens, over everything and only here (never in the export).
      if (state.current.safeOn && fw >= 2 && fh >= 2) drawSafeZone(tc, safe.current.platform, zoneNow(), fx, fy, fw, fh)
    }
  }

  // Draw every new frame while playing, and after each seek.
  useEffect(() => {
    const v = video.current
    if (!v) return
    let stop = false
    const hasVfc = 'requestVideoFrameCallback' in v
    // Playing, the cut stretches are jumped over: once per cut (not again while the jump is under
    // way), to the first frame after it, and never back. Returns whether it jumped.
    const skipCut = (at: number): boolean => {
      const st = state.current
      if (st.cutMap.empty || v.paused || v.seeking) return false
      const sk = skipTarget(st.cutMap, at, v.currentTime, st.fps || 30, v.duration, st.times)
      if (!sk) return false
      if (sk.end) {
        // The cut reaches the end of the video: playback stops there.
        v.pause()
        v.currentTime = sk.to
      } else v.currentTime = sk.to
      return true
    }
    const onFrame = (_: number, meta: { mediaTime: number }) => {
      if (stop) return
      if (!skipCut(meta.mediaTime)) {
        state.current.mediaTime = meta.mediaTime
        draw()
        setTime(meta.mediaTime)
      }
      ;(v as any).requestVideoFrameCallback(onFrame)
    }
    let raf = 0
    const loop = () => {
      if (stop) return
      if (!skipCut(v.currentTime)) {
        state.current.mediaTime = v.currentTime
        draw()
        setTime(v.currentTime)
      }
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
  }, [off, look, mark, cues, subStyle, tracks, selected, background, picture, steadied, framePath, crop, bleeped, words, safeOn, safeAvailable, blurView])

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
    releaseSpeechForExport() // a phone gives the speech model's memory back first
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

  // ---------------------------------------------------------------- a template's processing

  /** What the template is doing now (its step and how far), and what couldn't be done. */
  const [tplRun, setTplRun] = useState<{ step: string | null; progress: number; indet?: boolean; notes: string[] } | null>(() => (tpl && hasProcessing(tpl) ? { step: null, progress: 0, notes: [] } : null))
  const [saveTpl, setSaveTpl] = useState(false)
  const tplCtl = useRef<AbortController | null>(null)
  /** Stops the template's processing now (its speech worker is ended with it). */
  const cancelTpl = () => {
    tplCtl.current?.abort()
    setTplRun({ step: null, progress: 1, notes: [t('התבנית בוטלה')] })
  }
  useEffect(() => {
    if (!tpl || !hasProcessing(tpl)) return
    const ctl = new AbortController()
    tplCtl.current = ctl
    ;(async () => {
      const notes: string[] = []
      const run = (step: string | null, progress = 0) => setTplRun({ step, progress, notes: [...notes] })
      let ws: Word[] = []
      if (tpl.createSubs) {
        run(t('יוצר כתוביות'))
        try {
          ws = await transcribe(file, tpl.language, (st) => { const l = speechLine(st); setTplRun({ step: l.text, progress: l.progress ?? 0, indet: l.progress === null, notes: [...notes] }) }, ctl.signal)
          const cs = wordsToCues(ws, tpl.split.words, tpl.split.chars)
          reserveCueIds(cs)
          setWords(ws)
          setBleeped(new Set())
          setCues(cs)
          if (cs.length === 0) notes.push(t('לא נמצא דיבור בסרטון'))
          else if (tpl.translate) {
            // The template's translation (as the app's): each subtitle that still says what was translated, and has no translation typed meanwhile, takes it.
            run(t('מתרגם… %d%', 0))
            try {
              const tr = new Map((await translateCues(cs, tpl.language, (stage, p) => setTplRun({ step: stage === 'download' ? t('מוריד את חבילת השפה… %d%', Math.round(p * 100)) : t('מתרגם… %d%', Math.round(p * 100)), progress: p, notes: [...notes] }), ctl.signal)).map((c) => [c.id, c]))
              setCues((now) => now.map((c) => { const x = tr.get(c.id); return x && x.text === c.text && !c.tr ? { ...c, tr: x.tr, trm: x.trm } : c }))
            } catch (e) {
              if (ctl.signal.aborted) return
              notes.push(translateNote(e))
            }
          }
        } catch (e) {
          if (e instanceof Aborted) return
          notes.push((e as any)?.code === 'no-audio' ? t('לסרטון אין פס קול.') : t('הכתוביות לא נוצרו: %s', String((e as Error)?.message ?? e).slice(0, 160)))
          // A stuck or failed recognition can be run again from the subtitles tab.
        }
      }
      if (tpl.cutSilences || tpl.cutFillers) {
        run(t('חותך'))
        let ranges = state.current.cuts
        if (tpl.cutSilences) {
          try { ranges = addCuts(ranges, await findPauses(file, tpl.minPauseMs / 1000, ws.map(wordSpan), ctl.signal)).ranges } catch (e) { if (e instanceof Aborted) return; notes.push(t('אין סאונד בסרטון')) }
        }
        if (tpl.cutFillers) {
          if (ws.length === 0) notes.push(t('אין כתוביות, אז לא נחתכו מילות מילוי'))
          else ranges = addCuts(ranges, ws.filter((w) => isFiller(w.text)).map(wordSpan)).ranges
        }
        setCuts(ranges)
      }
      if (tpl.voice !== 'none' || tpl.denoise > 0 || tpl.enhance || tpl.loud) run(t('מעבד את הסאונד'))
      // The people (for the background) and the camera's motion: one pass, shown with its own bar.
      const people = tpl.background.fill !== 'none' && tpl.background.key === 'person'
      if (people || tpl.stabilize > 0) runPicture(people)
      setTplRun({ step: null, progress: 1, notes })
    })()
    return () => ctl.abort()
  }, [])
  // The sound's (voice change, cleanup, loudness) processing and the picture pass go on in their own places: said here too.
  const tplBusy = tplRun !== null && (tplRun.step !== null || voiceBusy || picBusy !== null)

  // ---------------------------------------------------------------- the project, kept as it's edited

  const projectId = useRef<string | null>(project?.id ?? null)
  const savedPicture = useRef<ProjectPicture | null | undefined>(project ? project.picture : undefined)
  const storageFull = useRef(false)
  useEffect(() => {
    if (projectId.current) return
    createProject(file, { name: file.name.replace(/\.[^.]+$/, ''), duration: 0, thumb: null, ...summarize({ analyzed, tracks, cues, bleeped: [...bleeped], voice, sfx, background, cuts }) })
      .then((id) => { projectId.current = id; setProjectTick((n) => n + 1) })
      .catch(() => { storageFull.current = true; setNotice(t('אין מקום לשמור את הפרויקט בדפדפן. אפשר להמשיך לערוך ולייצא.')) })
  }, [])
  const [projectTick, setProjectTick] = useState(0)
  /**
   * A small picture of the video as edited (faces blurred), for the projects' list. Always with the blur: with the blur view
   * on outlines the preview holds the faces as they are, so the frame is drawn blurred for the picture and then as it was.
   * Undefined (the saved picture is kept) when it can't be drawn blurred now.
   */
  const snapshot = (): string | null | undefined => {
    const c = canvas.current
    if (!c || !c.width) return null
    const outlines = !state.current.blurView
    try {
      if (outlines) {
        const v = video.current
        if (!v || v.readyState < 2) return undefined
        state.current.blurView = true
        draw()
      }
      const small = document.createElement('canvas')
      small.width = 160
      small.height = Math.max(1, Math.round((160 * c.height) / c.width))
      small.getContext('2d')!.drawImage(c, 0, 0, small.width, small.height)
      return small.toDataURL('image/jpeg', 0.7)
    } catch {
      return outlines ? undefined : null
    } finally {
      if (outlines) {
        state.current.blurView = false
        draw()
      }
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
      const thumb = snapshot()
      saveProject(id, { duration: dur, ...(thumb !== undefined ? { thumb } : {}), ...summarize(state) }, state, pic).catch(() => {
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
    try { localStorage.setItem(storageKey('substyle'), JSON.stringify(subStyle)) } catch {}
  }, [subStyle])

  // The watermark's font, loaded for the preview.
  useEffect(() => {
    let live = true
    if (!mark.image && mark.text.trim()) loadSubFont(mark, document.fonts, document.baseURI).then((ok) => { if (ok && live && video.current?.paused) draw() })
    return () => { live = false }
  }, [mark.font, mark.round, mark.image, !!mark.text.trim()])

  // The subtitles' font, loaded for the preview (an imported one from the browser's storage).
  useEffect(() => {
    let live = true
    ;(async () => {
      const bytes = subStyle.customFont ? await getFont(subStyle.customFont) : null
      if (await loadSubFont(subStyle, document.fonts, document.baseURI, bytes)) { if (live && video.current?.paused) draw() }
    })()
    return () => { live = false }
  }, [subStyle.font, subStyle.customFont, subStyle.round])

  // The logo, decoded once for the preview.
  useEffect(() => {
    if (!mark.image) { logo.current = null; return }
    const img = new Image()
    img.onload = () => { logo.current = img; if (video.current?.paused) draw() }
    // A project saved before logos were kept as data has a dead object URL: no logo, rather than a blank one.
    const dead = mark.image
    img.onerror = () => { if (dead.startsWith('blob:')) setMark((m) => (m.image === dead ? { ...m, image: null } : m)) }
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

  // The voice change and the bleeps, processed in a worker (the sound is decoded once). Each
  // request has a number, and only the latest one's answer counts.
  const audioSeq = useRef(0)
  const audioWorker = useMemo(() => new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' }), [file, audioTry])
  useEffect(() => () => audioWorker.terminate(), [audioWorker])
  // A layout effect, so the sound counts as busy before the changed settings are ever painted.
  useLayoutEffect(() => {
    const seq = ++audioSeq.current
    setSoundFail(null)
    setNoAudio(false)
    setSoundInflight(false)
    if (voice === 'none' && bleeped.size === 0 && !cleanupOn(clean)) {
      setVoiceAudio(null)
      setVoiceBusy(false)
      return
    }
    setVoiceBusy(true)
    const hard = voice !== 'none' || bleeped.size > 0
    const failed = (over = true) => {
      if (audioSeq.current !== seq) return
      // Never silently the original voice: a voice change that did not happen blocks the export.
      setVoiceAudio(null)
      setVoiceBusy(false)
      // The worker died or answered with an error: nothing is coming. A silent one (the watchdog) may still answer, and until it does a request is in flight.
      if (over) setSoundInflight(false)
      setSoundFail({ hard })
    }
    audioWorker.onerror = () => failed()
    audioWorker.onmessageerror = () => failed()
    // A worker that is dead or stuck (its script did not load, or it hung) is a failure, with a retry. A long job is not:
    // the worker sends a message at least every few seconds while it works, and each one restarts this clock.
    const silentMs = Math.min(600000, Math.max(500, Number(new URLSearchParams(location.search).get('audiowd')) || 20000))
    let watchdog: ReturnType<typeof setTimeout> | undefined
    const timer = setTimeout(() => {
      audioWorker.onmessage = (e: MessageEvent<AudioMessage>) => {
        const m = e.data
        if (audioSeq.current !== seq) return
        // Any heartbeat shows the worker is alive, an older job's too: this request waits behind it.
        if (m.type === 'started' || m.type === 'progress') { clearTimeout(watchdog); watchdog = setTimeout(() => failed(false), silentMs); return }
        if ((m as { id?: number }).id !== seq) return
        clearTimeout(watchdog)
        setSoundInflight(false)
        if (m.type === 'done') {
          // A late answer, after the watchdog gave up, is a success all the same.
          setSoundFail(null)
          setVoiceBusy(false)
          setVoiceAudio({ rate: m.rate, data: m.data, buffer: makeBuffer(m.data, m.rate) })
          if (clean.loud) {
            setLoudGain(m.lufs === null ? null : m.gainDb)
            if (m.lufs === null) { setLoudOn(false); setNotice(t('לא נמצא דיבור למדידה')) }
            else if (loudGain === null) setNotice(t('העוצמה: %s ← %s', `\u2066${m.lufs.toFixed(1)} LUFS\u2069`, `\u2066${(m.lufs + m.gainDb).toFixed(1)} LUFS\u2069`))
          }
        } else if (m.type === 'error' && m.code === 'no-audio') {
          // Nothing to anonymise or clean: the video has no sound.
          setVoiceAudio(null)
          setVoiceBusy(false)
          setNoAudio(true)
          if (cleanupOn(clean)) setNotice(t('אין סאונד בסרטון'))
        } else failed()
      }
      const req: AudioRequest = { id: seq, file, voice, bleeps: bleepRanges(words, bleeped), tone, clean }
      setSoundInflight(true)
      audioWorker.postMessage(req)
      watchdog = setTimeout(() => failed(false), silentMs)
    }, 250)
    return () => { clearTimeout(timer); clearTimeout(watchdog) }
  }, [voice, bleeped, tone, words, clean, audioWorker])

  const needsAudio = voice !== 'none' || bleeped.size > 0
  /** The sound isn't ready to be exported: still processing, or a voice change (or bleeps) without its processed sound. */
  const soundBlocked = voiceBusy || (needsAudio && !voiceAudio && !noAudio) || !!soundFail?.hard || soundInflight
  const retrySound = () => setAudioTry((n) => n + 1)
  // Only the voice change goes; the bleeps stay (and are made, or block the export again). They go only by their own action.
  const dropVoice = () => setVoice('none')
  const dropBleeps = () => setBleepedH(new Set())


  // Undo and redo. Quick changes in a row (typing, dragging a sound) are one step.
  const past = useRef<Snap[]>([])
  const future = useRef<Snap[]>([])
  const lastChange = useRef(0)
  const [, setHistoryVersion] = useState(0)
  const snap = (): Snap => ({ off: state.current.off, sfx: state.current.sfx, cues: state.current.cues, words: state.current.words, bleeped: state.current.bleeped, tracks: state.current.tracks, cuts: state.current.cuts })
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
    setWords(s.words)
    setBleeped(s.bleeped)
    setTracks(s.tracks)
    setCuts(s.cuts)
    if (s.tracks.every((tr) => tr.id !== state.current.selected)) setSelected(null)
    setSelectedCue((id) => (id !== null && s.cues.some((c) => c.id === id) ? id : null))
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
  /** Bleeped words are part of the undo steps (a quick run of taps is one). */
  const setBleepedH = (v: Set<number>) => { record(true); setBleeped(v) }
  /** The subtitles replaced as one undo step, with the words and the bleeps that belong to them. */
  const replaceSubs = (c: Cue[], w: Word[], b: Set<number>) => { record(false); setCues(c); setWords(w); setBleeped(b) }
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
      setSrcInfo({ codec: m.codec, fps: m.fps, bitrate: m.bitrate })
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
    setSelectedCue(null)
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
  // Two fingers on the video resize the selected blur (as the app's pinch; the slider in its bar does the same).
  const touches = useRef(new Map<number, [number, number]>())
  const pinch = useRef<{ id: number; dist: number; scale: number; recorded: boolean } | null>(null)
  const onScreenDown = (e: PointerEvent) => {
    if (!canvas.current) return
    if (e.isPrimary) touches.current.clear()
    touches.current.set(e.pointerId, [e.clientX, e.clientY])
    if (touches.current.size === 2 && !adding && !following) {
      const tr = state.current.tracks.find((x) => x.id === state.current.selected)
      if (tr && trackBox(tr, frameAt(times, state.current.mediaTime))) {
        const [a, b] = [...touches.current.values()]
        // The first finger's tap is no tap any more, and nothing moves while the two are down.
        press.current = null
        pinch.current = { id: tr.id, dist: Math.max(20, Math.hypot(a[0] - b[0], a[1] - b[1])), scale: tr.scale ?? 1, recorded: false }
        for (const id of touches.current.keys()) { try { (e.currentTarget as HTMLElement).setPointerCapture(id) } catch {} }
        return
      }
    }
    if (touches.current.size > 1) { press.current = null; return }
    const [fx, fy] = toFrac(e)
    const hit = adding ? null : blurAt(fx, fy)
    const base = hit !== null && hit === selected && !following ? state.current.tracks.find((x) => x.id === hit) ?? null : null
    press.current = { x: e.clientX, y: e.clientY, fx, fy, hit, base, moved: false }
    if (base) (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }
  const onScreenMove = (e: PointerEvent) => {
    if (touches.current.has(e.pointerId)) touches.current.set(e.pointerId, [e.clientX, e.clientY])
    const pc = pinch.current
    if (pc) {
      if (touches.current.size < 2) return
      const [a, b] = [...touches.current.values()]
      const sc = Math.min(MAX_SCALE, Math.max(MIN_SCALE, pc.scale * Math.hypot(a[0] - b[0], a[1] - b[1]) / pc.dist))
      // One undo step for the whole pinch.
      if (!pc.recorded) { pc.recorded = true; record(false) }
      setTracks((ts) => ts.map((x) => (x.id === pc.id ? { ...x, scale: sc } : x)))
      return
    }
    const d = press.current
    if (!d?.base) return
    if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) < 6) return
    if (!d.moved) { d.moved = true; record(false) }
    const [fx, fy] = toFrac(e)
    const moved = shift(d.base, fx - d.fx, fy - d.fy)
    setTracks((ts) => ts.map((x) => (x.id === moved.id ? moved : x)))
  }
  const onScreenUp = (e?: PointerEvent) => {
    if (e) touches.current.delete(e.pointerId)
    if (pinch.current) { if (touches.current.size < 2) pinch.current = null; press.current = null; return }
    if (touches.current.size > 0) { press.current = null; return }
    const d = press.current
    press.current = null
    if (!d || d.moved) return
    if (adding) { void addAt(d.fx, d.fy); return }
    if (d.hit !== null) { setSelected(d.hit === selected ? null : d.hit); setSelectedSfx(null); setSelectedCue(null) }
    else if (selected !== null) setSelected(null)
    else if (selectedCue !== null) setSelectedCue(null)
    else playPause()
  }

  // ---------------------------------------------------------------- cuts

  const cutSilences = (minPause: number) => {
    setCutBusy(true)
    const w = new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<AudioMessage>) => {
      const m = e.data
      if (m.type === 'started' || m.type === 'progress') return
      w.terminate()
      setCutBusy(false)
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
      if (e.key === 'Escape') { setAdding(false); setSelected(null); setSelectedSfx(null); setSelectedCue(null); setCutStart(null); return }
      const v = video.current
      if (!v) return
      const k = e.key
      const end = isFinite(v.duration) ? v.duration : dur
      if ((e.ctrlKey || e.metaKey) && (k === 'z' || k === 'Z')) { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return }
      if ((e.ctrlKey || e.metaKey) && (k === 'y' || k === 'Y')) { e.preventDefault(); redo(); return }
      if (e.ctrlKey || e.metaKey || e.altKey) return
      switch (k) {
        case ' ': e.preventDefault(); playPause(); break
        case 'k': case 'K': v.pause(); break
        case 'j': case 'J': v.currentTime = Math.max(0, v.currentTime - 2); break
        case 'l': case 'L': if (v.paused) v.play().catch(() => {}); else v.currentTime = Math.min(end, v.currentTime + 2); break
        case 'ArrowLeft': e.preventDefault(); stepFrame(-1); break
        case 'ArrowRight': e.preventDefault(); stepFrame(1); break
        case '-': case '_': timelineControls.zoom?.(1 / 1.5); break
        case '+': case '=': timelineControls.zoom?.(1.5); break
        case '1': case '2': case '3': case '4': case '5': case '6': pickGroup(GROUPS[Number(k) - 1][0], true); break
        case 'Delete': case 'Backspace':
          if (selectedSfx !== null) { setSfxH((list) => list.filter((c) => c.id !== selectedSfx)); setSelectedSfx(null) }
          else if (selected !== null && !following) deleteSel()
          else if (selCue) deleteCue()
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [hidden, detecting, selectedSfx, selected, selectedCue, cues, bleeped, following, tracks, dur, an])

  // ---------------------------------------------------------------- the selected subtitle

  const sortedCues = useMemo(() => [...cues].sort((a, b) => a.start - b.start), [cues])
  const selCue = cues.find((c) => c.id === selectedCue) ?? null
  const cueAtIdx = selCue ? sortedCues.findIndex((c) => c.id === selCue.id) : -1
  const selectCue = (id: number | null) => { setSelectedCue(id); if (id !== null) { setSelected(null); setSelectedSfx(null); setCueOpen(!phone) } }
  /** The selected subtitle now starts (or ends) at the playhead: the video's words stay where they were said. */
  const cueEdgeHere = (start: boolean) => {
    if (!selCue) return
    const [lo, hi] = cueRoom(cues, selCue, dur)
    setCuesH(cues.map((c) => (c.id === selCue.id ? retimeCue(c, start, state.current.mediaTime, lo, hi) : c)), true)
  }
  const splitSelCue = () => {
    if (!selCue) return
    video.current?.pause()
    const parts = splitCueAtTime(selCue, state.current.mediaTime, words, bleeped, orphans)
    if (!parts) { setNotice(t('אי אפשר לפצל כאן: הכתובית קצרה מדי, יש בה מילה אחת, או שמילה מוסתרת לא תישאר מוסתרת')); return }
    setCuesH(cues.flatMap((c) => (c.id === selCue.id ? parts : [c])), true)
  }
  /** Merge with the next subtitle or the one before it; the bar's Merge (auto) takes the next one, the last one the one before (as the app's). */
  const mergeSelCue = (with_: 'next' | 'prev' | 'auto' = 'auto') => {
    if (!selCue) return
    const next = with_ === 'prev' ? undefined : sortedCues[cueAtIdx + 1]
    const prev = with_ === 'next' ? undefined : sortedCues[cueAtIdx - 1]
    if (next) setCuesH(cues.filter((c) => c.id !== next.id).map((c) => (c.id === selCue.id ? mergeCues(selCue, next) : c)), true)
    else if (prev) { setCuesH(cues.filter((c) => c.id !== selCue.id).map((c) => (c.id === prev.id ? mergeCues(prev, selCue) : c)), true); setSelectedCue(prev.id) }
  }
  function deleteCue() {
    if (!selCue) return
    setCuesH(cues.filter((c) => c.id !== selCue.id), true)
    setSelectedCue(null)
    // Its words stay bleeped (the sound is as it was): the subtitles tab lists the bleeps no subtitle has and can remove them.
    const n = cueWi(selCue).filter((k) => bleeped.has(k) && !cues.some((c) => c.id !== selCue.id && cueWi(c).includes(k))).length
    if (n > 0) setNotice(n === 1 ? t('הכתובית נמחקה. מילה מצופצפת אחת נשארת מצופצפת בסאונד.') : t('הכתובית נמחקה. %d מילים מצופצפות נשארות מצופצפות בסאונד.', n))
  }
  const patchCue = (patch: Partial<Cue>) => { if (selCue) setCuesH(cues.map((c) => (c.id === selCue.id ? { ...c, ...patch } : c))) }
  const bleepMarks = useMemo(() => bleepRanges(words, bleeped), [words, bleeped])
  const cueSpeakers = cues.reduce((m, c) => Math.max(m, c.speaker ?? 0), 0)

  const enabled = tracks.filter((tr) => !off.has(tr.id))
  const setMode = (mode: BlurMode) => setLook({ ...look, mode })
  /** The cut the paused playhead is in (as the app says, with a way to bring it back). */
  const cutHere = cutAt(cuts, time)
  /** One frame back (-1) or forward (1), paused. */
  const stepFrame = (dir: number) => {
    const v = video.current
    if (!v) return
    const end = isFinite(v.duration) ? v.duration : dur
    v.pause()
    v.currentTime = Math.min(end, Math.max(0, v.currentTime + dir / (an.fps || 30)))
  }
  /** A subtitle's text with the bleeped words hidden (unless they're to show as said). */
  const masked = (c: Cue) => (subStyle.maskBleeped === false ? c.text : maskCue(c, words, bleeped, orphans))
  /** What the video shows of a subtitle: its masked text, and its translation where that can't give a masked word away. */
  const shown = (c: Cue) => shownText(c, masked(c), subStyle.show, subStyle.maskBleeped !== false)
  /** The subtitles whose text was edited so that a bleeped word of theirs may show (see [maskWarning]). */
  const maskWarned = useMemo(() => (subStyle.maskBleeped === false ? [] : cues.filter((c) => maskWarning(c, words, bleeped, orphans))), [cues, words, bleeped, orphans, subStyle.maskBleeped])
  /** The selected subtitle's bar and properties (a tablet with its panel open shows the properties there, as the app's). */
  const cueProps = selCue && !selTrack ? {
    cue: selCue,
    hasNext: cueAtIdx >= 0 && cueAtIdx < sortedCues.length - 1,
    hasPrev: cueAtIdx > 0,
    hasTranslation: cues.some((c) => c.tr !== undefined),
    open: cueOpen,
    onOpen: setCueOpen,
    sheet: phone,
    speakers: cueSpeakers > 1 ? cueSpeakers : 0,
    warn: maskWarned.some((c) => c.id === selCue.id),
    trHidden: subStyle.maskBleeped !== false && !!selCue.tr && !trVisible(selCue, maskCue(selCue, words, bleeped, orphans)),
    color: subStyle.color,
    chosen: subStyle.speakerColors,
    onStart: () => cueEdgeHere(true),
    onEnd: () => cueEdgeHere(false),
    onSplit: splitSelCue,
    onMerge: () => mergeSelCue(),
    onMergePrev: () => mergeSelCue('prev'),
    onMergeNext: () => mergeSelCue('next'),
    onDelete: deleteCue,
    onText: (text: string) => patchCue({ text }),
    onTranslation: (tr: string) => patchCue({ tr }),
    onSpeaker: (sp: number) => patchCue({ speaker: sp }),
    onClose: () => setSelectedCue(null),
  } : null
  const sideCue = !!cueProps && tablet && panelOpen

  const subList = subsOf(tab, { hasCues: cues.length > 0, hasWords: words.length > 0, hasFaces: analyzed || tracks.length > 0 })
  const part = (subTab[tab] && subList.some(([id]) => id === subTab[tab]) ? subTab[tab] : subList[0]?.[0]) ?? ''
  if (tab === 'subs') subsSeen.current = true
  useEffect(() => { if (tab === 'subs' && subsAlert) setSubsAlert(false) }, [tab, subsAlert])
  const lostBleeps = lost && lost.words === words && lost.bleeped === bleeped ? lost.n : 0
  // The subtitles tab's own tool (the one the Subtitles group has open), whichever group is open now.
  const subsList = subsOf('subs', { hasCues: cues.length > 0, hasWords: words.length > 0, hasFaces: false })
  const subsPart = (subTab.subs && subsList.some(([id]) => id === subTab.subs) ? subTab.subs : subsList[0][0]) as 'make' | 'list' | 'bleep' | 'style'
  // One panel body serves every group and tool: each opens at its top.
  useLayoutEffect(() => { bodyRef.current?.scrollTo(0, 0) }, [tab, part, styleTab])
  /** Whether a sub-tool's controls show: all of them on a wide screen, the open one on a phone. */
  const S = (k: string) => !narrow || part === k
  /** The group is changing the video now: a dot on its button (as in the app). */
  const groupOn = (g: EditorTab) => g === 'edit' ? cuts.length > 0 || steadyLevel > 0
    : g === 'picture' ? background.fill !== 'none' || exportOpts.aspect !== 'original'
    : g === 'subs' ? cues.length > 0
    : g === 'sound' ? voice !== 'none' || cleanupOn(clean) || loudOn || sfx.length > 0 || bleeped.size > 0
    : g === 'mark' ? watermarkOn(mark) : false
  /** A group's button: on a phone it opens the panel in the timeline's place, and a second tap closes it (as the app's group bar). */
  function pickGroup(g: EditorTab, keyboard = false) {
    if (phone && panelOpen && tab === g && !keyboard) { setPanelOpen(false); return }
    // (A blur being followed keeps its bar, with the progress and Cancel.)
    // (Focus inside the subtitles tab, which is about to be hidden, goes to the group's button.)
    if ((document.activeElement as HTMLElement | null)?.closest('.subs-keep') && g !== 'subs') (document.querySelector(`.tabs [data-g=${g}]`) as HTMLElement | null)?.focus()
    if (phone) { video.current?.pause(); if (!following) setSelected(null); setSelectedSfx(null); setAdding(false) }
    // A tablet's side panel shows the selected subtitle in the tool's place: a group picked there is shown instead.
    if (tablet) setSelectedCue(null)
    setTab(g)
    setPanelOpen(true)
  }
  /** The panel's close button: the timeline is back, and the focus goes to the group's button. */
  function closePanel() {
    setPanelOpen(false)
    // (The group's button is on screen all along: focused at once, not after a render.)
    ;(document.querySelector(`.tabs [data-g=${tab}]`) as HTMLElement | null)?.focus()
  }
  const groupName = GROUPS.find((g) => g[0] === tab)![1]
  const groupColor = GROUPS.find((g) => g[0] === tab)![3]
  const groupBar = (
    <div class="tabs" role="tablist">
          {GROUPS.map(([id, label, Icon, color], k) => (
            <button role="tab" data-g={id} class={tab === id && (!narrow || panelOpen) ? 'on' : ''} aria-selected={tab === id && (!narrow || panelOpen)} style={{ '--g': color } as any} title={`${t(label)} (${k + 1})`} onClick={() => pickGroup(id)}>
              <span class="pill-ic"><Icon size={22} />{(groupOn(id) || (id === 'subs' && (subsBusy || subsAlert))) && <span class={'dot' + (id === 'subs' && subsAlert ? ' alert' : id === 'subs' && subsBusy ? ' busy' : '')} aria-hidden="true" />}</span>
              <span>{t(label)}{id === 'sound' && sfx.length > 0 ? <span class="count"> · {sfx.length}</span> : id === 'edit' && cuts.length > 0 ? <span class="count"> · {cuts.length}</span> : null}</span>
            </button>
          ))}
        </div>
  )
  const exportBtn = (
    <button
      class="btn primary"
      // Not while a template is still being applied or the sound is being processed: the
      // export would go out without the voice change (anonymity), the cleanup or the cuts.
      disabled={!!following || tplBusy || soundBlocked}
      onClick={() => {
        video.current?.pause()
        setExportOpen(true)
        if (!srcInfo) probe()
      }}
    >
      <SaveIcon /> <span class="exp-label">{t('ייצוא')}</span>
    </button>
  )
  const saveTplBtn = <button class="btn ghost save-template-btn" onClick={() => setSaveTpl(true)}>{t('שמור כתבנית')}</button>
  const saveTplIcon = <button class="icon-btn save-template-btn" aria-label={t('שמור כתבנית')} title={t('שמור כתבנית')} onClick={() => setSaveTpl(true)}><TemplateIcon size={22} /></button>
  const editTools = (
    <div class="edit-tools">
            {!sideCue && tablet && !panelOpen && cueProps && <button class="btn tonal small-btn props-open" onClick={() => setPanelOpen(true)}><TuneIcon size={18} /> {t('מאפיינים')}</button>}
            {safeAvailable && <button class={'icon-btn safe-toggle' + (safeOn ? ' on' : '')} aria-pressed={safeOn} aria-label={t('אזור בטוח')} title={t('אזור בטוח')} onClick={() => setSafeOn(!safeOn)}><SafeAreaIcon /></button>}
            <button class="icon-btn" disabled={past.current.length === 0 || !!following} aria-label={t('בטל')} title={t('בטל') + ' (Ctrl+Z)'} onClick={undo}><span class="flip"><UndoIcon /></span></button>
            <button class="icon-btn" disabled={future.current.length === 0 || !!following} aria-label={t('בצע שוב')} title={t('בצע שוב') + ' (Ctrl+Shift+Z)'} onClick={redo}><span class="flip"><RedoIcon /></span></button>
            {/* (After undo and redo: the tests find those by place. Only with blurs to show either way.) */}
            {tracks.length > 0 && <button class={'icon-btn blur-view' + (blurView ? ' on' : '')} aria-pressed={blurView} aria-label={t('תצוגה')} title={t('תצוגה: טשטוש או קווי מתאר')} onClick={() => setBlurView(!blurView)}>{blurView ? <VisibleIcon /> : <EyeOffIcon />}</button>}
          </div>
  )
  const panelBody = (
    <>
        {tab === 'faces' && S('list') && !analyzed && (
          <section class="card">
            <h3 class="section-title">{t('פנים')}</h3>
            <p class="small muted" style="margin:0 0 12px">{t('הפנים בסרטון עוד לא זוהו. אפשר לזהות ולטשטש אותן עכשיו; הכתוביות, הסאונד וסימן המים יישארו.')}</p>
            <button class="btn primary" style="width:100%" disabled={!!following} onClick={() => { video.current?.pause(); setSelected(null); setDetecting(true) }}>{t('זהה פנים')}</button>
          </section>
        )}
        {tab === 'faces' && S('manual') && (
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
        {S('list') && <section class="card">
          <h3 class="section-title keep">{t('טשטוש פנים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{t('%d מתוך %d מטושטשות', enabled.length, tracks.length)}</span></h3>
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
        </section>}

        {S('look') && <section class="card">
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
        </section>}

          </>
        )}
        {tab === 'sound' && S('clean') && (
          <CleanupCard
            clean={clean}
            onClean={(c) => { setDenoise(c.denoise); setEnhance(c.enhance) }}
            loud={{ on: loudOn, gainDb: loudGain }}
            loudBusy={loudOn && voiceBusy}
            onLoud={(on) => { setLoudOn(on); if (!on) setLoudGain(null) }}
          />
        )}
        {tab === 'sound' && !(narrow && part === 'clean') && (
          <SoundTab
            part={narrow ? (part as 'voice' | 'sfx') : undefined}
            voice={voice}
            onVoice={setVoice}
            voiceBusy={voiceBusy}
            clips={sfx}
            time={time}
            onChange={setSfxH}
            onSeek={(at) => { const v = video.current; if (v) v.currentTime = at }}
          />
        )}
        {tab === 'edit' && S('cut') && (
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
            onMakeSubtitles={() => { setTab('subs'); setPanelOpen(true); setSubTab({ ...subTab, subs: 'make' }) }}
            onPause={() => video.current?.pause()}
          />
        )}
        {tab === 'edit' && S('steady') && <StabilizeCard level={steadyLevel} onLevel={chooseSteady} busy={picBusy} zoom={steadied ? Math.round((steadied.zoom - 1) * 100) : null} onCancel={() => { cancelPicture(); if (!picture) setSteadyLevel(0) }} />}
        {tab === 'picture' && (
          <>
            {S('bg') && <BackgroundCard bg={background} onChange={setBackground} masksReady={!!picture?.masks} busy={picBusy} onFindPeople={() => runPicture(true)} onCancel={cancelPicture} />}
            {S('frame') && <FrameCard aspect={exportOpts.aspect} onChange={(a) => setExportOpts((o) => ({ ...o, aspect: a }))} hasFaces={tracks.length > 0} />}
          </>
        )}
        {tab === 'mark' && <WatermarkTab mark={mark} onChange={setMark} />}
        {(tab === 'subs' || subsSeen.current) && (
          <div class="subs-keep" hidden={tab !== 'subs'}>
          <SubtitlesTab
            onBusy={setSubsBusy}
            onLost={setLost}
            onAttention={() => { setSubsAlert(true); setSubTab((s) => ({ ...s, subs: 'make' })) }}
            hidden={tab !== 'subs'}
            part={narrow ? subsPart : undefined}
            stylePart={styleTab}
            onPart={(p) => setSubTab((s) => ({ ...s, subs: p }))}
            file={file}
            cues={cues}
            onCues={setCuesH}
            words={words}
            onReplace={replaceSubs}
            bleeped={bleeped}
            onBleeped={setBleepedH}
            tone={tone}
            onTone={setTone}
            style={subStyle}
            onStyle={setSubStyle}
            time={tab === 'subs' ? (subsTime.current = time) : subsTime.current}
            onSeek={(at) => { const v = video.current; if (v) v.currentTime = at }}
          />
          </div>
        )}

    </>
  )
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
    <main class={'editor' + (narrow && !detecting && !hidden ? ' narrow' : '') + (tablet && !detecting && !hidden ? ' tablet' : '') + (phone && panelOpen ? ' panel-open' : '') + (tablet && panelOpen ? ' panel-shown' : '')} style={detecting || hidden ? 'display:none' : ''}>
      {tablet && groupBar}
      <div class="player">
        <div class={'row top-row' + (BETA ? ' beta' : '') + (safeAvailable && tracks.length > 0 ? ' many' : '')}>
          {narrow
            ? <button class="icon-btn back-btn" onClick={onClose} aria-label={t('סרטון חדש')} title={t('סרטון חדש')}><span class="flip"><BackIcon /></span></button>
            : <button class="btn ghost" onClick={onClose} style="padding-inline:6px"><span class="flip"><BackIcon /></span> {t('סרטון חדש')}</button>}
          {narrow && BETA && <span class="beta-mini">BETA</span>}
          <div class="grow" />
          <span class="small muted detect-info">{detectInfo}</span>
          {!narrow && editTools}
          {narrow && <div class="export-bar">{editTools}{saveTplIcon}<button class="icon-btn settings-open" onClick={() => window.dispatchEvent(new Event('onit:settings'))} aria-label={t('הגדרות')} title={t('הגדרות')}><SettingsIcon size={22} /></button>{exportBtn}</div>}
        </div>
        {an.reduced && !narrow && <DetectionNotice />}
        {an.fast && !narrow && <DetectionNotice fast />}
        {safeAvailable && <SafeZoneBar open={safeOn} platform={safePlatform} frameWidth={frameW} onPick={choosePlatform} />}
        <div class={'screen' + (adding ? ' adding' : '') + (selected !== null ? ' sel' : '')} onPointerDown={onScreenDown} onPointerMove={onScreenMove} onPointerUp={onScreenUp} onPointerCancel={(e) => { press.current = null; touches.current.delete(e.pointerId); if (touches.current.size < 2) pinch.current = null }}>
          <div class="view" ref={viewBox} style={{ aspectRatio: `${W} / ${H}`, width: `min(100%, calc(var(--screen-h) * ${(W / H).toFixed(4)}))` }}>
            <canvas ref={canvas} width={W} height={H} />
            <canvas ref={rings} class="layer" width={W} height={H} />
            <canvas ref={overlay} class="layer" width={W} height={H} />
            {(an.reduced || an.fast) && narrow && <DetectionNotice overlay reduced={!!an.reduced} fast={!!an.fast} />}
          </div>
          <video ref={video} src={url} playsInline preload="auto" />
          {adding && <div class="screen-hint">{t('הקש על הסרטון במקום שצריך לטשטש')}</div>}
          {!adding && !playing && cutHere && (
            <div class="banner cut-here" onPointerDown={(e) => e.stopPropagation()}>
              <span>{t('הקטע הזה נחתך מהסרטון')}</span>
              <button class="btn ghost small-btn" onClick={() => setCutsH(uncut(cuts, cutHere[0], cutHere[1]))}>{t('החזר')}</button>
            </div>
          )}
          {notice && <div class="toast" role="status">{notice}</div>}
        </div>
        <div class="transport">
          <div class="step-group" dir="ltr">
            <button class="icon-btn frame-prev" onClick={() => stepFrame(-1)} aria-label={t('פריים קודם')} title={t('פריים קודם')}><ChevronLeftIcon size={26} /></button>
            <button class="round" onClick={playPause} aria-label={playing ? t('השהה') : t('נגן')}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
            <button class="icon-btn frame-next" onClick={() => stepFrame(1)} aria-label={t('פריים הבא')} title={t('פריים הבא')}><ChevronRightIcon size={26} /></button>
          </div>
          <span class="time">{duration(time)} / {duration(dur)}</span>
          <div class="grow" />
          {selectedClip && (
            <div class="sel-sfx">
              <strong>{sfxName(selectedClip.sound)}</strong>
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
          cues={cues.map((c) => ({ ...c, text: shown(c) }))}
          sfx={sfx}
          sfxLength={sfxLength}
          selectedSfx={selectedSfx}
          selectedCue={selCue ? selCue.id : null}
          onSelectCue={selectCue}
          maskWarn={new Set(maskWarned.map((c) => c.id))}
          onTrimCueStart={() => record(false)}
          onTrimCue={(id, start, at) => {
            setCues((cs) => {
              const c = cs.find((x) => x.id === id)
              if (!c) return cs
              const [lo, hi] = cueRoom(cs, c, dur)
              return cs.map((x) => (x.id === id ? retimeCue(x, start, at, lo, hi) : x))
            })
            seek(Math.min(dur, Math.max(0, at)))
          }}
          bleeps={bleepMarks}
          speakerColor={(sp) => speakerColor(subStyle.color, sp, subStyle.speakerColors)}
          onScrub={(at) => { const v = video.current; if (v) v.currentTime = at }}
          onScrubStart={() => video.current?.pause()}
          selectedTrack={selected}
          onSelectTrack={(id) => { setSelected(id === selected ? null : id); setSelectedSfx(null); setSelectedCue(null) }}
          onTrimStart={() => record(false)}
          onTrim={(id, start, at) => {
            const i = frameAt(times, start ? at : at - FRAME)
            setTracks((ts) => ts.map((x) => (x.id === id ? (start ? setStart(x, i) : setEnd(x, i)) : x)))
            seek(times[i] ?? at)
          }}
          cuts={cuts}
          cutStart={cutStart}
          onSelectSfx={(id) => { setSelectedSfx(id); if (id !== null) { setSelected(null); setSelectedCue(null) } }}
          onMoveSfx={(id, at) => updateSfx(id, { at })}
          controls={timelineControls}
        />
        {/* (After the timeline, so the timeline does not move when a subtitle is selected on a wide screen; on a phone and a tablet the layout orders it.) */}
        {cueProps && <CueBar {...cueProps} open={cueOpen && !sideCue} noEdit={sideCue} />}
      </div>

      <aside class="side">
        {tplRun && (tplBusy || tplRun.notes.length > 0) && (
          <section class="card tpl-run" role="status">
            <div class="row">
              <strong class="grow">{tplBusy ? t('מחיל את התבנית…') : t('שלב בתבנית לא הושלם')}</strong>
              {!tplBusy && <button class="icon-btn" aria-label={t('סגור')} onClick={() => setTplRun(null)}><CloseIcon size={18} /></button>}
            </div>
            {tplBusy && (
              <>
                <div class="small muted tpl-step">{tplRun.step ?? (voiceBusy ? t('מעבד את הסאונד') : picBusy ? (picBusy.what === 'people' ? t('מזהה אנשים… %d%', Math.round(picBusy.progress * 100)) : t('מודד את תנועת המצלמה… %d%', Math.round(picBusy.progress * 100))) : '')}</div>
                <div class={'progress' + (tplRun.step !== null && tplRun.indet ? ' indet' : '')}><div style={tplRun.step !== null && tplRun.indet ? undefined : { width: `${Math.max(3, Math.round((tplRun.step !== null ? tplRun.progress : picBusy ? picBusy.progress : 0.5) * 100))}%` }} /></div>
                {tplRun.step !== null && <button class="btn tonal tpl-cancel" onClick={cancelTpl}>{t('ביטול')}</button>}
              </>
            )}
            {tplRun.notes.map((n) => <p class="small tpl-note" style="margin:0;color:var(--primary)">{n}</p>)}
          </section>
        )}
        {!tablet && groupBar}
        {/* One tree on every screen size (a phone turned past 600 px keeps a running job): the phone's panel, or on a wide screen a wrapper that is not there to the layout. */}
          {sideCue && <CueSidePanel {...cueProps!} />}
          <div hidden={(narrow && !panelOpen) || sideCue} class={narrow ? 'panel' + (tab === 'subs' && part === 'style' ? ' has-inner' : '') : 'panel-flat'} data-g={tab} style={{ '--g': groupColor } as any}>
            {/* A phone's panel takes the timeline's place: this thin scrubber stays over it (as the app's MiniScrubber), so moving through the video never means closing the panel. */}
            {phone && !tablet && panelOpen && (
              <input
                type="range"
                class="mini-scrubber"
                dir="ltr"
                min={0}
                max={Math.max(0.01, dur)}
                step="any"
                value={Math.min(time, dur)}
                aria-label={t('מיקום בסרטון')}
                onPointerDown={() => video.current?.pause()}
                onInput={(e) => { const v = video.current; if (v) { v.pause(); v.currentTime = Number((e.currentTarget as HTMLInputElement).value) } }}
              />
            )}
            <div class="panel-head" hidden={!narrow}>
              {!narrow ? null : subList.length > 0
                ? <ChipTabs items={subList} value={part} onPick={(id) => setSubTab({ ...subTab, [tab]: id })} label={t(groupName)} />
                : <h2 class="panel-title">{t(groupName)}</h2>}
              {narrow && <button class="icon-btn panel-close" onClick={closePanel} aria-label={t('סגור')} title={t('סגור')}><CloseIcon size={22} /></button>}
            </div>
            {narrow && tab === 'subs' && part === 'style' && <ChipTabs items={STYLE_PARTS} value={styleTab} onPick={setStyleTab} cls="inner" label={t('עיצוב כתוביות')} />}
            <div class="panel-body" ref={bodyRef} id={PANEL_ID} role={narrow ? 'tabpanel' : undefined} aria-labelledby={narrow && subList.length > 0 ? `tab-${tab === 'subs' && part === 'style' ? 'inner' : 'tool'}-${tab === 'subs' && part === 'style' ? styleTab : part}` : undefined} aria-label={narrow && subList.length === 0 ? t(groupName) : undefined}>{panelBody}</div>
          </div>
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
        {lostBleeps > 0 && (
          <div class="lost-bleeps card confirm" role="alert">
            <p class="small" style="margin:0">{lostBleeps === 1 ? t('מילה מצופצפת אחת לא נמצאה בכתוביות החדשות, ולכן היא לא מצופצפת. בטל את הפעולה או צפצף אותה שוב.') : t('%d מילים מצופצפות לא נמצאו בכתוביות החדשות, ולכן הן לא מצופצפות. בטל את הפעולה או צפצף אותן שוב.', lostBleeps)}</p>
            <div class="row"><button class="btn ghost small-btn" onClick={() => setLost(null)}>{t('הבנתי')}</button></div>
          </div>
        )}
        {maskWarned.length > 0 && (
          <div class="mask-warn card confirm" role="alert">
            <p class="small" style="margin:0">{maskWarned.length === 1 ? t('בכתובית אחת מילה מצופצפת לא נמצאת בטקסט, ולכן הכתובית עלולה להציג אותה. תקן את הטקסט כדי שיופיע בו, או צפצף את המילה שוב.') : t('ב-%d כתוביות מילה מצופצפת לא נמצאת בטקסט, ולכן הן עלולות להציג אותה. תקן את הטקסט, או צפצף את המילה שוב.', maskWarned.length)}</p>
            <div class="row"><button class="btn tonal small-btn mask-warn-go" onClick={() => { const c = [...maskWarned].sort((a, b) => a.start - b.start)[0]; selectCue(c.id); seek(c.start + 0.01) }}>{t('עבור לכתובית')}</button></div>
          </div>
        )}
        {soundFail && (
          <div class="sound-error card" role="alert" data-hard={soundFail.hard ? '1' : '0'}>
            <p class="small" style="margin:0">{soundFail.hard ? t('שינוי הקול נכשל, ולכן אי אפשר לייצא (כדי שהקול המקורי לא יישמר בטעות)') : t('עיבוד הסאונד נכשל. הסרטון ייוצא עם הסאונד המקורי')}</p>
            <div class="row" style="gap:8px;flex-wrap:wrap">
              <button class="btn tonal sound-retry" onClick={retrySound}>{t('נסה שוב')}</button>
              {soundFail.hard && <button class="btn ghost sound-drop" onClick={voice !== 'none' ? dropVoice : dropBleeps}>{voice !== 'none' ? t('הסר את שינוי הקול') : t('הסר את הצפצופים')}</button>}
              {!soundFail.hard && <button class="btn ghost" onClick={() => setSoundFail(null)}>{t('הבנתי')}</button>}
            </div>
          </div>
        )}
        {!narrow && <div class="export-bar">
          {exportBtn}
          {saveTplBtn}
        </div>}
      </aside>
    </main>
    {saveTpl && (
      <SaveTemplate
        settings={{
          blurOff: false, look, sensitive: !!an.sensitive, fast: !!an.fast, sensitivity: an.sensitivity ?? 0, createSubs: cues.length > 0, language: savedLanguage(lang.value === 'he'), translate: false, subStyle,
          split: { words: 0, chars: 0 }, burn: exportOpts.burn === false ? 'NONE' : subStyle.show === 'tr' ? 'TRANSLATED' : subStyle.show === 'both' ? 'BOTH' : 'ORIGINAL', saveSrt: exportOpts.srt,
          voice, denoise, enhance, loud: loudOn, cutSilences: cuts.length > 0, minPauseMs: 800, cutFillers: cuts.length > 0 && cues.length > 0,
          background, stabilize: steadyLevel, aspect: exportOpts.aspect, shortSide: exportOpts.shortSide, fps: exportOpts.fps, quality: exportOpts.quality, codec: exportOpts.codec, watermark: mark,
        }}
        initial={[...(analyzed || tracks.length > 0 ? ['blur' as const] : []), ...(cues.length || JSON.stringify(subStyle) !== JSON.stringify(DEFAULT_SUB_STYLE) ? ['subs' as const] : []), ...(voice !== 'none' || cleanupOn(clean) ? ['voice' as const] : []), ...(background.fill !== 'none' ? ['background' as const] : []), ...(steadyLevel > 0 ? ['stabilize' as const] : []), 'export']}
        onClose={() => setSaveTpl(false)}
        onSaved={() => { setSaveTpl(false); setNotice(t('התבנית נשמרה')) }}
      />
    )}
    {exportOpen && (
      <ExportSheet
        width={W}
        height={H}
        fps={srcInfo?.fps ?? (analyzed ? an.fps : null)}
        sourceHevc={srcInfo?.codec === 'hevc'}
        srcBitrate={srcInfo?.bitrate ?? null}
        duration={dur}
        hasCues={cues.length > 0}
        lostBleeps={lostBleeps}
        maskWarn={maskWarned.length}
        options={exportOpts}
        onChange={setExportOpts}
        onClose={() => setExportOpen(false)}
        onGo={() => {
          // A template or the sound still working, or a voice change that isn't in the processed sound: not exported.
          if (tplBusy || soundBlocked) { setExportOpen(false); return }
          setExportOpen(false)
          saveExportOptions(exportOpts)
          onExport({ file, times, tracks: enabled, look, width: W, height: H, sfx, watermark: mark, cues: cues.map((c) => ({ ...c, text: shown(c) })), subStyle, voice: voiceAudio ? { rate: voiceAudio.rate, data: voiceAudio.data } : null, anonymize: voice !== 'none' || bleeped.size > 0, cuts, words, options: exportOpts, picture: { background: background.fill !== 'none' && (background.key !== 'person' || picture?.masks) ? background : null, masks: background.key === 'person' && background.fill !== 'none' ? picture?.masks ?? null : null, steady: steadied, path: framePath } })
        }}
      />
    )}
    </>
  )
}
