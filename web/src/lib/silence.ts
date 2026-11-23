// Pauses in speech, from the sound's level, and filler sounds, from the words: the Android app's
// SilenceFinder.
import type { Range } from './cutmap'

const STEP = 0.02
/** Kept at each side of a cut pause: a breath, so the cut doesn't sound chopped. */
const KEEP = 0.14

/**
 * Stretches to cut (seconds): quiet runs longer than [minPause], less [KEEP] at each end, never
 * over a spoken [words] stretch. [x] is mono at [rate].
 */
export function findSilences(x: Float32Array, rate: number, minPause: number, words: Range[]): Range[] {
  const step = Math.max(1, Math.round(rate * STEP))
  const n = Math.floor(x.length / step)
  if (n < 10) return []
  const db = new Float32Array(n)
  for (let i = 0; i < n; i++) {
    let s = 0
    for (let j = i * step; j < (i + 1) * step; j++) s += x[j] * x[j]
    db[i] = 10 * Math.log10(Math.max(s / step, 1e-10))
  }
  // Quiet = well under the speech level, judged from this video's own levels.
  const sorted = Float32Array.from(db).sort()
  const floor = sorted[Math.floor(n * 0.1)]
  const speech = sorted[Math.min(n - 1, Math.floor(n * 0.9))]
  const thr = Math.min(Math.max(floor + 6, floor + (speech - floor) * 0.35), speech - 6)
  // Smoothed a little so a click doesn't split a pause.
  const quiet = new Uint8Array(n)
  for (let i = 0; i < n; i++) {
    let q = 0, t = 0
    for (let k = Math.max(0, i - 1); k <= Math.min(n - 1, i + 1); k++) { t++; if (db[k] < thr) q++ }
    quiet[i] = q * 2 > t ? 1 : 0
  }
  const out: Range[] = []
  let i = 0
  while (i < n) {
    if (!quiet[i]) { i++; continue }
    let j = i
    while (j < n && quiet[j]) j++
    const a = i * STEP, b = j * STEP
    if (b - a >= minPause - 1e-9) {
      const ca = i === 0 ? 0 : a + KEEP
      const cb = j >= n ? b : b - KEEP
      if (cb - ca >= 0.12) out.push([ca, cb])
    }
    i = j
  }
  return subtract(out, words)
}

/** [ranges] with the [words]' stretches taken out (a word is never cut by a pause). */
function subtract(ranges: Range[], words: Range[]): Range[] {
  if (words.length === 0) return ranges
  const ws = words.filter((w) => w[1] > w[0]).sort((a, b) => a[0] - b[0])
  const out: Range[] = []
  for (const r of ranges) {
    let pieces: Range[] = [r]
    for (const w of ws) {
      if (w[0] >= r[1]) break
      if (w[1] <= r[0]) continue
      pieces = pieces.flatMap((p): Range[] => {
        if (w[1] <= p[0] || w[0] >= p[1]) return [p]
        const left: Range = [p[0], w[0] - 0.06]
        const right: Range = [w[1] + 0.06, p[1]]
        return [left, right].filter((x) => x[1] - x[0] >= 0.2)
      })
    }
    out.push(...pieces)
  }
  return out
}

const FILLERS = new Set([
  'אה', 'אהה', 'אההה', 'אמ', 'אממ', 'אמממ', 'אממממ', 'אהם', 'אהמ', 'המ', 'הממ', 'הממם', 'אמם', 'אהממ',
  'um', 'umm', 'uh', 'uhh', 'uhm', 'erm', 'er', 'hmm', 'hm', 'mm', 'mmm', 'ah', 'eh',
  'اه', 'امم', 'اممم', 'ااا',
])

/** Whether [word] (punctuation and vowel marks aside) is a filler sound. */
export function isFiller(word: string): boolean {
  const w = [...word.toLowerCase()].filter((c) => /\p{L}/u.test(c) && !(c >= '֑' && c <= 'ׇ')).join('')
  return FILLERS.has(w)
}
