// The templates, kept in the browser (IndexedDB), as the projects are: the user's own, and the
// built-in looks' edits (a built-in can be changed; "restore original" drops its edit). The
// database is named per channel (the beta never sees the release's). Each is stored as the
// Android-shaped JSON of lib/template.ts.
import { computed, signal } from '@preact/signals'
import { storeName } from './channel'
import { BUILT_IN, builtIn, copyOf, templateFromJson, templateToJson, type Template } from './template'

const DB = storeName('templates')
type Store = 'user' | 'builtin'
type Row = { json: Record<string, unknown>; at: number }

let db: Promise<IDBDatabase> | null = null
function open(): Promise<IDBDatabase> {
  db ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1)
    r.onupgradeneeded = () => { for (const s of ['user', 'builtin']) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s) }
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => { db = null; reject(r.error) }
  })
  return db
}

const done = (t: IDBTransaction) => new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error ?? new Error('aborted')) })

async function readAll(store: Store): Promise<[string, Row][]> {
  const d = await open()
  const s = d.transaction(store).objectStore(store)
  const [keys, values] = await Promise.all([
    new Promise<IDBValidKey[]>((res, rej) => { const r = s.getAllKeys(); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) }),
    new Promise<Row[]>((res, rej) => { const r = s.getAll(); r.onsuccess = () => res(r.result as Row[]); r.onerror = () => rej(r.error) }),
  ])
  return (keys as string[]).map((k, i) => [k, values[i]])
}

async function put(store: Store, id: string, t: Template) {
  const d = await open()
  const tx = d.transaction(store, 'readwrite')
  tx.objectStore(store).put({ json: templateToJson(t), at: Date.now() } satisfies Row, id)
  await done(tx)
}

async function del(store: Store, id: string) {
  const d = await open()
  const tx = d.transaction(store, 'readwrite')
  tx.objectStore(store).delete(id)
  await done(tx)
}

/** The user's own templates, newest first. */
export const userTemplates = signal<Template[]>([])
/** The built-in looks as changed by the user (by id). */
export const builtInEdits = signal<Record<string, Template>>({})
/** Saving to the browser's storage failed (no room, or no storage): the change lasts until the page closes. */
export const storeFailed = signal(false)
const stored = (p: Promise<void>) => p.then(() => { storeFailed.value = false }, () => { storeFailed.value = true })

/** Loaded from the browser's storage (or failed to: the built-ins still work). */
export const templatesLoaded = signal(false)

let loading: Promise<void> | null = null
/** Reads the stored templates (once; again with [again]). */
export function loadTemplates(again = false): Promise<void> {
  if (loading && !again) return loading
  loading = (async () => {
    try {
      const users = (await readAll('user'))
        .map(([, r]) => ({ t: templateFromJson(r.json), at: r.at }))
        .filter((x): x is { t: Template; at: number } => !!x.t)
        .sort((a, b) => b.at - a.at)
      userTemplates.value = users.map((x) => ({ ...x.t, group: 'USER', builtIn: false }))
      const edits: Record<string, Template> = {}
      for (const [id, r] of await readAll('builtin')) {
        const t = templateFromJson(r.json)
        const base = builtIn(id)
        if (t && base) edits[id] = { ...t, id, name: base.name, group: 'LOOK', builtIn: true }
      }
      builtInEdits.value = edits
    } catch {
      // No storage (a private window): templates last until the page closes.
    }
    templatesLoaded.value = true
  })()
  return loading
}

/** The built-in looks, with their edits. */
export const lookTemplates = computed<Template[]>(() => BUILT_IN.map((b) => builtInEdits.value[b.id] ?? b))

/** The user's templates, then the built-in looks. */
export const allTemplates = computed<Template[]>(() => [...userTemplates.value, ...lookTemplates.value])

export const findTemplate = (id: string | null | undefined): Template | null => (id ? allTemplates.value.find((t) => t.id === id) ?? null : null)

/** A built-in look has been changed. */
export const isEdited = (id: string) => id in builtInEdits.value

export const newTemplateId = () => 'u' + Math.random().toString(36).slice(2, 10)

/** Saves [t]: a user's template as it is; a built-in look as an edit of it. */
export async function saveTemplate(t: Template): Promise<void> {
  if (t.builtIn) {
    builtInEdits.value = { ...builtInEdits.value, [t.id]: t }
    await stored(put('builtin', t.id, t))
    return
  }
  const own: Template = { ...t, group: 'USER', builtIn: false, name: t.name.trim().slice(0, 60) || t.name }
  userTemplates.value = [own, ...userTemplates.value.filter((x) => x.id !== own.id)]
  await stored(put('user', own.id, own))
}

export async function deleteTemplate(id: string): Promise<void> {
  userTemplates.value = userTemplates.value.filter((x) => x.id !== id)
  await stored(del('user', id))
}

/** A built-in look back to how it shipped. */
export async function restoreTemplate(id: string): Promise<void> {
  const { [id]: _gone, ...rest } = builtInEdits.value
  builtInEdits.value = rest
  await stored(del('builtin', id))
}

export async function renameTemplate(id: string, name: string): Promise<void> {
  const t = userTemplates.value.find((x) => x.id === id)
  const n = name.trim().slice(0, 60)
  if (!t || !n || n === t.name) return
  // The list keeps its order (a rename isn't a new template).
  const own: Template = { ...t, name: n }
  userTemplates.value = userTemplates.value.map((x) => (x.id === id ? own : x))
  await stored(put('user', id, own))
}

/** A copy of [id] (a built-in too) as the user's own, named [name]. */
export async function duplicateTemplate(id: string, name: string): Promise<Template | null> {
  const src = findTemplate(id)
  if (!src) return null
  const copy = copyOf(src, newTemplateId(), name)
  await saveTemplate(copy)
  return copy
}
