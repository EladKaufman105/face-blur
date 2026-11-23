// Finds the people in a frame, as the Android app's Segmenter: PP-HumanSeg (PaddleSeg, Apache
// 2.0, from OpenCV Zoo) with ONNX Runtime Web. The model takes a 192 x 192 square, so a wide (or
// tall) frame is covered by three overlapping square tiles, blended with feathered edges: people
// aren't squeezed and are seen bigger.
import * as ort from 'onnxruntime-web/webgpu'

const IN = 192

export class PeopleFinder {
  private tile = new OffscreenCanvas(IN, IN)
  private input = new Float32Array(3 * IN * IN)
  private weights = new Map<string, Float32Array[]>()

  private constructor(private session: ort.InferenceSession, readonly backend: string) {}

  /** [model]: the model's URL. WebGPU when the browser has it, else WebAssembly. */
  static async create(model: string, force?: 'webgpu' | 'wasm'): Promise<PeopleFinder> {
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    const bytes = new Uint8Array(await (await fetch(model)).arrayBuffer())
    if ((navigator as any).gpu && force !== 'wasm') {
      try {
        const s = await ort.InferenceSession.create(bytes, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all' })
        return new PeopleFinder(s, 'webgpu')
      } catch (e) {
        console.warn('ONIT: people on the CPU', e)
      }
    }
    return new PeopleFinder(await ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }), 'wasm')
  }

  /** The model on a square of [src] (x, y, side, in its pixels): person probability, 192 x 192. */
  private async infer(src: CanvasImageSource, x: number, y: number, side: number): Promise<Float32Array> {
    const ctx = this.tile.getContext('2d', { willReadFrequently: true })!
    ctx.drawImage(src, x, y, side, side, 0, 0, IN, IN)
    const px = ctx.getImageData(0, 0, IN, IN).data
    const n = IN * IN
    // (x / 255 - 0.5) / 0.5, as the model was trained (RGB).
    for (let i = 0; i < n; i++) {
      this.input[i] = px[i * 4] / 127.5 - 1
      this.input[n + i] = px[i * 4 + 1] / 127.5 - 1
      this.input[2 * n + i] = px[i * 4 + 2] / 127.5 - 1
    }
    const feeds: Record<string, ort.Tensor> = { [this.session.inputNames[0]]: new ort.Tensor('float32', this.input, [1, 3, IN, IN]) }
    const out = await this.session.run(feeds)
    const t = out[this.session.outputNames[0]]
    const d = (await t.getData()) as Float32Array
    // Channel 1: the person's probability (after the model's softmax).
    return d.slice(n, 2 * n)
  }

  /**
   * Person probability over the frame [src] (w x h), at [mw] x [mh] (the frame's shape): one
   * look when it's nearly square, else three overlapping square tiles.
   */
  async people(src: CanvasImageSource, w: number, h: number, mw: number, mh: number): Promise<Float32Array> {
    const out = new Float32Array(mw * mh)
    const side = Math.min(w, h), long = Math.max(w, h)
    const wide = w >= h
    const tiles = long < side * 1.2 ? 1 : 3
    if (tiles === 1) {
      const p = await this.infer(src, (w - side) / 2, (h - side) / 2, side)
      for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++) out[y * mw + x] = sample(p, (x + 0.5) / mw * IN - 0.5, (y + 0.5) / mh * IN - 0.5)
      return out
    }
    // In mask pixels: the tile's side and where each starts along the long axis.
    const mSide = wide ? mh : mw
    const mLong = wide ? mw : mh
    const ws = this.tileWeights(mSide, tiles)
    const sum = new Float32Array(mw * mh)
    for (let k = 0; k < tiles; k++) {
      const off = Math.round(((long - side) * k) / (tiles - 1))
      const p = await this.infer(src, wide ? off : 0, wide ? 0 : off, side)
      const mOff = Math.round(((mLong - mSide) * k) / (tiles - 1))
      const wk = ws[k]
      for (let j = 0; j < mSide; j++) {
        for (let i = 0; i < mSide; i++) {
          const x = wide ? mOff + i : i, y = wide ? j : mOff + j
          if (x >= mw || y >= mh) continue
          const along = wide ? i : j
          const v = sample(p, (i + 0.5) / mSide * IN - 0.5, (j + 0.5) / mSide * IN - 0.5)
          const q = y * mw + x
          out[q] += v * wk[along]
          sum[q] += wk[along]
        }
      }
    }
    for (let i = 0; i < out.length; i++) out[i] /= Math.max(1e-6, sum[i])
    return out
  }

  /** Each tile's weights along the tiling axis: 1 in the middle, falling to 0 over the outer quarter, except at the frame's edges. */
  private tileWeights(side: number, tiles: number): Float32Array[] {
    const key = `${side} ${tiles}`
    const cached = this.weights.get(key)
    if (cached) return cached
    const ramp = (i: number) => Math.min(1, Math.min(i + 1, side - i) / (side * 0.25))
    const ws = Array.from({ length: tiles }, (_, k) => {
      const v = new Float32Array(side)
      for (let i = 0; i < side; i++) v[i] = (k === 0 && i < side / 2) || (k === tiles - 1 && i >= side / 2) ? 1 : ramp(i)
      return v
    })
    this.weights.set(key, ws)
    return ws
  }
}

function sample(p: Float32Array, x: number, y: number): number {
  x = Math.max(0, Math.min(IN - 1.001, x))
  y = Math.max(0, Math.min(IN - 1.001, y))
  const x0 = x | 0, y0 = y | 0, fx = x - x0, fy = y - y0
  const i = y0 * IN + x0
  return p[i] * (1 - fx) * (1 - fy) + p[i + 1] * fx * (1 - fy) + p[i + IN] * (1 - fx) * fy + p[i + IN + 1] * fx * fy
}

/** Neighbours on each side in the median; motion (gray levels) that counts as moved; how much of the last mask is kept where still / moved. */
const HALF = 2
const MOTION = 6
const STILL_KEEP = 0.95
const MOVING_KEEP = 0.3

/**
 * The masks steadied over time, as in the app (the model looks at each frame on its own, so its
 * masks shimmer even when nothing moves): each stored mask is the per-pixel median of its frame's
 * and two neighbours on each side, eased into the previous stored mask wherever the picture
 * didn't change. A mask comes out two frames late; [flush] gives the rest (at a cut, at the end).
 */
export class MaskSmoother {
  private pending: { index: number; mask: Float32Array; gray: Float32Array }[] = []
  private next = 0
  private outPrev: Float32Array | null = null
  private grayPrev: Float32Array | null = null

  constructor(private n: number, private onMask: (index: number, mask: Uint8Array) => void) {}

  add(index: number, mask: Float32Array, gray: Float32Array, newShot: boolean) {
    if (newShot) { this.flush(); this.outPrev = null; this.grayPrev = null }
    this.pending.push({ index, mask, gray })
    while (this.next + HALF < this.pending.length) this.store(this.next++)
    while (this.next > HALF) { this.pending.shift(); this.next-- }
  }

  flush() {
    while (this.next < this.pending.length) this.store(this.next++)
    this.pending = []
    this.next = 0
  }

  private store(k: number) {
    const f = this.pending[k]
    const lo = Math.max(0, k - HALF), hi = Math.min(this.pending.length - 1, k + HALF)
    const m = hi - lo + 1
    const v = new Float32Array(m)
    const out = new Uint8Array(this.n)
    const cur = new Float32Array(this.n)
    const prev = this.outPrev, gp = this.grayPrev
    for (let i = 0; i < this.n; i++) {
      for (let j = 0; j < m; j++) v[j] = this.pending[lo + j].mask[i]
      v.sort()
      let med = m % 2 ? v[m >> 1] : (v[m / 2 - 1] + v[m / 2]) / 2
      if (prev && gp) {
        const a = Math.min(1, Math.abs(f.gray[i] - gp[i]) / MOTION)
        const keep = STILL_KEEP * (1 - a) + MOVING_KEEP * a
        med = med * (1 - keep) + prev[i] * keep
      }
      cur[i] = med
      out[i] = Math.max(0, Math.min(255, Math.round(med * 255)))
    }
    this.outPrev = cur
    this.grayPrev = f.gray
    this.onMask(f.index, out)
  }
}
