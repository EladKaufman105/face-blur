// How fast face detection runs on this device. The app's detection is the reference: the fine
// pass at 1280 px (GPU) or 960 px (CPU), a detection every `round(fps / 15)` frames (every frame
// when sensitive). A device that can't keep that up goes lighter, stepwise, and says so
// (`reduced`), as the app's FaceAnalyzer.adapt does: on the CPU the smaller frame first, then
// fewer detections; on the GPU fewer detections first, and a smaller frame only if the floor
// (8 a second) can't be met.
//
// Detections fall on whole frames, so the pace is a stride (a detection every N frames), not a
// rate: at 30 fps, 13 a second is really 10. `reduced` means the stride is longer than the
// app's, or the frame smaller.

/** The smallest the fine pass gets (the app's LOW_SIDE), the middle size, the fewest detections per second, and the GPU's floor in detections per second before it shrinks the frame. */
export const LOW_SIDE = 640
export const MID_SIDE = 960
export const MIN_RATE = 4
export const GPU_FLOOR_RATE = 8
/** Detection time allowed per second of video, per detector: analysis stays near the video's own speed. */
export const BUDGET_MS = 1000
/** Under this share of the rate the app would detect at, detection can't keep up. */
export const FULL_SHARE = 0.8
/** The rate size steps are judged against: sensitive mode (every frame) never shrinks the frame to chase 30 a second. */
export const SIZE_RATE = 15
/** The detections per second wanted. */
export const RATE = 15

/** Frames between detections as the app has them: round(fps / 15), 1 when sensitive. */
export const baseStride = (fps: number, sensitive: boolean): number => (sensitive ? 1 : Math.max(1, Math.round(fps / RATE)))

/** Frames between detections that give at most [rate] a second. */
export const strideFor = (fps: number, rate: number): number => Math.max(1, Math.ceil(fps / Math.max(rate, 1e-6) - 1e-6))

export interface PaceIn {
  /** The mean time of the latest detections, ms (not the GPU's check against the CPU, not the first ones). */
  recentMs: number
  /** Detectors running side by side. */
  workers: number
  /** The video's frames a second, and wide detection (every frame). */
  fps: number
  sensitive: boolean
  /** The fine pass's long side now, and the one detection starts at: 1280 GPU, 960 CPU, or the video's own long side if that is smaller. */
  side: number
  startSide: number
  /** Frames between detections now. */
  stride: number
  gpu: boolean
}

export interface Pace {
  /** Frames between detections from now on. */
  stride: number
  /** The fine pass's long side from now on. */
  side: number
  /** Detection is lighter than the app's (a longer stride, or a smaller frame). */
  reduced: boolean
}

/**
 * One step of pacing: a size step leaves the stride as it is (the next timings say what the new
 * size allows); a stride step leaves the size. When timings recover, the size steps back up.
 */
export function pace(i: PaceIn): Pace {
  const base = baseStride(i.fps, i.sensitive)
  const maxStride = Math.max(base, Math.floor(i.fps / MIN_RATE))
  const target = i.fps / base
  const sizeTarget = Math.min(target, SIZE_RATE)
  const wanted = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs)
  const stay = (stride: number, side = i.side): Pace => ({ stride, side, reduced: stride > base || side < i.startSide })
  if (wanted >= target * FULL_SHARE) {
    // Keeps up. If the frame was shrunk: back up a step when the bigger one would keep up too (time grows about with its area).
    if (i.side < i.startSide) {
      const up = Math.min(i.startSide, i.side < MID_SIDE ? MID_SIDE : i.startSide)
      if (wanted * (i.side / up) ** 2 >= sizeTarget * FULL_SHARE * 1.25) return stay(base, up)
    }
    return stay(base)
  }
  // Too slow for the app's pace. Whole frames: the stride is rounded up, so the rate is never more than what the device keeps up with.
  const slower = Math.min(maxStride, Math.max(base, strideFor(i.fps, wanted)))
  const down = i.side > MID_SIDE ? MID_SIDE : LOW_SIDE
  if (i.gpu) {
    // Fewer detections first; the frame shrinks only if the floor can't be met (then a detection takes over 125 ms, well over the app's 80 ms GPU limit, so 640 px is allowed).
    if (wanted >= GPU_FLOOR_RATE || i.side <= LOW_SIDE) return stay(slower)
    return stay(i.stride, down)
  }
  // The CPU: the smaller frame first (960 -> 640), then fewer detections.
  if (i.side > LOW_SIDE && wanted < sizeTarget * FULL_SHARE) return stay(i.stride, down)
  return stay(slower)
}
