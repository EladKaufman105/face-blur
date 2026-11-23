// The analysis pass, off the main thread: decodes every frame (small), finds cuts, detects faces
// on a regular subset of frames, then builds the face tracks and a thumbnail of each face.
import { ALL_FORMATS, BlobSource, CanvasSink, Input } from 'mediabunny'
import { CPU_SIDE, FaceDetector, type DetectStats } from '../lib/detector'
import { DetectorPool } from '../lib/detector-pool'
import { CutDetector } from '../lib/cuts'
import { track } from '../lib/tracker'
import type { Det, Track } from '../lib/types'

export type AnalyzeRequest = { file: File; sensitive: boolean; base: string; backend?: 'webgpu' | 'wasm'; /** How many CPU detectors to run side by side (default: by core count). */ workers?: number }
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

/** One line on where the time goes, per detection (ms) unless said otherwise. */
function statsLine(head: string, s: DetectStats, rate: number, detectMs: number, decodeWait: number, poolWait: number, cutMs: number, frames: number, fellBack: string | null): string {
  const n = Math.max(1, s.detections), f = (v: number, k = n) => (v / k).toFixed(0)
  return [
    `${head} · ${rate.toFixed(1)}/s`,
    `detect ${f(detectMs)} = prep ${f(s.pre)} + fine ${f(s.inf)} + decode ${f(s.dec)} + coarse ${f(s.coarse)} (${s.coarseRuns}/${s.detections}) + check ${f(s.check)} + verify ${f(s.verify)} + merge ${f(s.nms)}`,
    `per frame: frame wait ${f(decodeWait, Math.max(1, frames))} · detector wait ${f(poolWait, Math.max(1, frames))} · cuts ${f(cutMs, Math.max(1, frames))}`,
    fellBack ? `fell back: ${fellBack}` : '',
  ].filter(Boolean).join(' · ')
}

async function analyze({ file, sensitive, base, backend, workers }: AnalyzeRequest) {
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
  // On the GPU one detector in this thread. On the CPU, where a detection is a long single-thread
  // WebAssembly run, a few detectors in workers of their own, each with a frame at a time.
  const cores = (self.navigator?.hardwareConcurrency ?? 2)
  // Two even on two cores (measured: about 30% less time); up to four, half the cores, beyond.
  // Each worker holds its own model and WebAssembly heap: two at most where memory is small
  // (a phone saying 4 GB or less).
  const memory = (self.navigator as any)?.deviceMemory as number | undefined
  const most = memory !== undefined && memory <= 4 ? 2 : 4
  const poolSize = workers ?? (cores > 1 ? Math.min(most, Math.max(2, Math.floor(cores / 2))) : 1)
  const cpuOnly = !(self.navigator as any)?.gpu || backend === 'wasm'
  const pool = cpuOnly && poolSize > 1 ? await DetectorPool.create(new Uint8Array(await (await fetch(base + 'models/retinaface_fp32.onnx')).arrayBuffer()), poolSize) : null
  const detector = pool ? null : await FaceDetector.create(base + 'models/', backend)
  const backendNow = () => detector?.backend ?? 'wasm'
  const precision = pool?.precision ?? detector!.precision
  log(`detector ${backendNow()} ${precision}${pool ? ' x' + pool.size : ''} ${(performance.now() - t0).toFixed(0)} ms`)
  post({ type: 'ready', backend: backendNow(), width, height, duration })

  // Frames are decoded at the detector's starting size (it may lower it later).
  const startSide = detector?.side ?? CPU_SIDE
  const side = Math.min(Math.max(width, height), startSide)
  const s = side / Math.max(width, height)
  const iw = Math.max(32, Math.round(width * s / 32) * 32)
  const ih = Math.max(32, Math.round(height * s / 32) * 32)
  // Detection up to 15 times per second of video (every frame when sensitive); the tracker fills
  // the frames between. On a slow device fewer (down to 4 per second) and at a lower resolution,
  // so a minute of video doesn't take many minutes.
  const maxRate = sensitive ? nominalFps : 15
  let rate = Math.min(maxRate, backendNow() === 'webgpu' ? 15 : 10)
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
  let decodeWait = 0, detectMs = 0, cutMs = 0, poolWait = 0
  // Where a detection's result goes (in the pool, in any order), and the pace follows from it.
  const recordDets = (i: number, got: Det[], ms: number) => {
    lastDets = got
    detectMs += ms
    dets.set(i, got)
    found = Math.max(found, got.filter((d) => d.score >= 0.6).length)
    // After the first (warm-up) detections: aim for about 0.6 s of detection per second of
    // video, between 4 and 15 detections per second, at 640 px if 960 is too slow. With several
    // detectors working at once each gets that much more time per frame.
    spent.push(ms)
    if (spent.length >= 4 && spent.length % 4 === 0) {
      const recent = spent.slice(-4).reduce((a, b) => a + b, 0) / 4
      if (detector && recent > 200 && detector.side > CPU_SIDE) detector.side = CPU_SIDE
      rate = Math.min(maxRate, Math.max(4, Math.min(backendNow() === 'webgpu' ? 15 : 10, 600 * (pool?.size ?? 1) / recent)))
    }
  }
  const statsText = () => statsLine(
    `${backendNow()}/${precision}${pool ? ' x' + pool.size : ''} ${detector?.side ?? CPU_SIDE}px`,
    pool?.stats() ?? detector!.stats, rate, detectMs, decodeWait, poolWait, cutMs, times.length, detector?.fellBack ?? null)
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
    const tc = performance.now()
    const cut = cuts.isCut(canvas)
    cutMs += performance.now() - tc
    if (cut) cutList.push(i)
    if (cut || wc.timestamp - lastDetect >= 1 / rate - 1e-3) {
      lastDetect = wc.timestamp
      if (pool) {
        // Wait for a free detector (the decoder keeps going meanwhile), then pass the frame on.
        const tp = performance.now()
        await pool.free()
        poolWait += performance.now() - tp
        await pool.detect(canvas, i, recordDets)
      } else {
        const td = performance.now()
        const got = await detector!.detect(canvas)
        recordDets(i, got, performance.now() - td)
      }
    }
    const now = performance.now()
    if (now - lastPost > 250) {
      lastPost = now
      const ps = Math.min(1, 480 / Math.max(iw, ih))
      const preview = await createImageBitmap(canvas, { resizeWidth: Math.round(iw * ps), resizeHeight: Math.round(ih * ps) })
      post({ type: 'progress', done: i + 1, total: Math.max(total, i + 1), preview, dets: lastDets, faces: found, backend: backendNow(), stats: statsText() }, [preview])
    }
  }
  if (pool) await pool.drain()
  log(`frames ${times.length}, detections ${spent.length}, detect avg ${(detectMs / Math.max(1, spent.length)).toFixed(0)} ms, decode wait ${decodeWait.toFixed(0)} ms, side ${detector?.side ?? CPU_SIDE}, rate ${rate.toFixed(1)}`)
  log(`stats ${statsText()}`)
  pool?.close()
  const n = times.length
  const diffs: number[] = []
  for (let i = 1; i < n; i++) if (times[i] > times[i - 1]) diffs.push(times[i] - times[i - 1])
  diffs.sort((a, b) => a - b)
  const fps = Math.min(240, Math.max(5, diffs.length ? 1 / diffs[diffs.length >> 1] : nominalFps))
  const tracks = track(n, dets, cutList, fps, height / width, sensitive)

  // A square thumbnail of each face, from its clearest frame. The frames are decoded in one
  // pass in time order (each packet at most once): fetching them one by one, each from its
  // keyframe, kept a crowd's video at the end of the progress bar for seconds.
  const thumbs: (ImageBitmap | null)[] = tracks.map(() => null)
  const wanted = tracks.slice(0, 40).map((t, i) => ({ i, f: t.best })).sort((a, b) => a.f - b.f)
  const frameTimes = [...new Set(wanted.map((w) => w.f))]
  const big = new CanvasSink(video, { poolSize: 1, width: Math.round(width * Math.min(1, 1280 / Math.max(width, height))) })
  let fi = 0
  for await (const wc of big.canvasesAtTimestamps(frameTimes.map((f) => times[f]))) {
    const f = frameTimes[fi++]
    if (!wc) continue
    const c = wc.canvas as OffscreenCanvas
    for (const w of wanted) {
      if (w.f !== f) continue
      const t = tracks[w.i], k = t.best - t.first
      const cx = t.boxes[k * 4] * c.width, cy = t.boxes[k * 4 + 1] * c.height
      const r = Math.max(t.boxes[k * 4 + 2] * c.width, t.boxes[k * 4 + 3] * c.height) * 0.8
      const out = new OffscreenCanvas(160, 160)
      const ctx = out.getContext('2d')!
      ctx.fillStyle = '#000'
      ctx.fillRect(0, 0, 160, 160)
      ctx.drawImage(c, cx - r, cy - r, 2 * r, 2 * r, 0, 0, 160, 160)
      thumbs[w.i] = out.transferToImageBitmap()
    }
  }
  const msg: AnalyzeMessage = { type: 'done', times: Float64Array.from(times), width, height, fps, tracks, cuts: cutList, thumbs, backend: backendNow(), ms: performance.now() - t0 }
  post(msg, [msg.times.buffer, ...tracks.map((t) => t.boxes.buffer), ...thumbs.filter((b): b is ImageBitmap => !!b)])
}
