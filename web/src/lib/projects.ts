// Projects, as in the app: every video opened is kept in the browser (IndexedDB) while it's
// edited - the video itself, what was found in it and every edit - so it can be opened again and
// picked up where it was left. Nothing leaves the device.
import type { Analysis, Look, Track } from './types'
import type { Cue, SubStyle, Word } from './subtitles'
import type { SfxClip } from './sfx'
import type { Watermark } from './watermark'
import type { VoiceFx } from './voice'
import type { Range } from './cutmap'
import type { Background, PeopleMasks } from './background'
import type { Aspect } from './camera'
import { storeName } from './channel'

export type ProjectMeta = {
  id: string
  name: string
  created: number
  updated: number
  /** Seconds. */
  duration: number
  blurs: number
  cues: number
  /** Space it takes in the browser (mostly the video). */
  bytes: number
  /** A small picture of it (as edited, so faces are blurred). */
  thumb: string | null
  /** Faces were searched for. */
  detected: boolean
  // What else the list shows (from [summarize]). Missing in projects saved before it was kept
  // (and in the release's ones the beta copies): [listProjects] fills it in from their edits.
  /** Words bleeped. */
  bleeped?: number
  /** The voice is changed (or muted). */
  voice?: boolean
  /** Sound effects placed. */
  sounds?: number
  /** The background is replaced. */
  background?: boolean
  /** Stretches cut out. */
  cuts?: number
  /** Made by a batch: the projects of one batch are shown as one folder (named by [name], else by the time it started). */
  batch?: { id: string; at: number; name?: string }
}

/** What the projects' list shows of the edits, kept in the meta on every save. */
export type ProjectSummary = Required<Pick<ProjectMeta, 'blurs' | 'cues' | 'detected' | 'bleeped' | 'voice' | 'sounds' | 'background' | 'cuts'>>

/** [ProjectSummary] of a project's edits (fields an older project lacks count as none). */
export function summarize(s: Partial<Pick<ProjectState, 'analyzed' | 'tracks' | 'cues' | 'bleeped' | 'voice' | 'sfx' | 'background' | 'cuts'>>): ProjectSummary {
  return {
    blurs: s.tracks?.length ?? 0,
    cues: s.cues?.length ?? 0,
    detected: !!s.analyzed,
    bleeped: s.bleeped?.length ?? 0,
    voice: !!s.voice && s.voice !== 'none',
    sounds: s.sfx?.length ?? 0,
    background: !!s.background && s.background.fill !== 'none',
    cuts: s.cuts?.length ?? 0,
  }
}

/** Everything edited (structured-cloneable). */
export type ProjectState = {
  analysis: Analysis
  analyzed: boolean
  detectInfo: string
  tracks: Track[]
  thumbs: [number, string | null][]
  off: number[]
  look: Look
  cues: Cue[]
  words: Word[]
  bleeped: number[]
  tone: boolean
  voice: VoiceFx
  sfx: SfxClip[]
  mark: Watermark
  subStyle: SubStyle
  cuts: Range[]
  denoise: number
  enhance: boolean
  loud: boolean
  background: Background
  steady: number
  aspect: Aspect
  /** The safe zone's view aid (see lib/safezone.ts): on or off, and the platform. Missing in projects saved before it was kept. */
  safe?: { on: boolean; platform: 'tiktok' | 'reels' | 'shorts' }
}

/** What the picture pass found (kept apart: the people's masks are big). */
export type ProjectPicture = { motion: Float32Array; cuts: number[]; masks: PeopleMasks | null }

export type LoadedProject = { meta: ProjectMeta; file: File; state: ProjectState | null; picture: ProjectPicture | null }

const DB = storeName('projects')
/** The database's version: what this build reads and writes. */
export const DB_VERSION = 3
export const STORES = ['meta', 'state', 'picture', 'video'] as const
/** Fonts the user imported for subtitles (by name), kept apart from the projects. */
export const FONTS = 'fonts'
/** Sounds the user imported (by name: the file's bytes), as the app's "mine". */
export const SOUNDS = 'sounds'
type Store = (typeof STORES)[number]

let db: Promise<IDBDatabase> | null = null
function open(): Promise<IDBDatabase> {
  db ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, DB_VERSION)
    r.onupgradeneeded = () => { for (const s of [...STORES, FONTS, SOUNDS]) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s) }
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => { db = null; reject(r.error) }
  })
  return db
}

const req = <T>(r: IDBRequest<T>) => new Promise<T>((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error) })

async function tx(stores: Store[], mode: IDBTransactionMode, f: (s: Record<Store, IDBObjectStore>) => void): Promise<void> {
  const d = await open()
  const t = d.transaction(stores, mode)
  const map = Object.fromEntries(stores.map((s) => [s, t.objectStore(s)])) as Record<Store, IDBObjectStore>
  f(map)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error ?? new Error('aborted')) })
}

async function get<T>(store: Store, id: string): Promise<T | undefined> {
  const d = await open()
  return req(d.transaction(store).objectStore(store).get(id)) as Promise<T | undefined>
}

export async function listProjects(): Promise<ProjectMeta[]> {
  try {
    const d = await open()
    const all = (await req(d.transaction('meta').objectStore('meta').getAll())) as ProjectMeta[]
    const old = all.filter((m) => m.sounds === undefined)
    if (old.length) await fillSummaries(d, old).catch(() => {})
    return all.sort((a, b) => b.updated - a.updated)
  } catch {
    return []
  }
}

/**
 * Projects saved before their meta kept a [ProjectSummary] (or copied from such a release
 * project): filled in [old] from their edits, and kept, once (a meta changed meanwhile is left as
 * it is). Blurs, subtitles and the face search stay as the meta has them.
 */
async function fillSummaries(d: IDBDatabase, old: ProjectMeta[]) {
  const t = d.transaction(['meta', 'state'], 'readwrite')
  const metas = t.objectStore('meta')
  const states = t.objectStore('state')
  for (const m of old) {
    const r = states.get(m.id)
    r.onsuccess = () => {
      const state = r.result as ProjectState | undefined
      const { bleeped, voice, sounds, background, cuts } = summarize(state ?? {})
      Object.assign(m, { bleeped, voice, sounds, background, cuts })
      const cur = metas.get(m.id)
      cur.onsuccess = () => { if (cur.result && cur.result.sounds === undefined) metas.put({ ...cur.result, bleeped, voice, sounds, background, cuts }, m.id) }
    }
  }
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error ?? new Error('aborted')) })
}

/** A new project for [file]; its id. Asks the browser to keep the site's data. */
export async function createProject(file: File, meta: Omit<ProjectMeta, 'id' | 'created' | 'updated' | 'bytes'>): Promise<string> {
  navigator.storage?.persist?.().catch(() => {})
  const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const now = Date.now()
  const m: ProjectMeta = { ...meta, id, created: now, updated: now, bytes: file.size }
  await tx(['meta', 'video'], 'readwrite', (s) => {
    s.video.put(file, id)
    s.meta.put(m, id)
  })
  return id
}

/** Saves the edits ([picture] too when given); [patch] updates the listing. */
export async function saveProject(id: string, patch: Partial<ProjectMeta>, state: ProjectState, picture?: ProjectPicture | null) {
  const stores: Store[] = picture !== undefined ? ['meta', 'state', 'picture'] : ['meta', 'state']
  const old = await get<ProjectMeta>('meta', id)
  if (!old) return
  let extra = 0
  if (picture?.masks) extra += picture.masks.data.byteLength
  await tx(stores, 'readwrite', (s) => {
    s.state.put(state, id)
    if (picture !== undefined) {
      if (picture) s.picture.put(picture, id)
      else s.picture.delete(id)
    }
    s.meta.put({ ...old, ...patch, updated: Date.now(), bytes: Math.max(old.bytes, (patch.bytes ?? old.bytes) + extra) }, id)
  })
}

export async function loadProject(id: string): Promise<LoadedProject | null> {
  const meta = await get<ProjectMeta>('meta', id)
  const file = await get<File>('video', id)
  if (!meta || !file) return null
  const state = (await get<ProjectState>('state', id)) ?? null
  const picture = (await get<ProjectPicture>('picture', id)) ?? null
  return { meta, file, state, picture }
}

/** A project's every record, as kept (for a copy). */
export type ProjectRecords = { meta: ProjectMeta; state: ProjectState | null; picture: ProjectPicture | null; video: File }

/**
 * A new project from another one's records (the beta's copy of a release project): a new id and
 * [name], its own copy of every record, and the imported [fonts] and [sounds] it uses (kept unless
 * ones of the same name are). All in one transaction: on a failure nothing is kept. Never replaces
 * a project. Its id.
 */
export async function addProjectCopy(p: ProjectRecords, name: string, fonts: [string, ArrayBuffer][], sounds: [string, ArrayBuffer][]): Promise<string> {
  navigator.storage?.persist?.().catch(() => {})
  const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const d = await open()
  const t = d.transaction([...STORES, FONTS, SOUNDS], 'readwrite')
  const addIfMissing = (store: string, key: string, data: ArrayBuffer) => {
    const s = t.objectStore(store)
    const r = s.getKey(key)
    r.onsuccess = () => { if (r.result === undefined) s.add(data, key) }
  }
  for (const [n, data] of fonts) addIfMissing(FONTS, n, data)
  for (const [n, data] of sounds) addIfMissing(SOUNDS, n, data)
  t.objectStore('video').add(p.video, id)
  if (p.state) t.objectStore('state').add(p.state, id)
  if (p.picture) t.objectStore('picture').add(p.picture, id)
  t.objectStore('meta').add({ ...p.meta, id, name, updated: Date.now() }, id)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error ?? new Error('aborted')) })
  return id
}

export async function renameProject(id: string, name: string) {
  const m = await get<ProjectMeta>('meta', id)
  if (m) await tx(['meta'], 'readwrite', (s) => s.meta.put({ ...m, name }, id))
}

/** Renames the folder of batch [id] (its projects carry the name). */
export async function renameBatch(id: string, name: string) {
  const all = (await listProjects()).filter((m) => m.batch?.id === id)
  if (all.length) await tx(['meta'], 'readwrite', (s) => { for (const m of all) s.meta.put({ ...m, batch: { ...m.batch!, name } }, m.id) })
}

/** The name given to the folder of batch [id] (undefined: none). */
export async function batchName(id: string): Promise<string | undefined> {
  return (await listProjects()).find((m) => m.batch?.id === id && m.batch.name)?.batch?.name
}

/** Deletes every project of batch [id] (not the videos saved to the device). */
export async function deleteBatch(id: string) {
  const all = (await listProjects()).filter((m) => m.batch?.id === id)
  if (all.length) await tx([...STORES], 'readwrite', (s) => { for (const m of all) for (const k of STORES) s[k].delete(m.id) })
}

export async function deleteProject(id: string) {
  await tx([...STORES], 'readwrite', (s) => { for (const k of STORES) s[k].delete(id) })
}

// ------------------------------------------------------------------ imported fonts

export async function listFonts(): Promise<{ name: string; data: ArrayBuffer }[]> {
  try {
    const d = await open()
    const store = d.transaction(FONTS).objectStore(FONTS)
    const [keys, values] = await Promise.all([req(store.getAllKeys()), req(store.getAll())])
    return (keys as string[]).map((name, i) => ({ name, data: values[i] as ArrayBuffer }))
  } catch {
    return []
  }
}

export async function saveFont(name: string, data: ArrayBuffer) {
  const d = await open()
  const t = d.transaction(FONTS, 'readwrite')
  t.objectStore(FONTS).put(data, name)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error) })
}

export async function deleteFont(name: string) {
  const d = await open()
  const t = d.transaction(FONTS, 'readwrite')
  t.objectStore(FONTS).delete(name)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error) })
}

export async function getFont(name: string): Promise<ArrayBuffer | null> {
  try {
    const d = await open()
    return ((await req(d.transaction(FONTS).objectStore(FONTS).get(name))) as ArrayBuffer | undefined) ?? null
  } catch {
    return null
  }
}

// ------------------------------------------------------------------ imported sounds

export async function listSounds(): Promise<string[]> {
  try {
    const d = await open()
    return ((await req(d.transaction(SOUNDS).objectStore(SOUNDS).getAllKeys())) as string[]).sort()
  } catch {
    return []
  }
}

export async function saveSound(name: string, data: ArrayBuffer) {
  const d = await open()
  const t = d.transaction(SOUNDS, 'readwrite')
  t.objectStore(SOUNDS).put(data, name)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error) })
}

export async function deleteSound(name: string) {
  const d = await open()
  const t = d.transaction(SOUNDS, 'readwrite')
  t.objectStore(SOUNDS).delete(name)
  await new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error) })
}

export async function getSound(name: string): Promise<ArrayBuffer | null> {
  try {
    const d = await open()
    return ((await req(d.transaction(SOUNDS).objectStore(SOUNDS).get(name))) as ArrayBuffer | undefined) ?? null
  } catch {
    return null
  }
}
