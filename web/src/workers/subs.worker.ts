// Speech to text, on the device: Whisper (OpenAI, MIT; ONNX with word timings by onnx-community)
// through Transformers.js. Which model, processor and file kinds is decided by the page (see
// lib/speech.ts: a phone gets a small one); the model is downloaded once and kept by the browser,
// and the audio never leaves the device. The page ends this worker when the job is over or
// cancelled, and it watches the messages below: every phase says so, so nothing looks frozen.
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { pipeline, env } from '@huggingface/transformers'
import type { Word } from '../lib/subtitles'
import { isMemoryError } from '../lib/memerr'
import type { Dtype } from '../lib/speech'
// The runtime's own files, served by this site (not a CDN): the threads' workers start from the
// same file, the page's isolation (COEP) has nothing to allow, and it works offline once cached.
import ortWasmUrl from '../../node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.asyncify.wasm?url'
import ortMjsUrl from '../../node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.asyncify.mjs?url'

export type SubsRequest = {
  file: File; language: string | null; repo: string; revision: string | null; device: 'webgpu' | 'wasm'
  /** The file kinds of the encoder and the decoder. */
  encDtype: Dtype; dtype: Dtype
  /** WebAssembly threads (the page asks for more than one only when it is cross-origin isolated). */
  threads: number
}
export type SubsMessage =
  /** Still working (no news): resets the page's watchdog for [from] (the sound's reading, or the model; default the model). */
  | { type: 'tick'; from?: 'audio' | 'model' }
  /** Reading the sound out of the video (it runs beside the model's download and load): how far (0..1; 1 = done); `only`: nothing else is going on, the model is ready and this is what is waited for. */
  | { type: 'audio'; progress: number; only?: boolean }
  /** Loading the model into memory (its files are here): no percent exists for this phase. */
  | { type: 'load' }
  | { type: 'download'; progress: number; loaded: number; total: number }
  /** [win]: the 30-second window being recognized (1-based) and how many there are about. */
  | { type: 'transcribe'; progress: number; win?: { i: number; n: number } }
  | { type: 'done'; words: Word[]; device: string; /** Milliseconds: loading the model (or finding it kept), and recognizing. */ ms: { load: number; recognize: number; /** Where recognition went: up to the first token (the audio's features and the encoder), the other tokens, what follows (the word timings), and the token count. */ first: number; decode: number; after: number; tokens: number } }
  | { type: 'error'; code: string; message: string }

const RATE = 16000
/** Whisper's decoder holds 448 tokens; the prompt (start, language, task) takes 3 of them. */
const MAX_NEW = 440
const post = (m: SubsMessage) => (self as unknown as Worker).postMessage(m)

self.onmessage = async (e: MessageEvent<SubsRequest>) => {
  try {
    await run(e.data)
  } catch (err) {
    post({ type: 'error', code: isMemoryError(err) ? 'memory' : 'failed', message: String((err as Error)?.message ?? err) })
  }
}

/** The longest sound read (three hours of 16 kHz mono: about 690 MB). */
const MAX_SAMPLES = RATE * 3 * 3600

/** The sound is longer than [MAX_SAMPLES]. */
class TooLong extends Error {}

/** The sound, mono at 16 kHz (what Whisper takes). Written straight into one buffer sized from the file's length (no list of parts to join at the end: half the peak memory). */
async function audio16k(file: File, onProgress: (p: number) => void): Promise<Float32Array | null> {
  let seen = 0
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track || !(await track.canDecode())) return null
  // The length from the file's header (fast); without one the bar stays empty until the end.
  const dur = (await track.getDurationFromMetadata().catch(() => null)) ?? 0
  let lastP = 0
  // Sized from the header (a minute when it has none), grows by half when full, and stops at three hours.
  let buf = new Float32Array(Math.min(dur > 0 ? Math.ceil(dur * RATE) + RATE : RATE * 60, MAX_SAMPLES))
  let n = 0
  let ratio = 1
  let acc = 0, accN = 0, pos = 0
  for await (const sample of new AudioSampleSink(track).samples()) {
    if (++seen % 50 === 0) post({ type: 'tick', from: 'audio' })
    if (dur > 0) {
      const now = Date.now()
      if (now - lastP > 150) { lastP = now; onProgress(Math.max(0, Math.min(0.99, sample.timestamp / dur))) }
    }
    ratio = sample.sampleRate / RATE
    const ch = sample.numberOfChannels, len = sample.numberOfFrames
    const planes: Float32Array[] = []
    for (let c = 0; c < ch; c++) {
      const p = new Float32Array(len)
      sample.copyTo(p, { planeIndex: c, format: 'f32-planar' })
      planes.push(p)
    }
    sample.close()
    // Mono, then the average over each output sample's span (a box filter against aliasing).
    for (let i = 0; i < len; i++) {
      let v = 0
      for (const p of planes) v += p[i]
      acc += v / ch
      accN++
      pos++
      if (pos >= ratio) {
        if (n === buf.length) {
          if (n >= MAX_SAMPLES) throw new TooLong()
          const bigger = new Float32Array(Math.min(Math.ceil(buf.length * 1.5), MAX_SAMPLES)); bigger.set(buf); buf = bigger
        }
        buf[n++] = acc / accN
        acc = 0; accN = 0; pos -= ratio
      }
    }
  }
  return buf.subarray(0, n)
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

/** The model kept from the last job (the page keeps this worker for a while): the next job with the same model skips loading it. */
let loaded: { key: string; asr: any } | null = null

async function run({ file, language, repo, revision, device, encDtype, dtype, threads }: SubsRequest) {
  // The sound is read at once and beside the model's download and load (before: first, alone, with
  // the model not even asked for yet, so a long video sat at 0% for as long as its sound took).
  let failed = false
  let audioDone = false
  let audioP = 0
  post({ type: 'audio', progress: 0 })
  // A failure of the sound is said at once (not when the model has loaded): the page ends this worker on it.
  const audioJob = audio16k(file, (progress) => { audioP = progress; if (!failed) post({ type: 'audio', progress }) }).then((x) => {
    audioDone = true
    if (!failed) post({ type: 'audio', progress: 1 })
    if ((!x || x.length < RATE / 2) && !failed) { failed = true; post({ type: 'error', code: 'no-audio', message: 'no sound' }) }
    return x
  }, (err) => {
    audioDone = true
    if (!failed) { failed = true; post({ type: 'error', code: err instanceof TooLong ? 'too-long' : isMemoryError(err) ? 'memory' : 'audio', message: String((err as Error)?.message ?? err) }) }
    return null
  })

  env.allowLocalModels = false
  const wasm: any = (env.backends as any).onnx?.wasm
  if (wasm) {
    wasm.wasmPaths = { mjs: new URL(ortMjsUrl, self.location.href).href, wasm: new URL(ortWasmUrl, self.location.href).href }
    // Threads are for the CPU's path, and only in an isolated page (else the runtime would say it can't).
    wasm.numThreads = device === 'wasm' && self.crossOriginIsolated ? Math.max(1, threads) : 1
  }
  // Every request of the model (its metadata too) goes to the pinned commit: Transformers.js reads the
  // revision into the URL of some requests only, and its metadata requests ignore it, so the commit goes
  // into the path template itself (a fully cached model then needs no request to the Hub at all).
  env.remotePathTemplate = revision ? `{model}/resolve/${revision}/` : '{model}/resolve/{revision}/'
  const key = `${repo}|${revision ?? ''}|${device}|${encDtype}|${dtype}|${wasm?.numThreads}`
  // Phase: loading (or starting to download). The files' bytes come as 'download'; when they are all
  // here the sessions are created, which has no percent: 'load' says so.
  post({ type: 'load' })
  if (wasm && loaded?.key !== key) {
    // A phone that can't start the threads says so early (the page then tries again with one).
    // Transformers.js loads the runtime's wasm (from its cache) before the runtime starts this timer,
    // so the timeout measures only the threads' start, not a download. One thread: no timeout.
    wasm.initTimeout = wasm.numThreads > 1 ? 20000 : 0
  }
  const t0 = performance.now()
  let loading = true
  let news = 0
  let lastPost = 0
  let loadTimer = 0
  let asr: any = loaded?.key === key ? loaded.asr : null
  if (!asr && loaded) { const old = loaded.asr; loaded = null; await Promise.race([old.dispose?.(), sleep(1000)]).catch(() => {}) }
  if (!asr) try { asr = await pipeline('automatic-speech-recognition', repo, {
    device,
    // `model` too: Transformers.js 4.3.2's file listing for the download total (get_model_files -> selectDtype)
    // looks the encoder's dtype up by its session key `model`, not `encoder_model` (a bug of the library), and
    // without it falls back to the device's default (wasm: q8, encoder_model_quantized.onnx). That file is
    // never loaded and may not exist (ivrit.ai's repo has only q4): one uncacheable request to the Hub per load.
    // The sessions themselves read `encoder_model` and ignore `model`.
    dtype: { model: encDtype, encoder_model: encDtype, decoder_model_merged: dtype },
    progress_callback: (p: any) => {
      const now = Date.now()
      if (p.status === 'progress_total') {
        const n = ++news
        if (p.total > 0 && p.loaded >= p.total) {
          // All of what is known so far is here: if no other file starts soon, it is the loading.
          // (The page itself moves to its loading phase at once, from loaded >= total.)
          clearTimeout(loadTimer)
          loadTimer = setTimeout(() => { if (n === news && !loading) { loading = true; post({ type: 'load' }) } }, 1500) as unknown as number
        }
        loading = false
        // One message per update: it is also the sign of life, so no extra tick goes with it.
        lastPost = now
        post({ type: 'download', progress: p.progress / 100, loaded: p.loaded, total: p.total })
      } else if (now - lastPost > 500) {
        lastPost = now
        post({ type: 'tick' })
      }
    },
  }) } catch (e) {
    clearTimeout(loadTimer)
    // Threads that would not start (not a download that failed): the page tries once more with one.
    const message = String((e as Error)?.message ?? e)
    if (isMemoryError(e)) return post({ type: 'error', code: 'memory', message })
    if ((wasm?.numThreads ?? 1) > 1 && !/could not locate file|failed to fetch|network|load failed|https?:\/\/|\b(40\d|50\d)\b/i.test(message)) {
      return post({ type: 'error', code: 'threads', message })
    }
    throw e
  }
  // Loaded: a "loading" still pending from the last download message must not come after the recognition starts.
  clearTimeout(loadTimer)
  loading = true
  // The sound may still be being read: the wait is its own phase, with its own bar. Only its own progress
  // keeps the page's watchdog quiet for it (a hung decode is not hidden by anything else).
  if (!audioDone) post({ type: 'audio', progress: audioP, only: true })
  const x = await audioJob
  if (!x || x.length < RATE / 2) return

  try {
    const words: Word[] = []
    const t1 = performance.now()
    const stat = { first: 0, decode: 0, after: 0, tokens: 0 }
    // Timestamp tokens say how far into the window the speech has got: the progress between windows.
    const gc = asr.model?.generation_config
    const tsBegin: number = (typeof gc?.no_timestamps_token_id === 'number' ? gc.no_timestamps_token_id : 50363) + 1
    const windows = Math.max(1, Math.ceil(x.length / (30 * RATE)))
    let win = { i: 1, n: windows }
    post({ type: 'transcribe', progress: 0, win })
    // The bar only moves forward (a restarted window would take it back a little).
    let shown = 0
    const progress = (v: number) => { shown = Math.max(shown, v); post({ type: 'transcribe', progress: shown, win }) }
    for (let from = 0; from < x.length;) {
      const to = cutPoint(x, from)
      win = { i: Math.min(windows, Math.floor(from / (30 * RATE)) + 1), n: windows }
      progress(shown)
      const winSec = (to - from) / RATE
      const base = from / x.length, span = (to - from) / x.length
      if (!silent(x, from, to)) {
        let at = 0, sent = 0, tokens = 0
        let tFirst = 0, tLast = 0
        const tStart = performance.now()
        // One token at a time through here: a sign of life, and (from the timestamps) how far it is.
        const streamer = {
          put(v: bigint[][]) {
            tokens++
            stat.tokens++
            if (tokens === 1) tFirst = performance.now()
            tLast = performance.now()
            const row = v?.[0]
            const tok = Number(row?.[row.length - 1])
            if (tok >= tsBegin) at = Math.min(winSec, (tok - tsBegin) * 0.02)
            const now = Date.now()
            if (now - sent > 200) {
              sent = now
              progress(Math.min(0.99, base + span * (at / winSec)))
            }
          },
          end() {},
        }
        // The encoder is one long call with no messages: say so first, so the page's watchdog counts from here.
        post({ type: 'tick' })
        const out = await asr(x.subarray(from, to), {
          return_timestamps: 'word',
          task: 'transcribe',
          ...(language ? { language } : {}),
          streamer,
          // Always set: it keeps the library from re-running the window in a loop when the model
          // makes no progress (the cause of a hot phone stuck at 0%), and caps a runaway output.
          // The whole budget (a 3-token prompt, at most 448): Hebrew, Arabic and Persian need many
          // tokens a second in fast speech, and a smaller cap cuts the end of the window off.
          max_new_tokens: MAX_NEW,
        })
        const tEnd = performance.now()
        if (tFirst) { stat.first += tFirst - tStart; stat.decode += tLast - tFirst; stat.after += tEnd - tLast } else stat.first += tEnd - tStart
        const offset = from / RATE
        const chunks = (out.chunks ?? []).filter((c: { timestamp: [number | null, number | null] }) => c.timestamp[0] != null)
        // The cap was hit: the output stopped mid-word, so its last word is cut off (and has no end).
        const capped = tokens >= MAX_NEW - 2 && chunks.length > 1
        const cutStart = capped ? (chunks[chunks.length - 1].timestamp[0] as number) : 0
        // Restart there only when real speech filled the budget (fast speech fills it in no less than
        // about 15 s; a loop on music or noise fills it early). Each restart moves on by a third of a
        // window at least, so the work is at most about 3 times, and no speech is skipped.
        const restart = capped && cutStart >= Math.min(10, winSec / 3) && cutStart < winSec - 1
        for (const c of restart ? chunks.slice(0, -1) : chunks) {
          const [a, b] = c.timestamp as [number, number | null]
          const e = b ?? (a as number) + 0.3
          words.push({ start: offset + (a as number), end: offset + e, text: c.text })
        }
        if (restart) {
          // The next window starts at the cut-off word (a little before: these times can be late),
          // which is recognized again in full.
          const next = from + Math.floor(Math.max(cutStart - 0.15, 0.25) * RATE)
          if (next > from && next < to) {
            from = next
            progress(Math.min(1, from / x.length))
            continue
          }
        }
      }
      from = to
      progress(Math.min(1, from / x.length))
    }
    // The page keeps this worker (and so the model) for a few minutes, or ends it: either way it frees the memory.
    loaded = { key, asr }
    post({ type: 'done', words, device: `${device}/${encDtype}+${dtype}/${wasm?.numThreads ?? 1}t`, ms: { load: Math.round(t1 - t0), recognize: Math.round(performance.now() - t1), first: Math.round(stat.first), decode: Math.round(stat.decode), after: Math.round(stat.after), tokens: stat.tokens } })
  } catch (e) {
    loaded = null
    await Promise.race([asr.dispose?.(), sleep(1000)]).catch(() => {})
    throw e
  }
}
