import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { BlurRenderer, ellipseFor, type Ellipse } from '../lib/renderer'
import { DEFAULT_LOOK, frameAt, trackBox, type Analysis, type BlurMode, type Look } from '../lib/types'
import type { ExportJob } from './Exporting'
import { BackIcon, PauseIcon, PlayIcon, SaveIcon } from './icons'

type Props = {
  file: File
  analysis: Analysis
  thumbs: (string | null)[]
  info: string
  onExport: (job: ExportJob) => void
  onClose: () => void
}

function loadLook(): Look {
  try {
    const l = JSON.parse(localStorage.getItem('onit.look') ?? 'null')
    if (l && ['blur', 'pixelate', 'solid'].includes(l.mode)) return { ...DEFAULT_LOOK, ...l }
  } catch {}
  return DEFAULT_LOOK
}

export function Editor({ file, analysis, thumbs, info, onExport, onClose }: Props) {
  const { times, tracks, width: W, height: H } = analysis
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
  const state = useRef({ off, look, mediaTime: 0 })
  state.current.off = off
  state.current.look = look

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
    v.addEventListener('play', () => setPlaying(true))
    v.addEventListener('pause', () => setPlaying(false))
    v.addEventListener('ended', () => setPlaying(false))
    return () => {
      stop = true
      cancelAnimationFrame(raf)
      v.removeEventListener('seeked', onSeeked)
      v.removeEventListener('loadeddata', onMeta)
    }
  }, [url])

  // Redraw the paused frame when the look or the faces change.
  useEffect(() => {
    if (video.current?.paused) draw()
  }, [off, look])

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
    <main class="editor">
      <div class="player">
        <div class="row">
          <button class="btn ghost" onClick={onClose} style="padding-inline:6px"><span class="flip"><BackIcon /></span> {t('סרטון חדש')}</button>
          <div class="grow" />
          <span class="small muted">{info}</span>
        </div>
        <div class="screen" onClick={onTap}>
          <canvas ref={canvas} width={W} height={H} style={{ aspectRatio: `${W} / ${H}` }} />
          <canvas ref={overlay} width={W} height={H} style={{ position: 'absolute', inset: 0, margin: 'auto', pointerEvents: 'none', aspectRatio: `${W} / ${H}`, maxWidth: '100%', maxHeight: '62dvh' }} />
          <video ref={video} src={url} playsInline preload="auto" />
        </div>
        <div class="transport">
          <button class="round" onClick={playPause} aria-label={playing ? t('השהה') : t('נגן')}>{playing ? <PauseIcon size={26} /> : <PlayIcon size={26} />}</button>
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
          <span class="time">{duration(time)} / {duration(dur)}</span>
        </div>
      </div>

      <aside class="side">
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

        <div class="export-bar">
          <button
            class="btn primary"
            onClick={() => {
              video.current?.pause()
              onExport({ file, times, tracks: enabled, look, width: W, height: H })
            }}
          >
            <SaveIcon /> {t('ייצוא')}
          </button>
        </div>
      </aside>
    </main>
  )
}
