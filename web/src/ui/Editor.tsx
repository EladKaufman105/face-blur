import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { BlurRenderer, ellipseFor, type Ellipse } from '../lib/renderer'
import { DEFAULT_LOOK, frameAt, trackBox, type Analysis, type BlurMode, type Look } from '../lib/types'
import type { ExportJob } from './Exporting'
import { BackIcon, PauseIcon, PlayIcon, SaveIcon } from './icons'
import { SfxScheduler, type SfxClip } from '../lib/sfx'
import { drawWatermark, watermarkOn, type Watermark } from '../lib/watermark'
import { SoundTab, SubtitlesTab, WatermarkTab, loadWatermark } from './EditorTabs'
import { Analyzing } from './Analyzing'
import { bleepRanges, cueAt, DEFAULT_SUB_STYLE, drawSubtitle, maskCue, type Cue, type SubStyle, type Word } from '../lib/subtitles'
import { makeBuffer } from '../lib/sfx'
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
}

function loadLook(): Look {
  try {
    const l = JSON.parse(localStorage.getItem('onit.look') ?? 'null')
    if (l && ['blur', 'pixelate', 'solid'].includes(l.mode)) return { ...DEFAULT_LOOK, ...l }
  } catch {}
  return DEFAULT_LOOK
}

export type EditorTab = 'faces' | 'subs' | 'sound' | 'mark'

export function Editor({ file, analysis, thumbs: thumbs0, info, onExport, onClose, initialTab = 'faces', hidden = false }: Props) {
  // Opened from a tool on the home screen, faces aren't found yet; they can be, from here.
  const [an, setAn] = useState(analysis)
  const [thumbs, setThumbs] = useState(thumbs0)
  const [detecting, setDetecting] = useState(false)
  const [detectInfo, setDetectInfo] = useState(info)
  const analyzed = an.times.length > 0
  const { times, tracks, width: W, height: H } = an
  const url = useMemo(() => URL.createObjectURL(file), [file])
  useEffect(() => () => URL.revokeObjectURL(url), [url])
  const video = useRef<HTMLVideoElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const overlay = useRef<HTMLCanvasElement>(null)
  const renderer = useRef<BlurRenderer | null>(null)
  const [off, setOff] = useState<Set<number>>(new Set())
  const [look, setLook] = useState<Look>(loadLook)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [dur, setDur] = useState(times.length ? times[times.length - 1] : 0)
  const [tab, setTab] = useState<EditorTab>(initialTab)
  const [cues, setCues] = useState<Cue[]>([])
  const [words, setWords] = useState<Word[]>([])
  const [bleeped, setBleeped] = useState<Set<number>>(new Set())
  const [tone, setTone] = useState(true)
  const [voice, setVoice] = useState<VoiceFx>('none')
  const [voiceAudio, setVoiceAudio] = useState<{ rate: number; data: Float32Array; buffer: AudioBuffer } | null>(null)
  const [voiceBusy, setVoiceBusy] = useState(false)
  const [subStyle, setSubStyle] = useState<SubStyle>(() => {
    try { return { ...DEFAULT_SUB_STYLE, ...JSON.parse(localStorage.getItem('onit.substyle') ?? '{}') } } catch { return DEFAULT_SUB_STYLE }
  })
  const [sfx, setSfx] = useState<SfxClip[]>([])
  const [mark, setMark] = useState<Watermark>(loadWatermark)
  const logo = useRef<HTMLImageElement | null>(null)
  const scheduler = useMemo(() => new SfxScheduler(), [])
  const state = useRef({ off, look, mediaTime: 0, sfx, mark, cues, subStyle, words, bleeped, voiceBuf: null as AudioBuffer | null })
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

  const facesAt = (i: number, wantOff: boolean): Ellipse[] => {
    const out: Ellipse[] = []
    for (const tr of tracks) {
      if (state.current.off.has(tr.id) !== wantOff) continue
      const b = trackBox(tr, i)
      if (b) out.push(ellipseFor(b, W, H, state.current.look.padding))
    }
    return out
  }

  const draw = () => {
    const v = video.current, c = canvas.current
    if (!v || !c || v.readyState < 2) return
    if (!renderer.current) {
      try { renderer.current = new BlurRenderer(c) } catch { return }
    }
    const i = frameAt(times, state.current.mediaTime)
    renderer.current.draw(v, W, H, facesAt(i, false), state.current.look)
    // Faces the user un-blurred: a dashed ring, so they can be tapped back on.
    const o = overlay.current
    if (o) {
      if (o.width !== W || o.height !== H) { o.width = W; o.height = H }
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
      const m = state.current.mark
      if (watermarkOn(m)) drawWatermark(ctx, m, W, H, m.image ? logo.current : null)
      const cue = cueAt(state.current.cues, state.current.mediaTime)
      if (cue) drawSubtitle(ctx, maskCue(cue, state.current.words, state.current.bleeped), state.current.subStyle, W, H)
    }
  }

  // Draw every new frame while playing, and after each seek.
  useEffect(() => {
    const v = video.current
    if (!v) return
    let stop = false
    const hasVfc = 'requestVideoFrameCallback' in v
    const onFrame = (_: number, meta: { mediaTime: number }) => {
      if (stop) return
      state.current.mediaTime = meta.mediaTime
      draw()
      setTime(meta.mediaTime)
      ;(v as any).requestVideoFrameCallback(onFrame)
    }
    let raf = 0
    const loop = () => {
      if (stop) return
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
  }, [off, look, mark, cues, subStyle])

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
    if (voice === 'none' && bleeped.size === 0) {
      setVoiceAudio(null)
      setVoiceBusy(false)
      return
    }
    setVoiceBusy(true)
    const timer = setTimeout(() => {
      audioWorker.onmessage = (e: MessageEvent<AudioMessage>) => {
        const m = e.data
        setVoiceBusy(false)
        if (m.type === 'done') setVoiceAudio({ rate: m.rate, data: m.data, buffer: makeBuffer(m.data, m.rate) })
        else setVoiceAudio(null)
      }
      const req: AudioRequest = { file, voice, bleeps: bleepRanges(words, bleeped), tone }
      audioWorker.postMessage(req)
    }, 250)
    return () => clearTimeout(timer)
  }, [voice, bleeped, tone, words])

  const toggle = (id: number) => {
    setOff((s) => {
      const n = new Set(s)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })
  }

  // A tap on a face in the video turns its blur off or on.
  const onTap = (e: MouseEvent) => {
    const c = canvas.current
    if (!c) return
    const r = c.getBoundingClientRect()
    const x = ((e.clientX - r.left) / r.width) * W
    const y = ((e.clientY - r.top) / r.height) * H
    const i = frameAt(times, state.current.mediaTime)
    let hit: number | null = null, best = Infinity
    for (const tr of tracks) {
      const b = trackBox(tr, i)
      if (!b) continue
      const el = ellipseFor(b, W, H, look.padding)
      const d = Math.hypot((x - el.cx) / el.rx, (y - el.cy) / el.ry)
      if (d < 1.1 && d < best) { best = d; hit = tr.id }
    }
    if (hit !== null) toggle(hit)
    else playPause()
  }

  const playPause = () => {
    const v = video.current
    if (!v) return
    if (v.paused) v.play().catch(() => {})
    else v.pause()
  }

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
          setAn(a)
          setThumbs(th)
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
          <span class="small muted">{detectInfo}</span>
        </div>
        <div class="screen" onClick={onTap}>
          <canvas ref={canvas} width={W} height={H} style={{ aspectRatio: `${W} / ${H}` }} />
          <canvas ref={overlay} width={W} height={H} style={{ position: 'absolute', inset: 0, margin: 'auto', pointerEvents: 'none', aspectRatio: `${W} / ${H}`, maxWidth: '100%', maxHeight: '62dvh' }} />
          <video ref={video} src={url} playsInline preload="auto" />
        </div>
        <div class="transport">
          <button class="round" onClick={playPause} aria-label={playing ? t('השהה') : t('נגן')}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
          <div class="scrub">
            <input
              type="range"
              min={0}
              max={dur || 0}
              step={0.001}
              value={time}
              onInput={(e) => {
                const v = video.current
                if (v) v.currentTime = Number((e.currentTarget as HTMLInputElement).value)
              }}
            />
            {dur > 0 && sfx.map((c) => <span class="mark" style={{ left: `${(c.at / dur) * 100}%` }} />)}
          </div>
          <span class="time">{duration(time)} / {duration(dur)}</span>
        </div>
      </div>

      <aside class="side">
        <div class="seg tabs" role="tablist">
          <button role="tab" class={tab === 'faces' ? 'on' : ''} aria-selected={tab === 'faces'} onClick={() => setTab('faces')}>{t('פנים')}</button>
          <button role="tab" class={tab === 'subs' ? 'on' : ''} aria-selected={tab === 'subs'} onClick={() => setTab('subs')}>{t('כתוביות')}</button>
          <button role="tab" class={tab === 'sound' ? 'on' : ''} aria-selected={tab === 'sound'} onClick={() => setTab('sound')}>{t('סאונד')}{sfx.length > 0 ? ` · ${sfx.length}` : ''}</button>
          <button role="tab" class={tab === 'mark' ? 'on' : ''} aria-selected={tab === 'mark'} onClick={() => setTab('mark')}>{t('סימן מים')}</button>
        </div>
        {tab === 'faces' && !analyzed && (
          <section class="card">
            <h3 class="section-title">{t('פנים')}</h3>
            <p class="small muted" style="margin:0 0 12px">{t('הפנים בסרטון עוד לא זוהו. אפשר לזהות ולטשטש אותן עכשיו; הכתוביות, הסאונד וסימן המים יישארו.')}</p>
            <button class="btn primary" style="width:100%" onClick={() => { video.current?.pause(); setDetecting(true) }}>{t('זהה פנים')}</button>
          </section>
        )}
        {tab === 'faces' && analyzed && (
          <>
        <section class="card">
          <h3 class="section-title">{t('פנים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{t('%d מתוך %d מטושטשות', enabled.length, tracks.length)}</span></h3>
          {tracks.length === 0 ? (
            <p class="muted small" style="margin:0">{t('לא נמצאו פנים בסרטון.')}</p>
          ) : (
            <>
              <div class="faces">
                {tracks.map((tr, k) => (
                  <button class={'face' + (off.has(tr.id) ? ' off' : '')} onClick={() => toggle(tr.id)} aria-pressed={!off.has(tr.id)} title={t('פנים %d', k + 1)}>
                    {thumbs[k] ? <img src={thumbs[k]!} alt={t('פנים %d', k + 1)} /> : <div class="ph" />}
                    <span class="badge">{off.has(tr.id) ? '–' : '✓'}</span>
                    <span class="lbl">{off.has(tr.id) ? t('גלוי') : t('מטושטש')}</span>
                  </button>
                ))}
              </div>
              <p class="small muted" style="margin:12px 0 8px">{t('הקש על פנים כדי לבטל או להחזיר את הטשטוש שלהן.')}</p>
              <div class="actions">
                <button class="btn tonal" onClick={() => setOff(new Set())}>{t('הכל')}</button>
                <button class="btn tonal" onClick={() => setOff(new Set(tracks.map((x) => x.id)))}>{t('אף אחד')}</button>
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
          <SoundTab
            voice={voice}
            onVoice={setVoice}
            voiceBusy={voiceBusy}
            clips={sfx}
            time={time}
            onChange={setSfx}
            onSeek={(at) => { const v = video.current; if (v) v.currentTime = at }}
          />
        )}
        {tab === 'mark' && <WatermarkTab mark={mark} onChange={setMark} />}
        {tab === 'subs' && (
          <SubtitlesTab
            file={file}
            cues={cues}
            onCues={setCues}
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

        <div class="export-bar">
          <button
            class="btn primary"
            onClick={() => {
              video.current?.pause()
              onExport({ file, times, tracks: enabled, look, width: W, height: H, sfx, watermark: mark, cues: cues.map((c) => ({ ...c, text: maskCue(c, words, bleeped) })), subStyle, voice: voiceAudio ? { rate: voiceAudio.rate, data: voiceAudio.data } : null })
            }}
          >
            <SaveIcon /> {t('ייצוא')}
          </button>
        </div>
      </aside>
    </main>
    </>
  )
}
