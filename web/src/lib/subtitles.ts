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
export const holds = (c: Cue, at: number) => at >= winStart(c) - 0.01 && at < winEnd(c)

/** The shortest a subtitle can be made by hand (as the app: 100 ms). */
export const MIN_CUE = 0.1

/** [c] with its edge moved to [at] (clamped to the video and the neighbours [lo], [hi]), its words kept. */
export function retimeCue(c: Cue, start: boolean, at: number, lo: number, hi: number): Cue {
  const ws = winStart(c), we = winEnd(c)
  if (start) return { ...c, ws, we, start: Math.min(c.end - MIN_CUE, Math.max(lo, at)) }
  return { ...c, ws, we, end: Math.max(c.start + MIN_CUE, Math.min(hi, at)) }
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

/** [a] and the cue after it as one (the text and translation joined, the words of both). */
export function mergeCues(a: Cue, b: Cue): Cue {
  const tr = a.tr || b.tr ? [a.tr ?? '', b.tr ?? ''].join(' ').trim() : undefined
  const merged: Cue = { ...a, end: b.end, text: (a.text + ' ' + b.text).trim(), tr }
  const ws = Math.min(winStart(a), winStart(b)), we = Math.max(winEnd(a), winEnd(b))
  if (ws !== merged.start) merged.ws = ws
  else delete merged.ws
  if (we !== merged.end) merged.we = we
  else delete merged.we
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

/** [cue] in two at character [at] of its text: the time split where the words say, else by length. */
export function splitCue(cue: Cue, at: number, words: Word[]): [Cue, Cue] | null {
  const a = cue.text.slice(0, at).trim(), b = cue.text.slice(at).trim()
  if (!a || !b) return null
  const inCue = words.filter((w) => holds(cue, w.start))
  const before = a.split(/\s+/).length
  const len = cue.end - cue.start
  const exact = inCue.length === a.split(/\s+/).length + b.split(/\s+/).length ? inCue[before].start : null
  let t = exact ?? cue.start + len * (a.length / (a.length + b.length))
  t = Math.min(cue.end - 0.2, Math.max(cue.start + 0.2, t))
  const first: Cue = { ...cue, end: t, text: a, tr: undefined }
  const second: Cue = { ...cue, id: newCueId(), start: t, text: b, tr: undefined, speaker: cue.speaker }
  // A cue whose edges were moved keeps its words' stretch: it is shared out at the word where the text splits.
  if (cue.ws !== undefined || cue.we !== undefined) { const w = exact ?? t; first.we = w; second.ws = w }
  return [first, second]
}

/** [cue] split at the playhead [time]: before the first word that starts at or after it (as the app's split at the word nearest to the playhead). */
export function splitCueAtTime(cue: Cue, time: number, words: Word[]): [Cue, Cue] | null {
  const tokens = [...cue.text.matchAll(/\S+/g)]
  const inCue = words.filter((w) => holds(cue, w.start))
  let k = inCue.filter((w) => w.start < time - 0.02).length
  if (tokens.length < 2) return null
  // With no timed words (or words that do not match the text): by the playhead's place in the time.
  if (inCue.length !== tokens.length) k = Math.round(tokens.length * Math.min(1, Math.max(0, (time - cue.start) / Math.max(0.001, cue.end - cue.start))))
  k = Math.min(tokens.length - 1, Math.max(1, k))
  const at = tokens[k].index!
  return splitCue(cue, at, words)
}

/** The cue's text with its bleeped words as **** (the words timed inside the cue). */
export function maskCue(cue: Cue, words: Word[], bleeped: Set<number>): string {
  if (bleeped.size === 0) return cue.text
  let text = cue.text
  let from = 0
  words.forEach((w, k) => {
    if (!holds(cue, w.start)) return
    const word = w.text.trim()
    const at = text.indexOf(word, from)
    if (at < 0) return
    // Always 4 stars, the punctuation around kept: the length shouldn't hint at the word (as the app).
    if (bleeped.has(k)) {
      const m = /^([^\p{L}\p{N}]*)[\s\S]*?([^\p{L}\p{N}]*)$/u.exec(word)
      text = text.slice(0, at) + (m?.[1] ?? '') + '****' + (m?.[2] ?? '') + text.slice(at + word.length)
    }
    from = at + 1
  })
  return text
}

/** The time stretches of the bleeped words. */
export function bleepRanges(words: Word[], bleeped: Set<number>) {
  return [...bleeped].sort((a, b) => a - b).map((k) => ({ start: words[k].start, end: words[k].end }))
}
