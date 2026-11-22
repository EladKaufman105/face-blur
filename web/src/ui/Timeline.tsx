import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration as clock } from '../i18n'
import { SFX_NAMES, type SfxClip } from '../lib/sfx'
import { trackLast, type Track } from '../lib/types'
import type { Cue } from '../lib/subtitles'
import type { ThumbsMessage, ThumbsRequest } from '../workers/thumbs.worker'

/**
 * The timeline, as in the app: a fixed playhead in the middle and lanes under it (the video's
 * frames, the faces, the subtitles, the sound effects). Drag it (or swipe) to move through the
 * video, pinch or use −/+ to zoom; drag a sound to move it, tap one to select it, tap a face to
 * turn its blur off or on, tap a subtitle to go to it. Runs left to right in any language.
 */
type Props = {
  file: File
  duration: number
  time: number
  times: Float64Array
  tracks: Track[]
  off: Set<number>
  cues: Cue[]
  sfx: SfxClip[]
  sfxLength: Map<string, number>
  selectedSfx: number | null
  onScrub: (t: number) => void
  onScrubStart: () => void
  onToggleTrack: (id: number) => void
  onSelectSfx: (id: number | null) => void
  onMoveSfx: (id: number, at: number) => void
}

const MIN_PPS = 8
const MAX_PPS = 600

export function Timeline(p: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const [viewW, setViewW] = useState(600)
  const [pps, setPps] = useState(0)
  const [thumbs, setThumbs] = useState<{ t: number; url: string }[]>([])
  const lastSet = useRef(-1)
  const userUntil = useRef(0)
  const pointers = useRef(new Map<number, number>())
  const pinch = useRef<{ dist: number; pps: number } | null>(null)
  const dur = Math.max(0.1, p.duration)
  const step = Math.max(0.5, dur / 120)

  // About ten seconds across at first.
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const ro = new ResizeObserver(() => setViewW(el.clientWidth))
    ro.observe(el)
    setViewW(el.clientWidth)
    return () => ro.disconnect()
  }, [])
  useEffect(() => {
    if (pps === 0 && viewW > 0 && p.duration > 0) setPps(clamp(viewW / Math.min(dur, 10), MIN_PPS, MAX_PPS))
  }, [viewW, p.duration])

  // The filmstrip, decoded once in a worker.
  useEffect(() => {
    const w = new Worker(new URL('../workers/thumbs.worker.ts', import.meta.url), { type: 'module' })
    const urls: string[] = []
    w.onmessage = (e: MessageEvent<ThumbsMessage>) => {
      if (e.data.type !== 'thumb') { w.terminate(); return }
      const url = URL.createObjectURL(e.data.blob)
      urls.push(url)
      const item = { t: e.data.t, url }
      setThumbs((list) => [...list, item])
    }
    const req: ThumbsRequest = { file: p.file, step, height: 64 }
    w.postMessage(req)
    return () => { w.terminate(); urls.forEach((u) => URL.revokeObjectURL(u)) }
  }, [p.file, step])

  // Follow the video, unless the user is moving the timeline.
  useEffect(() => {
    const el = scroller.current
    if (!el || pps === 0 || performance.now() < userUntil.current) return
    const x = p.time * pps
    if (Math.abs(el.scrollLeft - x) > 0.5) {
      lastSet.current = Math.round(x)
      el.scrollLeft = x
    }
  }, [p.time, pps])

  const onScroll = () => {
    const el = scroller.current
    if (!el || pps === 0) return
    if (Math.abs(el.scrollLeft - lastSet.current) <= 1) return
    userUntil.current = performance.now() + 400
    p.onScrub(clamp(el.scrollLeft / pps, 0, dur))
  }

  const zoom = (factor: number) => {
    const next = clamp(pps * factor, MIN_PPS, MAX_PPS)
    setPps(next)
    // Keep the playhead's time in the middle.
    requestAnimationFrame(() => {
      const el = scroller.current
      if (!el) return
      lastSet.current = Math.round(p.time * next)
      el.scrollLeft = p.time * next
    })
  }

  // Two fingers pinch; a mouse zooms with Ctrl (or ⌘) and the wheel.
  const onPointerDown = (e: PointerEvent) => {
    pointers.current.set(e.pointerId, e.clientX)
    userUntil.current = performance.now() + 400
    p.onScrubStart()
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      pinch.current = { dist: Math.max(10, Math.abs(a - b)), pps }
    }
  }
  const onPointerMove = (e: PointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, e.clientX)
    userUntil.current = performance.now() + 400
    if (pinch.current && pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      const next = clamp(pinch.current.pps * Math.max(10, Math.abs(a - b)) / pinch.current.dist, MIN_PPS, MAX_PPS)
      setPps(next)
      const el = scroller.current
      if (el) { lastSet.current = Math.round(p.time * next); el.scrollLeft = p.time * next }
    }
  }
  const onPointerUp = (e: PointerEvent) => {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size < 2) pinch.current = null
  }
  const onWheel = (e: WheelEvent) => {
    if (!e.ctrlKey && !e.metaKey) return
    e.preventDefault()
    zoom(e.deltaY < 0 ? 1.15 : 1 / 1.15)
  }

  // Faces in rows, so overlapping ones don't hide each other.
  const faceRows = useMemo(() => {
    const rows: { track: Track; a: number; b: number }[][] = []
    for (const tr of [...p.tracks].sort((x, y) => x.first - y.first)) {
      const a = p.times[tr.first] ?? 0
      const last = trackLast(tr)
      const b = (p.times[last] ?? a) + 1 / 30
      const row = rows.find((r) => r[r.length - 1].b + 0.05 < a)
      if (row) row.push({ track: tr, a, b })
      else rows.push([{ track: tr, a, b }])
    }
    return rows.slice(0, 6)
  }, [p.tracks, p.times])

  // Ruler marks at least 70 px apart.
  const tick = [0.5, 1, 2, 5, 10, 15, 30, 60, 120].find((s) => s * pps >= 70) ?? 300
  const ticks: number[] = []
  if (pps > 0) for (let s = 0; s <= dur; s += tick) ticks.push(s)

  const inner = pps > 0 ? dur * pps + viewW : viewW
  const pad = viewW / 2
  const x = (sec: number) => pad + sec * pps

  return (
    <div class="tl" dir="ltr">
      <div
        class="tl-scroll"
        ref={scroller}
        onScroll={onScroll}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
      >
        <div class="tl-inner" style={{ width: `${inner}px` }}>
          <div class="tl-ruler">
            {ticks.map((s) => (
              <span class="tl-tick" style={{ left: `${x(s)}px` }}>{clock(s)}{tick < 1 && s % 1 ? '.5' : ''}</span>
            ))}
          </div>
          <div class="tl-lane tl-strip">
            {thumbs.map((th) => (
              <div class="tl-thumb" style={{ left: `${x(th.t)}px`, width: `${step * pps + 1}px`, backgroundImage: `url(${th.url})` }} />
            ))}
          </div>
          {p.cues.length > 0 && (
            <div class="tl-lane tl-subs">
              {p.cues.map((c) => (
                <button class="tl-cue" style={{ left: `${x(c.start)}px`, width: `${Math.max(6, (c.end - c.start) * pps)}px` }} onClick={() => p.onScrub(c.start + 0.01)} dir="auto">
                  {c.text}
                </button>
              ))}
            </div>
          )}
          {p.sfx.length > 0 && (
            <div class="tl-lane tl-sfx">
              {p.sfx.map((c) => (
                <SfxBlock clip={c} left={x(c.at)} width={Math.max(18, (p.sfxLength.get(c.sound) ?? 0.5) * pps)} pps={pps} selected={p.selectedSfx === c.id} onSelect={() => p.onSelectSfx(c.id)} onMove={(at) => p.onMoveSfx(c.id, clamp(at, 0, dur))} />
              ))}
            </div>
          )}
          {faceRows.map((row) => (
            <div class="tl-lane tl-faces">
              {row.map(({ track, a, b }) => (
                <button
                  class={'tl-face' + (p.off.has(track.id) ? ' off' : '')}
                  style={{ left: `${x(a)}px`, width: `${Math.max(6, (b - a) * pps)}px` }}
                  onClick={() => p.onToggleTrack(track.id)}
                  title={p.off.has(track.id) ? t('גלוי') : t('מטושטש')}
                >#{track.id + 1}</button>
              ))}
            </div>
          ))}
        </div>
      </div>
      <div class="tl-playhead" />
      <div class="tl-zoom">
        <button aria-label="−" onClick={() => zoom(1 / 1.5)}>−</button>
        <button aria-label="+" onClick={() => zoom(1.5)}>+</button>
      </div>
    </div>
  )
}

/** A sound on the timeline: drag to move it (the timeline doesn't scroll meanwhile). */
function SfxBlock({ clip, left, width, pps, selected, onSelect, onMove }: { clip: SfxClip; left: number; width: number; pps: number; selected: boolean; onSelect: () => void; onMove: (at: number) => void }) {
  const drag = useRef<{ x: number; at: number; moved: boolean } | null>(null)
  return (
    <div
      class={'tl-sound' + (selected ? ' on' : '')}
      style={{ left: `${left}px`, width: `${width}px` }}
      onPointerDown={(e) => {
        e.stopPropagation()
        ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
        drag.current = { x: e.clientX, at: clip.at, moved: false }
        onSelect()
      }}
      onPointerMove={(e) => {
        const d = drag.current
        if (!d) return
        e.stopPropagation()
        const dx = e.clientX - d.x
        if (Math.abs(dx) > 3) d.moved = true
        if (d.moved) onMove(d.at + dx / pps)
      }}
      onPointerUp={() => { drag.current = null }}
      onPointerCancel={() => { drag.current = null }}
    >
      {t(SFX_NAMES.get(clip.sound) ?? clip.sound)}
    </div>
  )
}

function clamp(v: number, a: number, b: number) {
  return Math.min(b, Math.max(a, v))
}
