// Selfie mode's detector: MediaPipe's BlazeFace full-range (Apache-2.0, converted from its TFLite
// model by scripts/blazeface_to_onnx.py) on ONNX Runtime Web's WebAssembly: at 192 x 192 it takes
// a few milliseconds a frame, which a GPU session (and the check against the CPU the face models'
// GPU pass needs) would not make worth it. Decoding and suppression are lib/blazeface.ts.
import * as ort from 'onnxruntime-web/webgpu'
import type { Det } from './types'
import { SIZE, faces, letterbox } from './blazeface'

/** The model's file in the web app's models folder (made from models/blazeface_full_range.onnx). */
export const BLAZEFACE_MODEL = 'blazeface_full_range.onnx'

export class BlazeFaceDetector {
  private canvas = new OffscreenCanvas(SIZE, SIZE)
  private input = new Float32Array(3 * SIZE * SIZE)
  /** The latest detection's extra faces (a sensitivity above 0); else empty. */
  lastExtras: Det[] = []
  /** The latest detection's time (ms) and the sums over the run, for the stats line. */
  lastMs = 0
  readonly stats = { detections: 0, pre: 0, inf: 0, dec: 0 }

  private constructor(private session: ort.InferenceSession, private sens: number) {}

  /** [model]: the ONNX file's bytes. [sensitivity] 0..1 (lib/sensitivity.ts): above 0 the faces a lower threshold also finds go to [lastExtras]. */
  static async create(model: Uint8Array, sensitivity = 0): Promise<BlazeFaceDetector> {
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    return new BlazeFaceDetector(await ort.InferenceSession.create(model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }), sensitivity)
  }

  /** The faces in [frame] (any size): fractions of the frame, the score over 0.5 as in MediaPipe. */
  async detect(frame: OffscreenCanvas | ImageBitmap): Promise<Det[]> {
    const t0 = performance.now()
    const w = frame.width, h = frame.height
    // The frame letterboxed into the model's square the way MediaPipe does it (scaled to fit, black bars), then RGB as (v - 127.5) / 127.5, planar.
    const lb = letterbox(w, h)
    const ctx = this.canvas.getContext('2d', { willReadFrequently: true })!
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, SIZE, SIZE)
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(frame, lb.padX * SIZE, lb.padY * SIZE, w * lb.scale, h * lb.scale)
    const px = ctx.getImageData(0, 0, SIZE, SIZE).data
    const n = SIZE * SIZE
    const input = this.input
    for (let i = 0, j = 0; i < n; i++, j += 4) {
      input[i] = (px[j] - 127.5) / 127.5
      input[n + i] = (px[j + 1] - 127.5) / 127.5
      input[2 * n + i] = (px[j + 2] - 127.5) / 127.5
    }
    const t1 = performance.now()
    const tensor = new ort.Tensor('float32', input, [1, 3, SIZE, SIZE])
    let regressors: Float32Array, scores: Float32Array
    try {
      const r = await this.session.run({ input: tensor })
      regressors = r.regressors.data as Float32Array
      scores = r.scores.data as Float32Array
    } finally {
      tensor.dispose()
    }
    const t2 = performance.now()
    const out = faces(regressors, scores, w, h, this.sens > 0)
    this.lastExtras = out.extras
    const t3 = performance.now()
    this.lastMs = t3 - t0
    this.stats.detections++
    this.stats.pre += t1 - t0
    this.stats.inf += t2 - t1
    this.stats.dec += t3 - t2
    return out.faces
  }

  release() {
    this.session.release().catch(() => {})
  }
}
