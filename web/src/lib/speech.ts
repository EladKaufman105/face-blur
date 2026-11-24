// Speech recognition on the device: which Whisper this device should use (a phone gets a small
// one), what its download costs, and one job runner that never leaves the person looking at a
// frozen bar: every phase reports, a watchdog ends a stuck one, the worker is ended as soon as
// the job is over or cancelled, and a graphics processor that fails or hangs falls back to the CPU.
import { signal } from '@preact/signals'
import { storageKey } from './channel'
import { t } from '../i18n'
import type { SubsMessage, SubsRequest } from '../workers/subs.worker'
import type { Word } from './subtitles'

/** What the person picks: "fast" (small, light on a phone) or "better" (bigger, slower, more exact). */
export type Quality = 'fast' | 'better'
export type Dtype = 'q4' | 'q4f16' | 'q8'
type ModelKey = 'base' | 'small' | 'large'

/** Hugging Face repos by onnx-community: Whisper with word timings (cross-attention alignment heads in their generation config). */
const MODELS: Record<ModelKey, { repo: string; label: string }> = {
  base: { repo: 'onnx-community/whisper-base_timestamped', label: 'Whisper base' },
  small: { repo: 'onnx-community/whisper-small_timestamped', label: 'Whisper small' },
  large: { repo: 'onnx-community/whisper-large-v3-turbo_timestamped', label: 'Whisper large-v3-turbo' },
}

/** [encoder, decoder] file sizes in bytes (as listed on the Hub), by dtype. The tokenizer and config files add a few MB. */
const BYTES: Record<ModelKey, Partial<Record<Dtype, [number, number]>>> = {
  base: { q4: [18771046, 123738327], q8: [23159167, 53712708] },
  small: { q4: [66178491, 233421212], q8: [92240498, 156795750] },
  large: { q4f16: [370035242, 193566135], q4: [425003939, 334207912] },
}
const EXTRA_BYTES = 4e6
const SUFFIX: Record<Dtype, string> = { q4: '_q4', q4f16: '_q4f16', q8: '_quantized' }

export interface SpeechPlan {
  quality: Quality
  model: ModelKey
  repo: string
  device: 'webgpu' | 'wasm'
  dtype: Dtype
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
  return { weak: isMobile() || (mem !== undefined && mem < 8) || cores < 4, gpu: null, probed: false }
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
    return v === 'fast' || v === 'better' ? v : null
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

export function defaultQuality(d: Device): Quality {
  return !d.weak && d.gpu?.big ? 'better' : 'fast'
}

const forcedModel = (): ModelKey | null => {
  const f = param('subsmodel')
  return f === 'base' || f === 'small' || f === 'large' ? f : null
}

function modelFor(q: Quality, d: Device): ModelKey {
  const forced = forcedModel()
  if (forced) return forced
  if (d.weak) return q === 'fast' ? 'base' : 'small'
  return q === 'fast' ? 'small' : 'large'
}

/** Set when the graphics processor failed or hung in this visit: the next job goes straight to the CPU. */
export const gpuFailed = signal(false)

/** The plan for [quality] on [d]: the model, the processor, the file kinds and what they weigh. */
export function planFor(quality: Quality, d: Device, forceCpu = gpuFailed.value): SpeechPlan {
  let model = modelFor(quality, d)
  const wanted = param('backend')
  let dev: 'webgpu' | 'wasm' = d.weak || !d.gpu || forceCpu ? 'wasm' : 'webgpu'
  if (!forceCpu && (wanted === 'wasm' || wanted === 'webgpu')) dev = wanted
  if (dev === 'webgpu' && model === 'large' && !d.gpu?.big) dev = 'wasm'
  // The big model on the CPU takes minutes for each 30 seconds: "better" is the small one there.
  if (dev === 'wasm' && model === 'large' && !forcedModel()) model = 'small'
  const dtype: Dtype = model === 'large' ? (dev === 'webgpu' && d.gpu?.f16 ? 'q4f16' : 'q4') : dev === 'webgpu' ? 'q4' : 'q8'
  const [enc, dec] = BYTES[model][dtype]!
  const s = SUFFIX[dtype]
  return { quality, model, repo: MODELS[model].repo, device: dev, dtype, files: [`onnx/encoder_model${s}.onnx`, `onnx/decoder_model_merged${s}.onnx`], bytes: enc + dec + EXTRA_BYTES }
}

/** The plan the person's choice (or the device's default) gives. */
export function currentPlan(d: Device = device.value): SpeechPlan {
  return planFor(qualityChoice.value ?? defaultQuality(d), d)
}

export const mbOf = (plan: SpeechPlan) => Math.max(1, Math.round(plan.bytes / 1e6))

/** Whether the plan's two big files are already in the browser (so there is no download). */
export async function planCached(plan: SpeechPlan): Promise<boolean> {
  try {
    if (!('caches' in window) || !(await caches.has('transformers-cache'))) return false
    const urls = (await (await caches.open('transformers-cache')).keys()).map((r) => r.url)
    return plan.files.every((f) => urls.some((u) => u.includes(`${plan.repo}/resolve/`) && u.includes(f)))
  } catch {
    return false
  }
}

// ------------------------------------------------------------------ asking before a download

/** An open question to the person (a dialog in app.tsx shows it): download the speech model? */
export const speechAsk = signal<{ id: number } | null>(null)
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
export async function confirmSpeech(signal?: AbortSignal): Promise<SpeechPlan | null> {
  if (signal?.aborted) throw new SpeechAborted()
  const d = await probeDevice()
  let plan = currentPlan(d)
  if (granted.has(plan.repo + plan.dtype) || (await planCached(plan))) return plan
  if (signal && declinedRuns.has(signal)) return null
  if (!asking) {
    const id = ++askId
    let answer!: (q: Answer) => void
    const promise = new Promise<Answer>((r) => { answer = r })
    asking = { id, promise, waiters: new Set(), answer: (q) => { asking = null; speechAsk.value = null; answer(q) } }
    speechAsk.value = { id }
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
  plan = planFor(q, d)
  granted.add(plan.repo + plan.dtype)
  return plan
}

/** A button pressed on purpose ("create subtitles", or Start in the batch's setup, where the size is shown) is a go-ahead for that plan. */
export function grantSpeech(plan: SpeechPlan) {
  granted.add(plan.repo + plan.dtype)
}

// ------------------------------------------------------------------ one job

export type SpeechStatus =
  | { stage: 'audio' }
  | { stage: 'download'; progress: number; loaded: number; total: number }
  | { stage: 'load' }
  | { stage: 'recognize'; progress: number }
  | { stage: 'fallback' }

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
 * Recognition: the encoder is one call with no messages (one thread: the page isn't cross-origin
 * isolated), 30 s of sound at a time; the limit is for one such window on this plan.
 */
function recognizeLimit(plan: SpeechPlan): number {
  if (plan.device === 'webgpu') return 120
  return plan.model === 'large' ? 600 : plan.model === 'small' ? 300 : 120
}

function limitFor(phase: keyof typeof LIMITS | 'recognize', plan: SpeechPlan): number {
  const forced = Number(param('subswatch'))
  return (forced > 0 ? forced : phase === 'recognize' ? recognizeLimit(plan) : LIMITS[phase]) * 1000
}

/**
 * The language Whisper is told. Transformers.js 4.3.2 has no language detection (a missing
 * language decodes as English), so "automatic" is the language the person chose last, else the
 * app's own (Hebrew in the Hebrew app).
 */
function spokenLanguage(language: string | null): string {
  if (language) return language
  try {
    const l = localStorage.getItem(storageKey('subs.lang'))
    if (l) return l
  } catch {}
  return (navigator.language || 'he').toLowerCase().startsWith('he') || !navigator.language ? 'hebrew' : 'english'
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
    const stop = () => {
      window.clearInterval(timer)
      worker?.terminate()
      worker = null
    }
    const end = (fn: () => void) => {
      if (finished) return
      finished = true
      signal?.removeEventListener('abort', onAbort)
      document.removeEventListener('visibilitychange', onVisible)
      document.removeEventListener('resume', alive)
      stop()
      fn()
    }
    // Cancel: the worker is ended on the spot (its GPU and memory go with it).
    const onAbort = () => end(() => reject(new SpeechAborted()))
    signal?.addEventListener('abort', onAbort)

    let usedFallback = false
    const attempt = (plan: SpeechPlan) => {
      stop()
      let phase: keyof typeof LIMITS | 'recognize' = 'audio'
      alive()
      const w = new Worker(new URL('../workers/subs.worker.ts', import.meta.url), { type: 'module' })
      worker = w
      const failOrFallback = (code: string, message: string) => {
        if (finished || worker !== w) return
        if (plan.device === 'webgpu' && !usedFallback && code !== 'no-audio' && (phase === 'load' || phase === 'recognize')) {
          usedFallback = true
          gpuFailed.value = true
          stop()
          const cpu = planFor('fast', device.value, true)
          // No download that wasn't asked for: only a CPU model that is already here.
          planCached(cpu).then((have) => {
            if (finished) return
            if (have) {
              onStatus({ stage: 'fallback' })
              attempt(cpu)
            } else {
              end(() => reject(new SpeechError(t('המעבד הגרפי לא הצליח. אפשר ללחוץ שוב כדי להמשיך על המעבד עם מודל קטן יותר (כ-%d MB, יורד פעם אחת).', mbOf(cpu)), 'gpu')))
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
          case 'done': return end(() => resolve({ words: m.words, plan }))
          case 'error': return failOrFallback(m.code, m.message)
        }
      }
      w.onerror = (e) => failOrFallback('failed', e.message || 'error')
      w.onmessageerror = () => failOrFallback('failed', 'error')
      const req: SubsRequest = { file, language: lang, repo: plan.repo, device: plan.device, dtype: plan.dtype }
      w.postMessage(req)
    }
    attempt(opts.plan)
  })
}
