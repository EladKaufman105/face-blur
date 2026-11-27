// Editing blurs (tracks): new ones by hand, start and end, split, size, the result of following.
// Tracks are never changed in place: undo keeps the old ones.
import { trackLast, type Track } from './types'

export type Box = [number, number, number, number]

/** The smallest and largest size a blur can be set to (the app's 0.4 to 3). */
export const MIN_SCALE = 0.4
export const MAX_SCALE = 3

export function nextTrackId(tracks: Track[]): number {
  return tracks.reduce((m, t) => Math.max(m, t.id), -1) + 1
}

/** The box at frame [i], or the nearest one when [i] is outside the track. */
export function boxNear(t: Track, i: number): Box {
  const n = t.boxes.length / 4
  const k = Math.min(n - 1, Math.max(0, i - t.first))
  return [t.boxes[k * 4], t.boxes[k * 4 + 1], t.boxes[k * 4 + 2], t.boxes[k * 4 + 3]]
}

/** A new blur on frame [i] only (following it fills the rest). */
export function newTrack(id: number, i: number, box: Box): Track {
  return { id, first: i, boxes: Float32Array.from(box), best: i, manual: true }
}

/** Frames [a, b] of [t], holding its first or last box where it didn't reach. */
function span(t: Track, a: number, b: number): Track {
  const out = new Float32Array((b - a + 1) * 4)
  for (let i = a; i <= b; i++) out.set(boxNear(t, i), (i - a) * 4)
  return { ...t, first: a, boxes: out, best: Math.min(b, Math.max(a, t.best)), edited: true }
}

/** [t] starting at frame [i] (shorter, or longer holding its first box). */
export function setStart(t: Track, i: number): Track {
  const last = trackLast(t)
  return span(t, Math.min(i, last), last)
}

/** [t] ending at frame [i]. */
export function setEnd(t: Track, i: number): Track {
  return span(t, t.first, Math.max(i, t.first))
}

/** [t] in two at frame [i]: [first, i - 1] keeps its id, [i, last] gets [newId]. */
export function split(t: Track, i: number, newId: number): [Track, Track] | null {
  const last = trackLast(t)
  if (i <= t.first || i > last) return null
  return [span(t, t.first, i - 1), { ...span(t, i, last), id: newId }]
}

/** Every box of [t] moved by (dx, dy) (fractions of the frame). */
export function shift(t: Track, dx: number, dy: number): Track {
  const b = Float32Array.from(t.boxes)
  for (let k = 0; k < b.length; k += 4) { b[k] += dx; b[k + 1] += dy }
  return { ...t, boxes: b, edited: true }
}

/**
 * Where following took [t] from frame [from]: [dir] 0 replaces it, 1 replaces it from [from] on,
 * -1 up to [from]. [res] covers [from] (and its boxes are without the user's size).
 */
export function applyFollow(t: Track, from: number, res: { first: number; boxes: Float32Array }, dir: number): Track {
  if (dir === 0) return { ...t, first: res.first, boxes: res.boxes, best: from, edited: true }
  const r: Track = { ...t, first: res.first, boxes: res.boxes }
  const rLast = trackLast(r)
  const a = dir > 0 ? Math.min(t.first, from) : res.first
  const b = dir > 0 ? rLast : Math.max(trackLast(t), from)
  const out = new Float32Array((b - a + 1) * 4)
  for (let i = a; i <= b; i++) {
    const fromResult = dir > 0 ? i >= from : i <= from
    out.set(boxNear(fromResult ? r : t, i), (i - a) * 4)
  }
  return { ...t, first: a, boxes: out, best: from, edited: true }
}

/** A blur's frames moved to new frame times (faces found after blurs were added by hand). */
export function retime(t: Track, oldTimes: Float64Array, frameAt: (time: number) => number): Track {
  return { ...t, first: frameAt(oldTimes[t.first] ?? 0), best: frameAt(oldTimes[t.best] ?? 0) }
}
