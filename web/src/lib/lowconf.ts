// Which videos a batch flags for another look at their faces (the app's BatchMath.lowConfidenceTracks).
import type { Det, Track } from './types'

/** A video whose faces are to be checked: some blur has no clear detection behind it, detection ran lighter than the app's on this device, or fast or selfie detection ran. */
export const isLowConfidence = (a: { lowConfidence?: number; reduced?: boolean; fast?: boolean; selfie?: boolean }): boolean => (a.lowConfidence ?? 0) > 0 || !!a.reduced || !!a.fast || !!a.selfie

/**
 * How many automatic face tracks have no clear detection behind them (the best detection inside
 * the track's box, or its re-check, scored under [clear]): faces to look at again. As the app's
 * BatchMath.lowConfidenceTracks, whose flag a batch shows.
 */
export function lowConfidenceTracks(tracks: Track[], dets: Map<number, Det[]>, clear = 0.8): number {
  let low = 0
  for (const t of tracks) {
    if (t.manual) continue
    let best = 0
    const n = t.boxes.length / 4
    for (let k = 0; k < n; k++) {
      const ds = dets.get(t.first + k)
      if (!ds) continue
      const cx = t.boxes[k * 4], cy = t.boxes[k * 4 + 1], w = t.boxes[k * 4 + 2], h = t.boxes[k * 4 + 3]
      for (const d of ds) {
        const inside = Math.abs(d.cx - cx) <= Math.max(w, d.w) * 0.75 && Math.abs(d.cy - cy) <= Math.max(h, d.h) * 0.75
        // A re-check counts only for a detection the default tracker counts (from 0.4): the ones under it are re-checked only
        // with a sensitivity above 0, which must never hide a track from the flag.
        if (inside) best = Math.max(best, d.score, d.score >= 0.4 ? d.verify : 0)
      }
    }
    if (best < clear) low++
  }
  return low
}
