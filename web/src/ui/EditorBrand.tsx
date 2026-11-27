import { useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { BETA } from '../lib/channel'
import { BetaLogo, BetaNote } from './Beta'
import { InfoIcon, LockIcon } from './icons'

/**
 * The desktop editor's one compact top row has the site's brand in it (the site header is not shown there,
 * so the video gets its height): the small logo (with the BETA tag in the beta), the name and an (i)
 * that opens the privacy line (and the beta's note, with its way back to the regular version) in a bubble.
 * The bubble takes the focus, closes with Escape (which then goes no further: the editor's selection stays)
 * and when the focus or a tap goes elsewhere.
 */
export function EditorBrand() {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const pop = useRef<HTMLDivElement>(null)
  const btn = useRef<HTMLButtonElement>(null)
  // A layout effect: the listeners are there as soon as the bubble is drawn (a passive one can come after a quick Escape).
  useLayoutEffect(() => {
    if (!open) return
    pop.current?.focus({ preventScroll: true })
    const away = (e: Event) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    // In the capture phase, so that the editor's own Escape (it clears the selection) never sees this one.
    const esc = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      setOpen(false)
      btn.current?.focus({ preventScroll: true })
    }
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('keydown', esc, true)
    return () => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('keydown', esc, true) }
  }, [open])
  return (
    <div class="brand-mini" ref={box} onFocusOut={(e) => { if (open && !box.current?.contains(e.relatedTarget as Node | null)) setOpen(false) }}>
      {BETA
        ? <BetaLogo />
        : <><img class="logo light" src="icons/logo-light.png" alt="" /><img class="logo dark" src="icons/logo-dark.png" alt="" /></>}
      <strong class="brand-name">ONIT{BETA && <span class="beta-name"> Beta</span>}</strong>
      <button ref={btn} class="icon-btn info-btn" aria-expanded={open} aria-label={t('מידע')} title={t('מידע')} onClick={() => setOpen(!open)}><InfoIcon size={20} /></button>
      {open && (
        <div class="info-pop" role="dialog" aria-label={t('מידע')} tabIndex={-1} ref={pop}>
          <p class="tag"><LockIcon size={14} /> {t('הכל מעובד על המכשיר. שום דבר לא עולה לרשת.')}</p>
          {BETA && <BetaNote />}
        </div>
      )}
    </div>
  )
}
