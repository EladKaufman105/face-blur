// A steadier camera and a frame that follows the faces, as the Android app's Stabilizer and
// Reframer: from the camera's motion (measured frame to frame) and the face tracks.
import type { Track } from './types'

/** Frame shape of the export: as shot, or cut to a vertical, square or 4:5 frame. */
export type Aspect = 'original' | '9:16' | '1:1' | '4:5'
export const ASPECT_RATIO: Record<Aspect, number> = { original: 0, '9:16': 9 / 16, '1:1': 1, '4:5': 4 / 5 }

/** The steadying: per frame how far to move the picture (fractions of its width / height), and one zoom. */
export type Steady = { sx: Float32Array; sy: Float32Array; zoom: number }

/** Per level (1 light, 2 medium, 3 strong): how long the path is smoothed over (s), the most a frame is moved. */
const sigmaS = (level: number) => (level === 1 ? 0.25 : level === 3 ? 0.9 : 0.5)
const maxShift = (level: number) => (level === 1 ? 0.03 : level === 3 ? 0.09 : 0.06)

/** A correction eased toward [margin] instead of stopped at it (a hard limit makes the picture stick, then jump). */
function soft(c: number, margin: number): number {
  const knee = 0.6 * margin
  const a = Math.abs(c)
  if (a <= knee) return c
  const r = margin - knee
  return Math.sign(c) * (knee + r * Math.tanh((a - knee) / r))
}

/**
 * Steadies a shaky video: the camera's path is smoothed over about a second and each frame moved
 * by the difference, then enlarged a little so the moved edges stay out of view. Each shot on its
 * own. [motion]: per frame (dx / width, dy / height) since the one before; [aspect]: height / width.
 * Null when there's nothing to steady.
 */
export function steady(motion: Float32Array, cuts: number[], aspect: number, fps: number, level: number): Steady | null {
  const n = motion.length / 2
  if (n < 3 || motion.every((v) => v === 0)) return null
  const cutSet = new Set(cuts)
  // The camera's path, both axes in widths (so they're smoothed alike).
  const ox = new Float32Array(n), oy = new Float32Array(n)
  for (let i = 1; i < n; i++) {
    if (cutSet.has(i)) continue
    ox[i] = ox[i - 1] + motion[2 * i]
    oy[i] = oy[i - 1] + motion[2 * i + 1] * aspect
  }
  for (let i = 0; i < n; i++) oy[i] /= aspect
  const starts = [0, ...[...cutSet].filter((c) => c > 0 && c < n).sort((a, b) => a - b), n]
  const sx = new Float32Array(n), sy = new Float32Array(n)
  const sigma = Math.max(1, sigmaS(level) * fps)
  const reach = Math.floor(3 * sigma)
  for (let s = 0; s + 1 < starts.length; s++) {
    const a = starts[s], b = starts[s + 1]
    for (let i = a; i < b; i++) {
      let ax = 0, ay = 0, w = 0
      for (let j = Math.max(a, i - reach); j < Math.min(b, i + reach + 1); j++) {
        const d = (j - i) / sigma
        const k = Math.exp(-0.5 * d * d)
        ax += ox[j] * k; ay += oy[j] * k; w += k
      }
      // The picture follows the smooth path instead of the shaky one.
      sx[i] = ax / w - ox[i]
      sy[i] = ay / w - oy[i]
    }
  }
  // Room for what nearly every frame needs (not the one wildest jolt), never more than the level allows.
  const need = Array.from({ length: n }, (_, i) => Math.max(Math.abs(sx[i]), Math.abs(sy[i]))).sort((a, b) => a - b)
  const margin = Math.min(maxShift(level), need[Math.floor((n - 1) * 0.98)] * 1.05)
  if (margin < 0.002) return null
  for (let i = 0; i < n; i++) { sx[i] = soft(sx[i], margin); sy[i] = soft(sy[i], margin) }
  // One zoom for the whole video (no "breathing").
  return { sx, sy, zoom: 1 / (1 - 2 * margin) }
}

/** Crop size as fractions of the frame (width, height) for [ratio] (w / h) on a [w] x [h] frame. */
export function cropFraction(w: number, h: number, ratio: number): [number, number] {
  if (ratio <= 0 || w <= 0 || h <= 0) return [1, 1]
  const frame = w / h
  return frame > ratio ? [ratio / frame, 1] : [1, frame / ratio]
}

/**
 * A virtual camera operator for vertical and square frames: per frame the crop's center
 * (fractions of the frame) follows the main face (the biggest, kept while it's still nearly as
 * big), stays still while it's near the middle, pans smoothly when it leaves, and jumps only at
 * cuts.
 */
export function reframePath(n: number, tracks: Track[], cuts: number[], fps: number, cropW: number, cropH: number): { x: Float32Array; y: Float32Array } {
  const tx = new Float32Array(n).fill(NaN), ty = new Float32Array(n).fill(NaN)
  let followed = -1
  for (let i = 0; i < n; i++) {
    let biggest = -1, bigArea = 0, keptArea = 0, keptK = -1
    const here: [number, number, number, number][] = []
    for (const t of tracks) {
      const k = i - t.first
      if (k < 0 || k * 4 >= t.boxes.length) continue
      const area = t.boxes[k * 4 + 2] * t.boxes[k * 4 + 3]
      here.push([t.id, t.boxes[k * 4], t.boxes[k * 4 + 1], area])
      if (area > bigArea) { bigArea = area; biggest = here.length - 1 }
      if (t.id === followed) { keptArea = area; keptK = here.length - 1 }
    }
    if (biggest < 0) continue
    // Keep the face being followed while it's at least 60% as big as the biggest.
    const k = keptK >= 0 && keptArea >= 0.6 * bigArea ? keptK : biggest
    followed = here[k][0]
    tx[i] = here[k][1]
    ty[i] = here[k][2]
  }
  const starts = [...new Set([0, ...cuts.filter((c) => c > 0 && c < n)])].sort((a, b) => a - b)
  starts.push(n)
  const x = new Float32Array(n), y = new Float32Array(n)
  for (let s = 0; s + 1 < starts.length; s++) {
    follow(tx, starts[s], starts[s + 1], fps, cropW, x)
    follow(ty, starts[s], starts[s + 1], fps, cropH, y)
  }
  return { x, y }
}

/** One axis of one shot [a, b): targets [t] (NaN: none) to crop centers in [out]. */
function follow(t: Float32Array, a: number, b: number, fps: number, crop: number, out: Float32Array) {
  if (crop >= 0.999) { out.fill(0.5, a, b); return }
  const lo = crop / 2, hi = 1 - crop / 2
  const clamp = (v: number) => Math.min(hi, Math.max(lo, v))
  let first = -1
  for (let i = a; i < b; i++) if (!isNaN(t[i])) { first = i; break }
  // No face in the shot: the middle.
  if (first < 0) { out.fill(clamp(0.5), a, b); return }
  // No face on a frame: hold the last one.
  const target = new Float32Array(b - a)
  let last = t[first]
  for (let i = a; i < b; i++) { if (!isNaN(t[i])) last = t[i]; target[i - a] = last }
  // A dead zone in the middle of the window; outside it the window pans at a limited speed.
  const dead = crop * 0.18
  const maxStep = (crop * 1.4) / Math.max(1, fps)
  let pos = clamp(target[0])
  const raw = new Float32Array(b - a)
  for (let i = 0; i < target.length; i++) {
    const d = target[i] - pos
    if (Math.abs(d) > dead) pos += Math.sign(d) * Math.min(Math.abs(d) - dead, maxStep)
    pos = clamp(pos)
    raw[i] = pos
  }
  // The starts and stops smoothed (a centered average over ~0.4 s).
  const r = Math.max(1, Math.round(fps * 0.2))
  for (let i = 0; i < raw.length; i++) {
    let s = 0, c = 0
    for (let j = Math.max(0, i - r); j <= Math.min(raw.length - 1, i + r); j++) { s += raw[j]; c++ }
    out[a + i] = clamp(s / c)
  }
}

/**
 * What part of the (blurred) frame becomes the exported picture, in source pixels: the crop
 * window that follows the faces, inside the steadied view (moved by sx, sy and zoomed).
 */
export function viewRect(w: number, h: number, crop: [number, number], center: [number, number], sx: number, sy: number, zoom: number): [number, number, number, number] {
  const z = Math.max(1, zoom)
  const cw = crop[0] / z, ch = crop[1] / z
  // The steadied view's center moves against the shift; the crop is taken inside it.
  const cx = 0.5 - sx + (center[0] - 0.5) / z
  const cy = 0.5 - sy + (center[1] - 0.5) / z
  return [(cx - cw / 2) * w, (cy - ch / 2) * h, cw * w, ch * h]
}
