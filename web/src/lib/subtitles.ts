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
   * The words this subtitle holds: their indices in the recording's word list, in the order of the text (as the app, where a
   * subtitle's words are its text's words). Split shares them out, merge joins them, moving an edge keeps them, a subtitle
   * imported from a file has none. Which words are bleeped, and so masked in the text, goes by these - never by time.
   */
  wi?: number[]
  /**
   * The text its translation was made from (the masked text, as the video shows it). The translation shows only while that is
   * what the subtitle shows now (see [trVisible]); with no bleeped words that is the text itself.
   */
  trm?: string
}

/** The words of [c] (indices into the word list). */
export const cueWi = (c: Cue): number[] => c.wi ?? []

/** The shortest a subtitle can be made by hand (as the app: 100 ms). */
export const MIN_CUE = 0.1

/** [c] with its edge moved to [at] (clamped to the video and the neighbours [lo], [hi]); its words are kept. */
export function retimeCue(c: Cue, start: boolean, at: number, lo: number, hi: number): Cue {
  if (start) return { ...c, start: Math.max(lo, Math.min(c.end - MIN_CUE, at)) }
  return { ...c, end: Math.min(hi, Math.max(c.start + MIN_CUE, at)) }
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
 * [a] and the cue after it as one: the text and the words of both. The translation is joined with the texts it was made
 * from, so it shows only if the masked text of the result is what they make together (see [trVisible]).
 */
export function mergeCues(a: Cue, b: Cue): Cue {
  const tr = a.tr || b.tr ? [a.tr ?? '', b.tr ?? ''].join(' ').trim() : undefined
  const merged: Cue = { ...a, end: b.end, text: (a.text + ' ' + b.text).trim(), tr, wi: [...cueWi(a), ...cueWi(b)] }
  if (tr !== undefined) merged.trm = [a.trm ?? a.text, b.trm ?? b.text].join(' ').trim()
  else delete merged.trm
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
  // Each word with its place in [words]: a subtitle keeps these.
  const sorted = words.map((w, i) => ({ w, i })).filter((x) => !isNoise(x.w.text)).sort((a, b) => a.w.start - b.w.start)
  const groups: { w: Word; i: number }[][] = []
  let cur: { w: Word; i: number }[] = []
  let chars = 0
  sorted.forEach((x, i) => {
    cur.push(x)
    chars += x.w.text.trim().length + 1
    const next = sorted[i + 1]?.w
    const end = /[.?!…؟۔]$/.test(x.w.text.trim())
    const pause = next !== undefined && next.start - x.w.end > PAUSE
    const full = next !== undefined && chars + next.text.trim().length > MAX_CHARS
    if (!next || end || pause || full) {
      groups.push(cur)
      cur = []
      chars = 0
    }
  })
  if (maxWords > 0) {
    // Each sentence in nearly equal parts of at most [maxWords].
    const split: typeof groups = []
    for (const g of groups) {
      const parts = Math.ceil(g.length / maxWords)
      const size = Math.ceil(g.length / parts)
      for (let i = 0; i < g.length; i += size) split.push(g.slice(i, i + size))
    }
    groups.splice(0, groups.length, ...split)
  } else if (maxChars > 0) {
    // Each sentence cut before the word that would pass [maxChars] (as the app's split by characters).
    const split: typeof groups = []
    for (const g of groups) {
      let part: typeof g = []
      let len = 0
      for (const x of g) {
        const n = x.w.text.trim().length
        const add = len === 0 ? n : len + 1 + n
        if (len > 0 && add > maxChars) { split.push(part); part = [x]; len = n }
        else { part.push(x); len = add }
      }
      if (part.length) split.push(part)
    }
    groups.splice(0, groups.length, ...split)
  }
  return groups.map((g, i) => {
    const a = g[0].w.start
    const nextStart = groups[i + 1]?.[0].w.start ?? Infinity
    const b = Math.min(Math.max(g[g.length - 1].w.end, a + 0.8), nextStart)
    return { id: nextCue++, start: a, end: Math.max(b, a + 0.2), text: g.map((x) => x.w.text.trim()).join(' '), wi: g.map((x) => x.i) }
  })
}

/**
 * Subtitles saved before they carried their words (or made by hand): each gets the words that were inside its time stretch
 * (as it was then), once. Those that already have words are as they are.
 */
export function migrateCues(cues: Cue[], words: Word[]): Cue[] {
  if (cues.every((c) => c.wi !== undefined)) return cues
  // A word goes to the first subtitle (by time) whose stretch has it, never to two.
  const taken = new Set<number>()
  const order = [...cues].sort((a, b) => a.start - b.start)
  const got = new Map<number, number[]>()
  for (const c of order) {
    if (c.wi !== undefined) { for (const k of c.wi) taken.add(k); continue }
    const { ws, we } = c as Cue & { ws?: number; we?: number }
    const a = (ws ?? c.start) - 0.01, b = we ?? c.end
    const mine = words.map((w, k) => (!taken.has(k) && w.start >= a && w.start < b && !isNoise(w.text) ? k : -1)).filter((k) => k >= 0)
    for (const k of mine) taken.add(k)
    got.set(c.id, mine)
  }
  return cues.map((c) => {
    if (c.wi !== undefined) return c
    const { ws, we, ...rest } = c as Cue & { ws?: number; we?: number }
    return { ...rest, wi: got.get(c.id) ?? [] }
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

/** Whether most of [a, b) is cut (as the app's cutCovers). */
const covers = (cuts: CutMap, a: number, b: number) => b > a && (b - a - cuts.kept(a, b)) * 2 >= b - a

/**
 * [cues] (raw: the spoken text and its words, not what shows) with the words cut out of the video taken out, as the app's exportCues:
 * a partly cut subtitle loses its cut words (and its translation, which is not aligned to them), one with no word left goes.
 * Times stay the source's. A word is cut when most of its span is. See [goneTokens] for how the words are found. Line breaks of the
 * text stay, and the subtitle's words list ([Cue.wi]) follows the words that stay.
 */
export function dropCutWords(cues: Cue[], cuts: CutMap, words: Word[] = []): Cue[] {
  if (cuts.empty) return cues
  const out: Cue[] = []
  for (const c of cues) {
    const toks = tokensOf(c.text)
    const r = goneTokens(c, toks.map((x) => x.t), words, cuts)
    if (r === 'all') continue
    if (r.gone.size === 0) { out.push(c); continue }
    const keep = toks.map((_, i) => i).filter((i) => !r.gone.has(i))
    if (keep.length === 0) continue
    let text = ''
    keep.forEach((i, j) => { text += (j > 0 ? c.text.slice(i > 0 ? toks[i - 1].i + toks[i - 1].t.length : 0, toks[i].i) : '') + toks[i].t })
    const wi = cueWi(c)
    const next = c.wi === undefined ? undefined : r.fits ? keep.map((i) => wi[i]) : wi.filter((k) => !r.goneWi.has(k))
    out.push({ ...c, text, tr: undefined, trm: undefined, wi: next })
  }
  return out
}

/** [cues] in the cut video's time (a subtitle cut out entirely is left out); [words] given: [dropCutWords] first (raw cues only). */
export function cutCues(cues: Cue[], cuts: CutMap, words?: Word[]): Cue[] {
  if (cuts.empty) return cues
  const out: Cue[] = []
  for (const c of [...(words ? dropCutWords(cues, cuts, words) : cues)].sort((a, b) => a.start - b.start)) {
    const kept = cuts.kept(c.start, c.end)
    if (kept < 0.05) continue
    const from = cuts.isCut(c.start) ? cuts.cutEnd(c.start) : c.start
    const start = cuts.out(from)
    out.push({ ...c, start, end: start + kept })
  }
  return out
}

/**
 * Which of [toks] (the words of [c]'s text) are cut out of the video (indices), or 'all' when a cut word can't be found in the text
 * (the whole subtitle goes rather than keep a cut word in). [fits]: the tokens are the subtitle's words one for one.
 * - The text's words are the subtitle's words (as made): a word is cut when most of its span is.
 * - The text was edited (the count differs): the cut words of the subtitle are found among the tokens by their text.
 * - An imported subtitle (no words): the recognized words inside its time are found the same way; with none recognized there, the
 *   subtitle's time is spread over its words by length, and only a word wholly inside a cut goes (a cut in a pause leaves it as it is).
 */
function goneTokens(c: Cue, toks: string[], words: Word[], cuts: CutMap): { gone: Set<number>; fits: boolean; goneWi: Set<number> } | 'all' {
  const gone = new Set<number>(), goneWi = new Set<number>()
  const none = { gone, fits: false, goneWi }
  if (toks.length === 0) return none
  const wi = cueWi(c)
  const cutAt = (k: number) => !!words[k] && covers(cuts, words[k].start, words[k].end)
  if (wi.length === toks.length && wi.every((k) => words[k])) {
    wi.forEach((k, i) => { if (cutAt(k)) gone.add(i) })
    return { gone, fits: true, goneWi }
  }
  // Cut words (of the subtitle's, or the recognized ones inside its time) found among the tokens by their text, nearest in place first.
  const find = (cutKs: number[], of: number[]): boolean => {
    for (const k of cutKs) {
      const keys = new Set([keyOf(words[k].text)])
      const place = (of.indexOf(k) / Math.max(1, of.length)) * toks.length
      let best = -1
      toks.forEach((t, i) => { if (!gone.has(i) && hit(t, keys) && (best < 0 || Math.abs(i - place) < Math.abs(best - place))) best = i })
      if (best < 0) return false
      gone.add(best); goneWi.add(k)
    }
    return true
  }
  if (wi.length > 0) {
    const cutKs = wi.filter(cutAt)
    return find(cutKs, wi) ? none : 'all'
  }
  const inside = words.map((w, k) => k).filter((k) => !isNoise(words[k].text) && (words[k].start + words[k].end) / 2 >= c.start && (words[k].start + words[k].end) / 2 < c.end)
  if (inside.length > 0) {
    // Those not found in this text (it may be in another language) are not this subtitle's to drop.
    for (const k of inside.filter(cutAt)) find([k], inside)
    goneWi.clear()
    return none
  }
  const total = toks.reduce((n, w) => n + w.length + 1, 0)
  let at = c.start
  toks.forEach((w, i) => {
    const len = ((w.length + 1) / total) * (c.end - c.start)
    if (len > 0 && cuts.kept(at, at + len) < len * 0.1) gone.add(i)
    at += len
  })
  return none
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

/**
 * What the video shows of [c] ([text]: its text with the bleeped words masked): the text, the translation or both. The
 * translation shows only where it can't give a masked word away (see [trVisible]; [maskOn] false: the words show as said).
 */
export function shownText(c: Cue, text: string, show: SubShow | undefined, maskOn = true): string {
  const tr = c.tr?.trim()
  if (!tr || !show || show === 'src') return text
  if (maskOn && !trVisible(c, text)) return text
  return show === 'tr' ? tr : text + '\n' + tr
}

/** Whether [c]'s translation may show next to [masked] (what the subtitle shows now): it was made from exactly that text. */
export const trVisible = (c: Cue, masked: string) => !c.tr || (c.trm ?? c.text) === masked

/** A word as compared: NFC, no zero-width marks, no Hebrew points or Arabic diacritics, the punctuation around it gone, lower case. */
export const normWord = (w: string) =>
  w.normalize('NFC')
    .replace(/[­​-‏‪-‮⁠-⁤﻿]/g, '')
    .replace(/[֑-ׇֽֿׁׂًׅׄ-ٰٟـ]/g, '')
    .replace(/^[^\p{L}\p{N}\p{M}]+|[^\p{L}\p{N}\p{M}]+$/gu, '')
    .toLowerCase()

const HEBREW_PREFIX = 'ובכלמשה'
/** The forms of a token that count as a bleeped word: itself, and without one or two Hebrew prefix letters (ו, ה, ב, כ, ל, מ, ש). */
function forms(norm: string): string[] {
  const out = [norm]
  if (/^[֐-׿]/.test(norm)) {
    if (norm.length > 2 && HEBREW_PREFIX.includes(norm[0])) out.push(norm.slice(1))
    if (norm.length > 3 && HEBREW_PREFIX.includes(norm[0]) && HEBREW_PREFIX.includes(norm[1])) out.push(norm.slice(2))
  }
  return out
}

/** What a token must match to count as a bleeped word of [text]: its normalized text (a word with no letters: itself). */
const keyOf = (text: string) => normWord(text) || 'raw:' + text.trim()

/**
 * Whether [token] is a bleeped word with the [keys] (the keys of their texts). A token joined with a hyphen or a maqaf,
 * "ו-כלב", counts by each of its parts too.
 */
function hit(token: string, keys: Set<string>): boolean {
  const n = normWord(token)
  if (n === '') return keys.has('raw:' + token.trim())
  return [n, ...n.split(/[-־]+/).map(normWord).filter(Boolean)].some((p) => forms(p).some((f) => keys.has(f)))
}

const tokensOf = (text: string) => [...text.matchAll(/\S+/g)].map((m) => ({ t: m[0], i: m.index! }))

/** The bleeped words that no subtitle holds (a subtitle was deleted): they are still said, so a subtitle made by hand over them masks them too. */
export function orphansOf(cues: Cue[], bleeped: Set<number>): number[] {
  if (bleeped.size === 0) return []
  const owned = new Set<number>()
  for (const c of cues) for (const k of cueWi(c)) owned.add(k)
  return [...bleeped].filter((k) => !owned.has(k))
}

/** The words with no subtitle yet that start inside [start, end] (what a subtitle made by hand there holds). */
export function unownedIn(cues: Cue[], words: Word[], start: number, end: number): number[] {
  const owned = new Set<number>()
  for (const c of cues) for (const k of cueWi(c)) owned.add(k)
  return words.map((w, k) => (!owned.has(k) && !isNoise(w.text) && w.start >= start - 0.01 && w.start < end ? k : -1)).filter((k) => k >= 0)
}

/** Whether [a] looks like a respelling of [b]: not the same, and they start alike (3 letters, or the shorter one whole). */
function near(a: string, b: string): boolean {
  if (!a || !b || a === b) return false
  const n = Math.min(3, a.length, b.length)
  return a.slice(0, n) === b.slice(0, n)
}

/**
 * Which tokens of [cue] are masked (by position, or by text once the text was edited), and whether a bleeped word may show.
 * [orphans]: bleeped words in no subtitle; those that start inside the cue's time count as its own, by text.
 */
function analyse(cue: Cue, words: Word[], bleeped: Set<number>, orphans?: number[]) {
  const tokens = tokensOf(cue.text)
  const masked = new Set<number>()
  const wi = cueWi(cue)
  const mine = wi.filter((k) => bleeped.has(k) && words[k])
  const orph = (orphans ?? []).filter((k) => words[k] && !wi.includes(k) && words[k].start >= cue.start - 0.01 && words[k].start < cue.end + 0.01)
  if (mine.length === 0 && orph.length === 0) return { tokens, masked, warn: false }
  const same = tokens.length === wi.length
  const noHit = (k: number) => !tokens.some((x) => hit(x.t, new Set([keyOf(words[k].text)])))
  const exact = same && wi.every((k, p) => words[k] && keyOf(tokens[p].t) === keyOf(words[k].text))
  let warn = false
  if (exact) {
    // The text as the words were said (one token for each word): masked by place, as the app does.
    wi.forEach((k, p) => { if (bleeped.has(k)) masked.add(p) })
  } else if (mine.length > 0) {
    // Edited: every token that says what a bleeped word of the subtitle said is masked, and, when the words still fit the tokens one to one, the tokens at their places.
    const keys = new Set(mine.map((k) => keyOf(words[k].text)))
    tokens.forEach((x, p) => { if (hit(x.t, keys)) masked.add(p) })
    if (same) wi.forEach((k, p) => { if (bleeped.has(k)) masked.add(p) })
    if (!same) {
      // Each word of the subtitle that says a bleeped word's text needs a token of its own: with fewer, one was respelled or lost.
      for (const key of keys) {
        const said = [...wi, ...orph].filter((k) => words[k] && keyOf(words[k].text) === key).length
        const found = tokens.filter((x) => hit(x.t, new Set([key]))).length
        if (found < said) warn = true
      }
    } else {
      // The same count: a bleeped word whose token no longer says it, while every other token still says its word, was respelled in place and is as good as said; anything else may have moved it.
      const lostAt = new Set<number>()
      wi.forEach((k, p) => { if (bleeped.has(k) && words[k] && keyOf(tokens[p].t) !== keyOf(words[k].text)) lostAt.add(p) })
      if (lostAt.size > 0) warn = !wi.every((k, p) => lostAt.has(p) || (words[k] && keyOf(tokens[p].t) === keyOf(words[k].text)))
      // Every bleeped word still stands at its place; but another token that is a respelling of one (and so may be it, moved) is not trusted.
      else {
        const bk = new Set(mine.map((k) => normWord(words[k].text)).filter(Boolean))
        wi.forEach((k, p) => { if (words[k] && keyOf(tokens[p].t) !== keyOf(words[k].text) && [...bk].some((w) => near(normWord(tokens[p].t), w))) warn = true })
      }
    }
  }
  if (orph.length > 0) {
    const keys = new Set(orph.map((k) => keyOf(words[k].text)))
    tokens.forEach((x, p) => { if (hit(x.t, keys)) masked.add(p) })
    if (orph.some(noHit)) warn = true
  }
  if (tokens.length === 0) warn = false
  return { tokens, masked, warn }
}

/**
 * The cue's text with its bleeped words as ****, the punctuation around them kept (always 4 stars, as the app, so the
 * length doesn't hint at the word). By the subtitle's own words (see [Cue.wi]); once its text was edited, also every token
 * that says what one of them said, and its words by place when they still fit the text one to one.
 */
export function maskCue(cue: Cue, words: Word[], bleeped: Set<number>, orphans?: number[]): string {
  if (bleeped.size === 0) return cue.text
  const { masked } = analyse(cue, words, bleeped, orphans)
  if (masked.size === 0) return cue.text
  let p = -1
  return cue.text.replace(/\S+/g, (tok) => {
    if (!masked.has(++p)) return tok
    const m = /^([^\p{L}\p{N}\p{M}]*)[\s\S]*?([^\p{L}\p{N}\p{M}]*)$/u.exec(tok)
    // (A token with no letters is hidden whole.)
    return /[\p{L}\p{N}]/u.test(tok) ? (m?.[1] ?? '') + '****' + (m?.[2] ?? '') : '****'
  })
}

/** Whether a bleeped word of [cue] is in none of its tokens and the text no longer fits its words: the subtitle may show it. */
export const maskWarning = (cue: Cue, words: Word[], bleeped: Set<number>, orphans?: number[]) => bleeped.size > 0 && analyse(cue, words, bleeped, orphans).warn

/** Whether [cue] holds a bleeped word. */
export const hasBleeped = (cue: Cue, bleeped: Set<number>) => bleeped.size > 0 && cueWi(cue).some((k) => bleeped.has(k))

/**
 * [cue] in two at character [at] of its text (moved to the nearest gap between words): the words go to the half that has
 * their text (the first [n] words, while the text is the words said). Null when it can't be split (a cue under 0.4 s, one
 * word), or when what a half masks is less than the whole did (given [bleeped]).
 */
export function splitCue(cue: Cue, at: number, words: Word[], bleeped?: Set<number>, orphans?: number[]): [Cue, Cue] | null {
  const tokens = tokensOf(cue.text)
  if (tokens.length < 2) return null
  // A cue too short to hold two (0.2 s each) is not split.
  if (cue.end - cue.start < 0.4 - 1e-6) return null
  let n1 = tokens.filter((x) => x.i < at).length
  const last = tokens[n1 - 1]
  if (last && at < last.i + last.t.length && at - last.i <= last.t.length / 2) n1--
  if (n1 <= 0 || n1 >= tokens.length) return null
  const textA = cue.text.slice(0, tokens[n1].i).trim(), textB = cue.text.slice(tokens[n1].i).trim()
  const wi = cueWi(cue)
  const w1: number[] = [], w2: number[] = []
  if (tokens.length === wi.length) { w1.push(...wi.slice(0, n1)); w2.push(...wi.slice(n1)) }
  else {
    // The text was edited: each word goes where the next token with its text is (a word with none stays with the one before).
    let cursor = 0, side = 0
    for (const k of wi) {
      const key = words[k] ? keyOf(words[k].text) : null
      let i = cursor
      while (key !== null && i < tokens.length && keyOf(tokens[i].t) !== key) i++
      if (key !== null && i < tokens.length) { cursor = i + 1; side = i >= n1 ? 1 : 0 }
      ;(side ? w2 : w1).push(k)
    }
  }
  const len = cue.end - cue.start
  const next = w2.map((k) => words[k]?.start).find((x) => x !== undefined && x > cue.start && x < cue.end)
  let t = next ?? cue.start + len * (textA.length / (textA.length + textB.length))
  t = Math.min(cue.end - 0.2, Math.max(cue.start + 0.2, t))
  const first: Cue = { ...cue, end: t, text: textA, tr: undefined, trm: undefined, wi: w1 }
  const second: Cue = { ...cue, id: newCueId(), start: t, text: textB, tr: undefined, trm: undefined, wi: w2, speaker: cue.speaker }
  if (bleeped && bleeped.size > 0) {
    const was = analyse(cue, words, bleeped, orphans)
    const a = analyse(first, words, bleeped, orphans), b = analyse(second, words, bleeped, orphans)
    // A subtitle that may already show a bleeped word is fixed first: its halves could not tell which word went where.
    if (was.warn || a.warn || b.warn) return null
    for (const p of was.masked) if (!(p < n1 ? a.masked.has(p) : b.masked.has(p - n1))) return null
  }
  return [first, second]
}

/** [cue] split at the playhead [time]: before the first word that starts at or after it (as the app's split at the word nearest to the playhead). */
export function splitCueAtTime(cue: Cue, time: number, words: Word[], bleeped?: Set<number>, orphans?: number[]): [Cue, Cue] | null {
  const tokens = tokensOf(cue.text)
  if (tokens.length < 2) return null
  const wi = cueWi(cue)
  let k: number
  // With the text not the words said: by the playhead's place in the time.
  if (wi.length === tokens.length && wi.every((j) => words[j])) k = wi.filter((j) => words[j].start < time - 0.02).length
  else k = Math.round(tokens.length * Math.min(1, Math.max(0, (time - cue.start) / Math.max(0.001, cue.end - cue.start))))
  k = Math.min(tokens.length - 1, Math.max(1, k))
  return splitCue(cue, tokens[k].i, words, bleeped, orphans)
}

/** The time stretches of the bleeped words. */
export function bleepRanges(words: Word[], bleeped: Set<number>) {
  return [...bleeped].sort((a, b) => a - b).map((k) => ({ start: words[k].start, end: words[k].end }))
}
