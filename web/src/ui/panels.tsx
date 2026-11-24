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

/** The sub-tools of a group (the app's panelsOf): the subtitle list once there are subtitles. */
export function subsOf(group: string, hasCues: boolean): Sub[] {
  switch (group) {
    case 'faces': return [['list', 'טשטוש פנים'], ['look', 'מראה'], ['manual', 'טשטוש ידני']]
    case 'edit': return [['cut', 'חיתוך'], ['steady', 'ייצוב']]
    case 'picture': return [['bg', 'רקע'], ['frame', 'פורמט']]
    case 'subs': return hasCues ? [['make', 'יצירה ותרגום'], ['list', 'רשימה'], ['style', 'עיצוב']] : [['make', 'יצירה ותרגום'], ['style', 'עיצוב']]
    case 'sound': return [['clean', 'ניקוי קול'], ['voice', 'שינוי קול'], ['sfx', 'אפקטים קוליים']]
    default: return []
  }
}

/** The style panel's own tabs (the app's SubtitleStylePanel). */
export const STYLE_PARTS: Sub[] = [['looks', 'סגנונות'], ['font', 'גופן'], ['color', 'צבע'], ['effect', 'אפקט'], ['layout', 'גודל ומיקום']]

/** A row of chips that scrolls sideways. */
export function ChipTabs({ items, value, onPick, cls }: { items: Sub[]; value: string; onPick: (id: string) => void; cls?: string }) {
  return (
    <div class={'subtabs ' + (cls ?? '')} role="tablist">
      {items.map(([id, label]) => (
        <button role="tab" data-sub={id} aria-selected={value === id} class={'chip' + (value === id ? ' on' : '')} onClick={() => onPick(id)}>{t(label)}</button>
      ))}
    </div>
  )
}
