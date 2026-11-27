// Stretches taken out of the video (seconds, sorted, not overlapping), as the Android app's
// CutMap and EditModel's cuts: what's left plays back to back. Nothing is lost from the source;
// every cut can be put back.

export type Range = [number, number]

/** Kept around a word cut by hand, so it joins a cut that nearly touches it (the app's 160 ms). */
export const CUT_JOIN = 0.16

function clean(r: Range[]): Range[] {
  return r.filter(([a, b]) => b > a).sort((x, y) => x[0] - y[0])
}

/** [a, b) added, merged with the cuts it touches (or comes within [join] of). */
export function addCut(ranges: Range[], a: number, b: number, join = 0): Range[] {
  if (b <= a) return ranges
  let s = a, e = b
  const out: Range[] = []
  for (const [x, y] of ranges) {
    if (x <= e + join && y >= s - join) { s = Math.min(s, x); e = Math.max(e, y) }
    else out.push([x, y])
  }
  out.push([s, e])
  return clean(out)
}

/** Many stretches at once (silences, filler words); returns the new list and how many were new. */
export function addCuts(ranges: Range[], add: Range[]): { ranges: Range[]; added: number } {
  let r = ranges
  let added = 0
  for (const [a, b] of add) {
    if (b <= a || covers(r, a, b)) continue
    r = addCut(r, a, b)
    added++
  }
  return { ranges: r, added }
}

/** [a, b) taken out of the cuts: it plays again. */
export function uncut(ranges: Range[], a: number, b: number): Range[] {
  const out: Range[] = []
  for (const [x, y] of ranges) {
    if (x >= b || y <= a) { out.push([x, y]); continue }
    if (x < a) out.push([x, a])
    if (y > b) out.push([b, y])
  }
  return clean(out)
}

/** Whether most of [a, b) is cut. */
export function covers(ranges: Range[], a: number, b: number): boolean {
  if (b <= a) return false
  let c = 0
  for (const [x, y] of ranges) c += Math.max(0, Math.min(y, b) - Math.max(x, a))
  return c * 2 >= b - a
}

/** The cut around [t], if it's in one. */
export function cutAt(ranges: Range[], t: number): Range | null {
  for (const r of ranges) if (t >= r[0] && t < r[1]) return r
  return null
}

export function cutTotal(ranges: Range[]): number {
  return ranges.reduce((s, [a, b]) => s + (b - a), 0)
}

/** Maps a time in the source to its time in the result. */
export class CutMap {
  private a: Float64Array
  private b: Float64Array
  /** Removed before cut k starts. */
  private before: Float64Array

  constructor(ranges: Range[]) {
    // Overlapping and touching cuts are one (a playhead never lands "between" two of them).
    const r: Range[] = []
    for (const [x, y] of clean(ranges)) {
      const last = r[r.length - 1]
      if (last && x <= last[1]) last[1] = Math.max(last[1], y)
      else r.push([x, y])
    }
    this.a = Float64Array.from(r.map((x) => x[0]))
    this.b = Float64Array.from(r.map((x) => x[1]))
    this.before = new Float64Array(r.length)
    let sum = 0
    for (let k = 0; k < r.length; k++) { this.before[k] = sum; sum += this.b[k] - this.a[k] }
  }

  get empty() { return this.a.length === 0 }

  /** Last cut starting at or before [t], or -1. */
  private at(t: number): number {
    let lo = 0, hi = this.a.length - 1, k = -1
    while (lo <= hi) {
      const m = (lo + hi) >> 1
      if (this.a[m] <= t) { k = m; lo = m + 1 } else hi = m - 1
    }
    return k
  }

  isCut(t: number): boolean {
    const k = this.at(t)
    return k >= 0 && t < this.b[k]
  }

  /** End of the cut [t] is in, or -1. */
  cutEnd(t: number): number {
    const k = this.at(t)
    return k >= 0 && t < this.b[k] ? this.b[k] : -1
  }

  /** The result's time for source time [t] (a cut time maps to where the cut was). */
  out(t: number): number {
    const k = this.at(t)
    if (k < 0) return t
    if (t < this.b[k]) return this.a[k] - this.before[k]
    return t - this.before[k] - (this.b[k] - this.a[k])
  }

  /** How much of [s, e) is kept. */
  kept(s: number, e: number): number {
    let k = e - s
    // From the cut at or before [s] on (the cuts are in order and apart), up to the first one after [e].
    for (let i = Math.max(0, this.at(s)); i < this.a.length && this.a[i] < e; i++) k -= Math.max(0, Math.min(this.b[i], e) - Math.max(this.a[i], s))
    return Math.max(0, k)
  }

  /** The kept pieces of [s, e). */
  pieces(s: number, e: number): Range[] {
    const out: Range[] = []
    let from = s
    for (let i = 0; i < this.a.length && from < e; i++) {
      if (this.b[i] <= from) continue
      if (this.a[i] >= e) break
      if (this.a[i] > from) out.push([from, this.a[i]])
      from = Math.max(from, this.b[i])
    }
    if (from < e) out.push([from, e])
    return out
  }
}

/**
 * Where playback has to jump to when it reaches a cut, or null when it needn't. [at]: the time
 * of the frame on screen; [current]: the player's currentTime; [fps]: the frame rate; [duration]:
 * the video's length; [times]: the frames' times, when they are known (sorted).
 *
 * A player shows the frame at or before the time it is sent to, so a seek to the end of a cut
 * can show the last frame of the cut itself, which would be jumped over again, and again (a
 * frozen clock and a flicker). The target is therefore the first frame at or after the cut's end
 * (or, with no frame times, a frame's length past it). Cuts that follow within half a frame are
 * one. [to] is never before [current] (no seek back into a cut). [end]: the cut runs to the end
 * of the video, where playback stops.
 */
export function skipTarget(cm: CutMap, at: number, current: number, fps: number, duration: number, times?: ArrayLike<number> | null): { to: number; end: boolean } | null {
  if (cm.empty) return null
  const step = 1 / (fps > 0 ? Math.min(fps, 240) : 30)
  // (The half-frame lookahead is for playback only; the cuts themselves, and the export, are exact.)
  let end = cm.cutEnd(at + step / 2)
  if (end < 0) return null
  for (let i = 0; i < 1000; i++) {
    const next = cm.cutEnd(end + step / 2)
    if (next > end) end = next
    else break
  }
  if (isFinite(duration) && end >= duration - step / 2) return { to: duration, end: true }
  // With no frame times, half a frame past the end: the next frame lies within a frame, and a kept frame is not skipped.
  let to = end + step / 2
  if (times && times.length > 1) {
    // The first frame at or after the end of the cut.
    let lo = 0, hi = times.length - 1
    while (lo < hi) {
      const m = (lo + hi) >> 1
      if (times[m] >= end - 1e-6) hi = m
      else lo = m + 1
    }
    if (times[lo] >= end - 1e-6) to = times[lo] + 1e-3
  }
  if (isFinite(duration)) to = Math.min(to, duration)
  if (to <= current) return null
  return { to, end: false }
}
