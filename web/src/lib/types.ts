import { storageKey } from './channel'

/** A face found on one frame: center and size as fractions of the frame. */
export interface Det {
  cx: number
  cy: number
  w: number
  h: number
  score: number
  /** Score of the same face re-detected on a centered, upscaled crop (0 if not). */
  verify: number
}

/** A face followed through the video: a box on each frame index it covers. */
export interface Track {
  id: number
  /** Frame indices, ascending, contiguous. */
  first: number
  /** 4 floats per frame (cx, cy, w, h), fractions of the frame. */
  boxes: Float32Array
  /** The best detection's frame (for the thumbnail). */
  best: number
  /** Placed by hand (and followed), not found by the detector. */
  manual?: boolean
  /** The blur's size relative to the face, set by the user (1 if not). */
  scale?: number
}

export interface Analysis {
  /** Presentation time of each frame, seconds. */
  times: Float64Array
  width: number
  height: number
  fps: number
  tracks: Track[]
  /** First frames of the shots after the first (hard cuts). */
  cuts?: number[]
  /** Automatic blurs with no clear detection behind them (a batch flags a video that has some). */
  lowConfidence?: number
  /** Detection ran lighter than the app's (a slow device: a smaller frame, fewer detections per second, or no YuNet): faces may be missed, so the video is to be checked. */
  reduced?: boolean
}

export type BlurMode = 'blur' | 'pixelate' | 'solid'

export interface Look {
  mode: BlurMode
  /** 0..1 */
  strength: number
  /** Ellipse size relative to the face box. */
  padding: number
}

export const DEFAULT_LOOK: Look = { mode: 'blur', strength: 0.6, padding: 1.6 }

/** The look last chosen (the ready screen and the editor share it). */
export function loadLook(): Look {
  try {
    const l = JSON.parse(localStorage.getItem(storageKey('look')) ?? 'null')
    if (l && ['blur', 'pixelate', 'solid'].includes(l.mode)) return { ...DEFAULT_LOOK, ...l }
  } catch {}
  return DEFAULT_LOOK
}

export function saveLook(look: Look) {
  try { localStorage.setItem(storageKey('look'), JSON.stringify(look)) } catch {}
}

export function trackBox(t: Track, i: number): [number, number, number, number] | null {
  const k = i - t.first
  if (k < 0 || k * 4 >= t.boxes.length) return null
  return [t.boxes[k * 4], t.boxes[k * 4 + 1], t.boxes[k * 4 + 2], t.boxes[k * 4 + 3]]
}

export function trackLast(t: Track) {
  return t.first + t.boxes.length / 4 - 1
}

/** The frame shown at [time] (seconds): the last one that starts at or before it. */
export function frameAt(times: Float64Array, time: number): number {
  let lo = 0, hi = times.length - 1
  if (hi < 0) return 0
  const t = time + 1e-4
  if (t < times[0]) return 0
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (times[mid] <= t) lo = mid
    else hi = mid - 1
  }
  return lo
}
