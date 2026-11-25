// Port of the Android app's OfflineTracker (without camera-motion compensation): turns the raw
// per-frame detections into face tracks after the whole video has been seen.
//  1. Hard cuts split shots.
//  2. Detections are linked greedily into tracklets with a constant-velocity prediction.
//  3. A tracklet counts as a face only with enough evidence: several confident detections, or
//     detections confirmed on an upscaled crop. Single-frame clutter rarely gathers that.
//  4. Weak tracklets that directly continue an accepted one are absorbed into it, and tracklets
//     of the same face split by a longer detector dropout are joined.
//  5. Gaps are interpolated, ends held a little longer, and the result is smoothed.
// Coordinates inside are "width units": x and w as a fraction of the width, y and h scaled by
// the aspect ratio so both axes share the unit.
import type { Det, Track } from './types'

interface SDet { x: number; y: number; w: number; h: number; score: number; verify: number }

class Tracklet {
  det = new Map<number, SDet>()
  keys: number[] = []
  constructor(readonly shotStart: number, readonly shotEnd: number) {}
  add(i: number, d: SDet) {
    if (!this.det.has(i)) this.keys.push(i)
    this.det.set(i, d)
  }
  absorb(o: Tracklet) {
    for (const [k, v] of o.det) this.det.set(k, v)
    this.keys = [...this.det.keys()].sort((a, b) => a - b)
  }
  get first() { return this.keys[0] }
  get last() { return this.keys[this.keys.length - 1] }
}

const DET_THRESHOLD = 0.4
const BIRTH_THRESHOLD = 0.45
const STRONG = 0.6
const VERIFIED = 0.5
const SHORT_N = 4
const SHORT_VERIFIED = 0.9
const MAX_GAP_S = 0.7
const GATE = 0.6
const GATE_PER_S = 1.5
const SIZE_RATIO = 1.7
const LINK_GAP_S = 1.2
const ABSORB_GAP_S = 0.7
const ABSORB_DIST = 1.0
const EXTEND_S = 0.25
const SIGMA_POS_S = 0.025
const SIGMA_SIZE_S = 0.2

export function track(n: number, dets: Map<number, Det[]>, cuts: number[], fps: number, aspect: number, sensitive: boolean): Track[] {
  if (n === 0) return []
  const bounds = [0, ...[...new Set(cuts.filter((c) => c > 0 && c < n))].sort((a, b) => a - b), n]
  const all: Tracklet[] = []
  for (let s = 0; s < bounds.length - 1; s++) all.push(...link(bounds[s], bounds[s + 1], dets, fps, aspect))
  const accepted = merge(all.filter((t) => accept(t, sensitive)), all, fps)
  return accepted.map((t, id) => {
    const filled = fill(t, fps, aspect)
    const keys = [...filled.keys()].sort((a, b) => a - b)
    const sm = smooth(keys, keys.map((k) => filled.get(k)!), fps)
    let best = t.first, bs = -1
    for (const [k, d] of t.det) if (d.score > bs) { bs = d.score; best = k }
    return { id, first: keys[0], boxes: sm, best }
  })
}

function link(s0: number, s1: number, dets: Map<number, Det[]>, fps: number, aspect: number): Tracklet[] {
  const maxGap = Math.round(MAX_GAP_S * fps)
  const predCap = Math.floor(0.3 * fps)
  const out: Tracklet[] = []
  let active: Tracklet[] = []
  for (let i = s0; i < s1; i++) {
    const raw = dets.get(i)
    if (!raw) continue
    const ds: SDet[] = raw.filter((d) => d.score >= DET_THRESHOLD).map((d) => ({ x: d.cx, y: d.cy * aspect, w: d.w, h: d.h * aspect, score: d.score, verify: d.verify }))
    active = active.filter((t) => i - t.last <= maxGap)
    const pairs: { cost: number; di: number; t: Tracklet }[] = []
    ds.forEach((d, di) => {
      for (const t of active) {
        const l = t.det.get(t.last)!
        let px = l.x, py = l.y
        if (t.keys.length >= 2) {
          const prevKey = t.keys[t.keys.length - 2]
          const a = t.det.get(prevKey)!
          const dt = t.last - prevKey
          if (dt >= 1 && dt <= maxGap) {
            const g = Math.min(i - t.last, predCap)
            px += (l.x - a.x) / dt * g
            py += (l.y - a.y) / dt * g
          }
        }
        const size = Math.max(l.w, l.h)
        const gap = (i - t.last) / fps
        const dist = Math.hypot(d.x - px, d.y - py)
        const gate = size * (GATE + GATE_PER_S * gap)
        const ratio = Math.max(d.w, l.w) / Math.max(1e-6, Math.min(d.w, l.w))
        if (dist < gate && ratio < SIZE_RATIO) pairs.push({ cost: dist / Math.max(size, 1e-6) + 0.3 * gap, di, t })
      }
    })
    const usedD = new Set<number>()
    const usedT = new Set<Tracklet>()
    for (const p of pairs.sort((a, b) => a.cost - b.cost)) {
      if (usedD.has(p.di) || usedT.has(p.t)) continue
      usedD.add(p.di)
      usedT.add(p.t)
      p.t.add(i, ds[p.di])
    }
    ds.forEach((d, di) => {
      if (usedD.has(di) || d.score < BIRTH_THRESHOLD) return
      const t = new Tracklet(s0, s1)
      t.add(i, d)
      out.push(t)
      active.push(t)
    })
  }
  return out
}

function accept(t: Tracklet, sensitive: boolean): boolean {
  const v = [...t.det.values()]
  const strong = v.filter((d) => d.score >= STRONG).length
  const verified = v.filter((d) => d.verify >= VERIFIED).length
  if (sensitive) return strong >= 3 || verified >= 1
  if (v.length < SHORT_N) return v.filter((d) => d.verify >= SHORT_VERIFIED).length >= 2
  return strong >= 4 || verified >= 2 || (verified >= 1 && strong >= 2)
}

function merge(list: Tracklet[], all: Tracklet[], fps: number): Tracklet[] {
  const gapMax = Math.floor(LINK_GAP_S * fps)
  list.sort((a, b) => a.first - b.first)
  absorb(list, all, fps)
  let merged = true
  while (merged) {
    merged = false
    outer: for (const a of list) {
      const la = a.det.get(a.last)!
      let best: Tracklet | null = null
      for (const b of list) {
        if (b === a || b.shotStart !== a.shotStart) continue
        const gap = b.first - a.last
        if (gap <= 0 || gap > gapMax) continue
        const fb = b.det.get(b.first)!
        const size = Math.max(la.w, la.h)
        const r = fb.w / la.w
        if (Math.hypot(la.x - fb.x, la.y - fb.y) < 1.5 * size && r > 1 / 1.7 && r < 1.7) {
          if (!best || b.first < best.first) best = b
        }
      }
      if (best) {
        a.absorb(best)
        list.splice(list.indexOf(best), 1)
        merged = true
        break outer
      }
    }
  }
  return list
}

function absorb(accepted: Tracklet[], all: Tracklet[], fps: number) {
  const acc = new Set(accepted)
  const extra = all.filter((t) => !acc.has(t))
  const gapMax = Math.floor(ABSORB_GAP_S * fps)
  let changed = true
  while (changed) {
    changed = false
    loop: for (const a of accepted) {
      for (const b of extra) {
        if (b.shotStart !== a.shotStart) continue
        for (const [x, y] of [[a, b], [b, a]] as const) {
          const g = y.first - x.last
          if (g <= 0 || g > gapMax) continue
          const lx = x.det.get(x.last)!
          const fy = y.det.get(y.first)!
          const size = Math.max(lx.w, lx.h)
          const r = fy.w / lx.w
          if (Math.hypot(lx.x - fy.x, lx.y - fy.y) < ABSORB_DIST * size && r > 1 / 1.5 && r < 1.5) {
            a.absorb(b)
            extra.splice(extra.indexOf(b), 1)
            changed = true
            break loop
          }
        }
      }
    }
  }
}

/** Every frame from a little before the first detection to a little after the last, in frame fractions. */
function fill(t: Tracklet, fps: number, aspect: number): Map<number, number[]> {
  const out = new Map<number, number[]>()
  const un = (x: number, y: number, w: number, h: number) => [x, y / aspect, w, h / aspect]
  const keys = t.keys
  for (let k = 0; k < keys.length - 1; k++) {
    const a = keys[k], b = keys[k + 1]
    const da = t.det.get(a)!, db = t.det.get(b)!
    for (let i = a; i < b; i++) {
      const f = (i - a) / (b - a)
      out.set(i, un(da.x + (db.x - da.x) * f, da.y + (db.y - da.y) * f, da.w + (db.w - da.w) * f, da.h + (db.h - da.h) * f))
    }
  }
  const ext = Math.round(EXTEND_S * fps)
  const d0 = t.det.get(keys[0])!
  const dl = t.det.get(keys[keys.length - 1])!
  out.set(t.last, un(dl.x, dl.y, dl.w, dl.h))
  for (let i = Math.max(t.shotStart, t.first - ext); i < t.first; i++) out.set(i, un(d0.x, d0.y, d0.w, d0.h))
  for (let i = t.last + 1; i < Math.min(t.shotEnd, t.last + ext + 1); i++) out.set(i, un(dl.x, dl.y, dl.w, dl.h))
  return out
}

/** Centered Gaussian over time: position barely (shaky cameras), size more (no breathing). */
function smooth(keys: number[], vals: number[][], fps: number): Float32Array {
  const sig = [SIGMA_POS_S, SIGMA_POS_S, SIGMA_SIZE_S, SIGMA_SIZE_S].map((s) => s * fps)
  const out = new Float32Array(keys.length * 4)
  for (let i = 0; i < keys.length; i++) {
    for (let c = 0; c < 4; c++) {
      const reach = 3 * sig[c] + 0.5
      const s = Math.max(sig[c], 0.5)
      let acc = 0, tw = 0
      for (let j = i; j >= 0 && keys[i] - keys[j] <= reach; j--) {
        const d = (keys[i] - keys[j]) / s
        const wt = Math.exp(-0.5 * d * d)
        acc += vals[j][c] * wt; tw += wt
      }
      for (let j = i + 1; j < keys.length && keys[j] - keys[i] <= reach; j++) {
        const d = (keys[j] - keys[i]) / s
        const wt = Math.exp(-0.5 * d * d)
        acc += vals[j][c] * wt; tw += wt
      }
      out[i * 4 + c] = acc / tw
    }
  }
  return out
}

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
        if (inside) best = Math.max(best, d.score, d.verify)
      }
    }
    if (best < clear) low++
  }
  return low
}
