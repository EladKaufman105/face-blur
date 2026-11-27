import { useEffect, useState } from 'preact/hooks'
import { lang, t } from '../i18n'
import { clearAllChoices, clearChoice, remembered } from '../lib/choices'
import { listRemembered } from '../lib/choicestore'
import { CloseIcon } from './icons'

/** A label in the app's language, from the registry (shared/prefs/choices.json: both languages are there). */
const label = (x: { he: string; en: string }) => (lang.value === 'en' ? x.en : x.he)

/** Settings > General: one row, "Remembered choices", that opens the list. */
export function RememberedChoicesRow() {
  const [open, setOpen] = useState(false)
  const n = listRemembered(remembered.value).length
  return (
    <>
      <button class="remembered-row" onClick={() => setOpen(true)} aria-haspopup="dialog">
        <span class="grow">
          <strong>{t('בחירות שנשמרו')}</strong>
          <span class="small muted">{n > 0 ? t('%d בחירות שמורות', n) : t('שואלים בכל פעם')}</span>
        </span>
        <span class="muted flip" aria-hidden="true">›</span>
      </button>
      {open && <RememberedChoicesSheet onClose={() => setOpen(false)} />}
    </>
  )
}

/** Every key with a remembered value: its value, Clear (back to ask each time), and Clear all. An empty state when there are none. */
function RememberedChoicesSheet({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    addEventListener('keydown', key, true)
    return () => removeEventListener('keydown', key, true)
  }, [])
  const list = listRemembered(remembered.value)
  return (
    <div class="sheet-back remembered-back" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet remembered-sheet" role="dialog" aria-modal="true" aria-label={t('בחירות שנשמרו')}>
        <div class="row">
          <h2 class="grow sheet-title">{t('בחירות שנשמרו')}</h2>
          <button class="icon-btn" aria-label={t('סגור')} onClick={onClose}><CloseIcon /></button>
        </div>
        <div class="sheet-body">
          {list.length === 0 ? (
            <div class="remembered-empty">
              <strong>{t('אין בחירות שמורות')}</strong>
              <p class="small muted" style="margin:4px 0 0">{t('כשתסמנו "זכור את הבחירה שלי" בחלון, הבחירה תופיע כאן, ואפשר יהיה לנקות אותה.')}</p>
            </div>
          ) : (
            <>
              <p class="small muted" style="margin:0">{t('בחירות שנשמרו כדי שלא נשאל שוב. ניקוי מחזיר את השאלה.')}</p>
              {list.map(({ key, value }) => (
                <div class="remembered-item" data-key={key.id}>
                  <span class="grow"><strong>{label(key)}</strong><span class="small remembered-value">{label(value)}</span></span>
                  <button class="btn tonal small-btn remembered-clear" onClick={() => clearChoice(key.id)}>{t('נקה')}</button>
                </div>
              ))}
            </>
          )}
        </div>
        {list.length > 0 && (
          <div class="actions"><button class="btn ghost remembered-clear-all" onClick={clearAllChoices}>{t('נקה הכל')}</button></div>
        )}
      </div>
    </div>
  )
}
