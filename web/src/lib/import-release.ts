// The beta only (see ui/Beta.tsx): copies projects from the release web app into the beta's own
// database. The two share an origin, so the beta can read the release's database; it only reads
// it. It's opened without a version (so the browser never upgrades it), never created when it
// isn't there, read in read-only transactions and closed right after. Each copy is a new project
// with a new id: nothing in either app is replaced.
//
// Older release databases have fewer stores (fonts came with version 2, sounds with 3) and the
// same records: what isn't there is skipped. A record's fields are normalized when the project is
// opened, as for any project of the beta's own. A newer database than this build knows is refused.
import { RELEASE_DB } from './channel'
import { addProjectCopy, DB_VERSION, FONTS, SOUNDS, STORES, type ProjectMeta, type ProjectRecords } from './projects'
import { USER_SFX } from './sfx'

/** The release keeps its projects in a newer format than this beta knows. */
export class ReleaseTooNew extends Error {}

const req = <T>(r: IDBRequest<T>) => new Promise<T>((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error) })

/** The release's database, read only, or null when there is none. Close it after use. */
async function openRelease(): Promise<IDBDatabase | null> {
  // Where the browser lists its databases: don't open one that isn't there.
  if (typeof indexedDB.databases === 'function') {
    const all = await indexedDB.databases().catch(() => null)
    if (all && !all.some((d) => d.name === RELEASE_DB)) return null
  }
  const db = await new Promise<IDBDatabase | null>((resolve, reject) => {
    let missing = false
    const r = indexedDB.open(RELEASE_DB)
    // Without a version this runs only when there is no such database: cancel, so none is made.
    r.onupgradeneeded = () => { missing = true; r.transaction?.abort() }
    r.onsuccess = () => resolve(r.result)
    r.onerror = (e) => { e.preventDefault(); if (missing) resolve(null); else reject(r.error) }
  })
  if (!db) return null
  // The release asking for a newer version while this is open: let it.
  db.onversionchange = () => db.close()
  if (db.version > DB_VERSION) { db.close(); throw new ReleaseTooNew(`release database version ${db.version}`) }
  return db
}

const has = (db: IDBDatabase, store: string) => db.objectStoreNames.contains(store)

/** A release project as listed: [video] false when its video is missing (it can't be copied). */
export type ReleaseProject = ProjectMeta & { video: boolean }

/** The release's projects, newest first. Throws [ReleaseTooNew]. */
export async function listReleaseProjects(): Promise<ReleaseProject[]> {
  const db = await openRelease()
  if (!db) return []
  try {
    if (!has(db, 'meta')) return []
    const t = db.transaction(has(db, 'video') ? ['meta', 'video'] : ['meta'], 'readonly')
    const [all, videos] = await Promise.all([
      req(t.objectStore('meta').getAll()) as Promise<ProjectMeta[]>,
      has(db, 'video') ? (req(t.objectStore('video').getAllKeys()) as Promise<IDBValidKey[]>) : Promise.resolve([]),
    ])
    const withVideo = new Set(videos.map(String))
    return all.map((m) => ({ ...m, video: withVideo.has(m.id) })).sort((a, b) => b.updated - a.updated)
  } finally {
    db.close()
  }
}

type Read = { project: ProjectRecords; fonts: [string, ArrayBuffer][]; sounds: [string, ArrayBuffer][] }

/** One release project's records, and the imported fonts and sounds it uses. */
async function readProject(db: IDBDatabase, id: string): Promise<Read | null> {
  const stores = STORES.filter((s) => has(db, s))
  if (!stores.includes('meta') || !stores.includes('video')) return null
  const t = db.transaction(stores, 'readonly')
  const get = <T>(s: string) => (stores.includes(s as never) ? req(t.objectStore(s).get(id)) as Promise<T | undefined> : Promise.resolve(undefined))
  const [meta, state, picture, video] = await Promise.all([get<ProjectRecords['meta']>('meta'), get<ProjectRecords['state']>('state'), get<ProjectRecords['picture']>('picture'), get<File>('video')])
  if (!meta || !video) return null
  const project: ProjectRecords = { meta, state: state ?? null, picture: picture ?? null, video }
  const extra = async (store: string, names: string[]) => {
    if (!names.length || !has(db, store)) return []
    const s = db.transaction(store, 'readonly').objectStore(store)
    const data = await Promise.all(names.map((n) => req(s.get(n)) as Promise<ArrayBuffer | undefined>))
    return names.flatMap((n, i) => (data[i] ? [[n, data[i]] as [string, ArrayBuffer]] : []))
  }
  const font = state?.subStyle?.customFont
  const sounds = [...new Set((state?.sfx ?? []).map((c) => c.sound).filter((s) => typeof s === 'string' && s.startsWith(USER_SFX)).map((s) => s.slice(USER_SFX.length)))]
  return { project, fonts: await extra(FONTS, typeof font === 'string' ? [font] : []), sounds: await extra(SOUNDS, sounds) }
}

/**
 * What an import did: the ids copied, the ones that weren't (with why, for the log), and whether
 * it stopped because the release's database turned out newer than this build knows.
 */
export type ImportResult = { copied: string[]; failed: { id: string; error: string }[]; tooNew: boolean }

/**
 * Copies the release's projects [ids] into the beta, each as a new project named "[name] [suffix]"
 * (with the imported fonts and sounds it uses, unless the beta has ones of the same name), each
 * whole or not at all. One that fails doesn't stop the others; a release database newer than
 * this build knows stops the import ([ImportResult.tooNew]), with what was copied before it.
 */
export async function importReleaseProjects(ids: string[], suffix: string): Promise<ImportResult> {
  const result: ImportResult = { copied: [], failed: [], tooNew: false }
  for (const id of ids) {
    try {
      // One at a time, the release's database open only while reading it.
      const db = await openRelease()
      if (!db) throw new Error('no release database')
      let read: Read | null
      try {
        read = await readProject(db, id)
      } finally {
        db.close()
      }
      if (!read) throw new Error('missing')
      await addProjectCopy(read.project, `${read.project.meta.name} ${suffix}`, read.fonts, read.sounds)
      result.copied.push(id)
    } catch (e) {
      if (e instanceof ReleaseTooNew) { result.tooNew = true; break }
      result.failed.push({ id, error: String((e as Error)?.message ?? e) })
    }
  }
  return result
}
