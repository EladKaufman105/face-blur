// Follows a blur placed by hand, as the Android app's ManualTracker: the median optical flow of
// a grid of points inside the box (translation and scale), frame by frame on small gray frames,
// pulled onto any found face it overlaps (this stops drift). Stops when the points are lost, the
// box leaves the frame, at a cut, or after 20 seconds.
import { ALL_FORMATS, BlobSource, CanvasSink, EncodedPacketSink, Input } from 'mediabunny'
import { CutDetector } from '../lib/cuts'
import { lkChecked, median, pyramid, toGray, type Gray } from '../lib/flow'
import { frameAt } from '../lib/types'

type Box = [number, number, number, number]
export type FollowRequest =
  | { type: 'times'; file: File }
  | {
      type: 'follow'
      file: File
      times: Float64Array
      fps: number
      /** The frame it starts from, and the box there (with the user's size). */
      from: number
      box: Box
      /** 1 forward, -1 back, 0 both ways. */
      dir: number
      /** The faces found, to pull onto. */
      guides: { first: number; boxes: Float32Array }[]
    }
export type FollowMessage =
  | { type: 'times'; times: Float64Array; fps: number }
  | { type: 'progress'; progress: number }
  | { type: 'done'; first: number; boxes: Float32Array }
  | { type: 'error'; message: string }

const post = (m: FollowMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer)

const MAX_SECONDS = 20
const GRID = 8
const MIN_POINTS = 8
const SIDE = 320

self.onmessage = async (e: MessageEvent<FollowRequest>) => {
  try {
    if (e.data.type === 'times') await times(e.data.file)
    else await follow(e.data)
  } catch (err) {
    post({ type: 'error', message: String((err as Error)?.message ?? err) })
  }
}

/** Every frame's time, from the packets (no decoding): for a video whose faces weren't searched. */
async function times(file: File) {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) throw new Error('no video track')
  const ts: number[] = []
  for await (const p of new EncodedPacketSink(video).packets(undefined, undefined, { metadataOnly: true })) ts.push(p.timestamp)
  ts.sort((a, b) => a - b)
  const diffs: number[] = []
  for (let i = 1; i < ts.length; i++) if (ts[i] > ts[i - 1]) diffs.push(ts[i] - ts[i - 1])
  diffs.sort((a, b) => a - b)
  const fps = Math.min(240, Math.max(5, diffs.length ? 1 / diffs[diffs.length >> 1] : 30))
  const out = Float64Array.from(ts)
  post({ type: 'times', times: out, fps }, [out.buffer])
}

async function follow(r: Extract<FollowRequest, { type: 'follow' }>) {
  const { times, fps, from, dir, guides } = r
  const input = new Input({ source: new BlobSource(r.file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) throw new Error('no video track')
  const s = SIDE / Math.max(video.displayWidth, video.displayHeight)
  const gw = Math.round(video.displayWidth * s / 2) * 2, gh = Math.round(video.displayHeight * s / 2) * 2
  const limit = Math.round(MAX_SECONDS * fps)
  const lo = dir <= 0 ? Math.max(0, from - limit) : from
  const hi = dir >= 0 ? Math.min(times.length - 1, from + limit) : from
  const raw = new Map<number, Box>()
  raw.set(from, r.box)

  const sink = new CanvasSink(video, { width: gw, height: gh, fit: 'fill', poolSize: 2 })
  const cuts = new CutDetector()
  // A cut at i: frame i starts a new shot.
  const cutAt = new Set<number>()
  // The frames before [from], kept for following back (8-bit gray).
  const before = new Map<number, Uint8Array>()
  let prev: Gray[] | null = null
  let box: Box = r.box
  let last = -1
  let decoded = 0
  const total = hi - lo + 1
  for await (const wc of sink.canvases(times[lo] - 1e-4, times[hi] + 1e-3)) {
    const i = frameAt(times, wc.timestamp)
    if (i <= last || i < lo) continue
    last = i
    const canvas = wc.canvas as OffscreenCanvas
    if (cuts.isCut(canvas) && i > lo) cutAt.add(i)
    const px = canvas.getContext('2d')!.getImageData(0, 0, gw, gh).data
    decoded++
    if (decoded % 10 === 0) post({ type: 'progress', progress: Math.min(0.99, decoded / total) })
    if (i < from) {
      const g = new Uint8Array(gw * gh)
      for (let k = 0; k < g.length; k++) g[k] = (77 * px[k * 4] + 150 * px[k * 4 + 1] + 29 * px[k * 4 + 2]) >> 8
      before.set(i, g)
      continue
    }
    const gray = pyramid(toGray(px, gw, gh))
    if (i === from) {
      if (dir <= 0) back(gray)
      before.clear()
      if (dir < 0) break
      prev = gray
      continue
    }
    // Forward, frame by frame as they come.
    if (!prev || cutAt.has(i)) break
    const next = step(prev, gray, box, i)
    if (!next) break
    box = next
    raw.set(i, box)
    prev = gray
  }

  function back(start: Gray[]) {
    let p = start
    let b: Box = r.box
    for (let i = from - 1; i >= lo; i--) {
      if (cutAt.has(i + 1)) break
      const g = before.get(i)
      if (!g) break
      const cur = pyramid(fromGray(g, gw, gh))
      const next = step(p, cur, b, i)
      if (!next) break
      b = next
      raw.set(i, b)
      p = cur
    }
  }

  /** The box on the next frame, or null when it's lost. */
  function step(a: Gray[], c: Gray[], b: Box, i: number): Box | null {
    let cx = b[0] * gw, cy = b[1] * gh, bw = b[2] * gw, bh = b[3] * gh
    const st = flowStep(a, c, cx, cy, bw, bh)
    if (!st) return null
    cx += st[0]; cy += st[1]; bw *= st[2]; bh *= st[2]
    // Pulled halfway onto a found face it overlaps.
    const tracked: Box = [cx / gw, cy / gh, bw / gw, bh / gh]
    let best: Box | null = null, bestIou = 0.25
    for (const g of guides) {
      const k = i - g.first
      if (k < 0 || k * 4 >= g.boxes.length) continue
      const d: Box = [g.boxes[k * 4], g.boxes[k * 4 + 1], g.boxes[k * 4 + 2], g.boxes[k * 4 + 3]]
      const ratio = Math.max(d[2], tracked[2]) / Math.max(1e-6, Math.min(d[2], tracked[2]))
      const u = iou(d, tracked)
      if (ratio < 1.6 && u > bestIou) { bestIou = u; best = d }
    }
    if (best) {
      cx = (cx + best[0] * gw) / 2; cy = (cy + best[1] * gh) / 2
      bw = (bw + best[2] * gw) / 2; bh = (bh + best[3] * gh) / 2
    }
    if (cx < 0 || cy < 0 || cx > gw || cy > gh || bw < 4 || bh < 4 || bw > gw * 1.5) return null
    return [cx / gw, cy / gh, bw / gw, bh / gh]
  }

  if (raw.size <= 1) return post({ type: 'done', first: from, boxes: Float32Array.from(r.box) })
  const keys = [...raw.keys()].sort((a, b) => a - b)
  const first = keys[0]
  const smooth = smoothBoxes(keys, keys.map((k) => raw.get(k)!), fps)
  post({ type: 'done', first, boxes: smooth }, [smooth.buffer])
}

function fromGray(g: Uint8Array, w: number, h: number): Gray {
  const d = new Float32Array(w * h)
  for (let i = 0; i < d.length; i++) d[i] = g[i]
  return { w, h, d }
}

/** (dx, dy, scale) in frame pixels, or null when the points are lost. */
function flowStep(prev: Gray[], cur: Gray[], cx: number, cy: number, bw: number, bh: number): [number, number, number] | null {
  const pts = new Float32Array(GRID * GRID * 2)
  let n = 0
  for (let j = 0; j < GRID; j++) for (let k = 0; k < GRID; k++) {
    pts[n++] = cx - bw / 2 + bw * (0.1 + 0.8 * k / (GRID - 1))
    pts[n++] = cy - bh / 2 + bh * (0.1 + 0.8 * j / (GRID - 1))
  }
  const { out, ok } = lkChecked(prev, cur, pts)
  const ga: number[] = [], gb: number[] = []
  for (let k = 0; k < GRID * GRID; k++) {
    if (!ok[k]) continue
    ga.push(pts[2 * k], pts[2 * k + 1])
    gb.push(out[2 * k], out[2 * k + 1])
  }
  const m = ga.length / 2
  if (m < MIN_POINTS) return null
  const dxs: number[] = [], dys: number[] = []
  for (let k = 0; k < m; k++) { dxs.push(gb[2 * k] - ga[2 * k]); dys.push(gb[2 * k + 1] - ga[2 * k + 1]) }
  // Scale from point pairs far apart (short baselines are mostly noise).
  const ratios: number[] = []
  const minBase = Math.max(2, 0.25 * bw)
  for (let k = 0; k < m; k++) {
    for (const off of [Math.max(1, m >> 2), Math.max(1, Math.floor(m / 3)), Math.max(1, m >> 1)]) {
      const l = (k + off) % m
      if (l === k) continue
      const d0 = Math.hypot(ga[2 * k] - ga[2 * l], ga[2 * k + 1] - ga[2 * l + 1])
      if (d0 < minBase) continue
      ratios.push(Math.hypot(gb[2 * k] - gb[2 * l], gb[2 * k + 1] - gb[2 * l + 1]) / d0)
    }
  }
  const s = ratios.length ? Math.min(1.1, Math.max(0.9, median(ratios))) : 1
  return [median(dxs), median(dys), s]
}

function iou(a: Box, b: Box): number {
  const x0 = Math.max(a[0] - a[2] / 2, b[0] - b[2] / 2), x1 = Math.min(a[0] + a[2] / 2, b[0] + b[2] / 2)
  const y0 = Math.max(a[1] - a[3] / 2, b[1] - b[3] / 2), y1 = Math.min(a[1] + a[3] / 2, b[1] + b[3] / 2)
  const inter = Math.max(0, x1 - x0) * Math.max(0, y1 - y0)
  const u = a[2] * a[3] + b[2] * b[3] - inter
  return u > 0 ? inter / u : 0
}

/** Gaussian smoothing over frames: the position lightly, the size more (the app's 0.025 s and 0.2 s). */
function smoothBoxes(keys: number[], vals: Box[], fps: number): Float32Array {
  const sig = [0.025, 0.025, 0.2, 0.2].map((x) => x * fps)
  const out = new Float32Array(keys.length * 4)
  for (let i = 0; i < keys.length; i++) {
    for (let c = 0; c < 4; c++) {
      const reach = 3 * sig[c] + 0.5
      const s = Math.max(sig[c], 0.5)
      let acc = 0, tw = 0
      for (let j = i; j >= 0 && keys[i] - keys[j] <= reach; j--) {
        const d = (keys[i] - keys[j]) / s, w = Math.exp(-0.5 * d * d)
        acc += vals[j][c] * w; tw += w
      }
      for (let j = i + 1; j < keys.length && keys[j] - keys[i] <= reach; j++) {
        const d = (keys[j] - keys[i]) / s, w = Math.exp(-0.5 * d * d)
        acc += vals[j][c] * w; tw += w
      }
      out[i * 4 + c] = acc / tw
    }
  }
  return out
}
