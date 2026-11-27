import { useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { BETA } from '../lib/channel'
import { BetaNote } from './Beta'
import { InfoIcon, LockIcon } from './icons'

/**
 * The desktop editor's one compact top row has the site's brand in it (the site header is not shown there,
 * so the video gets its height): the small logo, the name (with "Beta" and its tag in the beta) and an (i)
 * that opens the privacy line (and the beta's note, with its way back to the regular version) in a bubble.
 */
export function EditorBrand() {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  // A layout effect: the listeners are there as soon as the bubble is drawn (a passive one can come after a quick Escape).
  useLayoutEffect(() => {
    if (!open) return
    const away = (e: Event) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('keydown', esc) }
  }, [open])
  return (
    <div class="brand-mini" ref={box}>
      <img class="logo light" src="icons/logo-light.png" alt="" />
      <img class="logo dark" src="icons/logo-dark.png" alt="" />
      <strong class="brand-name">ONIT{BETA && <span class="beta-name"> Beta</span>}</strong>
      {BETA && <span class="beta-mini">BETA</span>}
      <button class="icon-btn info-btn" aria-expanded={open} aria-label={t('מידע')} title={t('מידע')} onClick={() => setOpen(!open)}><InfoIcon size={20} /></button>
      {open && (
        <div class="info-pop" role="dialog" aria-label={t('מידע')}>
          <p class="tag"><LockIcon size={14} /> {t('הכל מעובד על המכשיר. שום דבר לא עולה לרשת.')}</p>
          {BETA && <BetaNote />}
        </div>
      )}
    </div>
  )
}
