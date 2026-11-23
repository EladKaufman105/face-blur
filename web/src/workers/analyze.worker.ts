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
  | { type: 'progress'; done: number; total: number; preview?: ImageBitmap; dets?: Det[]; faces: number; backend: string; stats: string }
  | { type: 'done'; times: Float64Array; width: number; height: number; fps: number; tracks: Track[]; cuts: number[]; thumbs: (ImageBitmap | null)[]; backend: string; ms: number }
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
  const detector = await FaceDetector.create(base + 'models/', backend)
  log(`detector ${detector.backend} ${detector.precision} ${(performance.now() - t0).toFixed(0)} ms`)
  post({ type: 'ready', backend: detector.backend, width, height, duration })

  // Frames are decoded at the detector's starting size (it may lower it later).
  const side = Math.min(Math.max(width, height), detector.side)
  const s = side / Math.max(width, height)
  const iw = Math.max(32, Math.round(width * s / 32) * 32)
  const ih = Math.max(32, Math.round(height * s / 32) * 32)
  // Detection up to 15 times per second of video (every frame when sensitive); the tracker fills
  // the frames between. On a slow device fewer (down to 4 per second) and at a lower resolution,
  // so a minute of video doesn't take many minutes.
  const maxRate = sensitive ? nominalFps : 15
  let rate = Math.min(maxRate, detector.backend === 'webgpu' ? 15 : 10)
  const spent: number[] = []

  const sink = new CanvasSink(video, { width: iw, height: ih, fit: 'fill', poolSize: 3 })
  const cuts = new CutDetector()
  const times: number[] = []
  const cutList: number[] = []
  const dets = new Map<number, Det[]>()
  let lastPost = 0
  let lastDets: Det[] = []
  let lastDetect = -Infinity
  let found = 0
  let decodeWait = 0, detectMs = 0
  // The next frame decodes while this one is in detection.
  const frames = sink.canvases()
  let next = frames.next()
  while (true) {
    const tw = performance.now()
    const r = await next
    decodeWait += performance.now() - tw
    if (r.done) break
    next = frames.next()
    const wc = r.value
    const canvas = wc.canvas as OffscreenCanvas
    const i = times.length
    times.push(wc.timestamp)
    const cut = cuts.isCut(canvas)
    if (cut) cutList.push(i)
    if (cut || wc.timestamp - lastDetect >= 1 / rate - 1e-3) {
      lastDetect = wc.timestamp
      const td = performance.now()
      lastDets = await detector.detect(canvas)
      const ms = performance.now() - td
      detectMs += ms
      dets.set(i, lastDets)
      found = Math.max(found, lastDets.filter((d) => d.score >= 0.6).length)
      // After the first (warm-up) detections: aim for about 0.6 s of detection per second of
      // video, between 4 and 15 detections per second, at 640 px if 960 is too slow.
      spent.push(ms)
      if (spent.length >= 4 && spent.length % 4 === 0) {
        const recent = spent.slice(-4).reduce((a, b) => a + b, 0) / 4
        if (recent > 200 && detector.side > 640) detector.side = 640
        rate = Math.min(maxRate, Math.max(4, Math.min(detector.backend === 'webgpu' ? 15 : 10, 600 / recent)))
      }
    }
    const now = performance.now()
    if (now - lastPost > 250) {
      lastPost = now
      const ps = Math.min(1, 480 / Math.max(iw, ih))
      const preview = await createImageBitmap(canvas, { resizeWidth: Math.round(iw * ps), resizeHeight: Math.round(ih * ps) })
      const n = Math.max(1, spent.length)
      const stats = `${detector.backend}/${detector.precision} ${detector.side}px · ${rate.toFixed(1)}/s · detect ${(detectMs / n).toFixed(0)} ms · decode wait ${(decodeWait / (i + 1)).toFixed(0)} ms/frame${detector.fellBack ? ' · fell back: ' + detector.fellBack : ''}`
      post({ type: 'progress', done: i + 1, total: Math.max(total, i + 1), preview, dets: lastDets, faces: found, backend: detector.backend, stats }, [preview])
    }
  }
  log(`frames ${times.length}, detections ${spent.length}, detect avg ${(detectMs / Math.max(1, spent.length)).toFixed(0)} ms, decode wait ${decodeWait.toFixed(0)} ms, side ${detector.side}, rate ${rate.toFixed(1)}`)
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
  const msg: AnalyzeMessage = { type: 'done', times: Float64Array.from(times), width, height, fps, tracks, cuts: cutList, thumbs, backend: detector.backend, ms: performance.now() - t0 }
  post(msg, [msg.times.buffer, ...tracks.map((t) => t.boxes.buffer), ...thumbs.filter((b): b is ImageBitmap => !!b)])
}
