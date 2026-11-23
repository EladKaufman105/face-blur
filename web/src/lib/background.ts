// Background removal, as in the Android app: keep the people (found on the device) or everything
// but a green or blue screen, and put a blur, a color or a picture behind.

/** What's kept: the people, or all but a green / blue screen. */
export type BgKey = 'person' | 'green' | 'blue'
/** What goes behind: nothing changed, the background blurred, a color or a picture. */
export type BgFill = 'none' | 'blur' | 'color' | 'image'

export interface Background {
  key: BgKey
  fill: BgFill
  /** #rrggbb */
  color: string
  /** How far from the screen's color still counts as screen (0..1). */
  tolerance: number
  /** The picture (a data URL), for 'image'. */
  image: string | null
}

export const NO_BACKGROUND: Background = { key: 'person', fill: 'none', color: '#0f1115', tolerance: 0.5, image: null }

export const BG_COLORS = ['#00b140', '#0f1115', '#ffffff', '#ff4d6d', '#ffc53d', '#3ddcff', '#8b6cff']

/** The people's masks from the picture pass: [frames] ascending, one w x h mask (0..255) each. */
export type PeopleMasks = { w: number; h: number; frames: Int32Array; data: Uint8Array }

/** The mask nearest frame [i] (its index in [m]), or -1. */
export function maskIndex(m: PeopleMasks, i: number): number {
  const f = m.frames
  if (f.length === 0) return -1
  let lo = 0, hi = f.length - 1
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1
    if (f[mid] <= i) lo = mid
    else hi = mid
  }
  return Math.abs(f[hi] - i) < Math.abs(f[lo] - i) ? hi : lo
}

export const maskAt = (m: PeopleMasks, k: number) => m.data.subarray(k * m.w * m.h, (k + 1) * m.w * m.h)
