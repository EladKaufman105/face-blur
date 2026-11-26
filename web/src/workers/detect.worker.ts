// One CPU face detector on its own thread. WebAssembly runs on one thread here (no shared memory
// on a plain static host), so a few of these side by side are what use the other cores.
import { FaceDetector, type DetectStats } from '../lib/detector'
import type { Det } from '../lib/types'

export type DetectRequest =
  | { type: 'init'; model: Uint8Array; yunet: Uint8Array | null }
  /** [side] and [reduced]: how detection runs now (see the analysis worker's pacing); [sensitivity]: lib/sensitivity.ts. */
  | { type: 'frame'; id: number; frame: ImageBitmap; side: number; reduced: boolean; sensitivity?: number }
export type DetectReply =
  | { type: 'ready'; precision: string; yunet: boolean }
  /** [ms]: the whole detection; [rfMs], [fineMs]: RetinaFace's passes, its fine pass (the pacing's inputs). */
  | { type: 'dets'; id: number; dets: Det[]; ms: number; rfMs: number; fineMs: number; /** A sensitivity's extra work, left out of the pace. */ extraMs?: number; stats: DetectStats; yunet: boolean; /** A sensitivity above 0: the extra faces. */ extras: Det[] }
  | { type: 'error'; message: string }

const post = (m: DetectReply) => (self as unknown as Worker).postMessage(m)
let detector: FaceDetector | null = null
let canvas: OffscreenCanvas | null = null

self.onmessage = async (e: MessageEvent<DetectRequest>) => {
  const m = e.data
  try {
    if (m.type === 'init') {
      detector = await FaceDetector.create('', 'wasm', m.model, m.yunet)
      post({ type: 'ready', precision: detector.precision, yunet: !detector.yunetMissing })
    } else if (detector) {
      const f = m.frame
      detector.side = m.side
      detector.setReduced(m.reduced)
      detector.setSensitivity(m.sensitivity ?? 0)
      if (!canvas || canvas.width !== f.width || canvas.height !== f.height) canvas = new OffscreenCanvas(f.width, f.height)
      canvas.getContext('2d', { willReadFrequently: true })!.drawImage(f, 0, 0)
      f.close()
      const t = performance.now()
      const dets = await detector.detect(canvas)
      post({ type: 'dets', id: m.id, dets, ms: performance.now() - t, ...detector.last, stats: detector.stats, yunet: !detector.yunetMissing, extras: detector.lastExtras })
    }
  } catch (err) {
    post({ type: 'error', message: String((err as Error)?.message ?? err) })
  }
}
