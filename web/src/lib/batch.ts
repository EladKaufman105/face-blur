// Batch processing, as the Android app's (docs/specs/templates-and-batch.md): up to 10 videos,
// one template, processed one after another in the workers. One failure doesn't stop the rest;
// every video is exported and kept as a project in a folder (the batch). The tab has to stay
// open: the screen is kept awake and leaving the page asks first.
import { signal } from '@preact/signals'
import { storageKey } from './channel'
import { t } from '../i18n'
import { Aborted, exportVideo, jobOf, processVideo, type Phase, type Processed } from './templaterun'
import { hasProcessing, styleOf, type TemplateSettings } from './template'
import { batchName, createProject, saveProject, deleteProject, summarize, type ProjectPicture, type ProjectState } from './projects'
import { keepAwake } from './wakelock'
import { cutCues, toSrt } from './subtitles'
import { CutMap } from './cutmap'

export const MAX_VIDEOS = 10

export type ItemStatus = 'waiting' | 'faces' | 'processing' | 'exporting' | 'saved' | 'failed' | 'nofaces' | 'skipped'
const DONE: ItemStatus[] = ['saved', 'failed', 'nofaces', 'skipped']

export type BatchOutput = { name: string; blob: Blob; url: string; srt: { name: string; url: string } | null }

export type BatchItem = {
  index: number
  file: File
  name: string
  /** Seconds (0 until known). */
  duration: number
  status: ItemStatus
  phase: Phase | null
  /** The model's download progress and the like. */
  detail?: string
  /** 0..1 over the whole video's work. */
  progress: number
  error?: string
  /** Things that could not be done (the video was still saved). */
  notes: string[]
  projectId?: string
  output?: BatchOutput
  startedAt: number
  endedAt: number
  controller: AbortController
}

export type Batch = {
  id: string
  at: number
  items: BatchItem[]
  settings: TemplateSettings
  templateName: string
  /** The projects are kept in a folder (not for a single video saved straight away). */
  folder: boolean
  autoDownload: boolean
  finished: boolean
  cancelled: boolean
}

/** The batch of this run of the page (its results are also kept as projects). */
export const current = signal<Batch | null>(null)
/** Bumped on every change of the batch's items (they're plain objects). */
export const version = signal(0)
const bump = () => { version.value++ }

export const running = () => !!current.value && !current.value.finished

export const isDone = (i: BatchItem) => DONE.includes(i.status)
export const doneCount = (b: Batch) => b.items.filter(isDone).length
export const savedCount = (b: Batch) => b.items.filter((i) => !!i.output).length
export const failedCount = (b: Batch) => b.items.filter((i) => i.status === 'failed').length
/** Worth a look: no faces found, a step that didn't work out, or failed. */
export const needsCheck = (i: BatchItem) => i.status === 'failed' || i.status === 'nofaces' || (i.notes.length > 0 && i.status !== 'skipped')
export const checkCount = (b: Batch) => b.items.filter(needsCheck).length
export const progressOf = (b: Batch) => (b.items.length ? b.items.reduce((s, i) => s + (isDone(i) ? 1 : i.progress), 0) / b.items.length : 1)

// ------------------------------------------------------------------ the time it takes

/** Learned speeds of this device: analysis time per video time, render time per analysis time, speech recognition time per audio time. */
export type Speeds = { analysis: number; render: number; subs: number }
const DEFAULT_SPEEDS: Speeds = { analysis: 1.2, render: 0.7, subs: 0.8 }

export function loadSpeeds(): Speeds {
  try {
    const s = JSON.parse(localStorage.getItem(storageKey('speeds')) ?? 'null')
    if (s && typeof s.analysis === 'number') return { ...DEFAULT_SPEEDS, ...s }
  } catch {}
  return DEFAULT_SPEEDS
}

function learn(patch: Partial<Speeds>) {
  // Each new measure moves the learned one halfway (a video's own oddities don't take over).
  const s = loadSpeeds()
  const next = { ...s }
  for (const k of Object.keys(patch) as (keyof Speeds)[]) if (patch[k]! > 0 && isFinite(patch[k]!)) next[k] = (s[k] + patch[k]!) / 2
  try { localStorage.setItem(storageKey('speeds'), JSON.stringify(next)) } catch {}
}

const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v))
const OVERHEAD_MS = 4000

/** Estimated working time of one video of [ms], in ms. */
export function videoMs(ms: number, s: TemplateSettings, sp: Speeds): number {
  const analysis = ms * clamp(sp.analysis, 0.1, 20)
  const first = s.blurOff ? ms * 0.02 : analysis
  const render = (s.blurOff ? ms * 0.6 : analysis) * clamp(sp.render, 0.2, 5)
  let extra = 0
  if (s.createSubs) extra += ms * clamp(sp.subs, 0.05, 10)
  if (s.loud || s.denoise > 0 || s.enhance || s.voice !== 'none') extra += ms * 0.05
  if (s.cutSilences) extra += ms * 0.03
  if (s.background.fill !== 'none' && s.background.key === 'person') extra += (s.blurOff ? ms : analysis) * 0.5
  if (s.stabilize > 0 && s.blurOff) extra += ms * 0.4
  return first + render + extra + OVERHEAD_MS
}

/**
 * Time left for the whole batch, in ms. A video not started takes its estimate, scaled by how
 * far off the estimates were on the videos done; the one running mixes its own pace so far with
 * its estimate, trusting the pace more the further along it is.
 */
export function remainingMs(b: Batch, sp: Speeds, now = performance.now()): number {
  let est = 0, actual = 0
  for (const i of b.items) if (i.status === 'saved' || i.status === 'nofaces') {
    est += videoMs(i.duration * 1000, b.settings, sp)
    actual += i.endedAt - i.startedAt
  }
  const scale = est > 0 ? clamp(actual / est, 0.4, 2.5) : 1
  let total = 0
  for (const i of b.items) {
    if (isDone(i)) continue
    const e = videoMs(i.duration * 1000, b.settings, sp) * scale
    if (i.status === 'waiting') { total += e; continue }
    const p = clamp(i.progress, 0, 1)
    const byPace = p > 0.02 ? ((now - i.startedAt) * (1 - p)) / p : e * (1 - p)
    const w = clamp(p / 0.3, 0, 1)
    total += w * byPace + (1 - w) * e * (1 - p)
  }
  return total
}

/** How long the videos of [durations] (seconds) take. */
export const totalMs = (durations: number[], s: TemplateSettings, sp = loadSpeeds()) => durations.reduce((a, d) => a + videoMs(d * 1000, s, sp), 0)

/** The time left as words: "About 3 minutes left". */
export function remainingText(ms: number): string {
  const min = Math.ceil(ms / 60000)
  if (ms < 45000) return t('נותרה פחות מדקה')
  if (min < 60) return t('נותרו כ-%d דקות', min)
  return t('נותרו כ-%s שעות', `${Math.floor(min / 60)}:${String(min % 60).padStart(2, '0')}`)
}

/** A time as words: "About 3 minutes". */
export function estimateText(ms: number): string {
  const min = Math.max(1, Math.ceil(ms / 60000))
  if (min < 60) return t('כ-%d דקות', min)
  return t('כ-%s שעות', `${Math.floor(min / 60)}:${String(min % 60).padStart(2, '0')}`)
}

/** The length of a video (seconds), from the browser's own player. */
export function videoDuration(file: File): Promise<number> {
  return new Promise((resolve) => {
    const v = document.createElement('video')
    const url = URL.createObjectURL(file)
    const done = (d: number) => { URL.revokeObjectURL(url); resolve(isFinite(d) ? d : 0) }
    v.preload = 'metadata'
    // A file the player never answers for must not hold a batch up: 0 (unknown) after a while.
    const timer = setTimeout(() => done(0), 8000)
    const end = (d: number) => { clearTimeout(timer); done(d) }
    v.onloadedmetadata = () => end(v.duration)
    v.onerror = () => end(0)
    v.src = url
  })
}

/** Whether the speech model's files are already in the browser (so subtitles need no download). */
export async function speechModelCached(): Promise<boolean> {
  try {
    if (!('caches' in window) || !(await caches.has('transformers-cache'))) return false
    const c = await caches.open('transformers-cache')
    return (await c.keys()).some((r) => r.url.includes('whisper-large-v3-turbo'))
  } catch {
    return false
  }
}

/** Space the batch needs in the browser's storage (each video is kept as a project) with a margin, and what's free (null: unknown). */
export async function storageCheck(files: File[]): Promise<{ need: number; free: number | null }> {
  const need = Math.round(files.reduce((s, f) => s + f.size, 0) * 1.3)
  try {
    const e = await navigator.storage?.estimate?.()
    if (e?.quota !== undefined && e.usage !== undefined) return { need, free: e.quota - e.usage }
  } catch {}
  return { need, free: null }
}

// ------------------------------------------------------------------ running

let release: (() => void) | null = null
const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }

function stopHolding() {
  release?.()
  release = null
  window.removeEventListener('beforeunload', warn)
}

/** Whether a template did something the video's export shows (a video without faces is still worth saving then). */
function worthSaving(s: TemplateSettings): boolean {
  return hasProcessing(s) || s.aspect !== 'original' || s.shortSide > 0 || s.fps > 0 || s.quality !== 'original' || s.codec !== 'same' || !!s.watermark.text.trim() || !!s.watermark.image
}

/** Starts processing [files] with [settings] ([templateName]: what it's called in the queue). Returns at once; the work goes on. */
export function startBatch(files: File[], settings: TemplateSettings, templateName: string, opts: { folder: boolean; autoDownload: boolean }): Batch {
  const at = Date.now()
  // A finished batch being replaced: its saved videos' links go.
  if (current.value?.finished) releaseOutputs(current.value)
  const b: Batch = {
    id: `${at.toString(36)}-${Math.random().toString(36).slice(2, 8)}`, at, settings, templateName, folder: opts.folder, autoDownload: opts.autoDownload, finished: false, cancelled: false,
    items: files.slice(0, MAX_VIDEOS).map((file, index) => ({
      index, file, name: file.name, duration: 0, status: 'waiting', phase: null, progress: 0, notes: [], startedAt: 0, endedAt: 0, controller: new AbortController(),
    })),
  }
  current.value = b
  bump()
  release = keepAwake()
  window.addEventListener('beforeunload', warn)
  void runBatch(b)
  return b
}

async function runBatch(b: Batch) {
  try {
    // Lengths first: the time left needs them.
    await Promise.all(b.items.map(async (i) => { i.duration = await videoDuration(i.file) })).catch(() => {})
    bump()
    for (const item of b.items) {
      if (b.cancelled) { if (item.status === 'waiting') item.status = 'skipped'; continue }
      if (item.status === 'skipped') continue
      await processOne(b, item)
      bump()
    }
  } finally {
    for (const i of b.items) if (!isDone(i)) i.status = 'failed'
    b.finished = true
    stopHolding()
    bump()
  }
}

/** A small picture of a frame, for the project's thumbnail. */
function thumbOf(bitmap: ImageBitmap): string | null {
  try {
    const c = document.createElement('canvas')
    c.width = 160
    c.height = Math.max(1, Math.round((160 * bitmap.height) / bitmap.width))
    c.getContext('2d')!.drawImage(bitmap, 0, 0, c.width, c.height)
    return c.toDataURL('image/jpeg', 0.7)
  } catch {
    return null
  }
}

/** The project's state as the editor would keep it after the template's processing. */
function stateOf(s: TemplateSettings, p: Processed): ProjectState {
  return {
    analysis: p.analysis, analyzed: !s.blurOff, detectInfo: p.detectInfo, tracks: p.tracks, thumbs: p.thumbs, off: [], look: s.look, cues: p.cues, words: p.words,
    bleeped: [], tone: true, voice: s.voice, sfx: [], mark: s.watermark, subStyle: styleOf(s), cuts: p.cuts, denoise: s.denoise, enhance: s.enhance, loud: s.loud,
    background: s.background, steady: s.stabilize, aspect: s.aspect,
  }
}

async function processOne(b: Batch, item: BatchItem) {
  const s = b.settings
  item.startedAt = performance.now()
  item.status = s.blurOff ? 'processing' : 'faces'
  let id: string | undefined
  // How long each part took, for the learned speeds.
  const spent: Partial<Record<Phase, number>> = {}
  let last: { phase: Phase; at: number } = { phase: 'faces', at: performance.now() }
  const mark = (phase: Phase) => {
    const now = performance.now()
    spent[last.phase] = (spent[last.phase] ?? 0) + (now - last.at)
    last = { phase, at: now }
  }
  try {
    const p = await processVideo(item.file, s, (phase, progress, detail) => {
      if (phase !== last.phase) mark(phase)
      item.phase = phase
      item.detail = detail
      item.progress = progress
      item.status = phase === 'faces' ? 'faces' : 'processing'
      bump()
    }, item.controller.signal)
    mark('export')
    item.notes = p.notes
    const state = stateOf(s, p)
    const picture: ProjectPicture | null = p.picture ? { motion: p.picture.motion, cuts: p.picture.cuts, masks: p.picture.masks } : null
    const meta = (thumb: string | null) => ({ duration: p.analysis.times.length ? p.analysis.times[p.analysis.times.length - 1] : item.duration, thumb, ...summarize(state) })
    // The project exists from here on, so a failure later can still be opened in the editor.
    try {
      // The folder's name, if it has been renamed while the batch runs.
      const folderName = b.folder ? await batchName(b.id).catch(() => undefined) : undefined
      id = await createProject(item.file, { name: item.name.replace(/\.[^.]+$/, ''), ...meta(null), ...(b.folder ? { batch: { id: b.id, at: b.at, ...(folderName ? { name: folderName } : {}) } } : {}) })
      await saveProject(id, {}, state, picture ?? undefined)
      item.projectId = id
    } catch {
      id = undefined
      item.notes = [...item.notes, t('אין מקום לשמור את הפרויקט בדפדפן. אפשר להמשיך לערוך ולייצא.')]
    }
    const noFaces = !s.blurOff && p.tracks.length === 0
    if (noFaces && !worthSaving(s)) {
      // Nothing would change in the video: no copy is saved; the project is there to open.
      item.status = 'nofaces'
      item.progress = 1
      return
    }
    item.status = 'exporting'
    item.phase = 'export'
    item.detail = undefined
    const exportFrom = item.progress
    let thumb: string | null = null
    const r = await exportVideo(jobOf(item.file, s, p), (pr) => { item.progress = exportFrom + pr * (1 - exportFrom); bump() }, (bm) => { thumb = thumbOf(bm); bm.close() }, item.controller.signal)
    mark('export')
    const base = item.name.replace(/\.[^.]+$/, '') + '-onit'
    const url = URL.createObjectURL(r.blob)
    const srt = s.saveSrt && p.cues.length ? new Blob([toSrt(cutCues(p.cues, new CutMap(p.cuts)))], { type: 'application/x-subrip' }) : null
    item.output = { name: base + '.mp4', blob: r.blob, url, srt: srt ? { name: base + '.srt', url: URL.createObjectURL(srt) } : null }
    if (id && thumb) saveProject(id, { thumb }, state).catch(() => {})
    if (b.autoDownload) download(item.output.url, item.output.name)
    item.progress = 1
    item.status = noFaces ? 'nofaces' : 'saved'
    // What this device does, for the next estimate.
    const analysisMs = (spent.faces ?? 0)
    const subsMs = spent.subtitles ?? 0
    learn({
      analysis: !s.blurOff && item.duration > 0 ? analysisMs / (item.duration * 1000) : 0,
      render: !s.blurOff && analysisMs > 0 ? (spent.export ?? 0) / analysisMs : 0,
      subs: s.createSubs && p.cues.length > 0 && item.duration > 0 && !spent.download ? subsMs / (item.duration * 1000) : 0,
    })
  } catch (e) {
    if (e instanceof Aborted) {
      // Skipped or cancelled: the half-made project goes.
      item.status = 'skipped'
      if (id) await deleteProject(id).catch(() => {})
      item.projectId = undefined
    } else {
      item.status = 'failed'
      item.error = friendly(e)
    }
  } finally {
    item.endedAt = performance.now()
    if (!isDone(item)) item.status = 'failed'
    bump()
  }
}

function friendly(e: unknown): string {
  const m = String((e as Error)?.message ?? e)
  if (/decode|codec/i.test(m)) return t('הדפדפן לא מצליח לפענח את הסרטון הזה (%s). נסה Chrome או Safari מעודכנים.', m.replace("can't decode ", '').slice(0, 80))
  if (/memory|alloc/i.test(m)) return t('נגמר הזיכרון. נסה סרטון קצר יותר או סגור לשוניות אחרות.')
  return t('משהו השתבש') + ': ' + m.slice(0, 160)
}

/** Saves a file the way the export screen does: a link that downloads. */
export function download(url: string, name: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  setTimeout(() => a.remove(), 1000)
}

/** Downloads every saved video (one after another: browsers ask before saving many at once). */
export async function downloadAll(b: Batch) {
  for (const i of b.items) {
    if (!i.output) continue
    download(i.output.url, i.output.name)
    await new Promise((r) => setTimeout(r, 700))
  }
}

/** Skips one video: the one waiting never starts, the one running is stopped. */
export function skip(item: BatchItem) {
  if (isDone(item)) return
  if (item.status === 'waiting') { item.status = 'skipped'; item.endedAt = performance.now() } else item.controller.abort()
  bump()
}

/** Stops everything: the video being worked on and all that wait. */
export function cancelAll(b: Batch) {
  b.cancelled = true
  for (const i of b.items) {
    if (i.status === 'waiting') i.status = 'skipped'
    else if (!isDone(i)) i.controller.abort()
  }
  bump()
}

/** Frees a batch's saved outputs (their links hold the whole videos in memory). */
function releaseOutputs(b: Batch) {
  for (const i of b.items) if (i.output) { URL.revokeObjectURL(i.output.url); if (i.output.srt) URL.revokeObjectURL(i.output.srt.url) }
}

/** Forgets a finished batch (frees its saved outputs). */
export function dismiss() {
  const b = current.value
  if (!b || !b.finished) return
  releaseOutputs(b)
  current.value = null
  bump()
}
