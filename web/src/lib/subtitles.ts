// Subtitles: words from speech recognition -> cues, how they look, drawing them, and SRT.

export interface Word { start: number; end: number; text: string }
export interface Cue { id: number; start: number; end: number; text: string }

export type SubPosition = 'bottom' | 'middle' | 'top'
export type SubLook = 'box' | 'outline' | 'shadow'

export interface SubStyle {
  /** Multiplier of the base size (5.5% of the short side). */
  size: number
  position: SubPosition
  look: SubLook
  color: string
  weight: number
}

export const DEFAULT_SUB_STYLE: SubStyle = { size: 1, position: 'bottom', look: 'box', color: '#ffffff', weight: 700 }

export const LANGUAGES: [string | null, string][] = [[null, 'אוטומטי'], ['hebrew', 'עברית'], ['arabic', 'ערבית'], ['english', 'אנגלית'], ['persian', 'פרסית']]

const PAUSE = 0.7
const MAX_CHARS = 64
let nextCue = 1

function isNoise(t: string) {
  const s = t.trim()
  return !s || (s.startsWith('[') && s.endsWith(']')) || (s.startsWith('(') && s.endsWith(')')) || !/[\p{L}\p{N}]/u.test(s)
}

/**
 * Words -> cues, as the Android app makes them: a new one after . ? ! or a pause, or when it
 * would grow past 64 characters. At least 0.8 s on screen, no overlaps.
 */
export function wordsToCues(words: Word[]): Cue[] {
  const sorted = words.filter((w) => !isNoise(w.text)).sort((a, b) => a.start - b.start)
  const groups: Word[][] = []
  let cur: Word[] = []
  let chars = 0
  sorted.forEach((w, i) => {
    cur.push(w)
    chars += w.text.trim().length + 1
    const next = sorted[i + 1]
    const end = /[.?!…]$/.test(w.text.trim())
    const pause = next !== undefined && next.start - w.end > PAUSE
    const full = next !== undefined && chars + next.text.trim().length > MAX_CHARS
    if (!next || end || pause || full) {
      groups.push(cur)
      cur = []
      chars = 0
    }
  })
  return groups.map((g, i) => {
    const a = g[0].start
    const nextStart = groups[i + 1]?.[0].start ?? Infinity
    const b = Math.min(Math.max(g[g.length - 1].end, a + 0.8), nextStart)
    return { id: nextCue++, start: a, end: Math.max(b, a + 0.2), text: g.map((w) => w.text.trim()).join(' ') }
  })
}

export function newCueId() {
  return nextCue++
}

export function cueAt(cues: Cue[], t: number): Cue | null {
  for (const c of cues) if (t >= c.start && t < c.end) return c
  return null
}

function luminance(hex: string) {
  const n = parseInt(hex.slice(1), 16)
  const f = (c: number) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4 }
  return 0.2126 * f(n >> 16 & 255) + 0.7152 * f(n >> 8 & 255) + 0.0722 * f(n & 255)
}

/** Lines that fit [maxWidth] (words kept whole). */
function wrap(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const words = text.split(/\s+/).filter(Boolean)
  const lines: string[] = []
  let line = ''
  for (const w of words) {
    const t = line ? line + ' ' + w : w
    if (line && ctx.measureText(t).width > maxWidth) {
      lines.push(line)
      line = w
    } else line = t
  }
  if (line) lines.push(line)
  return lines
}

/** Draws a cue's [text] on a [width] x [height] frame. */
export function drawSubtitle(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, text: string, s: SubStyle, width: number, height: number, family = "'Allo Hebrew', system-ui, sans-serif") {
  if (!text.trim()) return
  const short = Math.min(width, height)
  const fontSize = short * 0.055 * s.size
  ctx.save()
  ctx.font = `${s.weight} ${fontSize}px ${family}`
  ctx.direction = /[֐-ࣿ]/.test(text) ? 'rtl' : 'ltr'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  const lines = wrap(ctx, text, width * 0.86)
  const lh = fontSize * 1.28
  const blockH = lines.length * lh
  const margin = height * 0.08
  const top = s.position === 'top' ? margin : s.position === 'middle' ? (height - blockH) / 2 : height - margin - blockH
  const cx = width / 2
  const contrast = luminance(s.color) > 0.45 ? '#000000' : '#ffffff'
  lines.forEach((line, k) => {
    const y = top + lh * (k + 0.5)
    if (s.look === 'box') {
      const w = ctx.measureText(line).width
      const padX = fontSize * 0.4, padY = fontSize * 0.14
      ctx.fillStyle = contrast === '#000000' ? 'rgba(0,0,0,0.62)' : 'rgba(255,255,255,0.8)'
      ctx.beginPath()
      ctx.roundRect(cx - w / 2 - padX, y - lh / 2 + padY / 2, w + 2 * padX, lh - padY, fontSize * 0.22)
      ctx.fill()
    } else if (s.look === 'outline') {
      ctx.lineJoin = 'round'
      ctx.lineWidth = fontSize * 0.16
      ctx.strokeStyle = contrast
      ctx.strokeText(line, cx, y)
    } else {
      ctx.shadowColor = 'rgba(0,0,0,0.85)'
      ctx.shadowBlur = fontSize * 0.25
      ctx.shadowOffsetY = fontSize * 0.05
    }
    ctx.fillStyle = s.color
    ctx.fillText(line, cx, y)
    ctx.shadowColor = 'transparent'
  })
  ctx.restore()
}

function srtTime(t: number) {
  const ms = Math.max(0, Math.round(t * 1000))
  const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, s = Math.floor(ms / 1000) % 60
  const pad = (n: number, l = 2) => String(n).padStart(l, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)},${pad(ms % 1000, 3)}`
}

export function toSrt(cues: Cue[]): string {
  return cues.map((c, i) => `${i + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n`).join('\n')
}

/** The cue's text with its bleeped words as **** (the words timed inside the cue). */
export function maskCue(cue: Cue, words: Word[], bleeped: Set<number>): string {
  if (bleeped.size === 0) return cue.text
  let text = cue.text
  let from = 0
  words.forEach((w, k) => {
    if (w.start < cue.start - 0.01 || w.start >= cue.end) return
    const word = w.text.trim()
    const at = text.indexOf(word, from)
    if (at < 0) return
    if (bleeped.has(k)) text = text.slice(0, at) + '*'.repeat(Math.max(3, [...word].length)) + text.slice(at + word.length)
    from = at + 1
  })
  return text
}

/** The time stretches of the bleeped words. */
export function bleepRanges(words: Word[], bleeped: Set<number>) {
  return [...bleeped].sort((a, b) => a - b).map((k) => ({ start: words[k].start, end: words[k].end }))
}
