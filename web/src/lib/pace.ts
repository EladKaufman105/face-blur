// How fast face detection runs on this device. The app's detection is the reference: the fine
// pass at 1280 px (GPU) or 960 px (CPU), up to 15 detections per second of video. A device that
// can't keep that up goes lighter, stepwise, and says so (`reduced`), as the app's
// FaceAnalyzer.adapt does: on the CPU the smaller frame first, then fewer detections; on the GPU
// fewer detections first, and a smaller frame only if even that fails (never below 960 px unless
// the GPU is slower than the app's 80 ms threshold).

/** The smallest the fine pass gets (the app's LOW_SIDE), the fewest detections per second, and the GPU's floor in detections per second before it shrinks the frame. */
export const LOW_SIDE = 640
export const MID_SIDE = 960
export const MIN_RATE = 4
export const GPU_FLOOR_RATE = 8
/** A GPU slower than this per detection (ms) may go below 960 px (the app's GPU_SLOW_MS). */
export const GPU_SLOW_MS = 80
/** Detection time allowed per second of video, per detector: analysis stays near the video's own speed. */
export const BUDGET_MS = 1000
/** Below this share of the wanted rate (or at a lower size) detection counts as reduced. */
export const FULL_SHARE = 0.8
/** The rate size steps are judged against: sensitive mode (every frame) never shrinks the frame to chase 30 a second. */
export const SIZE_RATE = 15

export interface PaceIn {
  /** The mean time of the latest detections, ms (not the GPU's check against the CPU, not the first ones). */
  recentMs: number
  /** Detectors running side by side. */
  workers: number
  /** The rate wanted: 15, or every frame when sensitive. */
  maxRate: number
  /** The fine pass's long side now, and the one detection starts at (1280 GPU, 960 CPU). */
  side: number
  startSide: number
  /** The rate now. */
  rate: number
  gpu: boolean
}

export interface Pace {
  /** Detections per second of video from now on. */
  rate: number
  /** The fine pass's long side from now on. */
  side: number
  /** Detection is lighter than the app's (a smaller frame, or a rate under 80% of the wanted). */
  reduced: boolean
}

/**
 * One step of pacing: a size step leaves the rate as it is (the next timings say what the new
 * size allows); a rate step leaves the size. When timings recover, the size steps back up.
 */
export function pace(i: PaceIn): Pace {
  const target = Math.min(i.maxRate, SIZE_RATE)
  const wanted = (BUDGET_MS * i.workers) / Math.max(1, i.recentMs)
  const stay = (rate: number, side = i.side): Pace => ({ rate, side, reduced: side < i.startSide || rate < i.maxRate * FULL_SHARE })
  if (wanted >= i.maxRate * FULL_SHARE) {
    // Keeps up. If the frame was shrunk: back up a step when the bigger one would keep up too (time grows about with its area).
    if (i.side < i.startSide) {
      const up = Math.min(i.startSide, i.side < MID_SIDE ? MID_SIDE : i.startSide)
      if (wanted * (i.side / up) ** 2 >= target * FULL_SHARE * 1.25) return stay(Math.min(i.maxRate, wanted), up)
    }
    return stay(Math.min(i.maxRate, wanted))
  }
  const rate = Math.min(i.maxRate, Math.max(MIN_RATE, wanted))
  if (i.gpu) {
    // Fewer detections first; the frame shrinks only if the floor can't be met, and below 960 only for a GPU as slow as that.
    if (wanted >= GPU_FLOOR_RATE) return stay(rate)
    const floor = i.recentMs > GPU_SLOW_MS ? LOW_SIDE : Math.min(MID_SIDE, i.side)
    if (i.side > floor) return stay(i.rate, i.side > MID_SIDE ? MID_SIDE : LOW_SIDE)
    return stay(rate)
  }
  // The CPU: the smaller frame first (960 -> 640), then fewer detections.
  if (i.side > LOW_SIDE && wanted < target * FULL_SHARE) return stay(i.rate, i.side > MID_SIDE ? MID_SIDE : LOW_SIDE)
  return stay(rate)
}
