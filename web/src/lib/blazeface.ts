// Adapted from MediaPipe (https://github.com/google-ai-edge/mediapipe, Apache License 2.0; text in
// shared/assets/licences/Apache-2.0.txt, see THIRD-PARTY-NOTICES.md): the anchors, box decoding and
// weighted NMS follow its calculators; no MediaPipe code is shipped.
//
// MediaPipe's BlazeFace full-range face detector (Apache-2.0), the post-processing as MediaPipe's
// own calculators do it (SsdAnchorsCalculator, TensorsToDetectionsCalculator,
// NonMaxSuppressionCalculator with the weighted algorithm), as the app's BlazeFace.kt. Pure (no
// ONNX Runtime, no browser), so a Node test imports it (tests/blazeface.cjs); the model runs in
// lib/blazeface-detector.ts.
//
// The model takes the frame letterboxed into a 192 x 192 square (black bars, scaled to fit),
// pixels as (v - 127.5) / 127.5, and gives for each of its 2304 anchors (one per cell of a 48 x 48
// grid, stride 4) 16 numbers (x, y, width, height of the box relative to the anchor, in input
// pixels, then 6 landmarks) and a score as a logit.
import type { Det } from './types'
// With the extension: tests run it in Node as it is.
import { SELFIE_POOL, extras } from './sensitivity.ts'

/** The model's input side, in pixels. */
export const SIZE = 192
/** The anchors' grid is GRID x GRID, one anchor per cell. */
export const GRID = 48
/** Anchors, and the numbers per anchor in the regressors' tensor (4 box, 6 landmarks x 2). */
export const ANCHORS = GRID * GRID
export const VALUES = 16
/** MediaPipe's score threshold for this model's faces (min_score_thresh). */
export const THRESHOLD = 0.5
/** Detections overlapping this much (IoU) are one face (min_suppression_threshold). */
export const NMS_IOU = 0.3
/** Scores are clipped to +-100 as logits before the sigmoid (score_clipping_thresh). */
const CLIP = 100

/** One detection in the model's square: its score and the box's corners as fractions of the side. */
export interface Cand { score: number; x0: number; y0: number; x1: number; y1: number }

/** Anchor centers (x, y as fractions of the square), row by row: anchor i is cell (i % GRID, i / GRID); every anchor's width and height is 1 (fixed_anchor_size). */
export const anchors: Float32Array = (() => {
  const a = new Float32Array(ANCHORS * 2)
  for (let y = 0; y < GRID; y++) for (let x = 0; x < GRID; x++) {
    const i = y * GRID + x
    a[2 * i] = (x + 0.5) / GRID
    a[2 * i + 1] = (y + 0.5) / GRID
  }
  return a
})()

export const sigmoid = (logit: number): number => 1 / (1 + Math.exp(-Math.min(CLIP, Math.max(-CLIP, logit))))

/** The anchors whose score reaches [threshold], decoded to boxes in the square. [regressors] is ANCHORS x VALUES numbers, [logits] ANCHORS. Not suppressed yet (see weightedNms). */
export function decode(regressors: ArrayLike<number>, logits: ArrayLike<number>, threshold: number): Cand[] {
  if (regressors.length < ANCHORS * VALUES || logits.length < ANCHORS) throw new Error('unexpected BlazeFace output sizes')
  const out: Cand[] = []
  for (let i = 0; i < ANCHORS; i++) {
    const score = sigmoid(logits[i])
    if (score < threshold) continue
    const r = i * VALUES
    const xc = regressors[r] / SIZE + anchors[2 * i]
    const yc = regressors[r + 1] / SIZE + anchors[2 * i + 1]
    const w = regressors[r + 2] / SIZE
    const h = regressors[r + 3] / SIZE
    out.push({ score, x0: xc - w / 2, y0: yc - h / 2, x1: xc + w / 2, y1: yc + h / 2 })
  }
  return out
}

const overlap = (a: Cand, b: Cand): number => {
  const iw = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0)
  const ih = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0)
  if (iw <= 0 || ih <= 0) return 0
  const inter = iw * ih
  const union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
  return union > 0 ? inter / union : 0
}

/**
 * MediaPipe's weighted non-maximum suppression: from the highest score down, every detection that
 * overlaps it by more than [minOverlap] (IoU; itself included) joins its group, which becomes one
 * detection: the group's boxes averaged by score, with the highest score. Highest score first.
 */
export function weightedNms(cands: Cand[], minOverlap = NMS_IOU): Cand[] {
  let remaining = [...cands].sort((a, b) => b.score - a.score)
  const out: Cand[] = []
  while (remaining.length > 0) {
    const top = remaining[0]
    const group: Cand[] = []
    const rest: Cand[] = []
    for (const c of remaining) (overlap(c, top) > minOverlap ? group : rest).push(c)
    // Can't happen (the top overlaps itself, unless its box is empty): drop it, as MediaPipe stops.
    if (group.length === 0) break
    let total = 0, x0 = 0, y0 = 0, x1 = 0, y1 = 0
    for (const c of group) {
      total += c.score
      x0 += c.x0 * c.score; y0 += c.y0 * c.score; x1 += c.x1 * c.score; y1 += c.y1 * c.score
    }
    out.push({ score: top.score, x0: x0 / total, y0: y0 / total, x1: x1 / total, y1: y1 / total })
    remaining = rest
  }
  return out
}

/** How a w x h frame sits in the square: scaled to fit and centered, so the bars are padX (left and right) and padY (top and bottom), as fractions of the square. */
export function letterbox(w: number, h: number) {
  const scale = SIZE / Math.max(w, h)
  return { scale, padX: (SIZE - w * scale) / 2 / SIZE, padY: (SIZE - h * scale) / 2 / SIZE }
}

/** A detection as a box in a frame whose letterbox is [lb] (fractions of its width and height). The tracker's own score for a crop re-check is the detector's score: BlazeFace runs alone here, with no second look. */
export function toDet(c: Cand, lb: { padX: number; padY: number }): Det {
  const sx = 1 - 2 * lb.padX, sy = 1 - 2 * lb.padY
  const x0 = (c.x0 - lb.padX) / sx, x1 = (c.x1 - lb.padX) / sx
  const y0 = (c.y0 - lb.padY) / sy, y1 = (c.y1 - lb.padY) / sy
  return { cx: (x0 + x1) / 2, cy: (y0 + y1) / 2, w: x1 - x0, h: y1 - y0, score: c.score, verify: c.score }
}

/**
 * The faces in a w x h frame: the default ones (score THRESHOLD, exactly as at sensitivity 0), and
 * with [withExtras] (a sensitivity above 0) the extra ones: faces from a lower, fixed pool
 * (SELFIE_POOL) that are not a face the default ones have (by IoU in the frame). The pool doesn't
 * change with the sensitivity, so a higher sensitivity has every face a lower one has.
 */
export function faces(regressors: ArrayLike<number>, logits: ArrayLike<number>, w: number, h: number, withExtras: boolean): { faces: Det[]; extras: Det[] } {
  const lb = letterbox(w, h)
  const kept = weightedNms(decode(regressors, logits, THRESHOLD)).map((c) => toDet(c, lb))
  if (!withExtras) return { faces: kept, extras: [] }
  const pool = weightedNms(decode(regressors, logits, SELFIE_POOL)).map((c) => toDet(c, lb))
  return { faces: kept, extras: extras(kept, pool, SELFIE_POOL) }
}
