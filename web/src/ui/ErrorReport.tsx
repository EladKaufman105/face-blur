// A failure's technical details folded under its message, and the dialog for an error nothing caught, as the app's
// MessageCard details and CrashDialog. The app hands the details to the share sheet; here they are copied, and the
// page itself sends nothing.
import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'
import { copyText, crashReport } from '../lib/errors'
import { CloseIcon } from './icons'

/** "Technical details" to open, with the text (selectable) and a button that copies it. */
export function ErrorDetails({ details }: { details: string }) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  return (
    <div class="error-details">
      <button class="btn ghost small-btn error-details-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? t('הסתר פרטים טכניים') : t('פרטים טכניים')}</button>
      {open && <pre class="error-details-text" dir="ltr">{details}</pre>}
      <button class="btn ghost small-btn error-copy" onClick={() => copyText(details).then(() => setCopied(true), () => {})}>{t('העתק את הפרטים')}</button>
      {copied && <span class="small muted error-copied" role="status">{t('הפרטים הועתקו')}</span>}
    </div>
  )
}

/** An error nothing caught: what happened, its details to copy (and send by hand), Close. */
export function CrashDialog() {
  const report = crashReport.value
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    setCopied(false)
    if (report === null) return
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') crashReport.value = null }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [report])
  if (report === null) return null
  const close = () => { crashReport.value = null }
  return (
    <div class="sheet-back crash-back" onClick={(e) => { if (e.target === e.currentTarget) close() }}>
      <div class="sheet crash-dialog" role="alertdialog" aria-modal="true" aria-label={t('משהו השתבש')}>
        <div class="row">
          <h3 class="section-title grow" style="margin:0">{t('משהו השתבש')}</h3>
          <button class="icon-btn" aria-label={t('סגור')} onClick={close}><CloseIcon size={20} /></button>
        </div>
        <p class="small" style="margin:0">{t('האפליקציה נתקלה בשגיאה. העתק את הפרטים ושלח אותם כדי שאפשר יהיה לתקן אותה. שום דבר לא נשלח מכאן בעצמו.')}</p>
        <pre class="error-details-text crash-text" dir="ltr">{report}</pre>
        <div class="row" style="gap:8px;justify-content:flex-end">
          {copied && <span class="small muted grow error-copied" role="status">{t('הפרטים הועתקו')}</span>}
          <button class="btn ghost crash-close" onClick={close}>{t('סגור')}</button>
          <button class="btn primary crash-copy" onClick={() => copyText(report).then(() => setCopied(true), () => {})}>{t('העתק את הפרטים')}</button>
        </div>
      </div>
    </div>
  )
}
