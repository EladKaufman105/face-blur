// Speech recognition on the device: which Whisper this device should use (a phone gets a small
// one), what its download costs, and one job runner that never leaves the person looking at a
// frozen bar: every phase reports, a watchdog ends a stuck one, the worker is ended as soon as
// the job is over or cancelled, and a graphics processor that fails or hangs falls back to the CPU.
import { signal } from '@preact/signals'
import { storageKey } from './channel'
import { lang, t } from '../i18n'
import type { SubsMessage, SubsRequest } from '../workers/subs.worker'
import type { Word } from './subtitles'
import { isMemoryError } from './memerr'

/** What the person picks: "fast" (small, light on a phone) or "better" (bigger, slower, more exact). */
/** "hebrew": the Hebrew model asked for by name (the only way a phone gets it: it is big). */
export type Quality = 'fast' | 'better' | 'hebrew'
export type Dtype = 'fp32' | 'fp16' | 'q4' | 'q4f16' | 'q8'
export type ModelKey = 'base' | 'small' | 'large' | 'ivrit'

/** Hugging Face repos by onnx-community: Whisper with word timings (cross-attention alignment heads in their generation config). */
const MODELS: Record<ModelKey, { repo: string; label: string; revision?: string }> = {
  base: { repo: 'onnx-community/whisper-base_timestamped', label: 'Whisper base' },
  small: { repo: 'onnx-community/whisper-small_timestamped', label: 'Whisper small' },
  large: { repo: 'onnx-community/whisper-large-v3-turbo_timestamped', label: 'Whisper large-v3-turbo' },
  // ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo (Apache-2.0): the model the Android app runs
  // for Hebrew (as ggml q5_0). This is a community ONNX export of the same checkpoint, exported with the
  // attention outputs that word timings need (the official ivrit.ai ONNX export has none). Pinned to one
  // commit, so a change of that repo can't change what the site runs.
  ivrit: { repo: 'instush/ivrit-whisper-large-v3-turbo-timestamped-onnx', label: 'ivrit.ai large-v3-turbo', revision: 'c71eadaca74c0923a06632bdaeb7413ff9cff4cf' },
}

/** File sizes in bytes (as listed on the Hub) of the encoder and the decoder, by dtype. The tokenizer and config files add a few MB. */
const ENC_BYTES: Record<ModelKey, Partial<Record<Dtype, number>>> = {
  base: { q4: 18771046, q8: 23159167, fp16: 41270731, fp32: 82451730 },
  small: { q4: 66178491, q8: 92240498, fp16: 176483570 },
  large: { q4f16: 370035242, q4: 425003939 },
  ivrit: { q4: 425000087 },
}
const DEC_BYTES: Record<ModelKey, Partial<Record<Dtype, number>>> = {
  base: { q4: 123738327, q8: 53712708, q4f16: 68541844 },
  small: { q4: 233421212, q8: 156795750, q4f16: 145778021 },
  large: { q4f16: 193566135, q4: 334207912 },
  ivrit: { q4: 334048758 },
}
const EXTRA_BYTES = 4e6
const SUFFIX: Record<Dtype, string> = { fp32: '', fp16: '_fp16', q4: '_q4', q4f16: '_q4f16', q8: '_quantized' }

export interface SpeechPlan {
  quality: Quality
  model: ModelKey
  repo: string
  /** The commit of the repo the files are read at (null: its latest). */
  revision: string | null
  device: 'webgpu' | 'wasm'
  /** The file kinds: the encoder's, and the decoder's. */
  encDtype: Dtype
  dtype: Dtype
  /** WebAssembly threads (1 when the page is not cross-origin isolated, or on the graphics processor). */
  threads: number
  /** The two model files this plan downloads (relative to the repo). */
  files: [string, string]
  bytes: number
}

export interface Device {
  /** A phone, or a device with little memory: gets the small models, on the CPU. */
  weak: boolean
  /** The graphics processor, when the browser has one that can hold the big model (null: none, or not asked yet). */
  gpu: { f16: boolean; big: boolean } | null
  probed: boolean
}

// ------------------------------------------------------------------ what the device can do

function isMobile(): boolean {
  const nav = navigator as any
  if (typeof nav.userAgentData?.mobile === 'boolean') return nav.userAgentData.mobile
  const ua = navigator.userAgent || ''
  return /Android|iPhone|iPad|iPod|Mobile/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)
}

function guess(): Device {
  const nav = navigator as any
  const mem: number | undefined = nav.deviceMemory
  const cores = navigator.hardwareConcurrency || 4
  // A tablet that says it isn't mobile (Android's tablets do) is a touch device with a small memory all the same:
  // a touch-first pointer and no (or a small) memory reading counts as weak.
  let touchFirst = false
  try { touchFirst = matchMedia('(pointer: coarse)').matches && !matchMedia('(any-pointer: fine)').matches } catch {}
  return { weak: isMobile() || (mem !== undefined && mem < 8) || cores < 4 || (touchFirst && mem === undefined), gpu: null, probed: false }
}

/** What this device is (the graphics processor is added once it was asked, see [probeDevice]). */
export const device = signal<Device>(guess())

let probing: Promise<Device> | null = null
/** Asks the browser's graphics processor what it can hold (once, with a time limit: some phones never answer). */
export function probeDevice(): Promise<Device> {
  probing ??= (async () => {
    let gpu: Device['gpu'] = null
    try {
      const adapter: any = await Promise.race([
        (navigator as any).gpu?.requestAdapter?.() ?? null,
        new Promise((r) => setTimeout(() => r(null), 4000)),
      ])
      if (adapter) {
        const l = adapter.limits ?? {}
        // The big model's largest buffers (its embedding table, the encoder's weights) need these.
        const big = (l.maxBufferSize ?? 0) >= 2 ** 30 && (l.maxStorageBufferBindingSize ?? 0) >= 2 ** 29
        gpu = { f16: adapter.features?.has?.('shader-f16') === true, big }
      }
    } catch {}
    device.value = { ...device.value, gpu, probed: true }
    return device.value
  })()
  return probing
}

// ------------------------------------------------------------------ the choice

const KEY = 'subs.quality'
function readChoice(): Quality | null {
  try {
    const v = localStorage.getItem(storageKey(KEY))
    return v === 'fast' || v === 'better' || v === 'hebrew' ? v : null
  } catch { return null }
}
/** What the person picked ("faster" / "better quality"); null: the device's default. Kept per channel (release and beta apart). */
export const qualityChoice = signal<Quality | null>(readChoice())
export function setQuality(q: Quality) {
  qualityChoice.value = q
  try { localStorage.setItem(storageKey(KEY), q) } catch {}
}

/** Test flags (the address): subsmodel=base|small|large forces the model, subswatch=N the watchdog's seconds, backend=wasm|webgpu the processor. */
const param = (name: string) => new URLSearchParams(location.search).get(name)

export function defaultQuality(d: Device, language: string | null = null): Quality {
  // The Hebrew model is the default only where it runs well: a page with threads (cross-origin isolated) or a big graphics processor.
  if (!d.weak && hebrew(language ?? spokenLanguage(null)) && (self.crossOriginIsolated || d.gpu?.big)) return 'better'
  return !d.weak && d.gpu?.big ? 'better' : 'fast'
}

const forcedModel = (): ModelKey | null => {
  const f = param('subsmodel')
  return f === 'base' || f === 'small' || f === 'large' || f === 'ivrit' ? f : null
}

/**
 * Hebrew has its own model, as in the app (ivrit.ai): "better quality" in Hebrew is that model, on
 * any device that is not a phone it is the default too. "Faster" stays the generic small one, and a
 * phone starts on it (the ivrit.ai model is a 760 MB download and a lot of memory for a phone's page).
 */
export const hebrew = (language: string | null) => language === 'hebrew' || language === 'he'

/**
 * The choice as it applies to [language] on [d]: "Hebrew model" ('hebrew') is a choice of a weak device for
 * Hebrew only. It is kept (localStorage) when the language changes, so for any other language it is "better
 * quality" (the multilingual model, by the rules of "better"), and on a computer, where "better" in Hebrew is
 * that model already, it is "better" too (the chip that shows as chosen).
 */
export function effectiveQuality(q: Quality, d: Device, language: string | null): Quality {
  return q === 'hebrew' && (!hebrew(language) || !d.weak) ? 'better' : q
}

function modelFor(q: Quality, d: Device, language: string | null): ModelKey {
  const forced = forcedModel()
  if (forced) return forced
  // Only Hebrew ever gets the Hebrew model. "Better quality" in Hebrew is that model, but not on a phone or
  // another weak device (it needs a lot of memory): there it is only the choice named "Hebrew model"
  // ('hebrew'), so a stored "better" never becomes it.
  if (hebrew(language) && (q === 'hebrew' || (q === 'better' && !d.weak))) return 'ivrit'
  if (d.weak) return q === 'fast' ? 'base' : 'small'
  return q === 'fast' ? 'small' : 'large'
}

/** Set when the graphics processor failed or hung in this visit: the next job goes straight to the CPU. */
export const gpuFailed = signal(false)

/** The plan for [quality] on [d]: the model, the processor, the file kinds and what they weigh. */
export function planFor(choice: Quality, d: Device, forceCpu = gpuFailed.value, language: string | null = spokenLanguage(null)): SpeechPlan {
  const quality = effectiveQuality(choice, d, language)
  let model = modelFor(quality, d, language)
  const wanted = param('backend')
  // A phone runs on the CPU unless the address asks for its graphics processor (?backend=webgpu): that
  // path is for trying it (its results can't be checked here), the CPU's is the one known to be right.
  let dev: 'webgpu' | 'wasm' = d.weak || !d.gpu || forceCpu ? 'wasm' : 'webgpu'
  if (!forceCpu && (wanted === 'wasm' || (wanted === 'webgpu' && d.gpu))) dev = wanted
  if (dev === 'webgpu' && (model === 'large' || model === 'ivrit') && !d.gpu?.big) dev = 'wasm'
  // The big model on the CPU takes minutes for each 30 seconds: "better" is the small one there.
  // (Not the Hebrew one: it is the app's model for Hebrew, slow on a CPU as well, and has no small kin.)
  if (dev === 'wasm' && model === 'large' && !forcedModel()) model = 'small'
  const f16 = dev === 'webgpu' && d.gpu?.f16 === true
  let encDtype: Dtype, dtype: Dtype
  // The Hebrew model is only there as 4-bit (on the CPU and the graphics processor alike).
  if (model === 'ivrit') encDtype = dtype = 'q4'
  else if (model === 'large') encDtype = dtype = f16 ? 'q4f16' : 'q4'
  else if (dev === 'wasm') encDtype = dtype = 'q8'
  else if (d.weak) {
    // A phone's graphics processor (only by asking for it, see below): the encoder in the half or full
    // precision the big ones use, a 4-bit decoder.
    encDtype = f16 ? 'fp16' : 'fp32'
    dtype = f16 ? 'q4f16' : 'q4'
  } else encDtype = dtype = 'q4'
  // What isn't on the Hub for this model (the small one's full-precision encoder is 350 MB) goes back to the CPU.
  if (ENC_BYTES[model][encDtype] === undefined || DEC_BYTES[model][dtype] === undefined) {
    dev = 'wasm'
    encDtype = dtype = 'q8'
  }
  const enc = ENC_BYTES[model][encDtype]!, dec = DEC_BYTES[model][dtype]!
  return {
    quality, model, repo: MODELS[model].repo, revision: MODELS[model].revision ?? null, device: dev, encDtype, dtype, threads: dev === 'wasm' ? wasmThreads(d) : 1,
    files: [`onnx/encoder_model${SUFFIX[encDtype]}.onnx`, `onnx/decoder_model_merged${SUFFIX[dtype]}.onnx`], bytes: enc + dec + EXTRA_BYTES,
  }
}

/**
 * How many threads WebAssembly gets: only a cross-origin isolated page has them (see lib/isolation.ts).
 * Half the logical cores, at most 4: measured on CI (4 vCPUs, whisper-base, 55 s of speech), 2 threads
 * took 9.9 s against 13.3 s on one, and 3 or 4 threads were slower (11-12 s): the encoder keeps
 * gaining but the decoder's many small steps lose to the cost of waking threads. ?threads=N sets it (tests).
 */
export function wasmThreads(_d: Device = device.value): number {
  const forced = Number(param('threads'))
  if (forced >= 1) return Math.min(16, Math.floor(forced))
  if (typeof crossOriginIsolated === 'undefined' || !crossOriginIsolated) return 1
  if (threadsFailed) return 1
  const n = Math.max(1, Math.min(4, Math.ceil((navigator.hardwareConcurrency || 2) / 2)))
  return _d.weak ? Math.min(2, n) : n
}

/** The threads didn't start on this device (this session): later jobs go straight to one thread. */
let threadsFailed = false

/** What a download agreement is for: the model and its file kinds. */
const planKey = (p: SpeechPlan) => `${p.repo}|${p.revision ?? ''}|${p.encDtype}|${p.dtype}`

/** The plan the person's choice (or the device's default) gives. */
export function currentPlan(d: Device = device.value, language: string | null = null): SpeechPlan {
  const l = spokenLanguage(language)
  return planFor(qualityChoice.value ?? defaultQuality(d, l), d, gpuFailed.value, l)
}

export const mbOf = (plan: SpeechPlan) => Math.max(1, Math.round(plan.bytes / 1e6))

/** Whether the plan's two big files are already in the browser (so there is no download). */
export async function planCached(plan: SpeechPlan): Promise<boolean> {
  try {
    if (!('caches' in window) || !(await caches.has('transformers-cache'))) return false
    const urls = (await (await caches.open('transformers-cache')).keys()).map((r) => r.url)
    return plan.files.every((f) => urls.some((u) => u.includes(`${plan.repo}/resolve/${plan.revision ? plan.revision + '/' : ''}`) && u.includes(f)))
  } catch {
    return false
  }
}

/**
 * A pinned model whose pin changed leaves the files of its old commit in the browser (hundreds of MB that
 * nothing reads any more): they go, once a visit. Only this repo's files at another commit (or at a branch
 * such as `main`) are removed: `<repo>/resolve/<other>/...`; other models and the pinned commit are kept.
 */
const pruned = new Set<string>()
export async function dropOldRevisions(plan: Pick<SpeechPlan, 'repo' | 'revision'>): Promise<number> {
  if (!plan.revision || pruned.has(plan.repo)) return 0
  pruned.add(plan.repo)
  let n = 0
  try {
    if (!('caches' in self) || !(await caches.has('transformers-cache'))) return 0
    const cache = await caches.open('transformers-cache')
    const base = `/${plan.repo}/resolve/`
    for (const req of await cache.keys()) {
      let path: string
      try { path = new URL(req.url).pathname } catch { continue }
      if (!path.startsWith(base) || path.startsWith(`${base}${plan.revision}/`)) continue
      if (await cache.delete(req)) n++
    }
    if (n) console.info(`ONIT subs: removed ${n} files of ${plan.repo} at an old commit`)
  } catch {}
  return n
}

// ------------------------------------------------------------------ asking before a download

/** An open question to the person (a dialog in app.tsx shows it): download the speech model? */
export const speechAsk = signal<{ id: number; language: string | null } | null>(null)
type Answer = Quality | null | 'closed'
let asking: { id: number; promise: Promise<Answer>; answer: (q: Answer) => void; waiters: Set<AbortSignal | undefined> } | null = null
let askId = 0
/** Plans the person agreed to download, for this visit (a batch asks once, not per video). */
const granted = new Set<string>()
/** The runs (their signals) whose question the person answered "no" to: only that batch or template run stops asking. */
const declinedRuns = new WeakSet<AbortSignal>()

/** The person's answer to the question. */
export function answerSpeechAsk(q: Quality | null) {
  asking?.answer(q)
}

/**
 * The plan to run for a template's subtitles. When its model isn't in the browser yet, the person
 * is asked first (with the size, and "faster" / "better quality"), so a big download and a hot phone
 * never start by themselves. Returns null when they decline (for this run [signal] only).
 */
export async function confirmSpeech(signal?: AbortSignal, language: string | null = null): Promise<SpeechPlan | null> {
  if (signal?.aborted) throw new SpeechAborted()
  const d = await probeDevice()
  let plan = currentPlan(d, language)
  if (granted.has(planKey(plan)) || (await planCached(plan))) return plan
  if (signal && declinedRuns.has(signal)) return null
  if (!asking) {
    const id = ++askId
    let answer!: (q: Answer) => void
    const promise = new Promise<Answer>((r) => { answer = r })
    asking = { id, promise, waiters: new Set(), answer: (q) => { asking = null; speechAsk.value = null; answer(q) } }
    speechAsk.value = { id, language }
  }
  const mine = asking
  mine.waiters.add(signal)
  let onAbort = () => {}
  const aborted = new Promise<'aborted'>((r) => { onAbort = () => r('aborted'); signal?.addEventListener('abort', onAbort) })
  const q = await Promise.race([mine.promise, aborted])
  signal?.removeEventListener('abort', onAbort)
  if (q === 'aborted') {
    // Only this waiter leaves; the question stays for the others, and closes with the last one.
    mine.waiters.delete(signal)
    if (mine.waiters.size === 0 && asking === mine) mine.answer('closed')
    throw new SpeechAborted()
  }
  if (signal?.aborted) throw new SpeechAborted()
  if (q === 'closed') return null
  if (!q) {
    for (const w of mine.waiters) if (w) declinedRuns.add(w)
    return null
  }
  plan = planFor(q, d, gpuFailed.value, spokenLanguage(language))
  granted.add(planKey(plan))
  return plan
}

/** A button pressed on purpose ("create subtitles", or Start in the batch's setup, where the size is shown) is a go-ahead for that plan. */
export function grantSpeech(plan: SpeechPlan) {
  granted.add(planKey(plan))
}

// ------------------------------------------------------------------ one job

export type SpeechStatus =
  | { stage: 'audio' }
  | { stage: 'download'; progress: number; loaded: number; total: number }
  | { stage: 'load' }
  | { stage: 'recognize'; progress: number }
  | { stage: 'fallback'; /** The same model, on the CPU (the Hebrew one), not a smaller one. */ same?: boolean }

export class SpeechAborted extends Error {
  constructor() { super('aborted') }
}
/** A failed job; [code]: 'no-audio' (no sound in the video), 'stalled' (no progress for too long), 'failed'. */
export class SpeechError extends Error {
  constructor(message: string, readonly code = 'failed') { super(message) }
}

/** Seconds without any sign of life, per phase, before the job is called stuck. */
const LIMITS = { audio: 90, load: 150, download: 60 }
/**
 * Recognition: the encoder is one call with no messages, 30 s of sound at a time; the limit is
 * for one such window on this plan (it is the limit of one thread, the slowest case).
 */
function recognizeLimit(plan: SpeechPlan): number {
  if (plan.device === 'webgpu') return 120
  return plan.model === 'large' || plan.model === 'ivrit' ? 600 : plan.model === 'small' ? 300 : 120
}

function limitFor(phase: keyof typeof LIMITS | 'recognize', plan: SpeechPlan): number {
  const forced = Number(param('subswatch'))
  return (forced > 0 ? forced : phase === 'recognize' ? recognizeLimit(plan) : LIMITS[phase]) * 1000
}

const readLang = (): string | null => { try { return localStorage.getItem(storageKey('subs.lang')) } catch { return null } }
/** The speech language the person chose last (null: automatic); kept per channel. Hebrew has its own model, so the plan follows it. */
export const langChoice = signal<string | null>(readLang())
export function setLangChoice(code: string | null) {
  langChoice.value = code
  try { if (code) localStorage.setItem(storageKey('subs.lang'), code); else localStorage.removeItem(storageKey('subs.lang')) } catch {}
}

/**
 * The language Whisper is told. Transformers.js 4.3.2 has no language detection (a missing
 * language decodes as English), so "automatic" is the language the person chose last, else the
 * app's own (Hebrew in the Hebrew app).
 */
export function spokenLanguage(language: string | null): string {
  if (language) return language
  if (langChoice.value) return langChoice.value
  // The app's language as the person set it (not the phone's: many Hebrew speakers keep it in English).
  return lang.value === 'he' ? 'hebrew' : 'english'
}

/**
 * A worker that finished a job keeps its loaded model for a while: the next video (a batch, a second
 * try, another language) starts at once instead of loading the model again. It holds memory only
 * (no work), so a phone keeps it for less time; leaving the page, a failure or a cancel ends it.
 */
let warm: { worker: Worker; key: string; timer: number } | null = null
const warmKey = (p: SpeechPlan) => `${planKey(p)}|${p.device}|${p.threads}`
function dropWarm() {
  if (!warm) return
  window.clearTimeout(warm.timer)
  warm.worker.terminate()
  warm = null
}
function keepWarm(worker: Worker, plan: SpeechPlan) {
  dropWarm()
  warm = { worker, key: warmKey(plan), timer: window.setTimeout(dropWarm, (device.value.weak ? 60 : 240) * 1000) }
}
/** An export, a face analysis or a picture pass is about to start: a phone gives the memory back first (a computer keeps the model for the next video). */
export function releaseSpeechForExport() {
  if (device.value.weak) dropWarm()
}
// A page that goes to the background (a phone's app switch) gives the model back too.
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && device.value.weak) dropWarm() })
function takeWarm(plan: SpeechPlan): Worker | null {
  if (!warm) return null
  if (warm.key !== warmKey(plan)) { dropWarm(); return null }
  window.clearTimeout(warm.timer)
  const w = warm.worker
  warm = null
  return w
}

/**
 * Recognizes the speech in [file] as timed words. [onStatus]: where it is. Ends the worker when
 * done, failed or stopped ([signal]); a stuck phase fails with code 'stalled'. A graphics processor
 * that errors or hangs is left for the CPU once, but only for a CPU model that is already in the
 * browser: otherwise the job fails and the person chooses (and agrees to the download) again.
 */
export function transcribeFile(
  file: File, language: string | null,
  opts: { plan: SpeechPlan; onStatus: (s: SpeechStatus) => void; signal?: AbortSignal },
): Promise<{ words: Word[]; plan: SpeechPlan }> {
  const { signal, onStatus } = opts
  const lang = spokenLanguage(language)
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new SpeechAborted())
    let worker: Worker | null = null
    let timer = 0
    let finished = false
    let last = Date.now()
    // A tab put to sleep (or a frozen worker) says nothing: that is not "stuck". Coming back counts from now.
    let lastCheck = Date.now()
    const alive = () => { last = Date.now(); lastCheck = last }
    const onVisible = () => { if (document.visibilityState === 'visible') alive() }
    document.addEventListener('visibilitychange', onVisible)
    document.addEventListener('resume', alive)
    const stop = (keep?: SpeechPlan) => {
      window.clearInterval(timer)
      if (worker && keep) keepWarm(worker, keep)
      else worker?.terminate()
      worker = null
    }
    const end = (fn: () => void, keep?: SpeechPlan) => {
      if (finished) return
      finished = true
      signal?.removeEventListener('abort', onAbort)
      document.removeEventListener('visibilitychange', onVisible)
      document.removeEventListener('resume', alive)
      if (worker) { worker.onmessage = null; worker.onerror = null; worker.onmessageerror = null }
      stop(keep)
      fn()
    }
    // Cancel: the worker is ended on the spot (its GPU and memory go with it).
    const onAbort = () => end(() => reject(new SpeechAborted()))
    signal?.addEventListener('abort', onAbort)

    // The files of an old pin of this model are dropped (they never load again).
    void dropOldRevisions(opts.plan)
    let usedFallback = false
    let usedOneThread = false
    const attempt = (plan: SpeechPlan) => {
      stop()
      let phase: keyof typeof LIMITS | 'recognize' = 'audio'
      alive()
      const w = takeWarm(plan) ?? new Worker(new URL('../workers/subs.worker.ts', import.meta.url), { type: 'module' })
      worker = w
      const failOrFallback = (code: string, message: string) => {
        if (finished || worker !== w) return
        // A lack of memory says so whatever phase or kind of error it came in (it is never "threads" or the graphics processor's).
        if (code !== 'stalled' && isMemoryError(message)) code = 'memory'
        // Threads that don't start (a phone that refuses the shared memory): once more with one, in a new worker.
        // (Only the worker's own 'threads' says so: a stalled or failed download is not the threads.)
        if (plan.device === 'wasm' && plan.threads > 1 && !usedOneThread && code === 'threads') {
          usedOneThread = true
          threadsFailed = true // (only a 'threads' failure sets this: not a lack of memory)
          stop()
          attempt({ ...plan, threads: 1 })
          return
        }
        if (code === 'memory') {
          end(() => reject(new SpeechError(t('אין מספיק זיכרון במכשיר לזיהוי הדיבור. אפשר לבחור "מהיר" (מודל קטן יותר).'), 'memory')))
          return
        }
        // The Hebrew model's repo answers that a file isn't there (or isn't open to us): it was removed or closed
        // (it is one uploader's repo, see docs/parity.md). Say so, and what still works (not the graphics processor's fault).
        if (plan.model === 'ivrit' && /could not locate file|unauthorized access to file/i.test(message)) {
          end(() => reject(new SpeechError(t('לא ניתן להוריד את מודל העברית (ivrit.ai) מ-Hugging Face. אפשר לבחור "מהיר" (מודל רב-לשוני).'), 'missing')))
          return
        }
        if (plan.device === 'webgpu' && !usedFallback && code !== 'no-audio' && (phase === 'load' || phase === 'recognize')) {
          usedFallback = true
          gpuFailed.value = true
          stop()
          // The Hebrew model goes on the CPU as itself (the same files); the others go to a smaller model.
          const same = plan.model === 'ivrit'
          const cpu = same ? planFor(plan.quality, device.value, true, lang) : planFor('fast', device.value, true, lang)
          // No download that wasn't asked for: only a CPU model that is already here.
          planCached(cpu).then((have) => {
            if (finished) return
            if (have) {
              onStatus({ stage: 'fallback', same })
              attempt(cpu)
            } else {
              end(() => reject(new SpeechError(same
                ? t('המעבד הגרפי לא הצליח. אפשר ללחוץ שוב כדי להמשיך על המעבד עם אותו מודל.')
                : t('המעבד הגרפי לא הצליח. אפשר ללחוץ שוב כדי להמשיך על המעבד עם מודל קטן יותר (כ-%d MB, יורד פעם אחת).', mbOf(cpu)), 'gpu')))
            }
          })
          return
        }
        end(() => reject(new SpeechError(message, code)))
      }
      timer = window.setInterval(() => {
        const now = Date.now()
        // The timer itself was paused (a frozen tab): the quiet is not the job's.
        if (now - lastCheck > 5000) last = now
        lastCheck = now
        const quiet = now - last
        if (quiet > limitFor(phase, plan)) {
          const secs = Math.round(quiet / 1000)
          failOrFallback('stalled', t('הזיהוי נתקע: אין התקדמות כבר %d שניות. אפשר לנסות שוב, או לבחור "מהיר".', secs))
        }
      }, 1000)
      w.onmessage = (e: MessageEvent<SubsMessage>) => {
        const m = e.data
        last = Date.now()
        switch (m.type) {
          case 'tick': return
          case 'audio': phase = 'audio'; return onStatus({ stage: 'audio' })
          case 'load': phase = 'load'; return onStatus({ stage: 'load' })
          case 'download':
            // Everything known so far is here: the loading starts (its limit), until another file starts.
            phase = m.total > 0 && m.loaded >= m.total ? 'load' : 'download'
            return onStatus({ stage: 'download', progress: m.progress, loaded: m.loaded, total: m.total })
          case 'transcribe': phase = 'recognize'; return onStatus({ stage: 'recognize', progress: m.progress })
          case 'done': console.info(`ONIT subs: ${plan.model} (${plan.repo}) ${m.device}, model ${m.ms.load} ms, recognition ${m.ms.recognize} ms (to the first token ${m.ms.first}, ${m.ms.tokens} tokens ${m.ms.decode}, word timings ${m.ms.after})`); return end(() => resolve({ words: m.words, plan }), plan)
          case 'error': return failOrFallback(m.code, m.message)
        }
      }
      // A thread of the runtime that fails to start throws outside the worker's own catch: while the
      // model loads with threads, an uncaught error is that (download errors come as messages).
      w.onerror = (e) => failOrFallback(plan.device === 'wasm' && plan.threads > 1 && (phase === 'load' || phase === 'download') ? 'threads' : 'failed', e.message || 'error')
      w.onmessageerror = () => failOrFallback('failed', 'error')
      const req: SubsRequest = { file, language: lang, repo: plan.repo, revision: plan.revision, device: plan.device, encDtype: plan.encDtype, dtype: plan.dtype, threads: plan.threads }
      w.postMessage(req)
    }
    attempt(opts.plan)
  })
}
