// The watermark's logo picture, kept in the browser (IndexedDB) between visits, as the templates keep
// theirs. The watermark's other choices stay in localStorage (see loadWatermark); the picture is too
// big for it. It is read once at start-up, so loadWatermark can stay synchronous.
import { storeName } from './channel'

const DB = storeName('logo')
const KEY = 'last'
let cached: string | null = null
let db: Promise<IDBDatabase> | null = null

function open(): Promise<IDBDatabase> {
  db ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1)
    r.onupgradeneeded = () => { if (!r.result.objectStoreNames.contains('logo')) r.result.createObjectStore('logo') }
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => { db = null; reject(r.error) }
  })
  return db
}

/** Reads the saved logo (a data URL) into memory; never fails (no storage: no logo). */
export async function loadLogo(): Promise<void> {
  try {
    const d = await open()
    cached = await new Promise<string | null>((res, rej) => {
      const r = d.transaction('logo').objectStore('logo').get(KEY)
      r.onsuccess = () => res(typeof r.result === 'string' ? r.result : null)
      r.onerror = () => rej(r.error)
    })
  } catch {
    // A private window: the logo lasts until the page closes.
  }
}

/** The logo saved last (a data URL), or none. */
export const lastLogo = () => cached

/** Keeps [dataUrl] as the logo (null: forgets it). */
export async function saveLogo(dataUrl: string | null): Promise<void> {
  cached = dataUrl
  try {
    const d = await open()
    const tx = d.transaction('logo', 'readwrite')
    if (dataUrl) tx.objectStore('logo').put(dataUrl, KEY)
    else tx.objectStore('logo').delete(KEY)
    await new Promise<void>((res, rej) => { tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); tx.onabort = () => rej(tx.error) })
  } catch {
    // No room, or no storage: it lasts until the page closes.
  }
}

/** The longest side a kept logo has (a watermark is small; a phone photo would fill the store). */
const MAX_SIDE = 512

/** A picked picture as a data URL (it outlives the page: an object URL does not), shrunk to [MAX_SIDE] when bigger. */
export async function readLogo(f: Blob): Promise<string> {
  const raw = await new Promise<string>((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(r.error)
    r.readAsDataURL(f)
  })
  try {
    const img = await new Promise<HTMLImageElement>((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = () => rej(new Error('image')); i.src = raw })
    const k = MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight)
    if (!(k < 1)) return raw
    const c = document.createElement('canvas')
    c.width = Math.max(1, Math.round(img.naturalWidth * k)); c.height = Math.max(1, Math.round(img.naturalHeight * k))
    c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height)
    // PNG keeps a logo's transparency.
    return c.toDataURL('image/png')
  } catch {
    return raw
  }
}
