// Who says each subtitle, on the device: the video's sound at 16 kHz, a voice embedding of each
// subtitle's stretch (3 s windows, averaged), then alike voices grouped (see lib/speakers).
import * as ort from 'onnxruntime-web/webgpu'
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { assignSpeakers, BINS, FBANK_RATE, fbank, normalizeEmbedding } from '../lib/speakers'

export type SpeakersRequest = { file: File; model: string; spans: [number, number][]; count: number | null }
export type SpeakersMessage =
  | { type: 'progress'; progress: number }
  | { type: 'done'; speakers: number[] }
  | { type: 'error'; code: string; message: string }

const post = (m: SpeakersMessage) => (self as unknown as Worker).postMessage(m)

/** Frames per model input (3 s); shorter speech than 0.6 s can't be told apart; windows per span. */
const T = 300
const MIN_FRAMES = 60
const MAX_WINDOWS = 6

self.onmessage = async (e: MessageEvent<SpeakersRequest>) => {
  try {
    await run(e.data)
  } catch (err) {
    post({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) })
  }
}

async function run({ file, model, spans, count }: SpeakersRequest) {
  const pcm = await decode16k(file, (p) => post({ type: 'progress', progress: p * 0.25 }))
  if (!pcm) return post({ type: 'error', code: 'no-audio', message: 'no sound' })
  ort.env.wasm.numThreads = 1
  ort.env.logLevel = 'error'
  const bytes = new Uint8Array(await (await fetch(model)).arrayBuffer())
  const session = await ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' })
  const embs: (Float32Array | null)[] = []
  for (let i = 0; i < spans.length; i++) {
    embs.push(await embed(session, pcm, spans[i]))
    post({ type: 'progress', progress: 0.25 + (0.75 * (i + 1)) / Math.max(1, spans.length) })
  }
  post({ type: 'done', speakers: assignSpeakers(embs, count) })
}

/** The voice of a span: normalized embeddings of 3 s windows over it, summed. Null if too short. */
async function embed(session: ort.InferenceSession, pcm: Float32Array, [s, e]: [number, number]): Promise<Float32Array | null> {
  const a = Math.min(pcm.length, Math.max(0, Math.round(s * FBANK_RATE)))
  const b = Math.min(pcm.length, Math.max(a, Math.round(e * FBANK_RATE)))
  const f = fbank(pcm, a, b)
  const n = f.length / BINS
  if (n < MIN_FRAMES) return null
  // Each bin's mean over the span taken out (as the model was trained).
  const mean = new Float32Array(BINS)
  for (let t = 0; t < n; t++) for (let k = 0; k < BINS; k++) mean[k] += f[t * BINS + k]
  for (let k = 0; k < BINS; k++) mean[k] /= n
  for (let t = 0; t < n; t++) for (let k = 0; k < BINS; k++) f[t * BINS + k] -= mean[k]
  // Short speech repeated to fill a window.
  const frames = n >= T ? f : Float32Array.from({ length: T * BINS }, (_, i) => f[i % (n * BINS)])
  const total = Math.max(n, T)
  const windows = Math.min(MAX_WINDOWS, 1 + Math.floor((total - T + 149) / 150))
  let acc: Float32Array | null = null
  for (let w = 0; w < windows; w++) {
    const start = windows === 1 ? 0 : Math.floor(((total - T) * w) / (windows - 1))
    const input = new ort.Tensor('float32', frames.slice(start * BINS, (start + T) * BINS), [1, T, BINS])
    const out = await session.run({ [session.inputNames[0]]: input })
    const v = normalizeEmbedding((await out[session.outputNames[0]].getData()) as Float32Array)
    if (!acc) acc = new Float32Array(v.length)
    for (let i = 0; i < v.length; i++) acc[i] += v[i]
  }
  return acc
}

/** The sound, mono at 16 kHz (low-passed before it's thinned out). */
async function decode16k(file: File, onProgress: (p: number) => void): Promise<Float32Array | null> {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track || !(await track.canDecode())) return null
  const duration = await track.computeDuration()
  const parts: Float32Array[] = []
  let rate = 48000, total = 0
  for await (const s of new AudioSampleSink(track).samples()) {
    rate = s.sampleRate
    const n = s.numberOfFrames, ch = s.numberOfChannels
    const mono = new Float32Array(n), p = new Float32Array(n)
    for (let c = 0; c < ch; c++) {
      s.copyTo(p, { planeIndex: c, format: 'f32-planar' })
      for (let i = 0; i < n; i++) mono[i] += p[i] / ch
    }
    const at = Math.round(s.timestamp * rate)
    if (at > total) { parts.push(new Float32Array(at - total)); total = at }
    parts.push(mono)
    total += n
    onProgress(Math.min(1, s.timestamp / Math.max(0.01, duration)))
    s.close()
  }
  const x = new Float32Array(total)
  let o = 0
  for (const p of parts) { x.set(p, o); o += p.length }
  if (rate === FBANK_RATE) return x
  // A moving average over the decimation step (a simple low-pass), then linear interpolation.
  const step = rate / FBANK_RATE
  const r = Math.max(1, Math.round(step))
  const smooth = new Float32Array(x.length)
  let acc = 0
  for (let i = 0; i < x.length; i++) {
    acc += x[i]
    if (i >= r) acc -= x[i - r]
    smooth[i] = acc / Math.min(r, i + 1)
  }
  const n = Math.floor(x.length / step)
  const y = new Float32Array(n)
  for (let i = 0; i < n; i++) {
    const t = i * step + (r - 1) / 2
    const k = Math.min(x.length - 2, Math.floor(t)), f = t - k
    y[i] = smooth[k] * (1 - f) + smooth[k + 1] * f
  }
  return y
}
