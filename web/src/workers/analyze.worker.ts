// The analysis pass, off the main thread: decodes every frame (small), finds cuts, detects faces
// on a regular subset of frames, then builds the face tracks and a thumbnail of each face.
import { ALL_FORMATS, BlobSource, CanvasSink, Input } from 'mediabunny'
import { CPU_SIDE, FaceDetector, GPU_SIDE } from '../lib/detector'
import { DetectGate, FrameRate, Pacer, follow, fullRate, needRate, pace, runSide, type Timing } from '../lib/pace'
import { CoverageMeter, type Coverage } from '../lib/coverage'
import { YunetWorker } from '../lib/yunet-worker'
import { StatsHistory, summary, type AnalysisStats, type StatsSnapshot } from '../lib/analysis-stats'
import { DetectorPool } from '../lib/detector-pool'
import { CutDetector } from '../lib/cuts'
import { track } from '../lib/tracker'
import { lowConfidenceTracks } from '../lib/lowconf'
import type { Det, Track } from '../lib/types'

export type AnalyzeRequest = { file: File; sensitive: boolean; base: string; backend?: 'webgpu' | 'wasm'; /** How many CPU detectors to run side by side (default: by core count). */ workers?: number; /** For tests on localhost only (the page's ?detect=yunet:0,side:640,rate:5): detection pinned to these settings, no pacing. A tune weaker than the default marks the video reduced, with the thresholds as they are. */ tune?: Tune }
export type AnalyzeMessage =
  | { type: 'ready'; backend: string; width: number; height: number; duration: number; fps: number }
  | { type: 'progress'; done: number; total: number; preview?: ImageBitmap; dets?: Det[]; faces: number; backend: string; stats: AnalysisStats }
  | { type: 'done'; times: Float64Array; width: number; height: number; fps: number; tracks: Track[]; cuts: number[]; lowConfidence: number; /** Detection ran lighter than the app's (see lib/pace.ts). */ reduced: boolean; thumbs: (ImageBitmap | null)[]; backend: string; ms: number }
  | { type: 'error'; code: string; message: string }
  | { type: 'log'; message: string }

export type Tune = { yunet?: boolean; side?: number; rate?: number; /** As if the device were too slow: the reduced mode from the start (the notice, the flag, the lower thresholds). */ reduced?: boolean }

/** The measures for a log line: "D 15.0/s (window min 14.6, need 12.0, 30 windows, 0 short), G 2 frames 0.083 s, cuts 3 (0 missed), S 960 px, YuNet on". */
const coverageText = (c: Coverage, side: number, yunet: boolean) =>
  `D ${c.rate.toFixed(2)}/s (window min ${c.minWindow?.toFixed(2) ?? '-'}, need ${c.need.toFixed(2)}, ${c.windows} windows, ${c.short} short), G ${c.maxGapFrames} frames ${c.maxGapS.toFixed(3)} s, cuts ${c.cuts} (${c.cutsMissed} missed), S ${side} px, YuNet ${yunet ? 'on' : 'off'}`
const measuredText = (c: Coverage) =>
  `the frames detected fall short: ${c.shortRate?.toFixed(1)}/s from ${c.shortAt?.toFixed(2)} s, ${c.shortNeed?.toFixed(1)}/s needed there`

const post = (m: AnalyzeMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer)
const log = (message: string) => post({ type: 'log', message })

self.onmessage = async (e: MessageEvent<AnalyzeRequest>) => {
  try {
    await analyze(e.data)
  } catch (err) {
    post({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) })
  }
}

async function analyze({ file, sensitive, base, backend, workers, tune }: AnalyzeRequest) {
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
  // Each worker holds its own model and WebAssembly heap: more than two only where the browser
  // says there is 8 GB (Firefox and Safari don't say, so they get two).
  const memory = (self.navigator as any)?.deviceMemory as number | undefined
  const most = memory !== undefined && memory >= 8 ? 4 : 2
  const asked = workers !== undefined ? Math.min(8, Math.max(1, Math.round(workers))) : undefined
  const poolSize = asked ?? (cores > 1 ? Math.min(most, Math.max(2, Math.floor(cores / 2))) : 1)
  // No GPU also where the browser has WebGPU but no adapter for it (common on Android and Linux).
  const gpu = (self.navigator as any)?.gpu
  const cpuOnly = backend === 'wasm' || !gpu || !(await gpu.requestAdapter().catch(() => null))
  // YuNet's bytes (null: it couldn't be had, and detection says so; or a test's tune turned it off).
  const yunet = tune?.yunet === false ? null : await fetch(base + 'models/yunet.onnx').then(async (r) => (r.ok ? new Uint8Array(await r.arrayBuffer()) : null)).catch(() => null)
  // If the workers can't start (memory, a module that won't load), one detector here, as before.
  let pool: DetectorPool | null = null
  if (cpuOnly && poolSize > 1) {
    try {
      pool = await DetectorPool.create(new Uint8Array(await (await fetch(base + 'models/retinaface_fp32.onnx')).arrayBuffer()), yunet, poolSize)
    } catch (err) {
      log(`detector pool failed, one detector: ${String((err as Error)?.message ?? err)}`)
    }
  }
  // One detector here (the GPU's, or one on the CPU): YuNet in a worker of its own, so that it runs
  // while RetinaFace does (else, or if that worker fails, in this thread, after it). The pool's
  // detectors (all the cores busy already) run it in theirs.
  const ynWorker = !pool && yunet ? await YunetWorker.create(yunet) : null
  const detector = pool ? null : await FaceDetector.create(base + 'models/', backend, undefined, yunet, ynWorker).catch((e) => { ynWorker?.close(); throw e })
  if (tune?.side && detector) detector.side = tune.side
  const backendNow = () => detector?.backend ?? 'wasm'
  // Why detection isn't on the GPU, for the stats panel.
  const noGpu = () => detector?.fellBack ?? (backend === 'wasm' ? 'forced with ?backend=wasm' : !gpu ? 'no WebGPU in this browser' : 'no WebGPU adapter')
  const precision = pool?.precision ?? detector!.precision
  log(`detector ${backendNow()} ${precision}${pool ? ' x' + pool.size : ''}${ynWorker ? ', YuNet in a worker' : ''} ${(performance.now() - t0).toFixed(0)} ms`)
  post({ type: 'ready', backend: backendNow(), width, height, duration, fps: nominalFps })

  // Frames are decoded at the detector's starting size (it may lower it later): the app's 1280 px
  // on the GPU, 960 px on the CPU.
  const startSide = tune?.side ?? detector?.side ?? (cpuOnly ? CPU_SIDE : GPU_SIDE)
  const side = Math.min(Math.max(width, height), startSide)
  const s = side / Math.max(width, height)
  const iw = Math.max(32, Math.round(width * s / 32) * 32)
  const ih = Math.max(32, Math.round(height * s / 32) * 32)
  // Detection 15 times per second of video (every frame of a clip with fewer), or on the app's
  // frames where those are more (every n-th frame, n = fps / 15 rounded: every frame of a 20 fps
  // clip; see fullRate: never under 15, even if the nominal frame rate reads lower), every frame
  // when sensitive; the tracker fills the frames between. A device that can't keep that up gets lighter
  // detection, stepwise (see lib/pace.ts), never lighter than the web's detection before YuNet.
  // That is never silent: the video is marked `reduced` (the editor says so, a batch flags it) and
  // detection gets lower score thresholds, so that two weak detections that agree can add up to a
  // face. Also when the frames really detected fall short (lib/coverage.ts), whatever the rate.
  // In wide detection the full rate is the frame rate measured as the frames come (the nominal one
  // can be far off, either way): every frame, or the pace's rate under it (see follow()).
  let maxRate = sensitive ? nominalFps : fullRate(nominalFps)
  let rate = tune?.rate ? Math.min(maxRate, tune.rate) : maxRate
  const frameRate = new FrameRate(nominalFps)
  let all = sensitive && !tune?.rate
  let fineSide = startSide
  // The long side of the frames detection gets: a fine pass set bigger than that changes nothing.
  const frameSide = Math.max(iw, ih)
  let reduced = false
  // `reduced`: lighter than the app's (the notice, the flag). Then the score thresholds drop too, except in a test's pinned tune.
  let lowThr = false
  const reduce = (why: string) => {
    if (!reduced) log(`detection reduced: ${why}`)
    reduced = true
    if (!tune || tune.reduced) {
      lowThr = true
      detector?.setReduced(true)
    }
  }
  // No YuNet (the model couldn't be loaded): weaker than the app's detection.
  let yunetOn = !!yunet
  if (detector?.yunetMissing || (pool && !pool.yunet)) {
    yunetOn = false
    reduce('no YuNet')
  }
  if (tune?.reduced) reduce('forced for a test')
  // A pinned tune weaker than the default is a reduced detection like any other (a size that changes nothing for this video isn't).
  const long = Math.max(width, height)
  if (tune && (Math.min(long, tune.side ?? startSide) < Math.min(long, cpuOnly ? CPU_SIDE : GPU_SIDE) || (tune.rate ?? maxRate) < Math.min(maxRate, nominalFps))) reduce('a weaker tune')
  const spent: number[] = []
  // The detections' times, in windows (the first ones, warm-up, left out).
  const pacer = new Pacer((pool?.size ?? 1) + 2)

  const sink = new CanvasSink(video, { width: iw, height: ih, fit: 'fill', poolSize: 3 })
  const cuts = new CutDetector()
  const times: number[] = []
  const cutList: number[] = []
  const dets = new Map<number, Det[]>()
  let lastPost = 0
  let lastDets: Det[] = []
  // Which frames get a detection: the rate a second on a fixed schedule (lib/pace.ts), and every
  // frame develop's gate (at most 15 a second, or the nominal rate when sensitive) would have taken
  // at that rate; a cut's frame always; every frame in wide detection at its full rate. What that
  // really covered is measured as it goes.
  const gate = new DetectGate(sensitive ? nominalFps : 15)
  const meter = new CoverageMeter({ needAt: (f) => needRate(f, sensitive, maxRate) })
  let cover: Coverage = meter.result()
  // The smallest fine pass that ran (its long side, px), recorded as each detection starts.
  let minSide = Infinity
  const ranSide = () => (Number.isFinite(minSide) ? minSide : runSide(fineSide, frameSide))
  let found = 0
  let decodeWait = 0, detectMs = 0, cutMs = 0, poolWait = 0
  // Where a detection's result goes (in the pool, in any order), and the pace follows from it.
  const recordDets = (i: number, got: Det[], tm: Timing, yunetOk = true) => {
    const ms = tm.ms
    // YuNet lost on the way (a session that fails to run): from here on it is the reduced mode.
    if (!yunetOk || detector?.yunetMissing) {
      yunetOn = false
      reduce('YuNet failed')
    }
    lastDets = got
    detectMs += ms
    dets.set(i, got)
    found = Math.max(found, got.filter((d) => d.score >= 0.6).length)
    // After the first (warm-up) detections, every 4 detections: the pace that keeps analysis
    // near the video's own speed (see lib/pace.ts). The GPU's check against the CPU is taken out
    // of a detection's time (it runs until faces are seen: on a video with none, all along).
    spent.push(ms)
    if (detector) fineSide = Math.min(fineSide, detector.side)
    const w = pacer.add(tm)
    if (!tune && w) {
      const { recentMs: recent, rfMs } = w
      const gpuNow = backendNow() === 'webgpu'
      const startNow = gpuNow ? GPU_SIDE : CPU_SIDE
      const p = pace({ ...w, workers: pool?.size ?? 1, maxRate, side: fineSide, startSide: startNow, rate, gpu: gpuNow, frameSide, frameRate: frameRate.fps, nominal: nominalFps })
      rate = p.rate
      if (p.side !== fineSide) {
        fineSide = Math.min(p.side, startNow, startSide)
        if (detector) detector.side = fineSide
      }
      if (p.reduced) reduce(`${recent.toFixed(0)} ms a detection (RetinaFace ${rfMs.toFixed(0)}): ${runSide(fineSide, frameSide)} px, ${rate.toFixed(1)}/s`)
    }
  }
  // The counters for the stats panel; the history makes the per-second values of them.
  const history = new StatsHistory()
  const loopStart = performance.now()
  const snapshot = (): StatsSnapshot => {
    const d = pool?.stats() ?? detector!.stats
    const now = performance.now()
    return {
      atMs: now, frames: times.length, frameMs: Math.max(0, now - loopStart - decodeWait - poolWait), waitMs: poolWait, decodeWaitMs: decodeWait, cutMs,
      detections: spent.length, detectMs, partDetections: d.detections, pre: d.pre, inf: d.inf, dec: d.dec, coarse: d.coarse, coarseRuns: d.coarseRuns, check: d.check, verify: d.verify, merge: d.nms,
      backend: backendNow(), precision, noGpu: backendNow() === 'wasm' ? noGpu() : null,
      side: fineSide, rate, workers: pool?.size ?? 1, cores,
      measured: { rate: cover.rate, minWindow: cover.minWindow, need: cover.need, gapFrames: cover.maxGapFrames, gapS: cover.maxGapS, minSide: ranSide(), yunet: yunetOn },
    }
  }
  const statsNow = () => history.add(snapshot())
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
    frameRate.add(wc.timestamp)
    if (sensitive) ({ maxRate, rate, all } = follow(frameRate.fps, maxRate, rate, !!tune?.rate))
    const take = gate.take(wc.timestamp, rate, cut, all)
    meter.frame(wc.timestamp, cut, take, rate)
    cover = meter.result()
    if (cover.short && !reduced) reduce(measuredText(cover))
    if (take) {
      minSide = Math.min(minSide, runSide(pool ? fineSide : detector!.side, frameSide))
      if (pool) {
        // Wait for a free detector (the decoder keeps going meanwhile), then pass the frame on.
        const tp = performance.now()
        await pool.free()
        poolWait += performance.now() - tp
        await pool.detect(canvas, i, recordDets, fineSide, lowThr)
      } else {
        const td = performance.now()
        const checkBefore = detector!.stats.check
        const got = await detector!.detect(canvas)
        recordDets(i, got, { ms: performance.now() - td, ...detector!.last, checkMs: detector!.stats.check - checkBefore })
      }
    }
    const now = performance.now()
    if (now - lastPost > 250) {
      lastPost = now
      const ps = Math.min(1, 480 / Math.max(iw, ih))
      const preview = await createImageBitmap(canvas, { resizeWidth: Math.round(iw * ps), resizeHeight: Math.round(ih * ps) })
      post({ type: 'progress', done: i + 1, total: Math.max(total, i + 1), preview, dets: lastDets, faces: found, backend: backendNow(), stats: statsNow() }, [preview])
    }
  }
  try {
    if (pool) await pool.drain()
  } finally {
    pool?.close()
    ynWorker?.close()
  }
  cover = meter.result(true)
  if (cover.short && !reduced) reduce(measuredText(cover))
  log(`frames ${times.length}, detections ${spent.length}, detect avg ${(detectMs / Math.max(1, spent.length)).toFixed(0)} ms, decode wait ${decodeWait.toFixed(0)} ms, side ${fineSide}, rate ${rate.toFixed(1)}, reduced ${reduced}`)
  log(`stats ${summary(statsNow())}`)
  let faces50 = 0
  for (const ds of dets.values()) faces50 += ds.filter((d) => d.score >= 0.5).length
  const n = times.length
  const diffs: number[] = []
  for (let i = 1; i < n; i++) if (times[i] > times[i - 1]) diffs.push(times[i] - times[i - 1])
  diffs.sort((a, b) => a - b)
  const fps = Math.min(240, Math.max(5, diffs.length ? 1 / diffs[diffs.length >> 1] : nominalFps))
  const tracks = track(n, dets, cutList, fps, height / width, sensitive)
  // The result, and what detection really covered: D (detections a second within shots, and the
  // fewest in a 2 s window against the need), G (the longest gap within a shot), S (the smallest
  // fine pass that ran, YuNet).
  log(`result detections ${dets.size}, faces ${faces50}, tracks ${tracks.length}, reduced ${reduced}, yunet ${(pool?.stats() ?? detector!.stats).yunetFaces}; ${coverageText(cover, ranSide(), yunetOn)}`)

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
  const msg: AnalyzeMessage = { type: 'done', times: Float64Array.from(times), width, height, fps, tracks, cuts: cutList, lowConfidence: lowConfidenceTracks(tracks, dets), reduced, thumbs, backend: backendNow(), ms: performance.now() - t0 }
  post(msg, [msg.times.buffer, ...tracks.map((t) => t.boxes.buffer), ...thumbs.filter((b): b is ImageBitmap => !!b)])
}
