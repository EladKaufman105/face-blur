// The beta web app's own parts (only in the beta build, see lib/channel.ts): the BETA tag on the
// logo as on the Android beta's icon, a note that it may have bugs with a way back to the release,
// and copying projects from the release.
import { useEffect, useRef, useState } from 'preact/hooks'
import { addEnglish, t, duration } from '../i18n'
import { importReleaseProjects, listReleaseProjects, ReleaseTooNew, type ReleaseProject } from '../lib/import-release'
import { CloseIcon } from './icons'

const EN_BETA: Record<string, string> = {
  'בטא: ייתכנו באגים': 'Beta: may have bugs',
  'לגרסה הרגילה': 'Open the regular ONIT',
  'ייבוא מ-ONIT': 'Import from ONIT',
  'עותקים של פרויקטים מ-ONIT הרגילה בדפדפן הזה. המקוריים נשארים כמו שהם.': 'Copies of projects from the regular ONIT in this browser. The originals stay as they are.',
  'אין פרויקטים ב-ONIT בדפדפן הזה': 'No projects in ONIT in this browser',
  'ב-ONIT הפרויקטים שמורים בפורמט חדש יותר ממה שהבטא הזו מכירה, ולכן אי אפשר לייבא אותם.': 'ONIT keeps its projects in a newer format than this beta knows, so they can’t be imported.',
  'הייבוא נכשל': 'The import failed',
  'בחר הכל': 'Select all',
  'ייבוא (%d)': 'Import (%d)',
  'מייבא…': 'Importing…',
  '(מ-ONIT)': '(from ONIT)',
  'הסרטון שלו חסר, אי אפשר לייבא': 'Its video is missing, so it can\u2019t be imported',
  'יובאו %d.': '%d imported.',
  'לא יובאו: %s': 'Not imported: %s',
  'שתי הגרסאות שומרות באותו אחסון של האתר בדפדפן: מחיקת נתוני האתר מוחקת את הפרויקטים של שתיהן.': 'Both versions keep their data in this site\u2019s storage in the browser: clearing the site\u2019s data deletes both versions\u2019 projects.',
}
let english = false
/** The strings' English, added once (here, so that the release build has none of this). */
const en = () => { if (!english) { addEnglish(EN_BETA); english = true } }

/** The logo with the BETA tag on its lower right, as on the beta's icons. */
export function BetaLogo() {
  return (
    <div class="logo-wrap">
      <img class="logo light" src="icons/logo-light.png" alt="" />
      <img class="logo dark" src="icons/logo-dark.png" alt="" />
      <span class="beta-tag" aria-hidden="true">BETA</span>
    </div>
  )
}

/** " Beta" after the name, smaller, as in the Android beta. */
export function BetaName() {
  return <span class="beta-name"> Beta</span>
}

/** A small note under the name: it may have bugs; the release is a link away. */
export function BetaNote() {
  en()
  return <p class="beta-note small muted">{t('בטא: ייתכנו באגים')} · <a href="../">{t('לגרסה הרגילה')}</a></p>
}

/** "Import from ONIT" by the projects: copies of the release's projects, picked from a list. */
export function ImportFromRelease({ onDone }: { onDone: () => void }) {
  en()
  const [open, setOpen] = useState(false)
  return (
    <>
      <button class="btn ghost small-btn import-release" onClick={() => setOpen(true)}>{t('ייבוא מ-ONIT')}</button>
      {open && <ImportSheet onClose={() => setOpen(false)} onDone={() => { setOpen(false); onDone() }} onCopied={onDone} />}
    </>
  )
}

function ImportSheet({ onClose, onDone, onCopied }: { onClose: () => void; onDone: () => void; onCopied: () => void }) {
  const [list, setList] = useState<ReleaseProject[] | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const fail = (e: unknown) => setError(e instanceof ReleaseTooNew ? t('ב-ONIT הפרויקטים שמורים בפורמט חדש יותר ממה שהבטא הזו מכירה, ולכן אי אפשר לייבא אותם.') : t('הייבוא נכשל'))
  useEffect(() => {
    listReleaseProjects().then(setList).catch((e) => { setList([]); fail(e) })
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busyRef.current) onClose() }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [])
  const close = () => { if (!busyRef.current) onClose() }
  const ok = (list ?? []).filter((p) => p.video)
  const toggle = (id: string) => {
    const next = new Set(picked)
    if (!next.delete(id)) next.add(id)
    setPicked(next)
  }
  const all = ok.length > 0 && ok.every((p) => picked.has(p.id))
  const go = async () => {
    busyRef.current = true
    setBusy(true)
    setError(null)
    // In the list's order, so the copies are listed as the originals were.
    const ids = ok.filter((p) => picked.has(p.id)).map((p) => p.id).reverse()
    try {
      const r = await importReleaseProjects(ids, t('(מ-ONIT)'))
      if (r.failed.length === 0 && !r.tooNew) { busyRef.current = false; onDone(); return }
      // Not all were copied: the ones that were are picked off, so trying again copies only the rest.
      if (r.failed.length) console.warn('Import from ONIT:', r.failed)
      const copied = new Set(r.copied)
      setPicked(new Set(ids.filter((id) => !copied.has(id))))
      const names = r.failed.map((f) => list!.find((p) => p.id === f.id)?.name ?? f.id)
      const parts = [
        r.tooNew ? t('ב-ONIT הפרויקטים שמורים בפורמט חדש יותר ממה שהבטא הזו מכירה, ולכן אי אפשר לייבא אותם.') : '',
        t('יובאו %d.', r.copied.length),
        names.length ? t('לא יובאו: %s', names.join(', ')) : '',
      ]
      setError(parts.filter(Boolean).join(' '))
    } catch (e) {
      fail(e)
    }
    onCopied()
    busyRef.current = false
    setBusy(false)
  }
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) close() }}>
      <div class="sheet import-sheet" role="dialog" aria-modal="true" aria-label={t('ייבוא מ-ONIT')}>
        <div class="row">
          <h2 class="grow sheet-title">{t('ייבוא מ-ONIT')}</h2>
          <button class="icon-btn" aria-label={t('סגור')} onClick={close} disabled={busy}><CloseIcon /></button>
        </div>
        <div class="sheet-body">
          <p class="small muted" style="margin:0">{t('עותקים של פרויקטים מ-ONIT הרגילה בדפדפן הזה. המקוריים נשארים כמו שהם.')}</p>
          {error && <p class="small import-error" style="margin:0;color:#e5484d">{error}</p>}
          {list && list.length === 0 && !error && <p class="small import-empty" style="margin:0">{t('אין פרויקטים ב-ONIT בדפדפן הזה')}</p>}
          {list && list.length > 0 && (
            <>
              <label class="check import-all"><input type="checkbox" checked={all} disabled={busy || ok.length === 0} onChange={() => setPicked(all ? new Set() : new Set(ok.map((p) => p.id)))} /> {t('בחר הכל')}</label>
              <div class="project-list">
                {list.map((p) => (
                  <label class={'project card import-item' + (p.video ? '' : ' import-missing')}>
                    <input type="checkbox" checked={picked.has(p.id)} disabled={busy || !p.video} onChange={() => toggle(p.id)} aria-label={p.name} />
                    {p.thumb ? <img src={p.thumb} alt="" /> : <div class="ph" />}
                    <div class="grow project-info">
                      <strong class="project-name"><bdi>{p.name}</bdi></strong>
                      <div class="small muted">{p.video ? <bdi dir="ltr">{duration(p.duration)}</bdi> : t('הסרטון שלו חסר, אי אפשר לייבא')}</div>
                    </div>
                  </label>
                ))}
              </div>
              <p class="small muted" style="margin:0">{t('שתי הגרסאות שומרות באותו אחסון של האתר בדפדפן: מחיקת נתוני האתר מוחקת את הפרויקטים של שתיהן.')}</p>
            </>
          )}
        </div>
        {ok.length > 0 && (
          <button class="btn primary import-go" disabled={busy || picked.size === 0} onClick={go}>{busy ? t('מייבא…') : t('ייבוא (%d)', picked.size)}</button>
        )}
      </div>
    </div>
  )
}
