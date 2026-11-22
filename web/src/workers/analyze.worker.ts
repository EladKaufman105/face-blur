// The analysis pass, off the main thread: decodes every frame (small), finds cuts, detects faces
// on a regular subset of frames, then builds the face tracks and a thumbnail of each face.
import { ALL_FORMATS, BlobSource, CanvasSink, Input } from 'mediabunny'
import { FaceDetector } from '../lib/detector'
import { CutDetector } from '../lib/cuts'
import { track } from '../lib/tracker'
import type { Det, Track } from '../lib/types'

export type AnalyzeRequest = { file: File; sensitive: boolean; base: string; backend?: 'webgpu' | 'wasm' }
export type AnalyzeMessage =
  | { type: 'ready'; backend: string; width: number; height: number; duration: number }
  | { type: 'progress'; done: number; total: number; preview?: ImageBitmap; dets?: Det[]; faces: number }
  | { type: 'done'; times: Float64Array; width: number; height: number; fps: number; tracks: Track[]; thumbs: (ImageBitmap | null)[]; backend: string; ms: number }
  | { type: 'error'; code: string; message: string }
  | { type: 'log'; message: string }

const post = (m: AnalyzeMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer)
const log = (message: string) => post({ type: 'log', message })

self.onmessage = async (e: MessageEvent<AnalyzeRequest>) => {
  try {
    await analyze(e.data)
  } catch (err) {
    post({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) })
  }
}

async function analyze({ file, sensitive, base, backend }: AnalyzeRequest) {
  const t0 = performance.now()
  log('start')
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) return post({ type: 'error', code: 'no-video', message: 'no video track' })
  if (!(await video.canDecode())) return post({ type: 'error', code: 'codec', message: `can't decode ${video.codec}` })
  const width = video.displayWidth, height = video.displayHeight
  const duration = await input.computeDuration()
  log(`video ${width}x${height} ${duration}s ${video.codec}`)
  const stats = await video.computePacketStats(120)
  const nominalFps = stats.averagePacketRate > 0 ? stats.averagePacketRate : 30
  const total = Math.max(1, Math.round(duration * nominalFps))

  log(`stats ${nominalFps} fps; isolated ${(self as any).crossOriginIsolated}`)
  const detector = await FaceDetector.create(base + 'models/retinaface_mv2.onnx', backend)
  log(`detector ${detector.backend} ${(performance.now() - t0).toFixed(0)} ms`)
  post({ type: 'ready', backend: detector.backend, width, height, duration })

  // Small and distant faces need the resolution; the GPU can afford more of it.
  const side = Math.min(Math.max(width, height), detector.backend === 'webgpu' ? 960 : 640)
  const s = side / Math.max(width, height)
  const iw = Math.max(32, Math.round(width * s / 32) * 32)
  const ih = Math.max(32, Math.round(height * s / 32) * 32)
  // Detection ~30 times per second of video on the GPU, ~15 without (every frame when sensitive).
  const rate = detector.backend === 'webgpu' ? 30 : 15
  const every = sensitive ? 1 : Math.max(1, Math.round(nominalFps / rate))

  const sink = new CanvasSink(video, { width: iw, height: ih, fit: 'fill', poolSize: 2 })
  const cuts = new CutDetector()
  const times: number[] = []
  const cutList: number[] = []
  const dets = new Map<number, Det[]>()
  let lastPost = 0
  let lastDets: Det[] = []
  let found = 0
  for await (const wc of sink.canvases()) {
    const canvas = wc.canvas as OffscreenCanvas
    const i = times.length
    times.push(wc.timestamp)
    if (cuts.isCut(canvas)) cutList.push(i)
    if (i % every === 0) {
      lastDets = await detector.detect(canvas)
      dets.set(i, lastDets)
      found = Math.max(found, lastDets.filter((d) => d.score >= 0.6).length)
    }
    const now = performance.now()
    if (now - lastPost > 250) {
      lastPost = now
      const ps = Math.min(1, 480 / Math.max(iw, ih))
      const preview = await createImageBitmap(canvas, { resizeWidth: Math.round(iw * ps), resizeHeight: Math.round(ih * ps) })
      post({ type: 'progress', done: i + 1, total: Math.max(total, i + 1), preview, dets: lastDets, faces: found }, [preview])
    }
  }
  const n = times.length
  const diffs: number[] = []
  for (let i = 1; i < n; i++) if (times[i] > times[i - 1]) diffs.push(times[i] - times[i - 1])
  diffs.sort((a, b) => a - b)
  const fps = Math.min(240, Math.max(5, diffs.length ? 1 / diffs[diffs.length >> 1] : nominalFps))
  const tracks = track(n, dets, cutList, fps, height / width, sensitive)

  // A square thumbnail of each face, from its clearest frame.
  const thumbs: (ImageBitmap | null)[] = []
  const big = new CanvasSink(video, { poolSize: 1, width: Math.round(width * Math.min(1, 1280 / Math.max(width, height))) })
  for (const t of tracks) {
    if (thumbs.length >= 40) { thumbs.push(null); continue }
    const k = t.best - t.first
    const wc = await big.getCanvas(times[t.best])
    if (!wc) { thumbs.push(null); continue }
    const c = wc.canvas as OffscreenCanvas
    const cx = t.boxes[k * 4] * c.width, cy = t.boxes[k * 4 + 1] * c.height
    const r = Math.max(t.boxes[k * 4 + 2] * c.width, t.boxes[k * 4 + 3] * c.height) * 0.8
    const out = new OffscreenCanvas(160, 160)
    const ctx = out.getContext('2d')!
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, 160, 160)
    ctx.drawImage(c, cx - r, cy - r, 2 * r, 2 * r, 0, 0, 160, 160)
    thumbs.push(out.transferToImageBitmap())
  }
  const msg: AnalyzeMessage = { type: 'done', times: Float64Array.from(times), width, height, fps, tracks, thumbs, backend: detector.backend, ms: performance.now() - t0 }
  post(msg, [msg.times.buffer, ...tracks.map((t) => t.boxes.buffer), ...thumbs.filter((b): b is ImageBitmap => !!b)])
}
