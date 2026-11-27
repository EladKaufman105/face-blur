// "Run detection again": what happens to the blurs when a second detection of the same video comes
// in. Pure (no browser, no worker), imported by tests/rerun.cjs; the app's twin is
// analysis/RerunMerge.kt (same rules, same IoU: SAME_FACE_IOU on at least half the shared frames).
//
//  - Add: every blur there stays exactly as it is (manual ones, edited ones, turned-off ones). A face
//    the new run found is added only if it isn't mostly the face of a blur already there. Never fewer.
//  - Replace: the blurs the detector made and the user never touched are taken out and the new run's
//    come in. Manual blurs, and automatic ones the user changed or turned off, stay (the new run's
//    face that is mostly one of those is not added twice).
import type { Analysis, Track } from './types'
import { sameFace } from './sensitivity.ts'

export type RerunMode = 'add' | 'replace'

/** The remembered choice's key (shared/prefs/choices.json). */
export const RERUN_KEY = 'rerun.merge'

/** A blur the user made or changed: it is never taken away by a re-run. */
export const isEdited = (t: Track, off: ReadonlySet<number>): boolean => !!t.manual || !!t.edited || t.scale !== undefined || off.has(t.id)

export type Merged = {
  tracks: Track[]
  /** Blurs turned off (kept ones only). */
  off: Set<number>
  /** The new run's blurs that came in, under their new ids. */
  added: Track[]
  /** The old blurs that went out (Replace). */
  removed: Track[]
  /** A new blur's id -> the id it had in the new run (to find its picture). */
  from: Map<number, number>
}

/**
 * The blurs after a re-run. [existing] are untouched in Add; in Replace the automatic ones nobody
 * edited go. New blurs get ids above every old id, so an undo that brings back an old one never clashes.
 */
export function mergeRerun(existing: Track[], off: ReadonlySet<number>, found: Track[], mode: RerunMode): Merged {
  const keep = mode === 'add' ? existing : existing.filter((t) => isEdited(t, off))
  const removed = existing.filter((t) => !keep.includes(t))
  let id = existing.reduce((m, t) => Math.max(m, t.id), -1) + 1
  const added: Track[] = []
  const from = new Map<number, number>()
  for (const f of found) {
    if (keep.some((k) => sameFace(k, f))) continue
    const tr = { ...f, id: id++ }
    added.push(tr)
    from.set(tr.id, f.id)
  }
  const gone = new Set(removed.map((t) => t.id))
  return { tracks: [...keep, ...added], off: new Set([...off].filter((x) => !gone.has(x))), added, removed, from }
}

/**
 * The flags of the detection after a re-run (what the notices, the check screen and a batch read).
 * Replace: the new run's own. Add: the stricter of the two (a notice either run had stays), and the
 * higher sensitivity. Never fewer warnings than the blurs deserve.
 */
export function mergeFlags(old: Analysis, fresh: Analysis, mode: RerunMode): Pick<Analysis, 'lowConfidence' | 'reduced' | 'fast' | 'selfie' | 'sensitive' | 'sensitivity'> {
  if (mode === 'replace') {
    return { lowConfidence: fresh.lowConfidence, reduced: fresh.reduced, fast: fresh.fast, selfie: fresh.selfie, sensitive: fresh.sensitive, sensitivity: fresh.sensitivity }
  }
  const flag = (a?: boolean, b?: boolean) => (a || b ? true : undefined)
  return {
    lowConfidence: Math.max(old.lowConfidence ?? 0, fresh.lowConfidence ?? 0) || undefined,
    reduced: flag(old.reduced, fresh.reduced),
    fast: flag(old.fast, fresh.fast),
    selfie: flag(old.selfie, fresh.selfie),
    sensitive: flag(old.sensitive, fresh.sensitive),
    sensitivity: Math.max(old.sensitivity ?? 0, fresh.sensitivity ?? 0) || undefined,
  }
}

/** The two runs have the same frames (a re-run of one file does): no track needs moving. */
export function sameFrames(a: Float64Array, b: Float64Array): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-4) return false
  return true
}

function nearest(times: Float64Array, x: number): number {
  let lo = 0, hi = times.length - 1
  if (hi < 0) return 0
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (times[mid] < x) lo = mid + 1
    else hi = mid
  }
  return lo > 0 && x - times[lo - 1] <= times[lo] - x ? lo - 1 : lo
}

/** [t] on another run's frames: each new frame takes the box of the nearest old one (when the two runs' frame times differ). */
export function resample(t: Track, from: Float64Array, to: Float64Array): Track {
  const n = t.boxes.length / 4
  if (n === 0 || to.length === 0 || from.length === 0) return t
  const old = (i: number) => Math.min(n - 1, Math.max(0, i - t.first))
  const first = nearest(to, from[Math.min(from.length - 1, t.first)])
  const last = Math.max(first, nearest(to, from[Math.min(from.length - 1, t.first + n - 1)]))
  const boxes = new Float32Array((last - first + 1) * 4)
  for (let j = first; j <= last; j++) {
    const k = old(nearest(from, to[j]))
    boxes.set(t.boxes.subarray(k * 4, k * 4 + 4), (j - first) * 4)
  }
  return { ...t, first, boxes, best: Math.min(last, Math.max(first, nearest(to, from[Math.min(from.length - 1, t.best)]))) }
}
