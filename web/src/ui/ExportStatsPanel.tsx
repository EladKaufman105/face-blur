import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { BETA } from '../lib/channel'
import { deviceLines } from '../lib/analysis-stats'
import { exportReport, fmt, type AudioStep, type ExportStats } from '../lib/export-stats'
import { chartPaths, copyText } from './AnalysisStatsPanel'
import { KEEP } from '../lib/analysis-stats'

declare const __BUILD__: string

/*
 * The export's progress screen's stats panel (a tap on the percentage, as the analysis screen's): how fast
 * the frames are encoded, how many are done, what a frame's blur costs, which encoder runs, the sound's steps
 * and the time. Same tiles, (i), "more details" and Copy as the analysis' panel (ui/AnalysisStatsPanel.tsx),
 * with the export's own numbers.
 */

type CellId = 'fps' | 'frames' | 'blur' | 'encoder' | 'time' | 'audio' | 'size' | 'bitrate'
type Size = 'big' | 'mid' | 'detail'

const MAIN: [Size, CellId[]][] = [
  ['big', ['fps', 'frames']],
  ['mid', ['blur', 'encoder']],
  ['mid', ['time']],
]
const DETAILS: [Size, CellId[]][] = [
  ['detail', ['audio']],
  ['detail', ['size', 'bitrate']],
]
const CHART = { big: 42, mid: 26, detail: 0 }

const label = (c: CellId): string => ({
  fps: t('קצב קידוד'), frames: t('פריימים'), blur: t('טשטוש לפריים'), encoder: t('מקודד'), time: t('זמן'),
  audio: t('שלבי הסאונד'), size: t('גודל'), bitrate: t('קצב סיביות'),
})[c]

const info = (c: CellId): string => ({
  fps: t('כמה פריימים מקודדים בכל שנייה, עכשיו. מעל קצב הסרטון עצמו: הייצוא מהיר מהצפייה בו.'),
  frames: t('כמה פריימים עברו עיבוד מתוך כל הפריימים של הסרטון (בקירוב). פריים שנחתך נספר גם הוא.'),
  blur: t('הזמן שפריים אחד לוקח בשלב הטשטוש, התמונה והכתוביות, בתהליך העיבוד. העבודה של המעבד הגרפי יכולה להימשך אחר כך.'),
  encoder: t('הקידוד: H.264 או HEVC דרך WebCodecs. "חומרה (כנראה)" אומר שהדפדפן דיווח שהמכשיר מקודד בחומרה; הוא לא אומר איזה מקודד נבחר בפועל.'),
  time: t('כמה זמן עבר, וכמה נשאר בהערכה לפי ההתקדמות.'),
  audio: t('מה קורה לסאונד בייצוא: מועתק כמו שהוא, מקודד מחדש (עם אפקטים או חיתוכים) או נוצר מחדש (שינוי קול).'),
  size: t('גודל התמונה: מהמקור לתוצאה.'),
  bitrate: t('כמה סיביות בשנייה מבקשים מהמקודד.'),
})[c]

const audioText = (s: AudioStep): string => ({
  copy: t('הסאונד מועתק כמו שהוא'), reencode: t('קידוד מחדש'), voice: t('קול ששונה'), sfx: t('אפקטי סאונד'), cuts: t('הוצאת קטעים'), none: t('אין סאונד'),
})[s]

const encoderText = (k: string): string => (k === 'hardware' ? t('חומרה (כנראה)') : k === 'software' ? t('תוכנה') : t('לא ידוע'))

/** The cell's value as [number, unit] parts, left to right; [after] is a small text after. */
function value(c: CellId, e: ExportStats, elapsedSec: number, leftSec: number | null): { num: string; unit?: string; after?: string } {
  const f = e.facts
  const mmss = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`
  switch (c) {
    case 'fps': return { num: fmt(e.fps), unit: 'fps' }
    case 'frames': return { num: `${Math.min(e.latest.frames, f.totalFrames)}/${f.totalFrames}` }
    case 'blur': return { num: fmt(e.blurMs), unit: 'ms' }
    case 'encoder': return { num: f.codec === 'hevc' ? 'HEVC' : 'H.264', after: encoderText(f.encoder) }
    case 'time': return { num: mmss(elapsedSec), after: leftSec !== null ? `~${mmss(leftSec)}` : undefined }
    case 'audio': return { num: f.audio.map(audioText).join(' · ') }
    case 'size': return { num: `${f.srcWidth}×${f.srcHeight} → ${f.width}×${f.height}`, unit: 'px' }
    case 'bitrate': return { num: (f.bitrate / 1e6).toFixed(1), unit: 'Mbps' }
  }
}

const series = (c: CellId, e: ExportStats): number[] | null => (c === 'fps' ? e.fpsHistory : c === 'blur' ? e.blurHistory : null)

function Chart({ id, v, size }: { id: CellId; v: number[]; size: Size }) {
  const h = CHART[size]
  const p = chartPaths(v, false, h)
  return (
    <svg class={`stat-chart c-${id}`} viewBox={`0 0 ${KEEP - 1} ${h}`} preserveAspectRatio="none" style={{ height: `${h}px` }} aria-hidden="true">
      {p && <><path class="area" d={p.area} /><path class="line" d={p.line} vector-effect="non-scaling-stroke" /></>}
    </svg>
  )
}

function Tile({ id, size, e, elapsedSec, leftSec, onInfo }: { id: CellId; size: Size; e: ExportStats; elapsedSec: number; leftSec: number | null; onInfo: (id: CellId, el: HTMLElement) => void }) {
  const v = value(id, e, elapsedSec, leftSec)
  const s = series(id, e)
  return (
    <div class={`stat ${size}`} data-cell={id}>
      <button type="button" class="stat-label" onClick={(ev) => onInfo(id, ev.currentTarget)} aria-label={`${label(id)}: ${info(id)}`}>
        <span>{label(id)}</span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" /></svg>
      </button>
      <div class="stat-value"><bdi dir="ltr"><span class="num">{v.num}</span>{v.unit && <span class="unit"> {v.unit}</span>}{v.after && <span class="unit"> · {v.after}</span>}</bdi></div>
      {s && CHART[size] > 0 && <Chart id={id} v={s} size={size} />}
    </div>
  )
}

export function ExportStatsPanel({ open, stats, percent, elapsedSec, leftSec }: { open: boolean; stats: ExportStats | null; percent: number; elapsedSec: number; leftSec: number | null }) {
  const [more, setMore] = useState(false)
  const [copied, setCopied] = useState(false)
  const [bubble, setBubble] = useState<{ id: CellId; x: number; y: number; below: boolean } | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const tip = useRef<HTMLDivElement>(null)
  const e = stats
  useEffect(() => {
    if (!copied) return
    const h = setTimeout(() => setCopied(false), 2000)
    return () => clearTimeout(h)
  }, [copied])
  useEffect(() => {
    if (!bubble) return
    const away = (ev: Event) => { if (!(ev.target as Element).closest?.('.stat-label, .stat-tip')) setBubble(null) }
    const esc = (ev: KeyboardEvent) => { if (ev.key === 'Escape') setBubble(null) }
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('keydown', esc) }
  }, [bubble?.id])
  useEffect(() => { if (!open) setBubble(null) }, [open])
  useLayoutEffect(() => {
    const el = tip.current, r = root.current
    if (!el || !r || !bubble) return
    const w = el.offsetWidth
    el.style.left = `${Math.min(Math.max(bubble.x - w / 2, 0), Math.max(0, r.clientWidth - w))}px`
  }, [bubble])

  const showInfo = (id: CellId, el: HTMLElement) => {
    if (bubble?.id === id) return setBubble(null)
    const r = root.current!.getBoundingClientRect(), b = el.getBoundingClientRect()
    const below = b.top - r.top < 70
    setBubble({ id, x: b.left - r.left + b.width / 2, y: below ? b.bottom - r.top + 4 : b.top - r.top - 4, below })
  }
  const copy = async () => {
    if (!e) return
    const app = `ONIT Web 2.0 (${__BUILD__}${BETA ? ', beta' : ''})`
    await copyText(exportReport(e, { app, device: await deviceLines(), elapsedSec, percent }))
    setCopied(true)
  }

  return (
    <div class={`sfold${open && e ? ' open' : ''}`} inert={!open}>
      <div class="sfold-in">
        <div class="stats" ref={root} data-testid="export-stats-panel">
          {e && MAIN.map(([size, ids]) => (
            <div class={`stats-row ${size}`} key={ids[0]}>{ids.map((id) => <Tile key={id} id={id} size={size} e={e} elapsedSec={elapsedSec} leftSec={leftSec} onInfo={showInfo} />)}</div>
          ))}
          <div class="stats-actions">
            <button type="button" class="btn ghost small-btn stats-more-btn" aria-expanded={more} onClick={() => setMore((m) => !m)}>
              {more ? t('פחות פרטים') : t('עוד פרטים')}
              <svg class={`chev${more ? ' up' : ''}`} viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" /></svg>
            </button>
            <span class="grow" />
            <button type="button" class="btn ghost small-btn stats-copy" onClick={copy}>
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">{copied
                ? <path d="M9 16.17 5.53 12.7a.996.996 0 1 0-1.41 1.41l4.18 4.18c.39.39 1.02.39 1.41 0L20.29 7.71a.996.996 0 1 0-1.41-1.41L9 16.17z" />
                : <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />}</svg>
              {copied ? t('הנתונים הועתקו') : t('העתק')}
            </button>
          </div>
          <div class={`sfold${more ? ' open' : ''}`} inert={!more}>
            <div class="sfold-in">
              <div class="stats-details">
                {e && DETAILS.map(([size, ids]) => (
                  <div class={`stats-row ${size}`} key={ids[0]}>{ids.map((id) => <Tile key={id} id={id} size={size} e={e} elapsedSec={elapsedSec} leftSec={leftSec} onInfo={showInfo} />)}</div>
                ))}
              </div>
            </div>
          </div>
          {bubble && <div class={`stat-tip${bubble.below ? ' below' : ''}`} ref={tip} role="tooltip" style={{ top: `${bubble.y}px`, left: `${bubble.x}px` }}>{info(bubble.id)}</div>}
        </div>
      </div>
    </div>
  )
}
