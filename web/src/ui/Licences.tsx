// "Open-source licences": the models, libraries and fonts ONIT is made of, each with its licence; a tap
// opens its notice and, on request, the full text (served by this site itself). Reached from
// the footer, as the app's Settings entry.
import { useEffect, useState } from 'preact/hooks'
import { addEnglish, t } from '../i18n'
import { loadEntries, loadText, type LicenceEntry } from '../lib/licences'
import { BackIcon, CloseIcon } from './icons'

addEnglish({
  'רישיונות קוד פתוח': 'Open-source licences',
  'המודלים, הספריות והגופנים ש-ONIT בנוי מהם. נוסחי הרישיונות המלאים מוצגים כאן, והם חלק מהאתר.': 'The models, libraries and fonts ONIT is made of. The full licence texts are shown here and are part of the site.',
  'מודלים': 'Models',
  'ספריות': 'Libraries',
  'גופנים': 'Fonts',
  'הצג את הרישיון המלא': 'Show the full licence',
  'הסתר את הרישיון המלא': 'Hide the full licence',
  'מקור המידע': 'Source',
  'הנוסח המלא של התנאים נמצא בכתובת המקור.': 'The full text of the terms is at the source address.',
  'נוסח הרישיון לא נמצא באתר.': 'The licence text isn’t on the site.',
  'לא הצלחתי לטעון את רשימת הרישיונות.': 'Couldn’t load the list of licences.',
})

/** The notice shown above the terms: copyright line, licence name, and the change note. */
function header(e: LicenceEntry) {
  const i = e.info
  return [i.copyright, i.licence, i.note].filter(Boolean).join('\n')
}

function Detail({ e }: { e: LicenceEntry }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState<string | null>(null)
  const i = e.info
  useEffect(() => {
    if (!open || text !== null || !i.licenceFile) return
    loadText(i.licenceFile).then(setText, () => setText(''))
  }, [open])
  return (
    <div class="licence-detail">
      <h3 class="licence-name">{i.title}</h3>
      {/* The notice and the terms are the upstream's own (English) wording: shown as written, left to right. */}
      <pre class="licence-notice" dir="ltr">{header(e)}</pre>
      {i.source && <div class="small muted">{t('מקור המידע')}: <bdi dir="ltr">{i.source}</bdi></div>}
      {i.licenceFile ? (
        <>
          <button class="btn tonal small-btn licence-toggle" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? t('הסתר את הרישיון המלא') : t('הצג את הרישיון המלא')}</button>
          {open && text === '' && <p class="small licence-missing">{t('נוסח הרישיון לא נמצא באתר.')}</p>}
          {open && text && <pre class="licence-text" dir="ltr">{text.trim()}</pre>}
        </>
      ) : (
        <p class="small muted">{t('הנוסח המלא של התנאים נמצא בכתובת המקור.')}</p>
      )}
    </div>
  )
}

function Section({ title, items, onOpen }: { title: string; items: LicenceEntry[]; onOpen: (e: LicenceEntry) => void }) {
  if (!items.length) return null
  return (
    <>
      <h4 class="caption">{title}</h4>
      {items.map((e) => (
        <button class="licence-row" data-id={e.id} onClick={() => onOpen(e)}>
          <span class="licence-title">{e.info.title}</span>
          <bdi dir="ltr" class="small muted">{e.info.licence}</bdi>
        </button>
      ))}
    </>
  )
}

export function Licences({ onClose }: { onClose: () => void }) {
  const [entries, setEntries] = useState<LicenceEntry[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [open, setOpen] = useState<LicenceEntry | null>(null)
  useEffect(() => {
    loadEntries().then(setEntries, () => setFailed(true))
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [])
  const of = (k: LicenceEntry['kind']) => (entries ?? []).filter((e) => e.kind === k)
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet licences-sheet" role="dialog" aria-modal="true" aria-label={t('רישיונות קוד פתוח')}>
        <div class="row">
          {open && <button class="icon-btn" aria-label={t('חזרה')} onClick={() => setOpen(null)}><BackIcon /></button>}
          <h2 class="grow sheet-title">{t('רישיונות קוד פתוח')}</h2>
          <button class="icon-btn" aria-label={t('סגור')} onClick={onClose}><CloseIcon /></button>
        </div>
        <div class="sheet-body">
          {open ? <Detail e={open} /> : (
            <>
              <p class="small muted" style="margin:0">{t('המודלים, הספריות והגופנים ש-ONIT בנוי מהם. נוסחי הרישיונות המלאים מוצגים כאן, והם חלק מהאתר.')}</p>
              {failed && <p class="small licence-missing">{t('לא הצלחתי לטעון את רשימת הרישיונות.')}</p>}
              <Section title={t('מודלים')} items={of('model')} onOpen={setOpen} />
              <Section title={t('ספריות')} items={of('library')} onOpen={setOpen} />
              <Section title={t('גופנים')} items={of('font')} onOpen={setOpen} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}
