// How fast face detection runs on this device. The app's detection is the reference: the fine
// pass at 1280 px (GPU) or 960 px (CPU), up to 15 detections per second of video. A device that
// can't keep that up goes lighter, stepwise, and says so (`reduced`).

/** The smallest the fine pass gets (the app's LOW_SIDE), and the fewest detections per second. */
export const LOW_SIDE = 640
export const MIN_RATE = 4
/** Detection time allowed per second of video, per detector: analysis stays near the video's own speed. */
export const BUDGET_MS = 1000
/** Below this share of the wanted rate (or at a lower size) detection counts as reduced. */
export const FULL_SHARE = 0.8

export interface Pace {
  /** Detections per second of video from now on. */
  rate: number
  /** The fine pass's long side from now on. */
  side: number
  /** Detection is lighter than the app's. */
  reduced: boolean
}

/**
 * [recentMs]: the mean time of the latest detections; [workers]: detectors running side by
 * side; [maxRate]: the rate wanted (15, or every frame when sensitive); [side]: the fine pass's
 * long side now. Keeps up: as it is. Too slow: the next smaller size first (1280, 960, 640),
 * then fewer detections per second, down to [MIN_RATE].
 */
export function pace(recentMs: number, workers: number, maxRate: number, side: number): Pace {
  const wanted = (BUDGET_MS * workers) / Math.max(1, recentMs)
  if (wanted >= maxRate * FULL_SHARE) return { rate: Math.min(maxRate, wanted), side, reduced: false }
  const rate = Math.min(maxRate, Math.max(MIN_RATE, wanted))
  if (side > LOW_SIDE) return { rate, side: side > 960 ? 960 : LOW_SIDE, reduced: true }
  return { rate, side, reduced: true }
}
