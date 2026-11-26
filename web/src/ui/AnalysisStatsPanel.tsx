import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { BETA } from '../lib/channel'
import { KEEP, backendText, deviceLines, fmt, max, min, statsReport, type AnalysisStats, type Series } from '../lib/analysis-stats'

declare const __BUILD__: string

/*
 * The progress screen's stats panel (a tap on the percentage while faces are found): where the
 * time goes, now and over the last minute, for whoever tunes detection on a device. The Android
 * app's panel, with the web's own numbers: tiles with a chart of the last 60 samples, an (i)
 * on each, more details folded away, and a button that copies a text report for an AI agent.
 */

type CellId =
  | 'detect' | 'wait' | 'frame' | 'resolution' | 'sampling'
  | 'backend' | 'workers' | 'fine' | 'coarse' | 'gpu' | 'check' | 'crops' | 'decode' | 'cuts' | 'merge'
  | 'measured' | 'gap' | 'strength'
type Size = 'big' | 'mid' | 'detail'

const MAIN: [Size, CellId[]][] = [
  ['big', ['detect', 'wait']],
  ['mid', ['frame', 'resolution', 'sampling']],
]
const DETAILS: [Size, CellId[]][] = [
  ['detail', ['backend']],
  ['detail', ['measured', 'gap', 'strength']],
  ['detail', ['workers', 'fine', 'coarse']],
  ['detail', ['gpu', 'check', 'crops']],
  ['detail', ['decode', 'cuts', 'merge']],
]
/** Resolution and sampling change in jumps: step charts. */
const STEP: CellId[] = ['resolution', 'sampling']
/** The chart's height in px per row size (also the SVG's own coordinate height). */
const CHART = { big: 42, mid: 26, detail: 0 }

const label = (c: CellId): string => ({
  detect: t('זיהוי'), wait: t('המתנה'), frame: t('פריים'), resolution: t('רזולוציה'), sampling: t('דגימה'),
  backend: t('מנוע'), workers: t('גלאים'), fine: t('מעבר עדין'), coarse: t('מעבר גס'), gpu: t('זמן GPU'),
  check: t('בדיקת GPU'), crops: t('בדיקות חיתוך'), decode: t('המתנה לפענוח'), cuts: t('חיתוכי סצנה'), merge: t('מיזוג'),
  measured: t('זיהויים בפועל'), gap: t('הפער הארוך'), strength: t('עוצמת הזיהוי'),
})[c]

const info = (c: CellId): string => ({
  detect: t('כמה זמן לוקח זיהוי פנים אחד. זה החלק הכבד.'),
  wait: t('כמה זמן פריים מחכה לגלאי פנוי. גבוה: המכשיר לא עומד בקצב.'),
  frame: t('הזמן לעיבוד פריים אחד, בלי ההמתנות.'),
  resolution: t('גודל התמונה שגלאי הפנים בודק. יורד אוטומטית במכשיר איטי.'),
  sampling: t('כמה זיהויים בכל שנייה של וידאו. המעקב משלים את הפריימים שביניהם. יורד אוטומטית במכשיר איטי.'),
  backend: t('איפה רץ זיהוי הפנים: במעבד הגרפי (GPU) או במעבד, ובאיזה דיוק. כשאין GPU, כתוב למה.'),
  workers: t('כמה גלאים עובדים במקביל, מתוך מספר הליבות במכשיר.'),
  fine: t('זמן המעבר המפורט בכל זיהוי.'),
  coarse: t('זמן המעבר המהיר שמוצא פנים גדולות, ובכמה מהזיהויים הוא רץ.'),
  gpu: t('זמן ההרצה במעבד הגרפי בכל זיהוי.'),
  check: t('זמן ההשוואה בין המעבד הגרפי למעבד בכל זיהוי, בפריימים הראשונים עם פנים.'),
  crops: t('זמן הבדיקה החוזרת של פנים לא ודאיות בכל זיהוי.'),
  decode: t('כמה זמן כל פריים מחכה לפענוח הווידאו.'),
  cuts: t('זמן הבדיקה אם הפריים הוא חיתוך בין סצנות, בכל פריים.'),
  merge: t('זמן מיזוג התוצאות של שני המעברים בכל זיהוי.'),
  measured: t('כמה פריימים באמת עברו זיהוי בכל שנייה של וידאו, ובחלון של 2 שניות עם הכי מעט.'),
  gap: t('הרווח הארוך ביותר בין שני זיהויים באותה סצנה, בפריימים ובשניות.'),
  strength: t('הגודל הקטן ביותר שבו רץ המעבר המפורט, והאם YuNet רץ.'),
})[c]

/** The cell's value as [number, unit] parts, left to right; [word] is a small text before or after. */
function value(c: CellId, a: AnalysisStats): { num: string; unit?: string; after?: string } {
  const l = a.latest
  const gpu = l.backend === 'webgpu'
  const ms = (v: number) => ({ num: fmt(v), unit: 'ms' })
  switch (c) {
    case 'detect': return ms(a.detectMs)
    case 'wait': return ms(a.waitMs)
    case 'frame': return ms(a.frameMs)
    case 'resolution': return { num: String(l.side), unit: 'px' }
    case 'sampling': return { num: fmt(l.rate), unit: '/s' }
    case 'backend': return { num: backendText(l) }
    case 'workers': return { num: `${l.workers}/${l.cores}` }
    case 'fine': return ms(a.fineMs)
    case 'coarse': return { ...ms(a.coarseMs), after: `${Math.round(a.coarseShare * 100)}%` }
    case 'gpu': return gpu ? ms(a.gpuMs) : { num: '—' }
    case 'check': return gpu ? ms(a.checkMs) : { num: '—' }
    case 'crops': return ms(a.verifyMs)
    case 'decode': return ms(a.decodeWaitMs)
    case 'cuts': return ms(a.cutMs)
    case 'merge': return ms(a.mergeMs)
    case 'measured': return { num: fmt(l.measured.rate), unit: '/s', after: `min ${l.measured.minWindow === null ? '—' : fmt(l.measured.minWindow)}` }
    case 'gap': return { num: String(l.measured.gapFrames), unit: t('פריימים'), after: `${l.measured.gapS.toFixed(2)} s` }
    case 'strength': return { num: String(l.measured.minSide), unit: 'px', after: l.measured.yunet ? 'YuNet' : t('בלי YuNet') }
  }
}

function series(c: CellId, a: AnalysisStats): Series | null {
  return c === 'detect' ? a.detectHistory : c === 'wait' ? a.waitHistory : c === 'frame' ? a.frameHistory : c === 'resolution' ? a.sideHistory : c === 'sampling' ? a.rateHistory : null
}

/** Tangents at points [y] [dx] apart for a curve through them that doesn't overshoot (Fritsch–Carlson). */
function monotoneSlopes(y: number[], dx: number): number[] {
  const n = y.length
  const d = Array.from({ length: n - 1 }, (_, i) => (y[i + 1] - y[i]) / dx)
  const m = new Array<number>(n)
  m[0] = d[0]
  m[n - 1] = d[n - 2]
  for (let k = 1; k < n - 1; k++) m[k] = d[k - 1] * d[k] <= 0 ? 0 : (d[k - 1] + d[k]) / 2
  for (let k = 0; k < n - 1; k++) {
    if (d[k] === 0) { m[k] = 0; m[k + 1] = 0; continue }
    const a = m[k] / d[k], b = m[k + 1] / d[k], s = a * a + b * b
    if (s > 9) {
      const f = 3 / Math.sqrt(s)
      m[k] = f * a * d[k]
      m[k + 1] = f * b * d[k]
    }
  }
  return m
}

/**
 * The paths of a chart [h] px tall, [KEEP] samples wide (newest at the right edge): times from 0
 * up as a smooth curve that never overshoots a sample, [step] values (around their range) as steps.
 */
export function chartPaths(v: number[], step: boolean, h: number): { line: string; area: string } | null {
  if (v.length < 2) return null
  const stroke = 1.5, top = stroke, bottom = h - stroke / 2
  const lo = step ? min(v) : 0, hi = max(v), span = hi - lo
  const ys = v.map((x) => span <= 0 ? (step ? h / 2 : hi > 0 ? top : bottom) : step ? bottom - (0.15 + 0.7 * (x - lo) / span) * (bottom - top) : bottom - (x - lo) / span * (bottom - top))
  const dx = 1, x0 = (KEEP - 1) - (v.length - 1) * dx
  const slopes = step ? null : monotoneSlopes(ys, dx)
  const n = (x: number) => x.toFixed(2)
  let trace = ''
  for (let i = 1; i < ys.length; i++) {
    const xa = x0 + (i - 1) * dx, xb = xa + dx
    trace += slopes
      ? ` C${n(xa + dx / 3)} ${n(ys[i - 1] + slopes[i - 1] * dx / 3)} ${n(xb - dx / 3)} ${n(ys[i] - slopes[i] * dx / 3)} ${n(xb)} ${n(ys[i])}`
      : ` L${n(xb)} ${n(ys[i - 1])} L${n(xb)} ${n(ys[i])}`
  }
  const line = `M${n(x0)} ${n(ys[0])}${trace}`
  return { line, area: `M${n(x0)} ${h} L${n(x0)} ${n(ys[0])}${trace} L${KEEP - 1} ${h} Z` }
}

function Chart({ id, v, size }: { id: CellId; v: number[]; size: Size }) {
  const h = CHART[size]
  const p = chartPaths(v, STEP.includes(id), h)
  return (
    <svg class={`stat-chart c-${id}`} viewBox={`0 0 ${KEEP - 1} ${h}`} preserveAspectRatio="none" style={{ height: `${h}px` }} aria-hidden="true">
      {p && <><path class="area" d={p.area} /><path class="line" d={p.line} vector-effect="non-scaling-stroke" /></>}
    </svg>
  )
}

function Tile({ id, size, a, onInfo }: { id: CellId; size: Size; a: AnalysisStats; onInfo: (id: CellId, el: HTMLElement) => void }) {
  const v = value(id, a)
  const s = series(id, a)
  return (
    <div class={`stat ${size}`} data-cell={id}>
      <button type="button" class="stat-label" onClick={(e) => onInfo(id, e.currentTarget)} aria-label={`${label(id)}: ${info(id)}`}>
        <span>{label(id)}</span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" /></svg>
      </button>
      <div class="stat-value"><bdi dir="ltr"><span class="num">{v.num}</span>{v.unit && <span class="unit"> {v.unit}</span>}{v.after && <span class="unit"> · {v.after}</span>}</bdi></div>
      {s && CHART[size] > 0 && <Chart id={id} v={s} size={size} />}
    </div>
  )
}

export type VideoFacts = { width: number; height: number; fps: number; duration: number }

/** Copies [text]; the clipboard API needs a secure page and a gesture, else the old way. */
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return
  } catch {}
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy') } finally { ta.remove() }
}

export function AnalysisStatsPanel({ open, stats, video, percent, elapsed }: { open: boolean; stats: AnalysisStats | null; video: VideoFacts | null; percent: number; elapsed: () => number }) {
  const [more, setMore] = useState(false)
  const [copied, setCopied] = useState(false)
  const [bubble, setBubble] = useState<{ id: CellId; x: number; y: number; below: boolean } | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const tip = useRef<HTMLDivElement>(null)
  // Kept while the panel folds shut after the stats are gone.
  const kept = useRef<AnalysisStats | null>(null)
  if (stats) kept.current = stats
  const a = stats ?? kept.current

  useEffect(() => {
    if (!copied) return
    const h = setTimeout(() => setCopied(false), 2000)
    return () => clearTimeout(h)
  }, [copied])
  // A tap anywhere else (or Escape) hides the bubble.
  useEffect(() => {
    if (!bubble) return
    const away = (e: Event) => { if (!(e.target as Element).closest?.('.stat-label, .stat-tip')) setBubble(null) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setBubble(null) }
    document.addEventListener('pointerdown', away, true)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('keydown', esc) }
  }, [bubble?.id])
  useEffect(() => { if (!open) setBubble(null) }, [open])
  // The bubble above its label, centered on it and kept inside the panel (below when there's no room).
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
    if (!a || !video) return
    const app = `ONIT Web 2.0 (${__BUILD__}${BETA ? ', beta' : ''})`
    await copyText(statsReport(a, { app, device: await deviceLines(), video, elapsedSec: elapsed(), percent }))
    setCopied(true)
  }

  return (
    <div class={`sfold${open && a ? ' open' : ''}`} inert={!open}>
      <div class="sfold-in">
        <div class="stats" ref={root} data-testid="stats-panel">
          {a && MAIN.map(([size, ids]) => (
            <div class={`stats-row ${size}`} key={ids[0]}>{ids.map((id) => <Tile key={id} id={id} size={size} a={a} onInfo={showInfo} />)}</div>
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
                {a && DETAILS.map(([size, ids]) => (
                  <div class={`stats-row ${size}`} key={ids[0]}>{ids.map((id) => <Tile key={id} id={id} size={size} a={a} onInfo={showInfo} />)}</div>
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
