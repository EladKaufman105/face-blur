// YuNet in a worker of its own (workers/yunet.worker.ts), so that it runs at the same time as
// RetinaFace: on the GPU, and on another core. In the detector's own thread it can't: one ONNX
// Runtime (WebAssembly) runs one model at a time, and a run waiting for the GPU can't be
// interleaved with another.
import type { Det } from './types'

export type YunetRequest =
  | { type: 'init'; model: Uint8Array }
  | { type: 'frame'; id: number; frame: ImageBitmap; threshold: number }
export type YunetReply =
  | { type: 'ready' }
  | { type: 'dets'; id: number; dets: Det[] }
  | { type: 'error'; id?: number; message: string }

/** YuNet in a worker of its own. Any failure ends it: its pending and later runs give null (the caller then runs YuNet itself). */
export class YunetWorker {
  private next = 0
  private waiting = new Map<number, (d: Det[] | null) => void>()
  dead = false

  private constructor(private w: Worker) {
    w.onmessage = (e: MessageEvent<YunetReply>) => {
      const m = e.data
      if (m.type === 'dets') this.settle(m.id, m.dets)
      else if (m.type === 'error') this.fail(m.message)
    }
    w.onerror = (e) => { e.preventDefault(); this.fail(e.message) }
    w.onmessageerror = () => this.fail('message error')
  }

  /** Null if it can't start (the worker or the model won't load). */
  static async create(model: Uint8Array): Promise<YunetWorker | null> {
    let w: Worker | null = null
    try {
      w = new Worker(new URL('../workers/yunet.worker.ts', import.meta.url), { type: 'module' })
      const worker = w
      await new Promise<void>((ok, no) => {
        worker.onmessage = (e: MessageEvent<YunetReply>) => (e.data.type === 'ready' ? ok() : no(new Error(e.data.type === 'error' ? e.data.message : 'unexpected')))
        worker.onerror = (e) => { e.preventDefault(); no(new Error(e.message)) }
        // A copy: the caller keeps its bytes (its own YuNet, the fallback).
        worker.postMessage({ type: 'init', model: model.slice() } satisfies YunetRequest)
        setTimeout(() => no(new Error('no answer')), 30000)
      })
      return new YunetWorker(worker)
    } catch (e) {
      console.warn('ONIT: no YuNet worker', e)
      w?.terminate()
      return null
    }
  }

  /** YuNet's faces on [frame] (taken: it is closed), or null if the worker failed. */
  run(frame: ImageBitmap, threshold: number): Promise<Det[] | null> {
    if (this.dead) {
      frame.close()
      return Promise.resolve(null)
    }
    const id = this.next++
    return new Promise((done) => {
      // A worker that doesn't answer (hung) counts as failed: detection goes on with YuNet in the caller's thread.
      const timer = setTimeout(() => this.fail(`no answer in ${YunetWorker.TIMEOUT_MS} ms`), YunetWorker.TIMEOUT_MS)
      this.waiting.set(id, (d) => { clearTimeout(timer); done(d) })
      try {
        this.w.postMessage({ type: 'frame', id, frame, threshold } satisfies YunetRequest, [frame])
      } catch {
        // Not sent (a closed bitmap, a worker gone): this run gives null, the caller runs YuNet itself.
        frame.close()
        this.settle(id, null)
      }
    })
  }

  /** A run takes ~20-150 ms; one not answered in this long means a hung worker. */
  static readonly TIMEOUT_MS = 10000

  private settle(id: number, dets: Det[] | null) {
    const done = this.waiting.get(id)
    this.waiting.delete(id)
    done?.(dets)
  }

  private fail(why: string) {
    if (!this.dead) console.warn('ONIT: YuNet worker failed:', why)
    this.dead = true
    this.w.terminate()
    for (const id of [...this.waiting.keys()]) this.settle(id, null)
  }

  close() {
    this.dead = true
    this.w.terminate()
    for (const id of [...this.waiting.keys()]) this.settle(id, null)
  }
}
