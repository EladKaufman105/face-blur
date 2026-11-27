// The speech model's parts of the screen: the choice (faster / better quality, with the size
// before anything downloads), the progress of a job, and the question before a template downloads.
import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'
import { answerSpeechAsk, device, gpuFailed, hebrew, spokenLanguage, mbOf, planCached, planFor, probeDevice, qualityChoice, setQuality, speechAsk, defaultQuality, type Quality, type SpeechPlan, type SpeechStatus } from '../lib/speech'

/** The plan now (the device's graphics processor is asked once, then the plan may change), and whether its files are already here. */
export function useSpeechPlan(language: string | null = null): { plan: SpeechPlan; cached: boolean | null } {
  const d = device.value
  const q = qualityChoice.value
  const l = spokenLanguage(language)
  const plan = planFor(q ?? defaultQuality(d, l), d, gpuFailed.value, l)
  const [cached, setCached] = useState<boolean | null>(null)
  useEffect(() => { probeDevice() }, [])
  useEffect(() => {
    let live = true
    setCached(null)
    planCached(plan).then((c) => { if (live) setCached(c) })
    return () => { live = false }
  }, [plan.repo, plan.dtype])
  return { plan, cached }
}

/** "Faster" and "Better quality", each with its download size; the choice is kept. */
export function QualityChoice({ cls, language = null }: { cls?: string; language?: string | null }) {
  const d = device.value
  const { plan } = useSpeechPlan(language)
  const options: [Quality, string][] = [['fast', 'מהיר'], ['better', 'איכות טובה יותר']]
  // On a phone or another weak device the Hebrew model (ivrit.ai) is its own choice, named, with a warning:
  // it needs a lot of memory, so "better quality" there stays the small multilingual model.
  const hebrewChoice = d.weak && hebrew(spokenLanguage(language))
  if (hebrewChoice) options.push(['hebrew', 'מודל עברית'])
  const sizes = options.map(([q]) => planFor(q, d, gpuFailed.value, spokenLanguage(language)))
  const [have, setHave] = useState<boolean[]>([false, false, false])
  // The Hebrew model (large-v3-turbo) on a phone or another weak device, or on the CPU with one thread (a page
  // without threads, no big graphics processor): minutes for each 30 s of speech.
  const slow = d.probed && plan.model === 'ivrit' && (d.weak || (plan.device === 'wasm' && plan.threads <= 1))
  useEffect(() => {
    let live = true
    Promise.all(sizes.map(planCached)).then((h) => { if (live) setHave(h) })
    return () => { live = false }
  }, [sizes.map((s) => s.repo + s.dtype).join()])
  return (
    <div class={'chips speech-quality ' + (cls ?? '')} role="radiogroup" aria-label={t('איכות הזיהוי')}>
      {options.map(([q, label], k) => (
        <button role="radio" aria-checked={plan.quality === q} data-q={q} class={'chip' + (plan.quality === q ? ' on' : '')} onClick={() => setQuality(q)}>
          {t(label)} · <span dir="ltr">{have[k] ? t('במכשיר') : `${mbOf(sizes[k])} MB`}</span>
        </button>
      ))}
      {hebrewChoice && plan.quality === 'hebrew' && <p class="small speech-memory" style="flex-basis:100%;margin:6px 0 0;color:var(--primary)">{t('מודל העברית (ivrit.ai) גדול וצריך הרבה זיכרון. אם הדף נסגר או נתקע, בחרו "מהיר".')}</p>}
      {slow && <p class="small speech-slow" style="flex-basis:100%;margin:6px 0 0;color:var(--primary)">{t('במכשיר הזה מודל העברית איטי: כמה דקות לכל חצי דקה של דיבור. "מהיר" מסיים הרבה יותר מהר.')}</p>}
    </div>
  )
}

/** What a job says, and how far (null: no percent exists for it). */
export function speechLine(s: SpeechStatus): { text: string; progress: number | null } {
  switch (s.stage) {
    case 'audio': return { text: t('מכין את הקול…'), progress: s.progress > 0 ? s.progress : null }
    case 'download': return { text: t('מוריד את המודל…'), progress: s.progress }
    case 'load': return { text: t('טוען את המודל למכשיר…'), progress: null }
    case 'fallback': return { text: s.same ? t('המעבד הגרפי לא הגיב. ממשיך על המעבד עם אותו מודל…') : t('המעבד הגרפי לא הגיב. ממשיך על המעבד עם מודל קטן יותר…'), progress: null }
    case 'recognize': return { text: t('מזהה דיבור…'), progress: s.progress }
  }
}

/** Seconds since [key] last changed (a counter proves a phase without a percent is alive). */
function useElapsed(key: string): number {
  const [n, setN] = useState(0)
  useEffect(() => {
    const t0 = Date.now()
    setN(0)
    const id = setInterval(() => setN(Math.floor((Date.now() - t0) / 1000)), 1000)
    return () => clearInterval(id)
  }, [key])
  return n
}

/** A job's progress: its phase, a percent or (when there is none) a moving bar and the seconds so far, and Cancel. */
export function SpeechProgress({ status, onCancel }: { status: SpeechStatus; onCancel?: () => void }) {
  const line = speechLine(status)
  const secs = useElapsed(status.stage)
  const pct = line.progress === null ? null : Math.floor(line.progress * 100)
  return (
    <div class="speech-progress" style="display:grid;gap:10px">
      <div class="row">
        <strong class="grow">{line.text}</strong>
        <span class="muted small" dir="ltr">
          {status.stage === 'download' && status.total ? `${(status.loaded / 1e6).toFixed(0)} / ${(status.total / 1e6).toFixed(0)} MB` : pct !== null ? `${pct}%` : secs > 2 ? `${secs}s` : ''}
        </span>
      </div>
      <div class={'progress' + (line.progress === null ? ' indet' : '')}><div style={line.progress === null ? undefined : { width: `${Math.max(2, (line.progress ?? 0) * 100)}%` }} /></div>
      {'audio' in status && status.audio !== undefined && <span class="muted small speech-audio" dir="auto">{t('מכין את הקול…')} {Math.floor(status.audio * 100)}%</span>}
      {status.stage === 'recognize' && status.win && status.win.n > 1 && <span class="muted small speech-win" dir="auto">{t('קטע %d מתוך %d (30 שניות כל אחד)', status.win.i, status.win.n)}</span>}
      {onCancel && <button class="btn tonal speech-cancel" onClick={onCancel}>{t('ביטול')}</button>}
    </div>
  )
}

/** The question before a template's subtitles download a model (shown over any screen). */
export function SpeechAsk() {
  const ask = speechAsk.value
  const { plan } = useSpeechPlan(ask?.language ?? null)
  useEffect(() => {
    if (!ask) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') answerSpeechAsk(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ask?.id])
  if (!ask) return null
  const d = device.value
  return (
    <div class="sheet-back speech-ask">
      <div class="sheet" role="dialog" aria-modal="true" aria-label={t('מודל זיהוי דיבור')}>
        <h3 class="sheet-title">{t('להוריד את מודל זיהוי הדיבור?')}</h3>
        <div class="sheet-body">
          <p style="margin:0">{t('כדי ליצור כתוביות צריך מודל זיהוי דיבור. הוא יורד פעם אחת (כ-%d MB) ונשמר בדפדפן. מומלץ להיות ב-Wi-Fi.', mbOf(plan))}</p>
          <p class="small muted" style="margin:0">{d.weak ? t('בטלפון "מהיר" קטן יותר, חם פחות ומסיים מהר יותר.') : t('"איכות טובה יותר" מדויק יותר, אבל גדול ואיטי יותר.')}</p>
          <QualityChoice language={ask.language} />
        </div>
        <div class="actions">
          <button class="btn primary speech-go" onClick={() => answerSpeechAsk(plan.quality)}>{t('הורד והמשך')}</button>
          <button class="btn ghost speech-skip" onClick={() => answerSpeechAsk(null)}>{t('בלי כתוביות')}</button>
        </div>
      </div>
    </div>
  )
}
