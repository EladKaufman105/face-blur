// Splitting a subtitle never unmasks a bleeped word (the burned-in text keeps it as ****), and never makes a cue that ends before it starts.
// Run: node tests/splitmask.cjs
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
// subtitles.ts on its own (its type imports and the style constants it re-exports are not needed here).
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/subtitles.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
const { splitCue, splitCueAtTime, maskCue, mergeCues, retimeCue, holds, normWord } = mod.exports
const W = (list) => list.map(([text, start, end]) => ({ text: ' ' + text, start, end: end ?? start + 0.2 }))
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const toks = (s) => s.match(/\S+/g) ?? []
/** The bleeped words' texts that start in [cue]'s stretch (with the 10 ms the masking allows at the outer edges). */
const hiddenOf = (cue, words, b) => new Set([...b].filter((k) => { const at = words[k].start; return at >= (cue.ws ?? cue.start) - 0.01 && at < (cue.we ?? cue.end) + 0.01 }).map((k) => normWord(words[k].text)).filter(Boolean))
/** A bleeped word's text shown, unmasked, in [cue] (by whole tokens): the one thing that must never happen. */
const shows = (cue, words, b, hide) => toks(maskCue(cue, words, b)).filter((t) => hide.has(normWord(t)))
function ok(name, parts, words, b, hide) {
  for (const c of parts) {
    if (!(c.start < c.end)) fail(`${name}: bad times ${JSON.stringify(c)}`)
    const leak = shows(c, words, b, hide)
    if (leak.length) fail(`${name}: ${leak} shows in "${maskCue(c, words, b)}" ${JSON.stringify(c)}`)
  }
}
/** Every split of [cue]: refused, or nothing shows in either half. */
function allSplits(name, cue, words, b) {
  const hide = hiddenOf(cue, words, b)
  let did = 0
  for (let at = 1; at < cue.text.length; at++) { const r = splitCue(cue, at, words, b); if (r) { ok(`${name} @${at}`, r, words, b, hide); did++ } }
  return did
}

// (a) the start dragged past the word, text edited, the word bleeped
{
  const words = W([['a', 1.0], ['b', 1.5], ['c', 2.0]])
  const cue = { id: 1, start: 2.4, end: 3, ws: 1, we: 3, text: 'a b c d' }
  const b = new Set([2])
  if (maskCue(cue, words, b) !== 'a b **** d') fail('a: the original: ' + maskCue(cue, words, b))
  const r = splitCueAtTime(cue, 2.7, words, b)
  if (!r) fail('a refused')
  ok('a', r, words, b, hiddenOf(cue, words, b))
}
// (b) extra text before the words
{
  const words = W([['a', 1.0], ['b', 1.5], ['c', 2.0]])
  const cue = { id: 1, start: 1, end: 3, text: 'xx a b c' }
  const r = splitCueAtTime(cue, 1.45, words, new Set([1]))
  if (!r) fail('b refused')
  ok('b', r, words, new Set([1]), new Set(['b']))
  allSplits('b', cue, words, new Set([1]))
}
// (c) the edge clamp on the time
{
  const words = W([['a', 1.0], ['b', 1.1], ['c', 2.0]])
  const cue = { id: 1, start: 1, end: 3, text: 'a b c' }
  const r = splitCue(cue, 2, words, new Set([1]))
  if (!r) fail('c refused')
  ok('c', r, words, new Set([1]), new Set(['b']))
  if (r[0].end !== 1.2) fail('c: the time was not held back from the edge: ' + r[0].end)
}
// t1.1: equal word starts straddling the split; the same bleeped text twice
{
  const words = W([['aa', 1.0], ['bad', 2.0], ['cc', 2.0], ['dd', 2.3], ['bad', 2.6], ['shit', 2.8]])
  const cue = { id: 1, start: 1, end: 3.2, text: 'aa bad cc dd bad shit' }
  const b = new Set([1, 5])
  if (maskCue(cue, words, b) !== 'aa **** cc dd **** ****') fail('t1.1 mask: ' + maskCue(cue, words, b))
  allSplits('t1.1', cue, words, b)
}
// t1.2: a bleeped word 10 ms before the boundary
{
  const words = W([['aa', 1.0], ['bad', 1.995], ['cc', 2.0], ['shit', 2.3], ['dd', 2.5], ['bad', 2.7]])
  const cue = { id: 1, start: 1, end: 3.2, text: 'aa bad cc shit dd bad' }
  const b = new Set([1, 3])
  allSplits('t1.2', cue, words, b)
  for (const t of [1.5, 2.0, 2.05, 2.4, 2.6]) { const r = splitCueAtTime(cue, t, words, b); if (r) ok('t1.2@' + t, r, words, b, hiddenOf(cue, words, b)) }
}
// t1.3: B deleted, A and C merged: "damn" is bleeped, its start is inside the merged stretch
{
  const words = W([['hello', 1.0], ['there', 1.3], ['the', 2.0], ['dog', 2.3], ['damn', 3.0], ['the', 3.3], ['cat', 3.6]])
  const A = { id: 1, start: 1, end: 1.8, text: 'hello there' }, C = { id: 3, start: 3, end: 3.9, text: 'damn the cat' }
  const b = new Set([4])
  const m = mergeCues(A, C, words, b)
  if (!m) fail('t1.3: refused')
  if (maskCue(m, words, b) !== 'hello there **** the cat') fail('t1.3: ' + maskCue(m, words, b))
}
// t1.4 / t2: a noise token in the gap
{
  const words = W([['a', 1.0], ['bad', 1.3], ['x', 2.0], ['bad', 2.3]])
  const A = { id: 1, start: 1, end: 1.8, text: 'a bad' }, B = { id: 2, start: 2, end: 2.8, text: 'x bad' }
  const m = mergeCues(A, B, words, new Set([3]))
  if (!m || maskCue(m, words, new Set([3])) !== 'a **** x ****') fail('t1.4: ' + (m && maskCue(m, words, new Set([3]))))
  const w2 = W([['hello', 1.0], ['-', 1.9], ['damn', 2.0], ['-', 2.2], ['ok', 2.4]])
  const A2 = { id: 1, start: 1, end: 1.8, text: 'hello' }, B2 = { id: 2, start: 2, end: 2.8, text: 'damn - ok' }
  const m2 = mergeCues(A2, B2, w2, new Set([2]))
  if (!m2 || maskCue(m2, w2, new Set([2])) !== 'hello **** - ok') fail('t2: ' + (m2 && maskCue(m2, w2, new Set([2]))))
}
// whole tokens: "classic ass"
{
  const words = W([['classic', 1.0], ['ass', 1.5]])
  const cue = { id: 1, start: 1, end: 2, text: 'classic ass' }
  if (maskCue(cue, words, new Set([1])) !== 'classic ****') fail('classic ass: ' + maskCue(cue, words, new Set([1])))
  if (maskCue({ ...cue, text: 'ass, classic' }, words, new Set([1])) !== '****, classic') fail('punctuation kept')
  if (maskCue({ ...cue, text: 'Ass classic' }, words, new Set([1])) !== '**** classic') fail('case')
  // A text edit: the word typed again is hidden by itself.
  if (maskCue({ ...cue, text: 'ass classic ass' }, words, new Set([1])) !== '**** classic ****') fail('an edit shows the word')
}
// Hebrew with prefixes
{
  const words = W([['כלב', 1.0], ['והחתול', 1.5], ['שלום', 2.0]])
  const cue = { id: 1, start: 1, end: 3, text: 'הכלב ו-כלב והחתול שלום' }
  const m = maskCue(cue, words, new Set([0, 1]))
  if (m !== '**** **** **** שלום') fail('hebrew: ' + m)
  // The attached form itself, bleeped.
  if (maskCue({ ...cue, text: 'וכלב' }, W([['וכלב', 1.0]]), new Set([0])) !== '****') fail('hebrew attached form')
  if (maskCue(cue, words, new Set([2])) !== 'הכלב ו-כלב והחתול ****') fail('hebrew plain: ' + maskCue(cue, words, new Set([2])))
}
// too short: no split; long enough: strictly inside
{
  const words = W([['a', 1.0, 1.1], ['b', 1.15, 1.25]])
  if (splitCue({ id: 1, start: 1, end: 1.3, text: 'a b' }, 2, words) !== null) fail('a cue of 0.3 s was split')
  if (splitCueAtTime({ id: 1, start: 1, end: 1.39, text: 'a b' }, 1.2, words) !== null) fail('a cue of 0.39 s was split')
  const r = splitCue({ id: 1, start: 1, end: 1.4, text: 'a b' }, 2, words)
  if (!r || !(r[0].start < r[0].end && r[1].start < r[1].end)) fail('0.4 s cue: ' + JSON.stringify(r))
}
// retimeCue: the previous cue's end wins over the minimum length
{
  const c = { id: 1, start: 2, end: 2.05, text: 'x' }
  if (retimeCue(c, true, 0.5, 2.0, 3).start < 2.0) fail('retime start under lo')
  if (retimeCue(c, false, 9, 0, 2.06).end > 2.06) fail('retime end over hi')
}
// the stretches of two halves never share a word
{
  const words = W([['aa', 1.0], ['bb', 1.4], ['cc', 1.9], ['dd', 2.4]])
  const cue = { id: 1, start: 1, end: 3, text: 'aa bb cc dd' }
  for (let at = 1; at < cue.text.length; at++) { const r = splitCue(cue, at, words); if (r) for (const w of words) if (holds(r[0], w.start) && holds(r[1], w.start)) fail('a word held by both halves at ' + at) }
}

// Property test: random cues, words and bleeps; every split and every merge. A bleeped word's text never shows in a cue that holds it.
{
  let seed = 12345
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32
  const pick = (a) => a[Math.floor(rnd() * a.length)]
  const vocab = ['bad', 'ass', 'classic', 'a', 'the', 'cat', 'Damn', 'damn,', 'ok', 'כלב', 'והכלב', 'הכלב', 'שלום', '-', '...']
  let splits = 0, merges = 0, refused = 0
  for (let round = 0; round < 1500; round++) {
    const n = 3 + Math.floor(rnd() * 8)
    let t = 1
    const list = []
    for (let i = 0; i < n; i++) { t += rnd() < 0.25 ? 0 : 0.05 + rnd() * 0.5; list.push([pick(vocab), Math.round(t * 1000) / 1000]) }
    const words = W(list)
    const b = new Set(); words.forEach((_, k) => { if (rnd() < 0.3) b.add(k) })
    // two cues over the words (cut at a random word), the text a random mix of the words, sometimes edited, sometimes windowed
    const cut = 1 + Math.floor(rnd() * (n - 1))
    const mk = (lo, hi, id) => {
      const ws = words.slice(lo, hi)
      const text = ws.map((w) => w.text.trim()).concat(rnd() < 0.3 ? [pick(vocab)] : []).join(' ')
      const c = { id, start: Math.round((ws[0].start - rnd() * 0.05) * 1000) / 1000, end: Math.round((ws[ws.length - 1].end + rnd() * 0.3) * 1000) / 1000, text }
      if (rnd() < 0.3) { c.ws = c.start; c.we = c.end; c.start = Math.round((c.start + rnd() * 0.4) * 1000) / 1000; if (c.start > c.end - 0.1) c.start = c.end - 0.1 }
      return c
    }
    const A = mk(0, cut, 1), B = mk(cut, n, 2)
    for (const cue of [A, B]) {
      const hide = hiddenOf(cue, words, b)
      for (let at = 1; at < cue.text.length; at++) {
        const r = splitCue(cue, at, words, b)
        if (!r) { refused++; continue }
        splits++
        ok(`split r${round} @${at} ${JSON.stringify(cue)}`, r, words, b, hide)
      }
      for (const tm of [cue.start + 0.1, (cue.start + cue.end) / 2, cue.end - 0.1]) {
        const r = splitCueAtTime(cue, tm, words, b)
        if (r) { splits++; ok('splitAtTime r' + round, r, words, b, hide) }
      }
    }
    const m = mergeCues(A, B, words, b)
    if (!m) refused++
    else {
      merges++
      const hide = new Set([...hiddenOf(A, words, b), ...hiddenOf(B, words, b)])
      ok('merge r' + round + ' ' + JSON.stringify([A, B]), [m], words, b, hide)
    }
  }
  console.log(`property: ${splits} splits, ${merges} merges, ${refused} refused`)
  if (splits < 1000 || merges < 100) fail('the property test did too little')
}
console.log('PASS')
