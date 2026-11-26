// What went wrong, as the app's ProcessingErrors and CrashLog: a message to act on, and the technical part to copy
// (to send by hand if it keeps happening). Nothing here sends anything anywhere: the details only go to the clipboard.
import { signal } from '@preact/signals'
import { t } from '../i18n'
import { BETA } from './channel'
import { isMemoryError } from './memerr'

declare const __BUILD__: string

/** "ONIT web (build 412) · <browser> · 2026-11-26 10:00:00": what the details start with, as the app's report header. */
export function reportHeader(): string {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`
  return `ONIT${BETA ? ' Beta' : ''} web (build ${__BUILD__}) · ${navigator.userAgent} · ${stamp}`
}

/** The technical details of a failure (a worker's code and message, or an error), with the header. */
export function technical(what: string, e: unknown, code?: string): string {
  const err = e instanceof Error ? `${e.name}: ${e.message}${e.stack ? '\n' + e.stack : ''}` : String(e ?? '')
  return `${reportHeader()}\n${what}${code ? ` [${code}]` : ''}\n\n${err}`.slice(0, 20_000)
}

/** An export's failure (the export worker's code and message), said so that it's clear what to try next. */
export function friendlyExport(code: string, message: string): string {
  if (code === 'anonymize') return t('השינוי בקול או הצפצופים לא הוחלו, ולכן שום דבר לא יוצא (כדי שהקול המקורי לא יישמר)')
  if (code === 'encoder') return t('המכשיר לא הצליח לשמור ברזולוציה הזו. בחר 1080p או 720p בהגדרות הייצוא ונסה שוב.')
  if (code === 'invalid') return t('המכשיר לא הצליח לפענח את הסרטון הזה. נסה רזולוציה נמוכה יותר בהגדרות הייצוא.')
  if (code === 'no-video') return t('לא ניתן לפתוח את הסרטון')
  if (code === 'no-audio') return t('אין סאונד בסרטון')
  if (isMemoryError(message)) return t('אין מספיק זיכרון במכשיר לייצוא הזה. סגור לשוניות אחרות, או בחר רזולוציה נמוכה יותר בהגדרות הייצוא, ונסה שוב.')
  if (/QuotaExceeded|NotReadableError|NotFoundError/i.test(message)) return t('לא הצלחתי לקרוא או לכתוב את הקובץ. בדוק שיש מקום פנוי במכשיר ונסה שוב.')
  return t('משהו השתבש בעיבוד. נסה שוב; אם זה חוזר, העתק את הפרטים הטכניים ושלח אותם.')
}

/** Copies [text]; the clipboard API needs a secure page and a gesture, else the old way. */
export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return
  } catch {}
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy') } finally { ta.remove() }
}

/** An error nothing caught (as the app's crash report): shown in a dialog with its details to copy. */
export const crashReport = signal<string | null>(null)

/** Whether an uncaught error is the page's own and worth a report (not an extension's, a cancelled job or the browser's notices). */
export function reportable(e: unknown, file?: string): boolean {
  if (!(e instanceof Error)) return false
  if (e.name === 'AbortError') return false
  if (/ResizeObserver loop/.test(e.message)) return false
  // Another origin's script (a browser extension): not ours to report.
  if (file && !file.startsWith(location.origin)) return false
  return true
}

let installed = false
/** Listens for errors nothing caught; the first one (until closed) is kept for the report dialog. */
export function installCrashReport() {
  if (installed) return
  installed = true
  const keep = (what: string, e: unknown) => {
    if (crashReport.value === null) crashReport.value = technical(what, e)
  }
  addEventListener('error', (ev) => { if (reportable(ev.error, ev.filename)) keep('uncaught error', ev.error) })
  addEventListener('unhandledrejection', (ev) => { if (reportable(ev.reason)) keep('unhandled promise rejection', ev.reason) })
}
