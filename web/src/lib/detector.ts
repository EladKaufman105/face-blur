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

export type Backend = 'webgpu' | 'wasm'

export class FaceDetector {
  private priorCache = new Map<string, Float32Array>()
  private coarse: OffscreenCanvas | null = null
  private crop = new OffscreenCanvas(VERIFY_SIDE, VERIFY_SIDE)

  // While the GPU's results haven't yet been compared with the CPU's on frames with faces.
  private reference: ort.InferenceSession | null = null
  private agreed = 0

  private constructor(private session: ort.InferenceSession, public backend: Backend) {}

  static async create(modelUrl: string, force?: Backend): Promise<FaceDetector> {
    // One thread: ORT's thread pool can't start from inside a bundled module worker (WebGPU is
    // the fast path anyway).
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    const model = new Uint8Array(await (await fetch(modelUrl)).arrayBuffer())
    if ((navigator as any).gpu && force !== 'wasm') {
      try {
        const adapter = await (navigator as any).gpu.requestAdapter()
        if (adapter) {
          const s = await ort.InferenceSession.create(model, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all' })
          const d = new FaceDetector(s, 'webgpu')
          // Some GPUs/drivers compute wrong results: the first frames with faces are compared
          // with the CPU, as the Android app does with its GPU path.
          d.reference = await ort.InferenceSession.create(model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
          return d
        }
      } catch (e) {
        console.warn('ONIT: no WebGPU face detection', e)
      }
    }
    const s = await ort.InferenceSession.create(model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
    return new FaceDetector(s, 'wasm')
  }

  /**
   * Fine pass on the frame as given (its sides are multiples of 32) + a coarse pass at 480 px for
   * faces bigger than the anchors. Every face is then re-checked on a centered crop: clutter
   * (knees, gloves, walls) rarely survives that, real faces often do (the tracker uses it).
   */
  async detect(frame: OffscreenCanvas): Promise<Det[]> {
    const w = frame.width, h = frame.height
    const out: Det[] = []
    // Below ~800 px the fine pass's biggest anchors (512 px) already cover the biggest faces.
    const big = Math.max(w, h) > 800
    const coarse = big ? this.shrink(frame) : frame
    // The GPU's first frames with faces are compared with the CPU (on the small copy: cheaper);
    // on disagreement, detection moves to the CPU from this frame on.
    if (this.reference) await this.check(coarse)
    out.push(...await this.run(frame, RF_THRESHOLD, 0))
    if (big) out.push(...await this.run(coarse, RF_THRESHOLD, COARSE_MIN_BOX))
    const kept = nmsIou(out, 0.4)
    // Re-check up to 12 per frame (a crowd: the most uncertain ones), all crops in one run.
    await this.verify(frame, [...kept].sort((a, b) => a.score - b.score).slice(0, 12))
    return kept
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
  private async check(frame: OffscreenCanvas): Promise<boolean> {
    const ref = this.reference!
    const gpu = await this.run(frame, RF_THRESHOLD, 0)
    const cpu = await this.run(frame, RF_THRESHOLD, 0, ref)
    if (gpu.length === 0 && cpu.length === 0) return true
    const same = gpu.length === cpu.length && cpu.every((c) => gpu.some((g) => iou(g, c) > 0.8 && Math.abs(g.score - c.score) < 0.08))
    if (!same) {
      console.warn('ONIT: GPU face detection disagrees with the CPU; using the CPU')
      await this.session.release()
      this.session = ref
      this.backend = 'wasm'
      this.reference = null
      return false
    }
    if (++this.agreed >= 3) {
      await ref.release()
      this.reference = null
    }
    return true
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
    const t = new ort.Tensor('float32', input, [n, 3, S, S])
    const res = await this.session.run({ input: t }, ['loc', 'conf'])
    const loc = res.loc.data as Float32Array
    const conf = res.conf.data as Float32Array
    t.dispose(); res.loc.dispose(); res.conf.dispose()
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

  private async run(c: OffscreenCanvas, threshold: number, minBox: number, session = this.session): Promise<Det[]> {
    const w = c.width, h = c.height
    const px = c.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, w, h).data
    const n = w * h
    const input = new Float32Array(3 * n)
    fill(input, 0, px, n)
    const t = new ort.Tensor('float32', input, [1, 3, h, w])
    const res = await session.run({ input: t }, ['loc', 'conf'])
    const loc = res.loc.data as Float32Array
    const conf = res.conf.data as Float32Array
    t.dispose()
    res.loc.dispose()
    res.conf.dispose()
    return this.decode(loc, conf, w, h, threshold, minBox)
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
