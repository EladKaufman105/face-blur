// Translating the subtitles on the device, with the browser's own translator (Chrome and Edge on
// a computer: the Translator API, language packs downloaded once). No model of ours is small and
// good enough in Hebrew to run in a phone's browser, so where the browser has none, it's said so.

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
export async function translateAll(texts: string[], from: string, to: string, onProgress: (stage: 'download' | 'translate', p: number) => void): Promise<string[]> {
  const T = (globalThis as any).Translator
  if (!T) throw new Error('unsupported')
  const options = { sourceLanguage: from, targetLanguage: to }
  if ((await T.availability(options)) === 'unavailable') throw new Error('unavailable')
  const tr = await T.create({
    ...options,
    monitor(m: EventTarget) {
      m.addEventListener('downloadprogress', (e: any) => onProgress('download', e.loaded ?? 0))
    },
  })
  const out: string[] = []
  try {
    for (let i = 0; i < texts.length; i++) {
      out.push(texts[i].trim() ? await tr.translate(texts[i]) : '')
      onProgress('translate', (i + 1) / texts.length)
    }
  } finally {
    tr.destroy?.()
  }
  return out
}
