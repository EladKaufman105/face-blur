// Speech to text, on the device: Whisper large-v3-turbo (OpenAI, MIT; ONNX by onnx-community,
// with word timings) through Transformers.js. The model is downloaded once and kept by the
// browser; the audio never leaves the device.
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { pipeline, env } from '@huggingface/transformers'
import type { Word } from '../lib/subtitles'

export type SubsRequest = { file: File; language: string | null }
export type SubsMessage =
  | { type: 'download'; progress: number; loaded: number; total: number }
  | { type: 'transcribe'; progress: number }
  | { type: 'done'; words: Word[]; device: string }
  | { type: 'error'; code: string; message: string }

const MODEL = 'onnx-community/whisper-large-v3-turbo_timestamped'
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
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track || !(await track.canDecode())) return null
  const parts: Float32Array[] = []
  let total = 0
  let pending: number[] = []
  let ratio = 1
  let acc = 0, accN = 0, pos = 0
  for await (const sample of new AudioSampleSink(track).samples()) {
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

async function run({ file, language }: SubsRequest) {
  const x = await audio16k(file)
  if (!x || x.length < RATE / 2) return post({ type: 'error', code: 'no-audio', message: 'no sound' })

  env.allowLocalModels = false
  const gpu = (navigator as any).gpu ? await (navigator as any).gpu.requestAdapter().catch(() => null) : null
  const f16 = gpu?.features?.has?.('shader-f16') === true
  const device = gpu ? 'webgpu' : 'wasm'
  const dtype = f16 ? 'q4f16' : 'q4'
  const asr: any = await pipeline('automatic-speech-recognition', MODEL, {
    device,
    dtype: { encoder_model: dtype, decoder_model_merged: dtype },
    progress_callback: (p: any) => {
      if (p.status === 'progress_total') post({ type: 'download', progress: p.progress / 100, loaded: p.loaded, total: p.total })
    },
  })

  const words: Word[] = []
  post({ type: 'transcribe', progress: 0 })
  for (let from = 0; from < x.length;) {
    const to = cutPoint(x, from)
    const out = await asr(x.subarray(from, to), {
      return_timestamps: 'word',
      task: 'transcribe',
      ...(language ? { language } : {}),
    })
    const offset = from / RATE
    for (const c of out.chunks ?? []) {
      const [a, b] = c.timestamp as [number, number | null]
      if (a == null) continue
      words.push({ start: offset + a, end: offset + (b ?? a + 0.3), text: c.text })
    }
    from = to
    post({ type: 'transcribe', progress: Math.min(1, from / x.length) })
  }
  post({ type: 'done', words, device: `${device}/${dtype}` })
}
