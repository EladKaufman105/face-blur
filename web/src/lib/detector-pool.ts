import type { DetectReply } from '../workers/detect.worker'
import type { DetectStats } from './detector'
import type { Det } from './types'

/**
 * CPU detection spread over a few workers. A WebAssembly inference runs on one thread and takes
 * most of a detection's time, so while one worker detects a frame the others take the next ones.
 */
export class DetectorPool {
  private idle: Worker[] = []
  private waiting: (() => void)[] = []
  private running = 0
  private drained: (() => void)[] = []
  private last = new Map<Worker, DetectStats>()
  private failed: string | null = null
  precision = 'fp32'

  private constructor(private all: Worker[]) {}

  /** [model]: the fp32 model's bytes, fetched once by the caller (the workers each build a session from them). */
  static async create(model: Uint8Array, n: number): Promise<DetectorPool> {
    const workers = Array.from({ length: n }, () => new Worker(new URL('../workers/detect.worker.ts', import.meta.url), { type: 'module' }))
    const pool = new DetectorPool(workers)
    await Promise.all(workers.map((w) => new Promise<void>((ok, fail) => {
      w.onmessage = (e: MessageEvent<DetectReply>) => {
        if (e.data.type === 'ready') {
          pool.precision = e.data.precision
          ok()
        } else if (e.data.type === 'error') fail(new Error(e.data.message))
      }
      w.onerror = (e) => fail(new Error(e.message))
      w.postMessage({ type: 'init', model })
    })))
    pool.idle = [...workers]
    return pool
  }

  get size() {
    return this.all.length
  }

  /** Waits until a worker is free. */
  async free(): Promise<void> {
    if (this.failed) throw new Error(this.failed)
    if (this.idle.length === 0) await new Promise<void>((r) => this.waiting.push(r))
    if (this.failed) throw new Error(this.failed)
  }

  /** Hands [frame] (copied here, so the canvas can be reused at once) to a free worker; call [free] first. */
  async detect(canvas: OffscreenCanvas, id: number, done: (id: number, dets: Det[], ms: number) => void) {
    const w = this.idle.pop()!
    this.running++
    const frame = await createImageBitmap(canvas)
    w.onmessage = (e: MessageEvent<DetectReply>) => {
      const m = e.data
      if (m.type === 'dets') {
        this.last.set(w, m.stats)
        done(m.id, m.dets, m.ms)
        this.idle.push(w)
        this.waiting.shift()?.()
        if (--this.running === 0) this.drained.splice(0).forEach((r) => r())
      } else if (m.type === 'error') this.fail(m.message)
    }
    w.onerror = (e) => this.fail(e.message)
    w.postMessage({ type: 'frame', id, frame }, [frame])
  }

  /** Resolves when every frame handed over has come back. */
  async drain(): Promise<void> {
    if (this.running > 0 && !this.failed) await new Promise<void>((r) => this.drained.push(r))
    if (this.failed) throw new Error(this.failed)
  }

  /** The workers' timings added up. */
  stats(): DetectStats {
    const sum: DetectStats = { detections: 0, pre: 0, inf: 0, dec: 0, coarse: 0, coarseRuns: 0, check: 0, verify: 0, nms: 0 }
    for (const s of this.last.values()) for (const k of Object.keys(sum) as (keyof DetectStats)[]) sum[k] += s[k]
    return sum
  }

  close() {
    this.all.forEach((w) => w.terminate())
  }

  private fail(message: string) {
    this.failed = message
    this.waiting.splice(0).forEach((r) => r())
    this.drained.splice(0).forEach((r) => r())
  }
}
