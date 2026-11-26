// YuNet (OpenCV Zoo, 2023mar; the app's own file), the second face detector, on WebAssembly: in
// the detector's thread, or in a worker of its own (lib/yunet-worker.ts) so that it runs while
// RetinaFace works.
import * as ort from 'onnxruntime-web/webgpu'
import type { Det } from './types'
import { YUNET_SIDE, decodeYunet } from './fusion'

export class Yunet {
  private canvas = new OffscreenCanvas(YUNET_SIDE, YUNET_SIDE)
  private input = new Float32Array(3 * YUNET_SIDE * YUNET_SIDE)

  private constructor(private session: ort.InferenceSession) {}

  static async create(model: Uint8Array): Promise<Yunet> {
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    return new Yunet(await ort.InferenceSession.create(model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }))
  }

  /** Its faces on [frame] (the app runs it at 640 px at most), as fractions of the frame; scores from [threshold]. Throws if the model fails to run. */
  async detect(frame: OffscreenCanvas | ImageBitmap, threshold: number): Promise<Det[]> {
    const S = YUNET_SIDE
    const s = Math.min(1, S / Math.max(frame.width, frame.height))
    const w = Math.max(16, Math.round(frame.width * s)), h = Math.max(16, Math.round(frame.height * s))
    const ctx = this.canvas.getContext('2d', { willReadFrequently: true })!
    // The model's input is a fixed 640 x 640: the frame in its top left corner, black around it.
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, S, S)
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(frame, 0, 0, w, h)
    const px = ctx.getImageData(0, 0, S, S).data
    const n = S * S
    const input = this.input
    // BGR, 0..255 as they are (no mean).
    for (let i = 0, j = 0; i < n; i++, j += 4) {
      input[i] = px[j + 2]
      input[n + i] = px[j + 1]
      input[2 * n + i] = px[j]
    }
    const t = new ort.Tensor('float32', input, [1, 3, S, S])
    try {
      const r = await this.session.run({ input: t })
      const get = (k: string) => r[k].data as Float32Array
      return decodeYunet({ cls: [get('cls_8'), get('cls_16'), get('cls_32')], obj: [get('obj_8'), get('obj_16'), get('obj_32')], bbox: [get('bbox_8'), get('bbox_16'), get('bbox_32')] }, w, h, threshold)
    } finally {
      t.dispose()
    }
  }

  release() {
    this.session.release().catch(() => {})
  }
}
