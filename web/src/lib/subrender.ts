// Draws one subtitle at a moment in time, as the Android app's SubtitleRenderer: the same code for
// the editor's preview and for burning into the video. Each word is laid out and drawn on its own,
// so it can be hidden until it's said, pop, fade, jump, shake or stand out in the accent color.
// Word times come from speech recognition when known; otherwise they're spread by word length.
import { fontFamily, fontWeight, type SubStyle } from './substyle'
import type { Word } from './subtitles'

const FADE_MS = 160, POP_MS = 260, WORD_POP_MS = 180, WORD_FADE_MS = 200, BOUNCE_MS = 260, SLIDE_MS = 260
const ZOOM_MS = 240, ROTATE_MS = 320, GLITCH_MS = 380, FLICKER_MS = 500, CURSOR_MS = 450
const FLICKER = [0.1, 0.9, 0.2, 1, 0.35, 1, 0.6, 1, 0.8, 1]
/** Words are spread over this part of the subtitle (speech ends a little before it does). */
const SPEECH_PART = 0.92

const easeOut = (x: number) => 1 - (1 - x) ** 3
const clamp01 = (x: number) => Math.min(1, Math.max(0, x))
/** Overshoot then settle: 0.55 -> 1.1 -> 1.0. */
export function pop(x: number): number {
  if (x >= 1) return 1
  if (x <= 0) return 0.55
  if (x < 0.6) { const u = x / 0.6; return 0.55 + 0.55 * (1 - (1 - u) * (1 - u)) }
  return 1.1 - 0.1 * ((x - 0.6) / 0.4)
}

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D
type W = { text: string; from: number; to: number }
const wordsOf = (text: string): W[] => [...text.matchAll(/\S+/g)].map((m) => ({ text: m[0], from: m.index!, to: m.index! + m[0].length }))
const rtlText = (s: string) => {
  const m = s.match(/[A-Za-zÀ-ɏ֐-ࣿ]/)
  return !!m && /[֐-ࣿ]/.test(m[0])
}

/** When each word of [text] starts: the recognized times if they fit, else spread by length. */
function starts(text: string, times: number[] | null | undefined, start: number, end: number): number[] {
  const ws = wordsOf(text)
  if (times && times.length === ws.length) return times
  const wt = ws.map((w) => w.text.length + 1)
  const total = wt.reduce((a, b) => a + b, 0) || 1
  const span = Math.max(0.001, end - start) * SPEECH_PART
  let acc = 0
  return wt.map((x) => { const s = start + (span * acc) / total; acc += x; return s })
}

/** The recognized start times of a subtitle's words (null if they don't match its text). */
export function cueWordTimes(text: string, start: number, end: number, words: Word[]): number[] | null {
  const n = wordsOf(text).length
  const ws = words.filter((w) => w.start >= start - 0.01 && w.start < end)
  return ws.length === n ? ws.map((w) => w.start) : null
}

const active = (st: number[], t: number) => { let a = -1; for (let i = 0; i < st.length; i++) { if (st[i] <= t) a = i; else break } return a }

type Look = { visible: boolean; alpha: number; scale: number; dy: number; rotate: number; accent: boolean }
type Placed = { w: W; x: number; width: number; line: number; look: Look }

/**
 * Draws a subtitle of [blocks] (paragraphs: the text, then its translation) shown from [start] to
 * [end] (seconds), at [t], into a [w] x [h] frame. [times]: per block, when each word starts.
 */
export function drawCue(ctx: Ctx, blocks: string[], start: number, end: number, t: number, st: SubStyle, w: number, h: number, times?: (number[] | null)[]) {
  if (!blocks.some((b) => b.trim()) || w <= 0 || h <= 0) return
  const effect = st.effect
  const oneWord = effect === 'oneWord'
  const base = Math.min(w, h) * 0.055 * st.size * (oneWord ? 1.25 : 1)
  const maxW = w * 0.86
  const family = fontFamily(st)
  const weight = fontWeight(st)
  const since = (t - start) * 1000, until = (end - t) * 1000

  // The whole subtitle's motion.
  let alpha = 1, scale = 1, dy = 0, rotate = 0, glitch = 0
  switch (effect) {
    case 'fade': alpha = clamp01(Math.min(since / FADE_MS, until / FADE_MS)); break
    case 'pop': scale = pop(since / POP_MS); alpha = clamp01(since / 70); break
    case 'slideUp': { const u = easeOut(clamp01(since / SLIDE_MS)); dy = (1 - u) * base * 1.4; alpha = Math.min(u, clamp01(until / FADE_MS)); break }
    case 'zoom': { const u = easeOut(clamp01(since / ZOOM_MS)); scale = 1.7 - 0.7 * u; alpha = u; break }
    case 'rotate': { const x = clamp01(since / ROTATE_MS); rotate = -16 * (1 - easeOut(x)); scale = pop(x); alpha = clamp01(since / 80); break }
    case 'glitch': if (since < GLITCH_MS) glitch = base * 0.28 * (1 - since / GLITCH_MS) * (Math.floor(since / 45) % 2 === 0 ? 1 : -1); break
    case 'flicker': if (since < FLICKER_MS) alpha = FLICKER[Math.min(FLICKER.length - 1, Math.max(0, Math.floor(since / 50)))]; break
  }

  // Each block laid out word by word (one word of it, in one-word mode).
  type Block = { text: string; size: number; lh: number; rtl: boolean; placed: Placed[]; lines: number; activeIdx: number; visibleEnd: number; typed: boolean }
  const laid: Block[] = []
  blocks.forEach((full, bi) => {
    if (!full.trim()) return
    const ws = wordsOf(full)
    const st0 = starts(full, times?.[bi], start, end)
    const a = active(st0, t)
    const size = laid.length === 0 ? base : base * 0.86
    let text = full
    let words = ws
    if (oneWord) {
      if (a < 0) return
      text = ws[a].text
      words = [{ text, from: 0, to: text.length }]
      scale = Math.min(scale, pop((t - st0[a]) * 1000 / WORD_POP_MS))
    }
    ctx.font = `${weight} ${size}px ${family}`
    const rtl = rtlText(text)
    const space = ctx.measureText(' ').width
    // Lines: as many words as fit.
    const lines: { items: { w: W; width: number }[]; width: number }[] = []
    for (const word of words) {
      const width = ctx.measureText(word.text).width
      const cur = lines[lines.length - 1]
      if (cur && cur.width + space + width <= maxW) { cur.items.push({ w: word, width }); cur.width += space + width }
      else lines.push({ items: [{ w: word, width }], width })
    }
    const placed: Placed[] = []
    lines.forEach((ln, li) => {
      let x = rtl ? (w + ln.width) / 2 : (w - ln.width) / 2
      for (const it of ln.items) {
        const left = rtl ? x - it.width : x
        placed.push({ w: it.w, x: left, width: it.width, line: li, look: { visible: true, alpha: 1, scale: 1, dy: 0, rotate: 0, accent: false } })
        x = rtl ? x - it.width - space : x + it.width + space
      }
    })
    // Per word: hidden until said, popping or fading in, the one being said standing out.
    if (!oneWord) {
      placed.forEach((p, i) => {
        const said = i <= a
        const inWord = said ? (t - st0[i]) * 1000 : 0
        const l = p.look
        switch (effect) {
          case 'wordPop': l.visible = said; if (said) l.scale = pop(inWord / WORD_POP_MS); break
          case 'wordFade': l.visible = said; if (said) { const u = clamp01(inWord / WORD_FADE_MS); l.alpha = u; l.dy = (1 - easeOut(u)) * size * 0.35 } break
          case 'activeZoom': if (i === a) { l.scale = 1.12; l.accent = true } break
          case 'bounce': if (i === a) { l.dy = -size * 0.3 * Math.sin(Math.PI * clamp01(inWord / BOUNCE_MS)); l.accent = true } break
          case 'shake': if (i === a) { l.rotate = 7 * Math.sin((2 * Math.PI * inWord) / 160); l.scale = 1.04; l.accent = true } break
          case 'highlight': if (i === a) l.accent = true; break
        }
      })
    }
    // How much of the text shows (letters as they're typed or said).
    let visibleEnd = text.length
    if (!oneWord) {
      if (effect === 'typewriter') visibleEnd = a < 0 ? 0 : ws[a].to
      else if (effect === 'letters') {
        if (a < 0) visibleEnd = 0
        else {
          const nextStart = a + 1 < st0.length ? st0[a + 1] : end
          const span = Math.max(0.001, (nextStart - st0[a]) * 0.7)
          const len = ws[a].to - ws[a].from
          visibleEnd = ws[a].from + Math.max(1, Math.min(len, Math.floor(len * clamp01((t - st0[a]) / span))))
        }
      } else if (effect === 'typing') {
        const perChar = Math.min(75, Math.max(28, ((end - start) * 1000 * 0.75) / Math.max(1, text.length)))
        visibleEnd = Math.min(text.length, Math.max(0, Math.floor(since / perChar)))
      }
    }
    laid.push({ text, size, lh: size * 1.24, rtl, placed, lines: lines.length, activeIdx: oneWord ? 0 : a, visibleEnd, typed: effect === 'typing' })
  })
  if (!laid.length) return
  const gap = base * 0.3
  const totalH = laid.reduce((s, b) => s + b.lines * b.lh, 0) + gap * (laid.length - 1)
  const margin = h * 0.04
  let top = Math.min(Math.max(h * st.position - totalH / 2, margin), Math.max(margin, h - margin - totalH))
  const cy = top + totalH / 2

  ctx.save()
  ctx.translate(0, dy)
  if (rotate) { ctx.translate(w / 2, cy); ctx.rotate((rotate * Math.PI) / 180); ctx.translate(-w / 2, -cy) }
  if (scale !== 1) { ctx.translate(w / 2, cy); ctx.scale(scale, scale); ctx.translate(-w / 2, -cy) }
  ctx.globalAlpha = alpha
  ctx.textBaseline = 'middle'
  for (const b of laid) {
    ctx.font = `${weight} ${b.size}px ${family}`
    ctx.direction = b.rtl ? 'rtl' : 'ltr'
    const lineY = (li: number) => top + b.lh * (li + 0.5)
    // A box behind each line (as far as it shows).
    if (st.box && b.visibleEnd > 0) {
      ctx.fillStyle = st.boxColor
      for (let li = 0; li < b.lines; li++) {
        const vis = b.placed.filter((p) => p.line === li && p.w.from < b.visibleEnd)
        if (!vis.length) continue
        const x0 = Math.min(...vis.map((p) => (b.rtl && p.w.to > b.visibleEnd ? p.x + p.width - partWidth(ctx, p, b.visibleEnd) : p.x)))
        const x1 = Math.max(...vis.map((p) => (!b.rtl && p.w.to > b.visibleEnd ? p.x + partWidth(ctx, p, b.visibleEnd) : p.x + p.width)))
        const hx = b.size * 0.32, vy = b.size * 0.08
        roundRect(ctx, x0 - hx, lineY(li) - b.lh / 2 - vy, x1 - x0 + 2 * hx, b.lh + 2 * vy, b.size * 0.3)
      }
    }
    // The word being said on a box in the accent color.
    if (effect === 'wordBox' && b.activeIdx >= 0 && b.placed[b.activeIdx]) {
      const p = b.placed[b.activeIdx]
      ctx.fillStyle = st.accent
      roundRect(ctx, p.x - b.size * 0.2, lineY(p.line) - b.lh / 2 - b.size * 0.06, p.width + b.size * 0.4, b.lh + b.size * 0.12, b.size * 0.24)
    }
    if (b.typed) drawCursor(ctx, b, lineY, st, since)
    // Red and cyan copies on either side.
    if (glitch) {
      for (const [dx, color] of [[glitch, 'rgba(255,45,85,0.8)'], [-glitch, 'rgba(61,242,255,0.8)']] as [number, string][]) {
        ctx.save()
        ctx.translate(dx, 0)
        ctx.fillStyle = color
        for (const p of b.placed) fillWord(ctx, p, lineY(p.line), b, b.visibleEnd)
        ctx.restore()
      }
    }
    const plain = b.placed.filter((p) => isPlain(p.look))
    passes(ctx, plain, b, lineY, st, b.visibleEnd, -1)
    for (const p of b.placed) {
      const l = p.look
      if (isPlain(l) || !l.visible || l.alpha <= 0.01) continue
      const wcx = p.x + p.width / 2, wcy = lineY(p.line)
      ctx.save()
      ctx.globalAlpha = alpha * l.alpha
      ctx.translate(0, l.dy)
      ctx.translate(wcx, wcy)
      if (l.rotate) ctx.rotate((l.rotate * Math.PI) / 180)
      if (l.scale !== 1) ctx.scale(l.scale, l.scale)
      ctx.translate(-wcx, -wcy)
      passes(ctx, [p], b, lineY, st, b.visibleEnd, l.accent ? b.placed.indexOf(p) : -1)
      ctx.restore()
    }
    top += b.lines * b.lh + gap
  }
  ctx.restore()
}

const isPlain = (l: Look) => l.visible && l.alpha >= 0.999 && l.scale === 1 && l.dy === 0 && l.rotate === 0 && !l.accent

/** The width of the part of a word that shows (up to character [end] of its block). */
function partWidth(ctx: Ctx, p: Placed, end: number): number {
  if (end >= p.w.to) return p.width
  if (end <= p.w.from) return 0
  return ctx.measureText(p.w.text.slice(0, end - p.w.from)).width
}

/** A word (or the part of it that shows), filled in the current style. */
function fillWord(ctx: Ctx, p: Placed, y: number, b: { rtl: boolean }, end: number, stroke = false) {
  if (end <= p.w.from) return
  const part = end >= p.w.to ? p.w.text : p.w.text.slice(0, end - p.w.from)
  // A partly shown word grows from its start: the right edge in Hebrew, the left in English.
  ctx.textAlign = b.rtl ? 'right' : 'left'
  const x = b.rtl ? p.x + p.width : p.x
  if (stroke) ctx.strokeText(part, x, y)
  else ctx.fillText(part, x, y)
}

/** Glow, outline (or shadow) and fill passes over [ps]; word [accent] of the block in the accent color. */
function passes(ctx: Ctx, ps: Placed[], b: { rtl: boolean; size: number; placed: Placed[] }, lineY: (l: number) => number, st: SubStyle, end: number, accent: number) {
  if (!ps.length) return
  const size = b.size
  const highlight = st.effect === 'highlight'
  if (st.glow) {
    ctx.save()
    ctx.fillStyle = st.color
    ctx.shadowColor = st.color
    ctx.shadowBlur = size * 0.5
    for (let k = 0; k < 2; k++) for (const p of ps) fillWord(ctx, p, lineY(p.line), b, end)
    ctx.restore()
  }
  if (st.outline > 0 || st.shadow) {
    ctx.save()
    if (st.shadow) { ctx.shadowColor = 'rgba(0,0,0,0.85)'; ctx.shadowBlur = size * 0.12; ctx.shadowOffsetY = size * 0.05 }
    if (st.outline > 0) {
      ctx.strokeStyle = st.outlineColor
      ctx.lineWidth = size * st.outline
      ctx.lineJoin = 'round'
      for (const p of ps) fillWord(ctx, p, lineY(p.line), b, end, true)
    } else {
      ctx.fillStyle = st.outlineColor
      for (const p of ps) fillWord(ctx, p, lineY(p.line), b, end)
    }
    ctx.restore()
  }
  for (const p of ps) {
    const i = b.placed.indexOf(p)
    ctx.fillStyle = i === accent || (highlight && p.look.accent) ? st.accent : st.color
    fillWord(ctx, p, lineY(p.line), b, end)
  }
}

function roundRect(ctx: Ctx, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.roundRect(x, y, w, h, r)
  ctx.fill()
}

/** A text cursor right after the last typed letter: solid while typing, then blinking. */
function drawCursor(ctx: Ctx, b: { text: string; size: number; lh: number; rtl: boolean; placed: Placed[]; visibleEnd: number }, lineY: (l: number) => number, st: SubStyle, sinceMs: number) {
  if (b.visibleEnd >= b.text.length && Math.floor(sinceMs / CURSOR_MS) % 2 === 1) return
  const at = b.visibleEnd
  let p = b.placed.find((q) => q.w.from < at && at <= q.w.to + 1) ?? b.placed.filter((q) => q.w.from < at).pop()
  let x: number, line: number
  if (!p || at === 0) { p = b.placed[0]; line = p.line; x = b.rtl ? p.x + p.width : p.x }
  else {
    line = p.line
    const wpart = partWidth(ctx, p, at)
    x = b.rtl ? p.x + p.width - wpart - b.size * 0.06 : p.x + wpart + b.size * 0.06
  }
  const y = lineY(line)
  ctx.fillStyle = st.color
  ctx.fillRect(x - b.size * 0.045, y - b.lh / 2 + b.size * 0.12, b.size * 0.09, b.lh - b.size * 0.2)
}
