// One pass over the video for the picture tools: the camera's motion frame to frame (to steady
// it), the cuts (where nothing is smoothed across), and, when asked, where the people are (to
// replace the background), as the Android app's analysis does.
import { ALL_FORMATS, BlobSource, CanvasSink, Input } from 'mediabunny'
import { CutDetector } from '../lib/cuts'
import { lkChecked, median, pyramid, toGray, type Gray } from '../lib/flow'
import { MaskSmoother, PeopleFinder } from '../lib/seg'
import { frameAt } from '../lib/types'

export type PictureRequest = {
  file: File
  /** The editor's frame times (empty: found here). */
  times: Float64Array
  people: boolean
  /** Where the people model is. */
  model: string
  backend?: 'webgpu' | 'wasm'
}
export type Masks = { w: number; h: number; /** The frame each mask is for, ascending. */ frames: Int32Array; data: Uint8Array }
export type PictureMessage =
  | { type: 'progress'; progress: number }
  | { type: 'done'; times: Float64Array; fps: number; motion: Float32Array; cuts: number[]; masks: Masks | null; backend: string }
  | { type: 'error'; message: string }

const post = (m: PictureMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer)

/** Long side of the frames looked at, of the masks, and of the frames the motion is measured on. */
const GRAB = 576
const MASK = 256
const MOTION_SIDE = 320
/** At most this many people masks a second, and in all (a long video gets them further apart). */
const MASK_RATE = 15
const MAX_MASKS = 1500

self.onmessage = async (e: MessageEvent<PictureRequest>) => {
  try {
    await run(e.data)
  } catch (err) {
    post({ type: 'error', message: String((err as Error)?.message ?? err) })
  }
}

async function run(r: PictureRequest) {
  const input = new Input({ source: new BlobSource(r.file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) throw new Error('no video track')
  const W = video.displayWidth, H = video.displayHeight
  const duration = await video.computeDuration()
  const stats = await video.computePacketStats(120)
  const nominal = stats.averagePacketRate > 0 ? stats.averagePacketRate : 30
  const s = (r.people ? GRAB : MOTION_SIDE) / Math.max(W, H)
  const gw = Math.max(16, Math.round(W * s)), gh = Math.max(16, Math.round(H * s))
  const ms = MASK / Math.max(W, H)
  const mw = Math.max(8, Math.round(W * ms)), mh = Math.max(8, Math.round(H * ms))
  const finder = r.people ? await PeopleFinder.create(r.model, r.backend) : null
  // The masks' frames: about MASK_RATE a second, at most MAX_MASKS.
  const total = Math.max(1, Math.round(duration * nominal))
  const stride = Math.max(1, Math.round(nominal / MASK_RATE), Math.ceil(total / MAX_MASKS))

  const known = r.times.length > 0
  const times: number[] = known ? Array.from(r.times) : []
  const motion: number[] = []
  const cuts: number[] = []
  const detector = new CutDetector()
  const small = new OffscreenCanvas(MOTION_SIDE, MOTION_SIDE)
  const sctx = small.getContext('2d', { willReadFrequently: true })!
  const mw2 = Math.round(W * MOTION_SIDE / Math.max(W, H)), mh2 = Math.round(H * MOTION_SIDE / Math.max(W, H))
  small.width = mw2; small.height = mh2
  const grayCanvas = new OffscreenCanvas(mw, mh)
  const gctx = grayCanvas.getContext('2d', { willReadFrequently: true })!
  const maskFrames: number[] = []
  const maskData: Uint8Array[] = []
  const smoother = new MaskSmoother(mw * mh, (index, m) => { maskFrames.push(index); maskData.push(m) })
  // The motion grid: 10 x 14 points over the whole frame (the median follows the background).
  const grid = new Float32Array(10 * 14 * 2)
  for (let j = 0, q = 0; j < 14; j++) for (let i = 0; i < 10; i++) {
    grid[q++] = mw2 * (0.06 + (0.88 * i) / 9)
    grid[q++] = mh2 * (0.06 + (0.88 * j) / 13)
  }
  let prev: Gray[] | null = null
  let last = -1
  let k = 0
  let lastPost = 0
  const sink = new CanvasSink(video, { width: gw, height: gh, fit: 'fill', poolSize: 2 })
  for await (const wc of sink.canvases()) {
    const canvas = wc.canvas as OffscreenCanvas
    let i: number
    if (known) {
      i = frameAt(r.times, wc.timestamp)
      if (i <= last) continue
    } else {
      i = times.length
      times.push(wc.timestamp)
    }
    // Frames the editor's times skipped (none, normally) get no motion.
    while (motion.length < 2 * i) motion.push(0, 0)
    last = i
    const cut = detector.isCut(canvas) && i > 0
    if (cut) cuts.push(i)
    // The camera: median flow of the grid, checked both ways.
    sctx.drawImage(canvas, 0, 0, mw2, mh2)
    const gray = pyramid(toGray(sctx.getImageData(0, 0, mw2, mh2).data, mw2, mh2))
    let dx = 0, dy = 0
    if (prev && !cut) {
      const { out, ok } = lkChecked(prev, gray, grid)
      const xs: number[] = [], ys: number[] = []
      for (let p = 0; p < ok.length; p++) if (ok[p]) { xs.push(out[2 * p] - grid[2 * p]); ys.push(out[2 * p + 1] - grid[2 * p + 1]) }
      if (xs.length >= 12) { dx = median(xs) / mw2; dy = median(ys) / mh2 }
    }
    motion.push(dx, dy)
    prev = gray
    // The people, on every [stride]th frame.
    if (finder && (i % stride === 0 || cut)) {
      const p = await finder.people(canvas, gw, gh, mw, mh)
      gctx.filter = 'blur(2px)'
      gctx.drawImage(canvas, 0, 0, mw, mh)
      const px = gctx.getImageData(0, 0, mw, mh).data
      const g = new Float32Array(mw * mh)
      for (let q = 0; q < g.length; q++) g[q] = 0.299 * px[q * 4] + 0.587 * px[q * 4 + 1] + 0.114 * px[q * 4 + 2]
      smoother.add(i, p, g, cut)
    }
    k++
    const now = performance.now()
    if (now - lastPost > 200) {
      lastPost = now
      post({ type: 'progress', progress: Math.min(0.99, wc.timestamp / Math.max(0.01, duration)) })
    }
  }
  smoother.flush()
  const n = known ? r.times.length : times.length
  while (motion.length < 2 * n) motion.push(0, 0)
  const ts = Float64Array.from(times)
  const diffs: number[] = []
  for (let q = 1; q < ts.length; q++) if (ts[q] > ts[q - 1]) diffs.push(ts[q] - ts[q - 1])
  diffs.sort((a, b) => a - b)
  const fps = Math.min(240, Math.max(5, diffs.length ? 1 / diffs[diffs.length >> 1] : nominal))
  const mo = Float32Array.from(motion.slice(0, 2 * n))
  let masks: Masks | null = null
  if (finder && maskData.length) {
    const data = new Uint8Array(mw * mh * maskData.length)
    maskData.forEach((m, q) => data.set(m, q * mw * mh))
    masks = { w: mw, h: mh, frames: Int32Array.from(maskFrames), data }
  }
  const transfer: Transferable[] = [ts.buffer, mo.buffer]
  if (masks) transfer.push(masks.frames.buffer, masks.data.buffer)
  post({ type: 'done', times: ts, fps, motion: mo, cuts, masks, backend: finder?.backend ?? '' }, transfer)
}
