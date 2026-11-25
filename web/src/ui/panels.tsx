import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'

/** The phone layout (as the app's): one group's panel at a time, its sub-tools as chips. */
const QUERY = '(max-width: 600px)'

export function useNarrow(): boolean {
  const mq = () => (typeof matchMedia === 'function' ? matchMedia(QUERY) : null)
  const [narrow, setNarrow] = useState(() => !!mq()?.matches)
  useEffect(() => {
    const m = mq()
    if (!m) return
    const on = () => setNarrow(m.matches)
    on()
    m.addEventListener('change', on)
    return () => m.removeEventListener('change', on)
  }, [])
  return narrow
}

export type Sub = [id: string, label: string]

/** What decides which sub-tools apply (the app's panelsOf): detected faces, subtitles, their words. */
export type SubCtx = { hasCues: boolean; hasWords: boolean; hasFaces: boolean }

/** The sub-tools of a group: the face look once faces were looked for, the subtitle list and the bleeped words once there are subtitles. */
export function subsOf(group: string, c: SubCtx): Sub[] {
  switch (group) {
    case 'faces': return c.hasFaces ? [['list', 'טשטוש פנים'], ['look', 'מראה'], ['manual', 'טשטוש ידני']] : [['list', 'טשטוש פנים'], ['manual', 'טשטוש ידני']]
    case 'edit': return [['cut', 'חיתוך'], ['steady', 'ייצוב']]
    case 'picture': return [['bg', 'רקע\u200E'], ['frame', 'פורמט']]
    case 'subs': return [['make', 'יצירה ותרגום'], ...(c.hasCues ? [['list', 'רשימה'] as Sub] : []), ...(c.hasCues && c.hasWords ? [['bleep', 'צפצוף מילים'] as Sub] : []), ['style', 'עיצוב']]
    case 'sound': return [['clean', 'ניקוי קול'], ['voice', 'שינוי קול'], ['sfx', 'אפקטים קוליים']]
    default: return []
  }
}

/** The style panel's own tabs (the app's SubtitleStylePanel). */
export const STYLE_PARTS: Sub[] = [['looks', 'סגנונות'], ['font', 'גופן'], ['color', 'צבע'], ['effect', 'אפקט'], ['layout', 'גודל ומיקום']]

/** The id of the panel body the chips control. */
export const PANEL_ID = 'panel-body'

/**
 * A row of chips, as tabs: the open one in the tab order, the others reached with the arrow keys
 * (Home and End too); the row wraps instead of hiding chips behind a sideways scroll.
 */
export function ChipTabs({ items, value, onPick, cls, label }: { items: Sub[]; value: string; onPick: (id: string) => void; cls?: string; label: string }) {
  const key = (e: KeyboardEvent) => {
    const k = e.key
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(k)) return
    const row = e.currentTarget as HTMLElement
    const rtl = getComputedStyle(row).direction === 'rtl'
    const at = items.findIndex(([id]) => id === value)
    // The next chip is the one further along the reading direction.
    const step = k === 'ArrowRight' ? (rtl ? -1 : 1) : k === 'ArrowLeft' ? (rtl ? 1 : -1) : 0
    const to = k === 'Home' ? 0 : k === 'End' ? items.length - 1 : (at + step + items.length) % items.length
    e.preventDefault()
    onPick(items[to][0])
    setTimeout(() => (row.querySelectorAll('[role=tab]')[to] as HTMLElement | undefined)?.focus(), 0)
  }
  return (
    <div class={'subtabs ' + (cls ?? '')} role="tablist" aria-label={label} onKeyDown={key}>
      {items.map(([id, name]) => (
        <button role="tab" id={`tab-${cls ?? 'tool'}-${id}`} data-sub={id} aria-selected={value === id} aria-controls={PANEL_ID} tabIndex={value === id ? 0 : -1} class={'chip' + (value === id ? ' on' : '')} onClick={() => onPick(id)}>{t(name)}</button>
      ))}
    </div>
  )
}
