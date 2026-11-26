// The detection modes and the sensitivity setting, as the app's DetectTuning.kt. Pure (no
// browser): web/tests/fusion.cjs imports it.
//
// Modes: normal (the default, about 15 detections a second), extended ("sensitive": every frame,
// weaker evidence) and fast (the same detector at 5 a second, never fewer; it may briefly miss
// small or distant faces, or faces at the frame's edge, and says so).
//
// Sensitivity is one number from 0 to 1 (in steps of 5%). 0 is the default detection exactly (no
// extra work at all). Above 0 every threshold moves, linearly, toward more detections, never above
// its default (the privacy floor), down to MOST at 1:
//
//   threshold                            0 (default)  1 (most)
//   detection cut (fused score)          0.40         0.25
//   tracklet birth                       0.45         0.30
//   strong evidence                      0.60         0.45
//   crop re-check evidence               0.50         0.30
//   crop re-check, very short tracklet   0.90         0.70
//
// The detectors' own thresholds don't move with it: above 0 their candidates are also taken from a
// fixed, lower pool (RetinaFace 0.20, YuNet 0.25, a face YuNet alone finds 0.45), fused as the
// default ones, and cut at the detection cut; those that are not a face the default detection has
// are the extra detections (re-checked on a crop too). The default detections and tracks are made
// exactly as without the setting and kept whole; the tracks of each 5% step up to the setting are
// added to them in turn, never changing or taking away a box (widen, combineTracks). So a higher
// sensitivity never blurs less than a lower one, nor than the default. CLEAR (0.8: no re-check
// needed, no "check the faces" flag) doesn't move.
import type { Det, Track } from './types'
// With the extension: tests/fusion.cjs runs it in Node as it is.
import { iou } from './fusion.ts'

export type DetectMode = 'normal' | 'extended' | 'fast'

/** Extended wins over fast: a choice never gets weaker than one asked for. */
export const modeOf = (sensitive: boolean, fast = false): DetectMode => (sensitive ? 'extended' : fast ? 'fast' : 'normal')

/** Fast detection's rate (detections a second of video): also its floor. */
export const FAST_RATE = 5

export interface Thresholds {
  /** A detection under this is no evidence of a face (the tracker's DET_THRESHOLD). */
  cut: number
  /** A detection that starts a new tracklet needs this much. */
  birth: number
  /** A detection this sure is strong evidence. */
  strong: number
  /** The crop re-check's score that counts as evidence. */
  verified: number
  /** The crop re-check's score a very short tracklet needs (twice). */
  shortVerified: number
}

export const DEFAULT_THRESHOLDS: Readonly<Thresholds> = Object.freeze({ cut: 0.4, birth: 0.45, strong: 0.6, verified: 0.5, shortVerified: 0.9 })
export const MOST: Readonly<Thresholds> = Object.freeze({ cut: 0.25, birth: 0.3, strong: 0.45, verified: 0.3, shortVerified: 0.7 })

/** The candidate pool above sensitivity 0: RetinaFace's and YuNet's score thresholds, and a face YuNet alone finds. */
export const POOL_RF = 0.2
export const POOL_YUNET = 0.25
export const POOL_YUNET_ONLY = 0.45
/** Extra detections a frame gets the crop re-check on (the likeliest first). */
export const MAX_EXTRA_CHECKS = 12
/** An extra detection overlapping a default one this much (IoU) is that face: not extra. */
export const SAME_FACE_IOU = 0.3
const STEPS = 20

/** [s] within 0..1 (anything else: 0). */
export const clamp = (s: unknown): number => (typeof s === 'number' && Number.isFinite(s) ? Math.min(1, Math.max(0, s)) : 0)
/** [s] on the setting's steps of 5%. */
export const quantize = (s: unknown): number => Math.round(clamp(s) * STEPS) / STEPS
/** The steps a sensitivity of [s] goes through: 5%, 10%, ... up to [s] (none at 0). */
export const levels = (s: unknown): number[] => Array.from({ length: Math.round(clamp(s) * STEPS) }, (_, i) => (i + 1) / STEPS)

/** The thresholds at sensitivity [s]: the defaults themselves at 0, each one lower as [s] grows. */
export function thresholds(s: unknown): Readonly<Thresholds> {
  const k = clamp(s)
  if (k === 0) return DEFAULT_THRESHOLDS
  const lerp = (a: number, b: number) => a + (b - a) * k
  const d = DEFAULT_THRESHOLDS
  return { cut: lerp(d.cut, MOST.cut), birth: lerp(d.birth, MOST.birth), strong: lerp(d.strong, MOST.strong), verified: lerp(d.verified, MOST.verified), shortVerified: lerp(d.shortVerified, MOST.shortVerified) }
}

/**
 * The extra detections at a cut: the pool's fused faces [pool] from [cut] up that aren't a face the
 * default detection has ([kept]). The pool doesn't change with the sensitivity, so a lower cut
 * keeps every face a higher one does.
 */
export const extras = (kept: Det[], pool: Det[], cut: number): Det[] =>
  pool.filter((p) => p.score >= cut && kept.every((k) => iou(k, p) <= SAME_FACE_IOU))

const boxAt = (t: Track, f: number): Det | null => {
  const k = f - t.first
  if (k < 0 || k * 4 >= t.boxes.length) return null
  return { cx: t.boxes[k * 4], cy: t.boxes[k * 4 + 1], w: t.boxes[k * 4 + 2], h: t.boxes[k * 4 + 3], score: 0, verify: 0 }
}
const lastOf = (t: Track) => t.first + t.boxes.length / 4 - 1

/** Mostly the same face: IoU over SAME_FACE_IOU on at least half the frames both have. */
function sameFace(a: Track, b: Track): boolean {
  const from = Math.max(a.first, b.first), to = Math.min(lastOf(a), lastOf(b))
  let shared = 0, over = 0
  for (let f = from; f <= to; f++) {
    const x = boxAt(a, f), y = boxAt(b, f)
    if (!x || !y) continue
    shared++
    if (iou(x, y) > SAME_FACE_IOU) over++
  }
  return shared > 0 && over * 2 >= shared
}

/**
 * Adds the tracks [more] (made with lower thresholds) to [tracks], never changing or taking away a
 * box that is there: a track that is mostly the face of one there only adds its frames right before
 * and after it (a face found earlier or followed longer is blurred longer); any other is another
 * face. The result has every frame and box of [tracks] (the ids of those stay).
 */
export function combineTracks(tracks: Track[], more: Track[]): Track[] {
  if (more.length === 0) return tracks
  const out = tracks.map((t) => ({ ...t }))
  for (const m of more) {
    const i = out.findIndex((t) => sameFace(t, m))
    if (i < 0) {
      out.push({ ...m, id: out.length })
      continue
    }
    const t = out[i]
    // Only the frames right before and after the track: both are contiguous and share frames (sameFace), so these join it.
    const pre = Math.max(0, t.first - m.first)
    const post = Math.max(0, lastOf(m) - lastOf(t))
    if (pre === 0 && post === 0) continue
    const boxes = new Float32Array(t.boxes.length + 4 * (pre + post))
    if (pre > 0) boxes.set(m.boxes.subarray(0, 4 * pre), 0)
    boxes.set(t.boxes, 4 * pre)
    if (post > 0) boxes.set(m.boxes.subarray(m.boxes.length - 4 * post), 4 * pre + t.boxes.length)
    out[i] = { ...t, first: t.first - pre, boxes }
  }
  return out
}

/**
 * The tracks at sensitivity [s]: the default tracks [base] as they are, then step by step (levels)
 * the tracks [more] makes at each step's thresholds (combineTracks). Each step only adds, so a
 * higher sensitivity has every box a lower one has.
 */
export const widen = (base: Track[], s: unknown, more: (level: number) => Track[]): Track[] =>
  levels(s).reduce((acc, level) => combineTracks(acc, more(level)), base)
