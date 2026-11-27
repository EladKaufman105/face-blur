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

/** A spoken word's stretch (a word without a length gets the app's 80 ms). */
export const wordSpan = (w: Word): [number, number] => [w.start, Math.max(w.end, w.start + 0.08)]

/**
 * [cues] (raw: the spoken text and its words, not what shows) with the words cut out of the video taken out, as the app's exportCues:
 * a partly cut subtitle loses its cut words (and its translation, which is not aligned to them), one with no word left goes.
 * Times stay the source's. A word is cut when most of its span is. See [goneTokens] for how the words are found. Line breaks of the
 * text stay, and the subtitle's words list ([Cue.wi]) loses the cut words, so what masks and what shows stay aligned.
 */
export function dropCutWords(cues: Cue[], cuts: CutMap, words: Word[] = []): Cue[] {
  if (cuts.empty) return cues
  const out: Cue[] = []
  for (const c of cues) {
    if (cuts.kept(c.start, c.end) >= c.end - c.start) { out.push(c); continue }
    const toks = tokensOf(c.text)
    const r = goneTokens(c, toks.map((x) => x.t), words, cuts)
    if (r.gone.size === 0 && r.goneWi.size === 0) { out.push(c); continue }
    const keep = toks.map((_, i) => i).filter((i) => !r.gone.has(i))
    if (keep.length === 0) continue
    let text = ''
    keep.forEach((i, j) => { text += (j > 0 ? c.text.slice(i > 0 ? toks[i - 1].i + toks[i - 1].t.length : 0, toks[i].i) : '') + toks[i].t })
    const wi = c.wi === undefined ? undefined : cueWi(c).filter((k) => !r.goneWi.has(k))
    out.push({ ...c, text, tr: r.gone.size > 0 ? undefined : c.tr, trm: r.gone.size > 0 ? undefined : c.trm, wi })
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

/** The index of the first word of [words] (in time order) that starts at or after [t]. */
function firstStartingAt(words: Word[], t: number): number {
  let lo = 0, hi = words.length
  while (lo < hi) { const m = (lo + hi) >> 1; if (words[m].start < t) lo = m + 1; else hi = m }
  return lo
}

/**
 * Which of [toks] (the words of [c]'s text) are cut out of the video (indices), and which of the subtitle's words went with them.
 * The tokens are aligned to the words by [alignCue] (the same alignment that masks): a token aligned to a cut word goes; a cut word
 * the user already removed from the text is ignored; one that may have been respelled takes its near-respelling token with it; no token
 * aligned to an uncut word ever goes.
 * An imported subtitle has no words of its own: the recognized words inside its time stand in for them; with none recognized there,
 * its time is spread over its words by length and only a word wholly inside a cut goes (a cut in a pause leaves it as it is).
 */
function goneTokens(c: Cue, toks: string[], words: Word[], cuts: CutMap): { gone: Set<number>; goneWi: Set<number> } {
  const gone = new Set<number>(), goneWi = new Set<number>()
  const none = { gone, goneWi }
  if (toks.length === 0) return none
  const wi = cueWi(c).filter((k) => words[k])
  const own = wi.length > 0
  let of = wi
  if (!own) {
    // The recognized words inside the cue's time (by binary search: words are in time order).
    of = []
    for (let k = firstStartingAt(words, c.start - 30); k < words.length && words[k].start < c.end; k++) {
      const mid = (words[k].start + words[k].end) / 2
      if (mid >= c.start && mid < c.end && !isNoise(words[k].text)) of.push(k)
    }
  }
  if (of.length > 0) {
    const al = alignCue(toks, of, words)
    of.forEach((k, q) => {
      const [a, b] = wordSpan(words[k])
      if (!covers(cuts, a, b)) return
      const p = al.word[q] >= 0 ? al.word[q] : (al.near.get(q) ?? -1)
      if (p >= 0) gone.add(p)
      if (own) goneWi.add(k)
    })
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

/** A respelling that counts for alignment: [near], and not for the shortest words (a one-letter word is "near" anything). */
const alignNear = (a: string, b: string) => Math.min(a.length, b.length) >= 3 && near(a, b)

/** How a token's text goes with a word's text: 3 the same, 2 a prefix or maqaf form of it, 1 a near respelling, 0 not at all. */
function pairScore(t: { key: string; forms: Set<string>; norm: string }, w: { key: string; norm: string }): number {
  if (t.key === w.key) return 3
  if (t.forms.has(w.key)) return 2
  return w.norm && t.norm && alignNear(t.norm, w.norm) ? 1 : 0
}

/** What a token is as compared (see [pairScore]); its forms are the ones [hit] accepts. */
function tokenInfo(token: string) {
  const norm = normWord(token)
  const fs = new Set<string>()
  if (norm === '') fs.add('raw:' + token.trim())
  else for (const p of [norm, ...norm.split(/[-־]+/).map(normWord).filter(Boolean)]) for (const f of forms(p)) fs.add(f)
  return { key: keyOf(token), forms: fs, norm }
}

export interface Align {
  /** The tokens are the words one for one and each says its word: the text is as said. */
  exact: boolean
  /** The word (its place in [of]) each token goes with, or -1: a token the user added. */
  tok: number[]
  /** The token each word (by its place in the list) goes with, or -1: the user took it out, or respelled it beyond telling. */
  word: number[]
  /** A word with no token while a token it may have been respelled to is left over: that token (by the word's place). */
  near: Map<number, number>
}

/**
 * The one alignment of a subtitle's tokens to its words [of] (indices into [words]), for masking and for cutting alike.
 * Exact when every token says its own word (then by place). Otherwise the best order-keeping match of the texts: the same text
 * before a prefix or maqaf form before a near respelling, equal words in the order they come (the first with the first).
 * A stretch where as many tokens as words are left over is a respelling in place (paired in order). A word left with no token is "taken out" by the user, unless a token that no word has looks like it ([Align.near]: uncertain).
 */
export function alignCue(tokens: string[], of: number[], words: Word[]): Align {
  const n = tokens.length, m = of.length
  const tok = new Array<number>(n).fill(-1), word = new Array<number>(m).fill(-1)
  const near = new Map<number, number>()
  const wi = of.map((k) => (words[k] ? { key: keyOf(words[k].text), norm: normWord(words[k].text) } : null))
  if (n === m && wi.every((w, p) => w && w.key === keyOf(tokens[p]))) {
    for (let p = 0; p < n; p++) tok[p] = word[p] = p
    return { exact: true, tok, word, near }
  }
  const ti = tokens.map(tokenInfo)
  // f[i][j]: the best total for tokens i.. and words j.. (walked forward from the start, so equal words match first with first).
  const f: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  const sc: number[][] = Array.from({ length: n }, (_, i) => of.map((_, j) => (wi[j] ? pairScore(ti[i], wi[j]!) : 0)))
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) f[i][j] = Math.max(f[i + 1][j], f[i][j + 1], sc[i][j] > 0 ? f[i + 1][j + 1] + sc[i][j] : 0)
  for (let i = 0, j = 0; i < n && j < m;) {
    if (sc[i][j] > 0 && f[i][j] === f[i + 1][j + 1] + sc[i][j]) { tok[i] = j; word[j] = i; i++; j++ }
    else if (f[i][j] === f[i + 1][j]) i++
    else j++
  }
  const mono = tok.map((q, i) => (q >= 0 ? i : -1)).filter((i) => i >= 0)
  const monoWord = mono.map((i) => tok[i])
  // A word still without a token whose text is in one that no word has (the text was reordered): out of order, the same text first.
  for (const minScore of [3, 2]) {
    for (let q = 0; q < m; q++) {
      if (word[q] >= 0 || !wi[q]) continue
      const i = tok.findIndex((x, i) => x < 0 && pairScore(ti[i], wi[q]!) === minScore)
      if (i >= 0) { tok[i] = q; word[q] = i }
    }
  }
  // A stretch between two matches where as many tokens as words are left over: each was respelled in place (the first with the first).
  const anchors = [-1, ...mono, n]
  for (let a = 0; a + 1 < anchors.length; a++) {
    const i0 = anchors[a] + 1, i1 = anchors[a + 1]
    const j0 = a === 0 ? 0 : monoWord[a - 1] + 1, j1 = a + 2 === anchors.length ? m : monoWord[a]
    const ts: number[] = [], ws_: number[] = []
    for (let i = i0; i < i1; i++) if (tok[i] < 0) ts.push(i)
    for (let j = j0; j < j1; j++) if (word[j] < 0 && wi[j]) ws_.push(j)
    if (ts.length > 0 && ts.length === ws_.length) ts.forEach((i, x) => { tok[i] = ws_[x]; word[ws_[x]] = i })
  }
  const taken = new Set<number>()
  word.forEach((p, q) => {
    if (p >= 0 || !wi[q]) return
    const i = tok.findIndex((x, i) => x < 0 && !taken.has(i) && !!wi[q]!.norm && ti[i].norm !== '' && alignNear(ti[i].norm, wi[q]!.norm))
    if (i >= 0) { taken.add(i); near.set(q, i) }
  })
  return { exact: false, tok, word, near }
}

/**
 * Which tokens of [cue] are masked, and whether a bleeped word may show.
 * The tokens are aligned to the subtitle's words by [alignCue]: a token aligned to a bleeped word is masked (by place while the text
 * is as said), and so is every token that says what a bleeped word of the subtitle said. A bleeped word the user took out of the text is
 * gone; one that may have been respelled (a token that looks like it is left over) may show, and says so ([warn]).
 * [orphans]: bleeped words in no subtitle; those that start inside the cue's time count as its own, by text.
 */
function analyse(cue: Cue, words: Word[], bleeped: Set<number>, orphans?: number[]) {
  const tokens = tokensOf(cue.text)
  const masked = new Set<number>()
  const wi = cueWi(cue)
  const mine = wi.filter((k) => bleeped.has(k) && words[k])
  const orph = (orphans ?? []).filter((k) => words[k] && !wi.includes(k) && words[k].start >= cue.start - 0.01 && words[k].start < cue.end + 0.01)
  if (mine.length === 0 && orph.length === 0) return { tokens, masked, warn: false }
  let warn = false
  if (mine.length > 0) {
    const al = alignCue(tokens.map((x) => x.t), wi, words)
    if (al.exact) {
      // The text as the words were said (one token for each word): masked by place, as the app does.
      wi.forEach((k, p) => { if (bleeped.has(k)) masked.add(p) })
    } else {
      // Edited: the tokens aligned to a bleeped word, and every token that says what one of them said.
      const keys = new Set(mine.map((k) => keyOf(words[k].text)))
      tokens.forEach((x, p) => { if (hit(x.t, keys)) masked.add(p) })
      wi.forEach((k, q) => {
        if (!bleeped.has(k) || !words[k]) return
        if (al.word[q] >= 0) masked.add(al.word[q])
        else {
          // Taken out, or respelled so that it may show: a token nobody has that looks like it is hidden, and said.
          const w = normWord(words[k].text)
          tokens.forEach((x, p) => { if (al.tok[p] < 0 && w && near(normWord(x.t), w)) { masked.add(p); warn = true } })
        }
      })
      // A token that looks like a bleeped word but is not hidden (it goes with another word, or is new): it may be that word, moved.
      const bn = mine.map((k) => normWord(words[k].text)).filter(Boolean)
      tokens.forEach((x, p) => { if (!masked.has(p) && bn.some((w) => near(normWord(x.t), w))) warn = true })
    }
  }
  if (orph.length > 0) {
    const keys = new Set(orph.map((k) => keyOf(words[k].text)))
    tokens.forEach((x, p) => { if (hit(x.t, keys)) masked.add(p) })
    const noHit = (k: number) => !tokens.some((x) => hit(x.t, new Set([keyOf(words[k].text)])))
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
