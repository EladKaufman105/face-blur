// RetinaFace (MobileNetV2 backbone, yakhyo/retinaface-pytorch, MIT; trained on WIDER FACE), the
// same model and decoding as the Android app, run with ONNX Runtime Web (WebGPU when the browser
// has it, else WebAssembly).
import * as ort from 'onnxruntime-web/webgpu'
import type { Det } from './types'

const STEPS = [8, 16, 32]
const MIN_SIZES = [[16, 32], [64, 128], [256, 512]]
const MEAN_B = 104, MEAN_G = 117, MEAN_R = 123
export const RF_THRESHOLD = 0.35
const COARSE_SIDE = 480
const COARSE_MIN_BOX = 64
const VERIFY_SIDE = 192
/** The long side the fine pass runs at on the CPU (960 on the GPU). */
export const CPU_SIDE = 640

export type Backend = 'webgpu' | 'wasm'

/** Where a detection's time goes, summed over the run (ms); the stats line shows the averages. */
export interface DetectStats {
  detections: number
  /** Fine pass: pixels to tensor, inference, decoding the outputs. */
  pre: number
  inf: number
  dec: number
  /** The whole coarse pass (resize, prep, inference, decode), and how many detections ran it. */
  coarse: number
  coarseRuns: number
  /** The GPU-vs-CPU check, the crop re-check of uncertain faces, the merge of both passes. */
  check: number
  verify: number
  nms: number
}

type Session = ort.InferenceSession

export class FaceDetector {
  private priorCache = new Map<string, Float32Array>()
  private coarse: OffscreenCanvas | null = null
  private fine: OffscreenCanvas | null = null
  private crop = new OffscreenCanvas(VERIFY_SIDE, VERIFY_SIDE)
  private cpu: Session | null = null
  // The GPU's results are compared with the CPU's on the first frames with faces.
  private checking: boolean
  private agreed = 0
  private gpuMs = 0
  private cpuMs = 0
  /** Long side the fine pass runs at (lowered when the device is slow). */
  side: number
  /** Why detection left the GPU, if it did. */
  fellBack: string | null = null
  readonly stats: DetectStats = { detections: 0, pre: 0, inf: 0, dec: 0, coarse: 0, coarseRuns: 0, check: 0, verify: 0, nms: 0 }

  private constructor(private gpu: Session | null, private model32: Uint8Array, public precision: 'fp16' | 'fp32') {
    this.checking = gpu !== null
    this.side = gpu ? 960 : CPU_SIDE
  }

  get backend(): Backend {
    return this.gpu ? 'webgpu' : 'wasm'
  }

  /** [base]: the folder with retinaface_fp32.onnx and retinaface_fp16.onnx; [fp32]: that model's bytes if already loaded. */
  static async create(base: string, force?: Backend, fp32?: Uint8Array): Promise<FaceDetector> {
    // One thread: ORT's thread pool can't start from inside a bundled module worker (WebGPU is
    // the fast path anyway).
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    const load = async (name: string) => new Uint8Array(await (await fetch(base + name)).arrayBuffer())
    const model32 = fp32 ?? await load('retinaface_fp32.onnx')
    const gpuApi = (navigator as any).gpu
    if (gpuApi && force !== 'wasm') {
      try {
        const adapter = await gpuApi.requestAdapter()
        if (adapter) {
          // Half precision where the GPU has it: about twice the speed on phones and tablets.
          const half = adapter.features?.has?.('shader-f16') === true
          const model = half ? await load('retinaface_fp16.onnx') : model32
          const s = await ort.InferenceSession.create(model, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all' })
          return new FaceDetector(s, model32, half ? 'fp16' : 'fp32')
        }
      } catch (e) {
        console.warn('ONIT: no WebGPU face detection', e)
      }
    }
    const d = new FaceDetector(null, model32, 'fp32')
    await d.cpuSession()
    return d
  }

  private async cpuSession(): Promise<Session> {
    this.cpu ??= await ort.InferenceSession.create(this.model32, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
    return this.cpu
  }

  /** From now on on the CPU. The GPU session isn't released: on some drivers that breaks WebGPU. */
  private leaveGpu(why: string) {
    console.warn('ONIT: face detection moves to the CPU:', why)
    this.fellBack = why
    this.gpu = null
    this.checking = false
    this.side = Math.min(this.side, CPU_SIDE)
  }

  /**
   * Fine pass on the frame (at [side]) + a coarse pass at 480 px for faces bigger than the
   * anchors. Uncertain faces are then re-checked on a centered crop: clutter (knees, gloves,
   * walls) rarely survives that, real faces often do (the tracker uses it).
   */
  async detect(frame: OffscreenCanvas): Promise<Det[]> {
    const src = Math.max(frame.width, frame.height) > this.side * 1.05 ? this.resize(frame, this.side) : frame
    const st = this.stats
    st.detections++
    const out: Det[] = []
    // Below ~800 px the fine pass's biggest anchors (512 px) already cover the biggest faces.
    const big = Math.max(src.width, src.height) > 800
    let t = performance.now()
    const coarse = big ? this.shrink(src) : src
    if (big) st.coarse += performance.now() - t
    if (this.checking) {
      t = performance.now()
      await this.check(coarse)
      st.check += performance.now() - t
    }
    out.push(...await this.run(src, RF_THRESHOLD, 0, st))
    if (big) {
      t = performance.now()
      out.push(...await this.run(coarse, RF_THRESHOLD, COARSE_MIN_BOX))
      st.coarse += performance.now() - t
      st.coarseRuns++
    }
    t = performance.now()
    const kept = nmsIou(out, 0.4)
    st.nms += performance.now() - t
    // Re-check the uncertain ones (up to 12, all crops in one run); confident faces count as
    // strong evidence in the tracker without it.
    t = performance.now()
    await this.verify(src, kept.filter((d) => d.score < 0.8).sort((a, b) => a.score - b.score).slice(0, 12))
    st.verify += performance.now() - t
    return kept
  }

  private resize(frame: OffscreenCanvas, side: number): OffscreenCanvas {
    const w = frame.width, h = frame.height
    const s = side / Math.max(w, h)
    const cw = Math.max(32, Math.round(w * s / 32) * 32), ch = Math.max(32, Math.round(h * s / 32) * 32)
    if (!this.fine || this.fine.width !== cw || this.fine.height !== ch) this.fine = new OffscreenCanvas(cw, ch)
    this.fine.getContext('2d', { willReadFrequently: true })!.drawImage(frame, 0, 0, cw, ch)
    return this.fine
  }

  private shrink(frame: OffscreenCanvas): OffscreenCanvas {
    const w = frame.width, h = frame.height
    const s = COARSE_SIDE / Math.max(w, h)
    const cw = Math.max(32, Math.round(w * s / 32) * 32), ch = Math.max(32, Math.round(h * s / 32) * 32)
    if (!this.coarse || this.coarse.width !== cw || this.coarse.height !== ch) this.coarse = new OffscreenCanvas(cw, ch)
    this.coarse.getContext('2d', { willReadFrequently: true })!.drawImage(frame, 0, 0, cw, ch)
    return this.coarse
  }

  /** Compares the GPU's faces with the CPU's; on disagreement, detection moves to the CPU. */
  private async check(frame: OffscreenCanvas) {
    const t0 = performance.now()
    const gpu = await this.run(frame, RF_THRESHOLD, 0)
    const t1 = performance.now()
    if (!this.gpu) return
    const cpu = await this.runOn(await this.cpuSession(), frame, RF_THRESHOLD, 0)
    const t2 = performance.now()
    if (gpu.length === 0 && cpu.length === 0) return
    const same = gpu.length === cpu.length && cpu.every((c) => gpu.some((g) => iou(g, c) > 0.8 && Math.abs(g.score - c.score) < 0.08))
    if (!same) return this.leaveGpu('results differ from the CPU')
    // Timing after the first (the GPU compiles its shaders then): a weak GPU can be slower
    // than the CPU.
    if (this.agreed > 0) {
      this.gpuMs += t1 - t0
      this.cpuMs += t2 - t1
    }
    if (++this.agreed >= 3) {
      this.checking = false
      if (this.cpuMs < this.gpuMs * 0.9) this.leaveGpu(`the CPU is faster here (${this.cpuMs.toFixed(0)} vs ${this.gpuMs.toFixed(0)} ms)`)
    }
  }

  /** Sets [dets]' verify scores: each re-detected on a square crop twice its size, 192 px. */
  private async verify(frame: OffscreenCanvas, dets: Det[]) {
    if (dets.length === 0) return
    // Batch sizes rounded up, so the GPU compiles a handful of shapes, not one per face count.
    const n = [1, 2, 4, 8, 12].find((b) => b >= dets.length)!
    const S = VERIFY_SIDE, px = S * S
    const input = new Float32Array(n * 3 * px)
    const w = frame.width, h = frame.height
    const ctx = this.crop.getContext('2d', { willReadFrequently: true })!
    dets.forEach((d, k) => {
      const cx = d.cx * w, cy = d.cy * h
      const r = Math.max(d.w * w, d.h * h)
      ctx.fillStyle = '#000'
      ctx.fillRect(0, 0, S, S)
      ctx.drawImage(frame, cx - r, cy - r, 2 * r, 2 * r, 0, 0, S, S)
      fill(input, k * 3 * px, ctx.getImageData(0, 0, S, S).data, px)
    })
    const [loc, conf] = await this.infer(input, [n, 3, S, S])
    const per = loc.length / n
    dets.forEach((d, k) => {
      let best = 0
      const found = this.decode(loc.subarray(k * per, (k + 1) * per), conf.subarray(k * per / 2, (k + 1) * per / 2), S, S, 0.05, 0)
      for (const v of found) {
        if (Math.abs(v.cx * S - S / 2) < S * 0.25 && Math.abs(v.cy * S - S / 2) < S * 0.25 && v.w * S > S * 0.2) best = Math.max(best, v.score)
      }
      d.verify = best
    })
  }

  /** [st] set: the time of each step is added to it (the fine pass; the coarse one is timed whole). */
  private async run(c: OffscreenCanvas, threshold: number, minBox: number, st?: DetectStats): Promise<Det[]> {
    const w = c.width, h = c.height
    const t0 = performance.now()
    const px = c.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, w, h).data
    const input = new Float32Array(3 * w * h)
    fill(input, 0, px, w * h)
    const t1 = performance.now()
    const [loc, conf] = await this.infer(input, [1, 3, h, w])
    const t2 = performance.now()
    const dets = this.decode(loc, conf, w, h, threshold, minBox)
    if (st) {
      st.pre += t1 - t0
      st.inf += t2 - t1
      st.dec += performance.now() - t2
    }
    return dets
  }

  private async runOn(session: Session, c: OffscreenCanvas, threshold: number, minBox: number): Promise<Det[]> {
    const w = c.width, h = c.height
    const px = c.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, w, h).data
    const input = new Float32Array(3 * w * h)
    fill(input, 0, px, w * h)
    const [loc, conf] = await this.inferOn(session, input, [1, 3, h, w])
    return this.decode(loc, conf, w, h, threshold, minBox)
  }

  /** On the GPU while it works; if it fails (a lost device, a driver error), on the CPU. */
  private async infer(input: Float32Array, dims: number[]): Promise<[Float32Array, Float32Array]> {
    if (this.gpu) {
      try {
        return await this.inferOn(this.gpu, input, dims)
      } catch (e) {
        this.leaveGpu(String((e as Error)?.message ?? e).slice(0, 200))
      }
    }
    return this.inferOn(await this.cpuSession(), input, dims)
  }

  private async inferOn(session: Session, input: Float32Array, dims: number[]): Promise<[Float32Array, Float32Array]> {
    const t = new ort.Tensor('float32', input, dims)
    const res = await session.run({ input: t }, ['loc', 'conf'])
    const out: [Float32Array, Float32Array] = [res.loc.data as Float32Array, res.conf.data as Float32Array]
    t.dispose()
    return out
  }

  private decode(loc: Float32Array, conf: Float32Array, iw: number, ih: number, threshold: number, minBox: number): Det[] {
    const pri = this.priors(ih, iw)
    const n = pri.length / 4
    if (loc.length < n * 4 || conf.length < n * 2) return []
    const raw: Det[] = []
    for (let i = 0; i < n; i++) {
      const score = conf[i * 2 + 1]
      if (score < threshold) continue
      const px = pri[i * 4], py = pri[i * 4 + 1], pw = pri[i * 4 + 2], ph = pri[i * 4 + 3]
      const cx = px + loc[i * 4] * 0.1 * pw
      const cy = py + loc[i * 4 + 1] * 0.1 * ph
      const bw = pw * Math.exp(loc[i * 4 + 2] * 0.2)
      const bh = ph * Math.exp(loc[i * 4 + 3] * 0.2)
      if (Math.min(bw * iw, bh * ih) < minBox) continue
      raw.push({ cx, cy, w: bw, h: bh, score, verify: 0 })
    }
    return nmsIou(raw, 0.4)
  }

  /** RetinaFace anchors, order: level, row, column, size. */
  private priors(h: number, w: number): Float32Array {
    const key = `${w}x${h}`
    let p = this.priorCache.get(key)
    if (p) return p
    const list: number[] = []
    for (let k = 0; k < STEPS.length; k++) {
      const step = STEPS[k]
      const fh = Math.ceil(h / step), fw = Math.ceil(w / step)
      for (let i = 0; i < fh; i++) for (let j = 0; j < fw; j++) for (const ms of MIN_SIZES[k]) {
        list.push((j + 0.5) * step / w, (i + 0.5) * step / h, ms / w, ms / h)
      }
    }
    p = new Float32Array(list)
    this.priorCache.set(key, p)
    return p
  }
}

/** RGBA pixels -> the model's planar BGR minus the mean, at [at] in [out]. */
function fill(out: Float32Array, at: number, px: Uint8ClampedArray, n: number) {
  for (let i = 0, j = 0; i < n; i++, j += 4) {
    out[at + i] = px[j + 2] - MEAN_B
    out[at + n + i] = px[j + 1] - MEAN_G
    out[at + 2 * n + i] = px[j] - MEAN_R
  }
}

export function iou(a: Det, b: Det): number {
  const iw = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
  const ih = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
  if (iw <= 0 || ih <= 0) return 0
  const inter = iw * ih
  return inter / (a.w * a.h + b.w * b.h - inter)
}

export function nmsIou(dets: Det[], thr: number): Det[] {
  const keep: Det[] = []
  for (const d of [...dets].sort((a, b) => b.score - a.score)) {
    if (keep.every((k) => iou(k, d) < thr)) keep.push(d)
  }
  return keep
}
