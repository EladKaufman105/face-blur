// The workers' jobs as promises, and a template's processing of one video with no screen (the
// batch, and "blur and save" with a template): the same steps the editor's panels run -
// find the faces, create the subtitles, change the voice and clean the sound, cut the pauses and
// filler words, find the people for the background, steady the picture - then export.
import type { Analysis, Track } from './types'
import type { AnalyzeMessage, AnalyzeRequest } from '../workers/analyze.worker'
import type { AudioMessage, AudioRequest } from '../workers/audio.worker'
import type { FollowMessage, FollowRequest } from '../workers/follow.worker'
import type { PictureMessage, PictureRequest } from '../workers/picture.worker'
import { confirmSpeech, releaseSpeechForExport, SpeechAborted, SpeechError, transcribeFile, type SpeechStatus } from './speech'
import type { ExportMessage } from '../workers/export.worker'
import { exportRequest, type ExportJob } from './exportjob'
import { dropCutWords, wordsToCues, type Cue, type Word } from './subtitles'
import { addCuts, CutMap, type Range } from './cutmap'
import { isFiller } from './silence'
import { cleanupOn, type Cleanup } from './cleanup'
import type { VoiceFx } from './voice'
import { ASPECT_RATIO, cropFraction, reframePath, steady, type Steady } from './camera'
import type { PeopleMasks } from './background'
import { exportOptionsOf, hasProcessing, styleOf, type TemplateSettings } from './template'
import { t } from '../i18n'
import { translateCues, translateNote } from './translate'

/** A job that was stopped (skipped or cancelled). */
export class Aborted extends Error {
  constructor() { super('aborted') }
}

/** A step that failed, with its code ('no-audio' when the video has no sound). */
export class StepError extends Error {
  constructor(message: string, readonly code = 'failed') { super(message) }
}

const forcedBackend = () => {
  const b = new URLSearchParams(location.search).get('backend')
  return b === 'wasm' || b === 'webgpu' ? b : undefined
}

/** Runs one worker job: [handle] settles it from the worker's messages; [signal] stops it (the worker is ended). */
function run<T, M>(make: () => Worker, start: (w: Worker) => void, handle: (m: M, ok: (v: T) => void, fail: (e: Error) => void) => void, signal?: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) return reject(new Aborted())
    const w = make()
    const end = () => { signal?.removeEventListener('abort', onAbort); w.terminate() }
    const onAbort = () => { end(); reject(new Aborted()) }
    signal?.addEventListener('abort', onAbort)
    w.onmessage = (e: MessageEvent<M>) => handle(e.data, (v) => { end(); resolve(v) }, (err) => { end(); reject(err) })
    w.onerror = (e) => { end(); reject(new StepError(e.message || 'error')) }
    w.onmessageerror = () => { end(); reject(new StepError('error')) }
    start(w)
  })
}

/** The frames' times, the frame rate and the codec (from the packets, no decoding). */
export function frameTimes(file: File, signal?: AbortSignal) {
  return run<{ times: Float64Array; fps: number; codec: string }, FollowMessage>(
    () => new Worker(new URL('../workers/follow.worker.ts', import.meta.url), { type: 'module' }),
    (w) => w.postMessage({ type: 'times', file } satisfies FollowRequest),
    (m, ok, fail) => { if (m.type === 'times') ok(m); else if (m.type === 'error') fail(new StepError(m.message)) },
    signal,
  )
}

/** The video's displayed size, from the browser's own player. */
export function videoSize(file: File): Promise<[number, number] | null> {
  return new Promise((resolve) => {
    const v = document.createElement('video')
    const url = URL.createObjectURL(file)
    const done = (r: [number, number] | null) => { URL.revokeObjectURL(url); resolve(r) }
    v.preload = 'metadata'
    v.muted = true
    v.onloadedmetadata = () => done(v.videoWidth > 0 ? [v.videoWidth, v.videoHeight] : null)
    v.onerror = () => done(null)
    v.src = url
  })
}

/** Faces found in [file]: what the analyzing screen does, with no screen. [onProgress]: 0..1. */
export function findFaces(file: File, sensitive: boolean, onProgress: (p: number) => void, signal?: AbortSignal, detect: { fast?: boolean; selfie?: boolean; sensitivity?: number } = {}) {
  return run<{ analysis: Analysis; thumbs: (string | null)[]; ms: number; backend: string }, AnalyzeMessage>(
    () => { releaseSpeechForExport(); return new Worker(new URL('../workers/analyze.worker.ts', import.meta.url), { type: 'module' }) },
    (w) => {
      const req: AnalyzeRequest = { file, sensitive, fast: detect.fast, selfie: detect.selfie, sensitivity: detect.sensitivity, base: new URL('.', document.baseURI).href, backend: forcedBackend(), workers: Number(new URLSearchParams(location.search).get('workers')) || undefined }
      w.postMessage(req)
    },
    (m, ok, fail) => {
      if (m.type === 'progress') {
        onProgress(Math.min(1, m.done / Math.max(1, m.total)))
        m.preview?.close()
      } else if (m.type === 'done') {
        const thumbs = m.thumbs.map((b) => {
          if (!b) return null
          const c = document.createElement('canvas')
          c.width = b.width
          c.height = b.height
          c.getContext('2d')!.drawImage(b, 0, 0)
          b.close()
          return c.toDataURL('image/jpeg', 0.85)
        })
        ok({ analysis: { times: m.times, width: m.width, height: m.height, fps: m.fps, tracks: m.tracks, cuts: m.cuts, lowConfidence: m.lowConfidence, reduced: m.reduced, fast: m.fast, selfie: m.selfie, sensitive: m.sensitive, sensitivity: m.sensitivity }, thumbs, ms: m.ms, backend: m.backend })
      } else if (m.type === 'error') fail(new StepError(m.message, m.code))
    },
    signal,
  )
}

/**
 * Speech in [file] as timed words. [onStatus]: where it is (the download, the loading, the
 * recognition and how far). When the model isn't in the browser yet the person is asked first.
 */
export async function transcribe(file: File, language: string | null, onStatus: (s: SpeechStatus) => void, signal?: AbortSignal) {
  try {
    const plan = await confirmSpeech(signal, language)
    if (!plan) throw new StepError(t('הורדת המודל לא אושרה'), 'declined')
    return (await transcribeFile(file, language, { plan, onStatus, signal })).words
  } catch (e) {
    if (e instanceof SpeechAborted) throw new Aborted()
    if (e instanceof SpeechError) throw new StepError(e.message, e.code)
    throw e
  }
}

/** The pauses longer than [minPause] seconds (not over [words]). */
export function findPauses(file: File, minPause: number, words: Range[], signal?: AbortSignal) {
  return run<Range[], AudioMessage>(
    () => new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' }),
    (w) => w.postMessage({ type: 'silences', file, minPause, words } satisfies AudioRequest),
    (m, ok, fail) => { if (m.type === 'silences') ok(m.ranges); else if (m.type === 'error') fail(new StepError(m.message, m.code)) },
    signal,
  )
}

/** The sound with the voice change and the cleanup. */
export function processSound(file: File, voice: VoiceFx, clean: Cleanup, signal?: AbortSignal) {
  return run<{ rate: number; data: Float32Array; lufs: number | null; gainDb: number }, AudioMessage>(
    () => new Worker(new URL('../workers/audio.worker.ts', import.meta.url), { type: 'module' }),
    (w) => w.postMessage({ file, voice, bleeps: [], tone: true, clean } satisfies AudioRequest),
    (m, ok, fail) => { if (m.type === 'done') ok(m); else if (m.type === 'error') fail(new StepError(m.message, m.code)) },
    signal,
  )
}

/** One pass over the video: the camera's motion and cuts, and ([people]) where the people are. */
export function picturePass(file: File, times: Float64Array, people: boolean, onProgress: (p: number) => void, signal?: AbortSignal) {
  return run<{ times: Float64Array; fps: number; motion: Float32Array; cuts: number[]; masks: PeopleMasks | null }, PictureMessage>(
    () => { releaseSpeechForExport(); return new Worker(new URL('../workers/picture.worker.ts', import.meta.url), { type: 'module' }) },
    (w) => {
      const req: PictureRequest = { file, times, people, model: new URL('models/people.onnx', document.baseURI).href, backend: forcedBackend() }
      w.postMessage(req)
    },
    (m, ok, fail) => {
      if (m.type === 'progress') onProgress(m.progress)
      else if (m.type === 'done') ok(m)
      else fail(new StepError(m.message))
    },
    signal,
  )
}

/** The export of [job]. [onFrame]: a small picture of a frame just made, now and then (the caller closes it). */
export function exportVideo(job: ExportJob, onProgress: (p: number) => void, onFrame?: (b: ImageBitmap) => void, signal?: AbortSignal) {
  return run<{ blob: Blob; ms: number }, ExportMessage>(
    () => { releaseSpeechForExport(); return new Worker(new URL('../workers/export.worker.ts', import.meta.url), { type: 'module' }) },
    (w) => {
      exportRequest(job).then(({ req, transfer }) => { if (!signal?.aborted) w.postMessage(req, transfer) }).catch((e) => w.onerror?.(new ErrorEvent('error', { message: String(e?.message ?? e) })))
    },
    (m, ok, fail) => {
      if (m.type === 'progress') onProgress(m.progress)
      else if (m.type === 'frame') { if (onFrame) onFrame(m.bitmap); else m.bitmap.close() }
      else if (m.type === 'done') ok({ blob: m.blob, ms: m.ms })
      else if (m.type === 'error') fail(new StepError(m.message, m.code))
    },
    signal,
  )
}

// ------------------------------------------------------------------ a template's processing

/** What a template's processing found in a video. */
export type Processed = {
  analysis: Analysis
  thumbs: [number, string | null][]
  tracks: Track[]
  words: Word[]
  cues: Cue[]
  cuts: Range[]
  voice: { rate: number; data: Float32Array } | null
  picture: { motion: Float32Array; cuts: number[]; masks: PeopleMasks | null } | null
  steady: Steady | null
  path: { x: Float32Array; y: Float32Array } | null
  /** What could not be done (the video is still processed): said on the video, never skipped silently. */
  notes: string[]
  detectInfo: string
}

/** Where a step is, for the queue: its name and how far (0..1) in the video's whole work. */
export type Phase = 'read' | 'faces' | 'subtitles' | 'download' | 'translate' | 'sound' | 'picture' | 'export'

/** The share of a video's bar that each part takes. */
export function shares(s: TemplateSettings): { faces: number; processing: number; export: number } {
  // With no face blur the faces are not looked for at all: only the frames' times are read (a moment).
  const faces = s.blurOff ? 0.02 : 0.35
  const processing = s.createSubs ? 0.25 : hasProcessing(s) ? 0.08 : 0
  return { faces, processing, export: 1 - faces - processing }
}

const message = (e: unknown) => String((e as Error)?.message ?? e).slice(0, 160)

/**
 * Finds the faces and applies [s]'s processing to [file], step by step, each waited for. Steps
 * that fail leave a note and the rest goes on. [report]: the phase and the whole video's progress
 * (0..1, up to the end of the processing). Throws [Aborted] when [signal] stops it.
 */
export async function processVideo(file: File, s: TemplateSettings, report: (phase: Phase, progress: number, detail?: string) => void, signal?: AbortSignal): Promise<Processed> {
  const sh = shares(s)
  const notes: string[] = []

  // The faces (with no face blur: only the frames' times).
  let analysis: Analysis
  let thumbs: (string | null)[] = []
  let detectInfo = ''
  if (s.blurOff) {
    report('read', 0)
    const [ft, size] = await Promise.all([frameTimes(file, signal), videoSize(file)])
    if (!size) throw new StepError(t('לא ניתן לפתוח את הסרטון'))
    analysis = { times: ft.times, width: size[0], height: size[1], fps: ft.fps, tracks: [] }
    report('read', sh.faces)
  } else {
    report('faces', 0)
    const r = await findFaces(file, s.sensitive, (p) => report('faces', p * sh.faces), signal, { fast: s.fast, selfie: s.selfie, sensitivity: s.sensitivity })
    analysis = r.analysis
    thumbs = r.thumbs
    detectInfo = t('זמן עיבוד: %s', `${Math.round(r.ms / 1000)}s`)
  }
  const tracks = analysis.tracks
  const base = sh.faces
  const steps = (s.createSubs ? 1 : 0) + (s.voice !== 'none' || cleanupOn({ denoise: s.denoise, enhance: s.enhance, loud: s.loud }) ? 1 : 0) + (s.cutSilences || s.cutFillers ? 1 : 0) + ((s.background.fill !== 'none' && s.background.key === 'person') || s.stabilize > 0 ? 1 : 0)
  let stepNo = 0
  const within = (phase: Phase, p: number, detail?: string) => report(phase, base + sh.processing * ((stepNo + p) / Math.max(1, steps)), detail)

  // Subtitles first: filler words and the pauses read their word times.
  let words: Word[] = []
  let cues: Cue[] = []
  if (s.createSubs) {
    within('subtitles', 0)
    try {
      words = await transcribe(file, s.language, (st) => {
        if (st.stage === 'download') within('download', 0, `${Math.round(st.progress * 100)}%`)
        else if (st.stage === 'recognize') within('subtitles', st.progress)
        else within('subtitles', 0, st.stage === 'load' ? t('טוען את המודל…') : st.stage === 'fallback' ? t('עובר למעבד…') : undefined)
      }, signal)
      cues = wordsToCues(words, s.split.words, s.split.chars)
      if (cues.length === 0) notes.push(t('לא נמצא דיבור בסרטון'))
      else if (s.burn === 'NONE') notes.push(t('הכתוביות נוצרו, אבל לא ייצרבו בסרטון (ההגדרה: בלי כתוביות בסרטון)'))
    } catch (e) {
      if (e instanceof Aborted) throw e
      notes.push((e as StepError).code === 'no-audio' ? t('לסרטון אין פס קול.') : t('הכתוביות לא נוצרו: %s', message(e)))
    }
    // The translation, as the app's template (automatic: to English, English speech to Hebrew). The subtitles stay if it fails.
    if (s.translate && cues.length > 0) {
      try {
        within('translate', 1)
        cues = await translateCues(cues, s.language, (stage, p) => within('translate', 1, stage === 'download' ? t('מוריד את חבילת השפה… %d%', Math.round(p * 100)) : `${Math.round(p * 100)}%`), signal)
      } catch (e) {
        if (signal?.aborted) throw new Aborted()
        notes.push(translateNote(e))
      }
    }
    stepNo++
  }

  // Voice and the sound's cleanup (the loudness is measured on the cleaned sound).
  let voice: Processed['voice'] = null
  const clean: Cleanup = { denoise: s.denoise, enhance: s.enhance, loud: s.loud }
  if (s.voice !== 'none' || cleanupOn(clean)) {
    within('sound', 0)
    try {
      const r = await processSound(file, s.voice, clean, signal)
      voice = { rate: r.rate, data: r.data }
      if (s.loud && r.lufs === null) notes.push(t('לא נמצא דיבור למדידת העוצמה'))
    } catch (e) {
      if (e instanceof Aborted) throw e
      if ((e as StepError).code === 'no-audio') notes.push(t('אין סאונד בסרטון'))
      else if (s.voice !== 'none') {
        // A voice change is for anonymity: the video is never saved with the real voice.
        throw new StepError(t('שינוי הקול נכשל, ולכן הסרטון לא נשמר (כדי שהקול המקורי לא יישמר בטעות): %s', message(e)), 'voice-failed')
      } else notes.push(t('עיבוד הסאונד נכשל, והסרטון יישמר עם הסאונד המקורי: %s', message(e)))
    }
    stepNo++
  }

  // Cuts: the pauses, then the filler words.
  let cuts: Range[] = []
  if (s.cutSilences || s.cutFillers) {
    within('sound', 0)
    if (s.cutSilences) {
      try {
        const ranges = await findPauses(file, s.minPauseMs / 1000, words.map((w): Range => [w.start, Math.max(w.end, w.start + 0.08)]), signal)
        cuts = addCuts(cuts, ranges).ranges
      } catch (e) {
        if (e instanceof Aborted) throw e
        notes.push((e as StepError).code === 'undecodable' ? t('לא ניתן לפענח את הסאונד של הסרטון, ולכן לא נמצאו שתיקות') : t('אין סאונד בסרטון'))
      }
    }
    if (s.cutFillers) {
      if (words.length === 0) notes.push(t('אין כתוביות, אז לא נחתכו מילות מילוי'))
      else cuts = addCuts(cuts, words.filter((w) => isFiller(w.text)).map((w): Range => [w.start, Math.max(w.end, w.start + 0.08)])).ranges
    }
    stepNo++
  }

  // The people (for the background) and the camera's motion (to steady): one pass over the video.
  const people = s.background.fill !== 'none' && s.background.key === 'person'
  let picture: Processed['picture'] = null
  let times = analysis.times
  if (people || s.stabilize > 0) {
    within('picture', 0)
    try {
      const r = await picturePass(file, times, people, (p) => within('picture', p), signal)
      picture = { motion: r.motion, cuts: r.cuts, masks: r.masks }
      if (!times.length) { times = r.times; analysis = { ...analysis, times: r.times, fps: r.fps } }
    } catch (e) {
      if (e instanceof Aborted) throw e
      if (people) notes.push(t('זיהוי האנשים נכשל, אז הרקע לא הוחלף'))
      if (s.stabilize > 0) notes.push(t('הייצוב לא הופעל'))
    }
    stepNo++
  }
  const steadied = picture && s.stabilize > 0 ? steady(picture.motion, picture.cuts, analysis.height / analysis.width, analysis.fps, s.stabilize) : null
  if (picture && s.stabilize > 0 && !steadied) notes.push(t('הייצוב לא הופעל'))
  if (people && !picture?.masks && !notes.includes(t('זיהוי האנשים נכשל, אז הרקע לא הוחלף'))) notes.push(t('זיהוי האנשים נכשל, אז הרקע לא הוחלף'))

  // A frame of another shape follows the faces.
  const [cw, ch] = cropFraction(analysis.width, analysis.height, ASPECT_RATIO[s.aspect])
  const path = s.aspect !== 'original' && times.length ? reframePath(times.length, tracks, analysis.cuts ?? picture?.cuts ?? [], analysis.fps, cw, ch) : null

  report('export', base + sh.processing)
  return {
    analysis, tracks, words, cues, cuts, voice, picture, steady: steadied, path, notes, detectInfo,
    thumbs: tracks.map((tr, k): [number, string | null] => [tr.id, thumbs[k] ?? null]),
  }
}

/** The export of [file] with what [p] found, as [s] says. */
export function jobOf(file: File, s: TemplateSettings, p: Processed): ExportJob {
  const style = styleOf(s)
  const bg = s.background
  const usable = bg.fill !== 'none' && (bg.key !== 'person' || !!p.picture?.masks)
  return {
    file, times: p.analysis.times, tracks: p.tracks, look: s.look, width: p.analysis.width, height: p.analysis.height, sfx: [],
    watermark: s.watermark, cues: dropCutWords(p.cues, new CutMap(p.cuts), p.words), subStyle: style, words: p.words, voice: p.voice, anonymize: s.voice !== 'none', cuts: p.cuts, options: exportOptionsOf(s), standalone: true,
    picture: { background: usable ? bg : null, masks: bg.key === 'person' && bg.fill !== 'none' ? p.picture?.masks ?? null : null, steady: p.steady, path: p.path },
  }
}
