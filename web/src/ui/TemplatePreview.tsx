import { useEffect, useRef, useState } from 'preact/hooks'
import { t, lang } from '../i18n'
import { ASPECT_RATIO } from '../lib/camera'
import { drawCue } from '../lib/subrender'
import { loadSubFont } from '../lib/substyle'
import { drawWatermark, watermarkOn } from '../lib/watermark'
import { getFont } from '../lib/projects'
import { styleOf, type TemplateSettings } from '../lib/template'

/** The last frame of a picked video, for the previews where no video is at hand (the template editor). */
export const lastFrame: { canvas: HTMLCanvasElement | null } = { canvas: null }

/** A small picture of [file]'s first frame (null if the browser can't show it). */
export function grabFrame(file: File): Promise<HTMLCanvasElement | null> {
  return new Promise((resolve) => {
    const v = document.createElement('video')
    const url = URL.createObjectURL(file)
    let settled = false
    const done = (c: HTMLCanvasElement | null) => {
      if (settled) return
      settled = true
      URL.revokeObjectURL(url)
      v.removeAttribute('src')
      v.load()
      if (c) lastFrame.canvas = c
      resolve(c)
    }
    const grab = () => {
      if (!v.videoWidth) return done(null)
      const s = Math.min(1, 480 / Math.max(v.videoWidth, v.videoHeight))
      const c = document.createElement('canvas')
      c.width = Math.max(1, Math.round(v.videoWidth * s))
      c.height = Math.max(1, Math.round(v.videoHeight * s))
      try { c.getContext('2d')!.drawImage(v, 0, 0, c.width, c.height); done(c) } catch { done(null) }
    }
    v.muted = true
    v.preload = 'auto'
    v.playsInline = true
    v.onloadeddata = () => { if (v.currentTime < 0.05 && v.duration > 0.2) v.currentTime = Math.min(0.1, v.duration / 2); else grab() }
    v.onseeked = grab
    v.onerror = () => done(null)
    setTimeout(() => done(null), 8000)
    v.src = url
  })
}

/** The first video's frame as a canvas, once it's there (null until, and if never). */
export function useFrame(file: File | null): HTMLCanvasElement | null {
  const [frame, setFrame] = useState<HTMLCanvasElement | null>(null)
  useEffect(() => {
    let live = true
    setFrame(null)
    if (file) grabFrame(file).then((c) => { if (live) setFrame(c) })
    return () => { live = false }
  }, [file])
  return frame
}

const logos = new Map<string, Promise<HTMLImageElement | null>>()
function logo(src: string): Promise<HTMLImageElement | null> {
  let p = logos.get(src)
  if (!p) {
    p = new Promise((resolve) => { const i = new Image(); i.onload = () => resolve(i); i.onerror = () => resolve(null); i.src = src })
    logos.set(src, p)
  }
  return p
}

/** The frame's shape: the shape chosen, else the video's own. */
export function previewRatio(s: TemplateSettings, frame: HTMLCanvasElement | null): number {
  if (s.aspect !== 'original') return ASPECT_RATIO[s.aspect]
  return frame ? Math.min(1.8, Math.max(0.5, frame.width / frame.height)) : 9 / 16
}

/** A made-up frame (a person on a gradient) where there's no video. */
function scene(c: HTMLCanvasElement, w: number, h: number, frame: HTMLCanvasElement | null) {
  const g = c.getContext('2d')!
  if (frame) {
    // The frame, cut to the shape (the middle of it).
    const r = w / h, fr = frame.width / frame.height
    const sw = fr > r ? frame.height * r : frame.width
    const sh = fr > r ? frame.height : frame.width / r
    g.drawImage(frame, (frame.width - sw) / 2, (frame.height - sh) / 2, sw, sh, 0, 0, w, h)
    return
  }
  const bg = g.createLinearGradient(0, 0, w, h)
  bg.addColorStop(0, '#3b3f7a')
  bg.addColorStop(1, '#8a4a78')
  g.fillStyle = bg
  g.fillRect(0, 0, w, h)
  g.fillStyle = '#d9a98a'
  g.beginPath()
  g.ellipse(w * 0.5, h * 0.36, w * 0.13, w * 0.16, 0, 0, Math.PI * 2)
  g.fill()
  g.fillStyle = '#2d3a63'
  g.beginPath()
  g.ellipse(w * 0.5, h * 0.78, w * 0.3, h * 0.2, 0, 0, Math.PI * 2)
  g.fill()
}

/** How long a sample subtitle is up, from its first word's start: the words come 0.6 s apart. */
const wordGap = 0.6
const cueLength = (words: number) => words * wordGap + 1.2

/**
 * Draws what a video would look like with [s]: the blur look on a face, the subtitles, the watermark, in the
 * frame's shape. Returns a function that draws the same picture with the subtitle [t] seconds into its
 * showing (the effect moving), or null when nothing was drawn (a newer drawing took over).
 */
export async function drawPreview(canvas: HTMLCanvasElement, s: TemplateSettings, opts: { frame: HTMLCanvasElement | null; subs: boolean; isLive: () => boolean }): Promise<{ at: (t: number) => void; length: number } | null> {
  const w = canvas.width, h = canvas.height
  const g = canvas.getContext('2d')!
  const base = document.createElement('canvas')
  base.width = w
  base.height = h
  scene(base, w, h, opts.frame)
  // The picture with the blur, kept: each moment of an animation is this plus the subtitle.
  const still = document.createElement('canvas')
  still.width = w
  still.height = h
  const sg = still.getContext('2d')!
  sg.drawImage(base, 0, 0)
  // The blur's look, on the middle (where a face would be; a real frame has its own).
  if (!s.blurOff) {
    const cx = w * 0.5, cy = h * (opts.frame ? 0.4 : 0.36), rx = w * 0.13 * (s.look.padding / 1.6) * 1.35, ry = rx * 1.2
    sg.save()
    sg.beginPath()
    sg.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2)
    sg.clip()
    if (s.look.mode === 'solid') {
      sg.fillStyle = '#16181f'
      sg.fillRect(0, 0, w, h)
    } else {
      // Smaller and back up: smooth for the blur, in blocks for the pixels.
      const k = s.look.mode === 'pixelate' ? 4 + s.look.strength * 10 : 3 + s.look.strength * 12
      const small = document.createElement('canvas')
      small.width = Math.max(2, Math.round(w / k))
      small.height = Math.max(2, Math.round(h / k))
      small.getContext('2d')!.drawImage(base, 0, 0, small.width, small.height)
      sg.imageSmoothingEnabled = s.look.mode !== 'pixelate'
      sg.drawImage(small, 0, 0, w, h)
    }
    sg.restore()
  }
  // The subtitles, in their font (loaded first: the canvas would draw the default font until it is).
  const style = styleOf(s)
  const text = t('כך ייראו הכתוביות')
  const words = text.split(' ')
  const times = words.map((_, i) => i * wordGap)
  const length = cueLength(words.length)
  if (opts.subs) {
    const bytes = style.customFont ? await getFont(style.customFont).catch(() => null) : null
    await loadSubFont(style, document.fonts, document.baseURI, bytes)
    if (!opts.isLive()) return null
  }
  let wm: HTMLImageElement | null = null
  const wmark = s.watermark
  if (watermarkOn(wmark)) {
    if (!wmark.image) await loadSubFont(wmark, document.fonts, document.baseURI)
    wm = wmark.image ? await logo(wmark.image) : null
    if (!opts.isLive()) return null
  }
  const at = (tm: number) => {
    g.clearRect(0, 0, w, h)
    g.drawImage(still, 0, 0)
    if (opts.subs && tm < length) drawCue(g, [text], 0, length, tm, style, w, h, [times])
    if (watermarkOn(wmark)) drawWatermark(g, wmark, w, h, wm)
  }
  // At rest: the subtitle as it stands once every word is said.
  at(words.length * wordGap)
  return { at, length }
}

const reducedMotion = () => { try { return matchMedia('(prefers-reduced-motion: reduce)').matches } catch { return false } }

/**
 * A template's look as a picture: [s] (the settings it makes) on [frame] (or a made-up scene),
 * drawn with the same subtitle, blur and watermark drawing as the export.
 */
export function TemplatePreview({ s, frame, subs, height = 124, class: cls, animate = false }: { s: TemplateSettings; frame: HTMLCanvasElement | null; subs: boolean; height?: number; class?: string; /** The subtitle's effect plays, over and over (not with less motion asked for). */ animate?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const ratio = previewRatio(s, frame)
  const cssH = height
  const cssW = Math.round(Math.min(cssH * 1.8, Math.max(cssH * 0.5, cssH * ratio)))
  // The key of what's drawn: a change redraws (fonts arrive later, then it's drawn again).
  const key = JSON.stringify([s.look, s.blurOff, subs, s.subStyle, s.burn, s.watermark, s.aspect, lang.value]) + (frame ? frame.width + 'x' + frame.height : '')
  useEffect(() => {
    const c = canvas.current
    if (!c) return
    let live = true
    const dpr = 2.5
    c.width = Math.round(cssW * dpr)
    c.height = Math.round(cssH * dpr)
    let raf = 0
    let io: IntersectionObserver | null = null
    drawPreview(c, s, { frame, subs, isLive: () => live }).then((p) => {
      if (!p || !live || !animate || !subs || s.subStyle.effect === 'none' || reducedMotion()) return
      // The effect plays from the start, then rests a moment before it plays again.
      const period = p.length + 0.5
      const t0 = performance.now()
      // About 30 pictures a second, and none while the canvas is off screen.
      let visible = true, last = 0
      if (typeof IntersectionObserver !== 'undefined') {
        io = new IntersectionObserver((es) => { visible = es.some((e) => e.isIntersecting) })
        io.observe(c)
      }
      const tick = (now: number) => {
        if (!live) return
        if (visible && now - last >= 33) { last = now; p.at(((now - t0) / 1000) % period) }
        raf = requestAnimationFrame(tick)
      }
      raf = requestAnimationFrame(tick)
    }).catch(() => {})
    return () => { live = false; cancelAnimationFrame(raf); io?.disconnect() }
  }, [key, cssW, cssH, animate])
  return <canvas ref={canvas} class={'tpl-canvas ' + (cls ?? '')} style={{ width: cssW + 'px', height: cssH + 'px' }} aria-hidden="true" />
}
