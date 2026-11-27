import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t, lang } from '../i18n'

/**
 * The editor's layouts, as the app's (isWide in Adaptive.kt):
 * - a phone, and a tablet held upright and under 840 px (the app keeps phones and these in one column): the video, the
 *   compact transport, the timeline or (in its place) the open group's panel, and the group bar at the foot;
 * - a tablet (a screen at least 600 px both ways, up to about 1100 px wide): a rail of groups at the side, the panel
 *   always beside the video (closed with its X, opened by any group), the timeline under the video;
 * - anything wider, or a short landscape screen: the page as before.
 */
export const PHONE_QUERY = '(max-width: 600px), (min-width: 601px) and (max-width: 839px) and (orientation: portrait)'
export const TABLET_QUERY = '(min-width: 840px) and (max-width: 1100px) and (min-height: 600px), (min-width: 601px) and (max-width: 839px) and (min-height: 600px) and (orientation: landscape)'

function useMedia(query: string): boolean {
  const mq = () => (typeof matchMedia === 'function' ? matchMedia(query) : null)
  const [on, setOn] = useState(() => !!mq()?.matches)
  useEffect(() => {
    const m = mq()
    if (!m) return
    const f = () => setOn(m.matches)
    f()
    m.addEventListener('change', f)
    return () => m.removeEventListener('change', f)
  }, [query])
  return on
}

/** The phone layout (see above). */
export const useNarrow = () => useMedia(PHONE_QUERY)
/** The tablet layout: a rail of groups, the panel beside the video. */
export const useTablet = () => useMedia(TABLET_QUERY)
/** 840 px and wider: where the page has the video and the controls side by side (and the editor's compact top row). */
export const useWideScreen = () => useMedia('(min-width: 840px)')

export type Sub = [id: string, label: string]

/** What decides which sub-tools apply (the app's panelsOf): detected faces, subtitles, their words. */
export type SubCtx = { hasCues: boolean; hasWords: boolean; hasFaces: boolean }

/** The sub-tools of a group: the face look once faces were looked for, the subtitle list and the bleeped words once there are subtitles. */
export function subsOf(group: string, c: SubCtx): Sub[] {
  switch (group) {
    case 'faces': return c.hasFaces ? [['list', 'טשטוש פנים'], ['look', 'מראה'], ['manual', 'טשטוש ידני']] : [['list', 'טשטוש פנים'], ['manual', 'טשטוש ידני']]
    case 'edit': return [['cut', 'חיתוך'], ['steady', 'ייצוב']]
    // 'רקע\u200E' (an invisible left-to-right mark after it) is its own translation key: plain 'רקע' is the subtitle "Box" in English, here it reads "Background" (the same trick as in Projects).
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

/** A tool's shorter name, for a row too narrow for the whole names (each is its own translation). */
const SHORT: Record<string, string> = {
  'יצירה ותרגום': 'יצירה', 'צפצוף מילים': 'צפצוף', 'אפקטים קוליים': 'אפקטים', 'ניקוי קול': 'ניקוי', 'שינוי קול': 'קול',
  'טשטוש פנים': 'פנים', 'טשטוש ידני': 'ידני', 'גודל ומיקום': 'גודל',
}

/**
 * A row of chips, as tabs: the open one in the tab order, the others reached with the arrow keys (Home and End too).
 * The row is never scrolled: its chips share its width; with names too long for it the short names are used, and
 * (a last resort) the names are cut short with an ellipsis. Every chip stays whole in view.
 */
export function ChipTabs({ items, value, onPick, cls, label }: { items: Sub[]; value: string; onPick: (id: string) => void; cls?: string; label: string }) {
  const row = useRef<HTMLDivElement>(null)
  // 0: the whole names, 1: the short ones, 2: the short ones cut to fit.
  const [fit, setFit] = useState(0)
  const names = items.map(([, n]) => n).join('|') + lang.value
  // A new row (other tools, another language) or a new width starts again from the whole names.
  useEffect(() => { setFit(0) }, [names])
  useEffect(() => {
    const r = row.current
    if (!r || typeof ResizeObserver === 'undefined') return
    let w = r.clientWidth
    const ro = new ResizeObserver(() => { if (Math.abs(r.clientWidth - w) > 0.5) { w = r.clientWidth; setFit(0) } })
    ro.observe(r)
    return () => ro.disconnect()
  }, [])
  useLayoutEffect(() => {
    const r = row.current
    if (r && fit < 2 && r.scrollWidth > r.clientWidth + 0.5) setFit(fit + 1)
  })
  const key = (e: KeyboardEvent) => {
    const k = e.key
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(k)) return
    const el = e.currentTarget as HTMLElement
    const rtl = getComputedStyle(el).direction === 'rtl'
    const at = items.findIndex(([id]) => id === value)
    // The next chip is the one further along the reading direction.
    const step = k === 'ArrowRight' ? (rtl ? -1 : 1) : k === 'ArrowLeft' ? (rtl ? 1 : -1) : 0
    const to = k === 'Home' ? 0 : k === 'End' ? items.length - 1 : (at + step + items.length) % items.length
    // Not also a frame step of the video (the editor's own arrow keys).
    e.preventDefault()
    e.stopPropagation()
    onPick(items[to][0])
    setTimeout(() => (el.querySelectorAll('[role=tab]')[to] as HTMLElement | undefined)?.focus(), 0)
  }
  return (
    <div ref={row} class={'subtabs ' + (cls ?? '') + (fit === 2 ? ' tight' : '')} data-fit={fit} role="tablist" aria-label={label} onKeyDown={key}>
      {items.map(([id, name]) => {
        const shown = fit > 0 && SHORT[name] ? SHORT[name] : name
        return (
          <button role="tab" id={`tab-${cls ?? 'tool'}-${id}`} data-sub={id} aria-selected={value === id} aria-controls={PANEL_ID} aria-label={shown !== name ? t(name) : undefined} tabIndex={value === id ? 0 : -1} class={'chip' + (value === id ? ' on' : '')} onClick={() => onPick(id)}>{t(shown)}</button>
        )
      })}
    </div>
  )
}
