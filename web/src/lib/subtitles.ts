import type { CutMap } from './cutmap'
// Subtitles: words from speech recognition -> cues, how they look, drawing them, and SRT.

export interface Word { start: number; end: number; text: string }
export interface Cue {
  id: number
  start: number
  end: number
  text: string
  /** Its translation, if it was translated. */
  tr?: string
  /** Who says it (1..; none: like speaker 1). */
  speaker?: number
  /**
   * The stretch of the video whose words this subtitle holds, once its edges were moved by hand (until then it is
   * start..end). The words (and the bleeps on them) stay where they were said: moving an edge changes when the text
   * shows, never which words it holds.
   */
  ws?: number
  we?: number
}

/** Where the words of [c] start (see [Cue.ws]). */
export const winStart = (c: Cue) => c.ws ?? c.start
/** Where the words of [c] end. */
export const winEnd = (c: Cue) => c.we ?? c.end
/** Whether the word starting at [at] is one of [c]'s. */
// (Shifted by 10 ms as a whole, so the edges that two halves of a split share belong to exactly one of them.)
export const holds = (c: Cue, at: number) => at >= winStart(c) - 0.01 && at < winEnd(c) - 0.01

/** The shortest a subtitle can be made by hand (as the app: 100 ms). */
export const MIN_CUE = 0.1

/** [c] with its edge moved to [at] (clamped to the video and the neighbours [lo], [hi]), its words kept. */
export function retimeCue(c: Cue, start: boolean, at: number, lo: number, hi: number): Cue {
  const ws = winStart(c), we = winEnd(c)
  if (start) return { ...c, ws, we, start: Math.max(lo, Math.min(c.end - MIN_CUE, at)) }
  return { ...c, ws, we, end: Math.min(hi, Math.max(c.start + MIN_CUE, at)) }
}

/** The room [c] has to move its edges in: the end of the one before it and the start of the one after (or the video's ends). */
export function cueRoom(cues: Cue[], c: Cue, duration: number): [number, number] {
  let lo = 0, hi = duration
  for (const x of cues) {
    if (x.id === c.id) continue
    if (x.end <= c.start + 0.001) lo = Math.max(lo, x.end)
    else if (x.start >= c.end - 0.001) hi = Math.min(hi, x.start)
  }
  return [lo, hi]
}

/**
 * [a] and the cue after it as one (the text and translation joined, the words of both). Null when a word hidden in one of
 * them would show in the merged cue (given [words] and [bleeped]).
 */
export function mergeCues(a: Cue, b: Cue, words?: Word[], bleeped?: Set<number>): Cue | null {
  const tr = a.tr || b.tr ? [a.tr ?? '', b.tr ?? ''].join(' ').trim() : undefined
  const merged: Cue = { ...a, end: b.end, text: (a.text + ' ' + b.text).trim(), tr }
  const ws = Math.min(winStart(a), winStart(b)), we = Math.max(winEnd(a), winEnd(b))
  if (ws !== merged.start) merged.ws = ws
  else delete merged.ws
  if (we !== merged.end) merged.we = we
  else delete merged.we
  if (words && bleeped && bleeped.size > 0 && leaks(merged, words, bleeped, new Set([...hidden(a, words, bleeped), ...hidden(b, words, bleeped)]))) return null
  return merged
}

export type { SubShow, SubStyle } from './substyle'
export { DEFAULT_SUB_STYLE } from './substyle'
import type { SubShow } from './substyle'

export const LANGUAGES: [string | null, string][] = [[null, 'אוטומטי'], ['hebrew', 'עברית'], ['arabic', 'ערבית'], ['english', 'אנגלית'], ['persian', 'פרסית']]

const PAUSE = 0.7
const MAX_CHARS = 64
let nextCue = 1

export function isNoise(t: string) {
  const s = t.trim()
  return !s || (s.startsWith('[') && s.endsWith(']')) || (s.startsWith('(') && s.endsWith(')')) || !/[\p{L}\p{N}]/u.test(s)
}

/**
 * Words -> cues, as the Android app makes them: a new one after . ? ! or a pause, or when it
 * would grow past 64 characters ([maxWords]: also after that many words, evenly). At least 0.8 s
 * on screen, no overlaps.
 */
export function wordsToCues(words: Word[], maxWords = 0, maxChars = 0): Cue[] {
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
  if (maxWords > 0) {
    // Each sentence in nearly equal parts of at most [maxWords].
    const split: Word[][] = []
    for (const g of groups) {
      const parts = Math.ceil(g.length / maxWords)
      const size = Math.ceil(g.length / parts)
      for (let i = 0; i < g.length; i += size) split.push(g.slice(i, i + size))
    }
    groups.splice(0, groups.length, ...split)
  } else if (maxChars > 0) {
    // Each sentence cut before the word that would pass [maxChars] (as the app's split by characters).
    const split: Word[][] = []
    for (const g of groups) {
      let part: Word[] = []
      let len = 0
      for (const w of g) {
        const n = w.text.trim().length
        const add = len === 0 ? n : len + 1 + n
        if (len > 0 && add > maxChars) { split.push(part); part = [w]; len = n }
        else { part.push(w); len = add }
      }
      if (part.length) split.push(part)
    }
    groups.splice(0, groups.length, ...split)
  }
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

/** New subtitles get ids after [cues]' (a project opened again). */
export function reserveCueIds(cues: Cue[]) {
  for (const c of cues) nextCue = Math.max(nextCue, c.id + 1)
}

export function cueAt(cues: Cue[], t: number): Cue | null {
  for (const c of cues) if (t >= c.start && t < c.end) return c
  return null
}

function srtTime(t: number) {
  const ms = Math.max(0, Math.round(t * 1000))
  const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, s = Math.floor(ms / 1000) % 60
  const pad = (n: number, l = 2) => String(n).padStart(l, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)},${pad(ms % 1000, 3)}`
}

/** The subtitles at their times in the result, without what was cut (a cue cut whole is left out). */
export function cutCues(cues: Cue[], cuts: CutMap): Cue[] {
  if (cuts.empty) return cues
  const out: Cue[] = []
  for (const c of [...cues].sort((a, b) => a.start - b.start)) {
    const kept = cuts.kept(c.start, c.end)
    if (kept < 0.05) continue
    const from = cuts.isCut(c.start) ? cuts.cutEnd(c.start) : c.start
    const start = cuts.out(from)
    out.push({ ...c, start, end: start + kept })
  }
  return out
}

/** Subtitles from an SRT file (times in seconds; HTML-like tags dropped). */
export function parseSrt(text: string): Cue[] {
  const out: Cue[] = []
  const time = (h: string, m: string, s: string, ms: string) => Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(ms.padEnd(3, '0')) / 1000
  for (const block of text.replace(/\r/g, '').split(/\n\s*\n/)) {
    const lines = block.split('\n').filter((l) => l.trim())
    const k = lines.findIndex((l) => l.includes('-->'))
    if (k < 0) continue
    const m = lines[k].match(/(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)/)
    if (!m) continue
    const body = lines.slice(k + 1).join('\n').replace(/<[^>]+>/g, '').trim()
    if (!body) continue
    const start = time(m[1], m[2], m[3], m[4]), end = time(m[5], m[6], m[7], m[8])
    if (end > start) out.push({ id: nextCue++, start, end, text: body.replace(/\n/g, ' ') })
  }
  return out.sort((a, b) => a.start - b.start)
}

export function toSrt(cues: Cue[]): string {
  return cues.map((c, i) => `${i + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n`).join('\n')
}

/** What the video shows of [c] ([text]: its text with the bleeped words masked). */
export function shownText(c: Cue, text: string, show: SubShow | undefined): string {
  const tr = c.tr?.trim()
  if (!tr || !show || show === 'src') return text
  return show === 'tr' ? tr : text + '\n' + tr
}

/** A word as compared: lower case, the punctuation around it gone (letters of any script kept). */
export const normWord = (w: string) => w.replace(/^[^\p{L}\p{N}\p{M}]+|[^\p{L}\p{N}\p{M}]+$/gu, '').toLowerCase()

const HEBREW_PREFIX = 'ובכלמשה'
/** The forms of a token that count as a bleeped word: itself, and without one or two Hebrew prefix letters (ו, ה, ב, כ, ל, מ, ש). */
function forms(norm: string): string[] {
  const out = [norm]
  if (/^[\u0590-\u05ff]/.test(norm)) {
    if (norm.length > 2 && HEBREW_PREFIX.includes(norm[0])) out.push(norm.slice(1))
    if (norm.length > 3 && HEBREW_PREFIX.includes(norm[0]) && HEBREW_PREFIX.includes(norm[1])) out.push(norm.slice(2))
  }
  return out
}

/** The outer edges of the words a cue masks: a little wider than [holds], so a word at an edge is never missed. */
const maskHolds = (c: Cue, at: number) => at >= winStart(c) - 0.01 && at < winEnd(c) + 0.01

/** The normalized text of the bleeped words that start inside [cue]'s stretch. */
function hidden(cue: Cue, words: Word[], bleeped: Set<number>): Set<string> {
  const out = new Set<string>()
  for (const k of bleeped) {
    const w = words[k]
    if (!w || !maskHolds(cue, w.start)) continue
    const n = normWord(w.text)
    if (n) out.add(n)
  }
  return out
}

// (A token joined with a hyphen or a maqaf, "ו-כלב", counts by each of its parts too: the whole token is hidden.)
const isHit = (token: string, hide: Set<string>) => {
  const n = normWord(token)
  return n !== '' && [n, ...n.split(/[-\u05be]+/).map(normWord).filter(Boolean)].some((p) => forms(p).some((f) => hide.has(f)))
}

/**
 * The cue's text with its bleeped words as ****: every token whose text equals that of a bleeped word that starts inside the
 * cue's stretch, wherever it stands in the text (a word hidden twice is better than one shown). Whole tokens only, the
 * punctuation around them kept (always 4 stars, as the app, so the length doesn't hint at the word).
 */
export function maskCue(cue: Cue, words: Word[], bleeped: Set<number>): string {
  if (bleeped.size === 0) return cue.text
  const hide = hidden(cue, words, bleeped)
  if (hide.size === 0) return cue.text
  return cue.text.replace(/\S+/g, (tok) => {
    if (!isHit(tok, hide)) return tok
    const m = /^([^\p{L}\p{N}\p{M}]*)[\s\S]*?([^\p{L}\p{N}\p{M}]*)$/u.exec(tok)
    return (m?.[1] ?? '') + '****' + (m?.[2] ?? '')
  })
}

/** Whether [cue] shows a token equal to one of [hide] (the texts of words that were hidden) unmasked. */
function leaks(cue: Cue, words: Word[], bleeped: Set<number>, hide: Set<string>): boolean {
  const shown = maskCue(cue, words, bleeped).match(/\S+/g) ?? []
  return shown.some((tok) => isHit(tok, hide))
}

/**
 * [cue] in two at character [at] of its text: the text and the words split at the same place (the first word whose token is
 * at or after [at] starts the second half), the time where that word starts, else by length.
 * Null when it can't be split, or when a hidden word would show in either half (given [bleeped]).
 */
export function splitCue(cue: Cue, at: number, words: Word[], bleeped?: Set<number>): [Cue, Cue] | null {
  const a = cue.text.slice(0, at).trim(), b = cue.text.slice(at).trim()
  if (!a || !b) return null
  // A cue too short to hold two (0.2 s each) is not split.
  if (cue.end - cue.start < 0.4 - 1e-6) return null
  // The words of the stretch, each matched to the next token of the text with the same text.
  const tokens = [...cue.text.matchAll(/\S+/g)]
  let cursor = 0
  let boundary: number | null = null
  for (const w of words) {
    if (!holds(cue, w.start)) continue
    const n = normWord(w.text)
    let i = cursor
    while (i < tokens.length && normWord(tokens[i][0]) !== n) i++
    if (i >= tokens.length) continue
    cursor = i + 1
    if (boundary === null && tokens[i].index! >= at) boundary = w.start
  }
  const len = cue.end - cue.start
  let t = boundary ?? cue.start + len * (a.length / (a.length + b.length))
  t = Math.min(cue.end - 0.2, Math.max(cue.start + 0.2, t))
  const first: Cue = { ...cue, end: t, text: a, tr: undefined }
  const second: Cue = { ...cue, id: newCueId(), start: t, text: b, tr: undefined, speaker: cue.speaker }
  // The words' stretch is shared out exactly at the word where the text splits (even when [t] was held back from an edge).
  // The halves' stretches meet, they don't overlap.
  const w = boundary ?? t
  first.ws = winStart(cue); first.we = w; second.ws = w; second.we = winEnd(cue)
  for (const c of [first, second]) { if (c.ws === c.start) delete c.ws; if (c.we === c.end) delete c.we }
  if (bleeped && bleeped.size > 0) {
    const hide = hidden(cue, words, bleeped)
    if (hide.size > 0 && (leaks(first, words, bleeped, hide) || leaks(second, words, bleeped, hide))) return null
  }
  return [first, second]
}

/** [cue] split at the playhead [time]: before the first word that starts at or after it (as the app's split at the word nearest to the playhead). */
export function splitCueAtTime(cue: Cue, time: number, words: Word[], bleeped?: Set<number>): [Cue, Cue] | null {
  const tokens = [...cue.text.matchAll(/\S+/g)]
  const inCue = words.filter((w) => holds(cue, w.start))
  let k = inCue.filter((w) => w.start < time - 0.02).length
  if (tokens.length < 2) return null
  // With no timed words (or words that do not match the text): by the playhead's place in the time.
  if (inCue.length !== tokens.length) k = Math.round(tokens.length * Math.min(1, Math.max(0, (time - cue.start) / Math.max(0.001, cue.end - cue.start))))
  k = Math.min(tokens.length - 1, Math.max(1, k))
  const at = tokens[k].index!
  return splitCue(cue, at, words, bleeped)
}

/** The time stretches of the bleeped words. */
export function bleepRanges(words: Word[], bleeped: Set<number>) {
  return [...bleeped].sort((a, b) => a - b).map((k) => ({ start: words[k].start, end: words[k].end }))
}
