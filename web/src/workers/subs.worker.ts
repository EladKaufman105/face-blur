// Speech to text, on the device: Whisper (OpenAI, MIT; ONNX with word timings by onnx-community)
// through Transformers.js. Which model, processor and file kinds is decided by the page (see
// lib/speech.ts: a phone gets a small one); the model is downloaded once and kept by the browser,
// and the audio never leaves the device. The page ends this worker when the job is over or
// cancelled, and it watches the messages below: every phase says so, so nothing looks frozen.
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { pipeline, env } from '@huggingface/transformers'
import type { Word } from '../lib/subtitles'

export type SubsRequest = { file: File; language: string | null; repo: string; device: 'webgpu' | 'wasm'; dtype: 'q4' | 'q4f16' | 'q8' }
export type SubsMessage =
  /** Still working (no news): resets the page's watchdog. */
  | { type: 'tick' }
  /** Reading the sound out of the video. */
  | { type: 'audio' }
  /** Loading the model into memory (its files are here): no percent exists for this phase. */
  | { type: 'load' }
  | { type: 'download'; progress: number; loaded: number; total: number }
  | { type: 'transcribe'; progress: number }
  | { type: 'done'; words: Word[]; device: string }
  | { type: 'error'; code: string; message: string }

const RATE = 16000
const post = (m: SubsMessage) => (self as unknown as Worker).postMessage(m)

self.onmessage = async (e: MessageEvent<SubsRequest>) => {
  try {
    await run(e.data)
  } catch (err) {
    post({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) })
  }
}

/** The sound, mono at 16 kHz (what Whisper takes). */
async function audio16k(file: File): Promise<Float32Array | null> {
  let seen = 0
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track || !(await track.canDecode())) return null
  const parts: Float32Array[] = []
  let total = 0
  let pending: number[] = []
  let ratio = 1
  let acc = 0, accN = 0, pos = 0
  for await (const sample of new AudioSampleSink(track).samples()) {
    if (++seen % 50 === 0) post({ type: 'tick' })
    ratio = sample.sampleRate / RATE
    const ch = sample.numberOfChannels, n = sample.numberOfFrames
    const planes: Float32Array[] = []
    for (let c = 0; c < ch; c++) {
      const p = new Float32Array(n)
      sample.copyTo(p, { planeIndex: c, format: 'f32-planar' })
      planes.push(p)
    }
    sample.close()
    // Mono, then the average over each output sample's span (a box filter against aliasing).
    for (let i = 0; i < n; i++) {
      let v = 0
      for (const p of planes) v += p[i]
      acc += v / ch
      accN++
      pos++
      if (pos >= ratio) {
        pending.push(acc / accN)
        acc = 0; accN = 0; pos -= ratio
      }
    }
    if (pending.length > RATE * 10) {
      parts.push(Float32Array.from(pending))
      total += pending.length
      pending = []
    }
  }
  parts.push(Float32Array.from(pending))
  total += pending.length
  const out = new Float32Array(total)
  let o = 0
  for (const p of parts) { out.set(p, o); o += p.length }
  return out
}

/** Where to cut the next window: the quietest 100 ms between 24 and 30 s after [from]. */
function cutPoint(x: Float32Array, from: number): number {
  const end = from + 30 * RATE
  if (end >= x.length) return x.length
  const w = RATE / 10
  let best = end, bestE = Infinity
  for (let s = from + 24 * RATE; s + w <= end; s += w / 2) {
    let e = 0
    for (let i = s; i < s + w; i++) e += x[i] * x[i]
    if (e < bestE) { bestE = e; best = s + w / 2 }
  }
  return Math.floor(best)
}

/** Quiet enough to skip: Whisper only makes up words for silence, and the device stays cooler. */
function silent(x: Float32Array, from: number, to: number): boolean {
  let e = 0
  for (let i = from; i < to; i++) e += x[i] * x[i]
  return e / Math.max(1, to - from) < 1e-8
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

async function run({ file, language, repo, device, dtype }: SubsRequest) {
  post({ type: 'audio' })
  const x = await audio16k(file)
  if (!x || x.length < RATE / 2) return post({ type: 'error', code: 'no-audio', message: 'no sound' })

  env.allowLocalModels = false
  // Phase: loading (or starting to download). The files' bytes come as 'download'; when they are all
  // here the sessions are created, which has no percent: 'load' says so.
  post({ type: 'load' })
  let loading = true
  let news = 0
  const asr: any = await pipeline('automatic-speech-recognition', repo, {
    device,
    dtype: { encoder_model: dtype, decoder_model_merged: dtype },
    progress_callback: (p: any) => {
      if (p.status === 'progress_total') {
        const n = ++news
        if (p.total > 0 && p.loaded >= p.total) {
          // All of what is known so far is here: if no other file starts soon, it is the loading.
          setTimeout(() => { if (n === news && !loading) { loading = true; post({ type: 'load' }) } }, 1500)
        }
        loading = false
        post({ type: 'download', progress: p.progress / 100, loaded: p.loaded, total: p.total })
      } else post({ type: 'tick' })
    },
  })

  try {
    const words: Word[] = []
    // Timestamp tokens say how far into the window the speech has got: the progress between windows.
    const gc = asr.model?.generation_config
    const tsBegin: number = (typeof gc?.no_timestamps_token_id === 'number' ? gc.no_timestamps_token_id : 50363) + 1
    post({ type: 'transcribe', progress: 0 })
    for (let from = 0; from < x.length;) {
      const to = cutPoint(x, from)
      const winSec = (to - from) / RATE
      const base = from / x.length, span = (to - from) / x.length
      if (!silent(x, from, to)) {
        let at = 0, sent = 0
        // One token at a time through here: a sign of life, and (from the timestamps) how far it is.
        const streamer = {
          put(v: bigint[][]) {
            const row = v?.[0]
            const tok = Number(row?.[row.length - 1])
            if (tok >= tsBegin) at = Math.min(winSec, (tok - tsBegin) * 0.02)
            const now = Date.now()
            if (now - sent > 200) {
              sent = now
              post({ type: 'transcribe', progress: Math.min(0.99, base + span * (at / winSec)) })
            }
          },
          end() {},
        }
        const out = await asr(x.subarray(from, to), {
          return_timestamps: 'word',
          task: 'transcribe',
          ...(language ? { language } : {}),
          streamer,
          // Always set: it keeps the library from re-running the window in a loop when the model
          // makes no progress (the cause of a hot phone stuck at 0%), and caps a runaway output.
          max_new_tokens: Math.min(440, Math.ceil(winSec * 9) + 24),
        })
        const offset = from / RATE
        for (const c of out.chunks ?? []) {
          const [a, b] = c.timestamp as [number, number | null]
          if (a == null) continue
          words.push({ start: offset + a, end: offset + (b ?? a + 0.3), text: c.text })
        }
      }
      from = to
      post({ type: 'transcribe', progress: Math.min(1, from / x.length) })
    }
    // Free the processor and the memory before the page ends this worker.
    await Promise.race([asr.dispose?.(), sleep(3000)]).catch(() => {})
    post({ type: 'done', words, device: `${device}/${dtype}` })
  } catch (e) {
    await Promise.race([asr.dispose?.(), sleep(1000)]).catch(() => {})
    throw e
  }
}
