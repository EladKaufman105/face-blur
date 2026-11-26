// Translating the subtitles on the device, with the browser's own translator (Chrome and Edge on
// a computer: the Translator API, language packs downloaded once). No model of ours is small and
// good enough in Hebrew to run in a phone's browser, so where the browser has none, it's said so.
import { t } from '../i18n'

export const LANG_NAMES: [string, string][] = [['he', 'עברית'], ['en', 'אנגלית'], ['ar', 'ערבית'], ['fa', 'פרסית']]

/** Whether this browser translates on the device. */
export const canTranslate = () => typeof (globalThis as any).Translator !== 'undefined'

/** The language of [text], from its letters (Hebrew, Persian, Arabic, else English). */
export function guessLang(text: string): string {
  if (/[֐-׿]/.test(text)) return 'he'
  if (/[پچژگکی]/.test(text)) return 'fa'
  if (/[؀-ۿ]/.test(text)) return 'ar'
  return 'en'
}

/**
 * [texts] from [from] to [to]. Must start from a tap (the browser may download a language pack).
 * [onProgress]: what it's doing and how far (0..1).
 */
export async function translateAll(texts: string[], from: string, to: string, onProgress: (stage: 'download' | 'translate', p: number) => void, signal?: AbortSignal): Promise<string[]> {
  const T = (globalThis as any).Translator
  if (!T) throw new Error('unsupported')
  const options = { sourceLanguage: from, targetLanguage: to }
  if ((await T.availability(options)) === 'unavailable') throw new Error('unavailable')
  const tr = await T.create({
    ...options,
    ...(signal ? { signal } : {}),
    monitor(m: EventTarget) {
      m.addEventListener('downloadprogress', (e: any) => onProgress('download', e.loaded ?? 0))
    },
  })
  const out: string[] = []
  try {
    for (let i = 0; i < texts.length; i++) {
      if (signal?.aborted) throw new DOMException('aborted', 'AbortError')
      out.push(texts[i].trim() ? await tr.translate(texts[i]) : '')
      onProgress('translate', (i + 1) / texts.length)
    }
  } finally {
    tr.destroy?.()
  }
  return out
}

// ------------------------------------------------------------------ a template's translation

/** Whisper's names of the spoken languages, as the translator's codes. */
const SPOKEN_CODE: Record<string, string> = { hebrew: 'he', english: 'en', arabic: 'ar', persian: 'fa' }

/** How long a template's translation waits for the browser to say whether it translates. */
const AVAILABILITY_WAIT_MS = 20000

/** The language a template's translation goes to (as the app's "automatic"): English, and English speech to Hebrew. */
export const autoTarget = (from: string) => (from === 'en' ? 'he' : 'en')

/**
 * Gets the translator ready while a tap still counts (the browser downloads a language pack only
 * on a tap): what automatic processing will translate later, from [spoken] (Whisper's name; null:
 * not known yet, nothing is done). Never throws.
 */
export function prepareTranslation(s: { createSubs: boolean; translate: boolean; language: string | null }) {
  const from = s.language ? SPOKEN_CODE[s.language] : undefined
  const T = (globalThis as any).Translator
  if (!s.createSubs || !s.translate || !from || !T) return
  try {
    Promise.resolve(T.create({ sourceLanguage: from, targetLanguage: autoTarget(from) })).then((tr: any) => tr?.destroy?.(), () => {})
  } catch {}
}

/**
 * [cues] with their translations (a template's processing, no screen): from [spoken] (Whisper's
 * name; null: from the text's letters) to [autoTarget]. Each is translated from the text it shows
 * (no word is bleeped yet). Throws as [translateAll]; [translateNote] says why in a line.
 */
export async function translateCues<C extends { text: string; tr?: string; trm?: string }>(cues: C[], spoken: string | null, onProgress: (stage: 'download' | 'translate', p: number) => void, signal?: AbortSignal): Promise<C[]> {
  const from = (spoken && SPOKEN_CODE[spoken]) || guessLang(cues.map((c) => c.text).join(' '))
  // With no screen to cancel from, a translator that never answers (seen in a headless browser) must not hold the video.
  const T = (globalThis as any).Translator
  if (!T) throw new Error('unsupported')
  let timer = 0
  const known = await Promise.race([
    Promise.resolve(T.availability({ sourceLanguage: from, targetLanguage: autoTarget(from) })),
    new Promise((r) => { timer = setTimeout(() => r('timeout'), AVAILABILITY_WAIT_MS) as unknown as number }),
  ]).finally(() => clearTimeout(timer))
  if (known === 'timeout') throw new Error('timeout')
  const out = await translateAll(cues.map((c) => c.text), from, autoTarget(from), onProgress, signal)
  return cues.map((c, k) => ({ ...c, tr: out[k], trm: c.text }))
}

/** Why a template's translation was not made, as a note on the video. */
export function translateNote(e: unknown): string {
  const m = String((e as Error)?.message ?? e)
  if (m === 'unsupported') return t('התרגום לא נוצר: הדפדפן הזה לא מתרגם על המכשיר (כרום ואדג׳ במחשב כן). הכתוביות נשארו בשפת הדיבור.')
  if (m === 'unavailable') return t('התרגום לא נוצר: הדפדפן לא מתרגם בין השפות האלה. הכתוביות נשארו בשפת הדיבור.')
  if ((e as Error)?.name === 'NotAllowedError') return t('התרגום לא נוצר: חבילת השפה עוד לא הורדה. אפשר לתרגם פעם אחת בעורך כדי להוריד אותה, ואז התרגום יעבוד גם כאן.')
  return t('התרגום לא הצליח, והכתוביות נשארו בשפת הדיבור.')
}
