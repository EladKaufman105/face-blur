// RetinaFace (MobileNetV2 backbone, yakhyo/retinaface-pytorch, MIT; trained on WIDER FACE) fused
// with YuNet (OpenCV Zoo, 2023mar), the same models, decoding and fusion as the Android app, run
// with ONNX Runtime Web (RetinaFace on WebGPU when the browser has it, else WebAssembly; YuNet,
// a 230 KB model, on WebAssembly).
import * as ort from 'onnxruntime-web/webgpu'
import type { Det } from './types'
import { recheckList, RF_THRESHOLD, RF_THRESHOLD_REDUCED, YUNET_SIDE, YUNET_THRESHOLD, YUNET_THRESHOLD_REDUCED, decodeYunet, fuse, iou, nmsIou } from './fusion'

export { RF_THRESHOLD, iou, nmsIou }

const STEPS = [8, 16, 32]
const MIN_SIZES = [[16, 32], [64, 128], [256, 512]]
const MEAN_B = 104, MEAN_G = 117, MEAN_R = 123
const COARSE_SIDE = 480
const COARSE_MIN_BOX = 64
const VERIFY_SIDE = 192
/** The long side the fine pass runs at: on the GPU, on the CPU, and on a device too slow for those (as the app's 1280 / 960 / 640). */
export const GPU_SIDE = 1280
export const CPU_SIDE = 960
export const LOW_SIDE = 640

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
  /** Faces YuNet found (before the fusion), summed: 0 means it never ran. */
  yunetFaces: number
}

type Session = ort.InferenceSession

export class FaceDetector {
  private priorCache = new Map<string, Float32Array>()
  private coarse: OffscreenCanvas | null = null
  private fine: OffscreenCanvas | null = null
  private crop = new OffscreenCanvas(VERIFY_SIDE, VERIFY_SIDE)
  private cpu: Session | null = null
  private yunetModel: Uint8Array | null
  private yn: Session | null = null
  private ynCanvas = new OffscreenCanvas(YUNET_SIDE, YUNET_SIDE)
  private ynInput = new Float32Array(3 * YUNET_SIDE * YUNET_SIDE)
  private thr = RF_THRESHOLD
  private ynThr = YUNET_THRESHOLD
  // The GPU's results are compared with the CPU's on the first frames with faces.
  /** Public for the pacing: a detection made while the GPU is being checked against the CPU isn't the device's pace. */
  checking: boolean
  private agreed = 0
  private gpuMs = 0
  private cpuMs = 0
  /** Long side the fine pass runs at (lowered when the device is slow). */
  side: number
  /** Why detection left the GPU, if it did. */
  fellBack: string | null = null
  /** YuNet couldn't be loaded: RetinaFace alone (weaker than the app's detection). */
  yunetMissing = false
  readonly stats: DetectStats = { detections: 0, pre: 0, inf: 0, dec: 0, coarse: 0, coarseRuns: 0, check: 0, verify: 0, nms: 0, yunetFaces: 0 }

  private constructor(private gpu: Session | null, private model32: Uint8Array, public precision: 'fp16' | 'fp32', yunet: Uint8Array | null) {
    this.checking = gpu !== null
    this.side = gpu ? GPU_SIDE : CPU_SIDE
    this.yunetModel = yunet
    this.yunetMissing = yunet === null
  }

  /**
   * The reduced mode (the device is too slow for the app's full detection): lower score
   * thresholds for what the first passes let into the fusion. That is modest: the tracker still
   * takes nothing under 0.4, so a weak face (RetinaFace 0.25 to 0.35, YuNet 0.3 to 0.4) only
   * counts where the other detector agrees and the noisy-or lifts it to 0.4 or more (the tracker and the
   * re-check of uncertain faces judge the extra ones).
   */
  setReduced(on: boolean) {
    this.thr = on ? RF_THRESHOLD_REDUCED : RF_THRESHOLD
    this.ynThr = on ? YUNET_THRESHOLD_REDUCED : YUNET_THRESHOLD
  }

  get backend(): Backend {
    return this.gpu ? 'webgpu' : 'wasm'
  }

  /** [base]: the folder with retinaface_fp32.onnx, retinaface_fp16.onnx and yunet.onnx; [fp32], [yunet]: those models' bytes if already loaded. */
  static async create(base: string, force?: Backend, fp32?: Uint8Array, yunet?: Uint8Array | null): Promise<FaceDetector> {
    // One thread: ORT's thread pool can't start from inside a bundled module worker (WebGPU is
    // the fast path anyway).
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    const load = async (name: string) => {
      const r = await fetch(base + name)
      if (!r.ok) throw new Error(`${name}: ${r.status}`)
      return new Uint8Array(await r.arrayBuffer())
    }
    const model32 = fp32 ?? await load('retinaface_fp32.onnx')
    // yunet === null: the caller tried and failed. Without it detection goes on (RetinaFace alone) and says so.
    const ynBytes = yunet !== undefined ? yunet : await load('yunet.onnx').catch((e) => { console.warn('ONIT: no YuNet', e); return null })
    const gpuApi = (navigator as any).gpu
    if (gpuApi && force !== 'wasm') {
      try {
        const adapter = await gpuApi.requestAdapter()
        if (adapter) {
          // Half precision where the GPU has it: about twice the speed on phones and tablets.
          const half = adapter.features?.has?.('shader-f16') === true
          const model = half ? await load('retinaface_fp16.onnx') : model32
          const s = await ort.InferenceSession.create(model, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all' })
          const g = new FaceDetector(s, model32, half ? 'fp16' : 'fp32', ynBytes)
          // YuNet's session here too: a failure to start it shows (yunetMissing) before the first frame.
          await g.yunetSession()
          return g
        }
      } catch (e) {
        console.warn('ONIT: no WebGPU face detection', e)
      }
    }
    const d = new FaceDetector(null, model32, 'fp32', ynBytes)
    await d.cpuSession()
    await d.yunetSession()
    return d
  }

  private async cpuSession(): Promise<Session> {
    this.cpu ??= await ort.InferenceSession.create(this.model32, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
    return this.cpu
  }

  /** YuNet's session (always WebAssembly: the model is tiny); null if it can't be had. */
  private async yunetSession(): Promise<Session | null> {
    if (!this.yn && this.yunetModel) {
      try {
        this.yn = await ort.InferenceSession.create(this.yunetModel, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
      } catch (e) {
        console.warn('ONIT: no YuNet', e)
        this.yunetModel = null
        this.yunetMissing = true
      }
    }
    return this.yn
  }

  /** YuNet on the frame (the app runs it at 640 px at most): its faces as fractions of the frame. */
  private async yunet(frame: OffscreenCanvas): Promise<Det[]> {
    const sess = await this.yunetSession()
    if (!sess) return []
    const S = YUNET_SIDE
    const s = Math.min(1, S / Math.max(frame.width, frame.height))
    const w = Math.max(16, Math.round(frame.width * s)), h = Math.max(16, Math.round(frame.height * s))
    const ctx = this.ynCanvas.getContext('2d', { willReadFrequently: true })!
    // The model's input is a fixed 640 x 640: the frame in its top left corner, black around it.
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, S, S)
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(frame, 0, 0, w, h)
    const px = ctx.getImageData(0, 0, S, S).data
    const n = S * S
    const input = this.ynInput
    // BGR, 0..255 as they are (no mean).
    for (let i = 0, j = 0; i < n; i++, j += 4) {
      input[i] = px[j + 2]
      input[n + i] = px[j + 1]
      input[2 * n + i] = px[j]
    }
    const t = new ort.Tensor('float32', input, [1, 3, S, S])
    let r: Awaited<ReturnType<Session['run']>>
    try {
      r = await sess.run({ input: t })
    } catch (e) {
      // YuNet fails to run: detection goes on with RetinaFace alone, and says so (yunetMissing).
      console.warn('ONIT: YuNet failed', e)
      this.yn = null
      this.yunetModel = null
      this.yunetMissing = true
      return []
    } finally {
      t.dispose()
    }
    const get = (k: string) => r[k].data as Float32Array
    return decodeYunet({ cls: [get('cls_8'), get('cls_16'), get('cls_32')], obj: [get('obj_8'), get('obj_16'), get('obj_32')], bbox: [get('bbox_8'), get('bbox_16'), get('bbox_32')] }, w, h, this.ynThr)
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
   * anchors, fused with YuNet's faces (as the app's FaceDetector). Uncertain faces are then re-checked on a centered crop: clutter (knees, gloves,
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
    out.push(...await this.run(src, this.thr, 0, st))
    if (big) {
      t = performance.now()
      out.push(...await this.run(coarse, this.thr, COARSE_MIN_BOX))
      st.coarse += performance.now() - t
      st.coarseRuns++
    }
    t = performance.now()
    const rf = nmsIou(out, 0.4)
    // YuNet adds the faces RetinaFace misses and raises the score where both agree; its time and the fusion count as the merge.
    const yn = await this.yunet(src)
    st.yunetFaces += yn.length
    const kept = fuse(rf, yn)
    st.nms += performance.now() - t
    // Re-check the uncertain ones (up to 12, all crops in one run); confident faces count as
    // strong evidence in the tracker without it.
    t = performance.now()
    await this.verify(src, recheckList(kept))
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
