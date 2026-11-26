// Splitting a subtitle never unmasks a bleeped word (the burned-in text keeps it as ****), and never makes a cue that ends before it starts.
// Run: node tests/splitmask.cjs
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
// subtitles.ts on its own (its type imports and the style constants it re-exports are not needed here).
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/subtitles.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
const { splitCue, splitCueAtTime, maskCue } = mod.exports
const W = (list) => list.map(([text, start, end]) => ({ text: ' ' + text, start, end: end ?? start + 0.3 }))
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const stars = (c, words, b) => (maskCue(c, words, b).match(/\*\*\*\*/g) ?? []).length
/** Both halves together hide exactly what the whole did, and the half that shows the bleeped word's text hides it. */
function check(name, cue, words, bleeped, parts, shown) {
  if (!parts) fail(name + ': refused');
  const [x, y] = parts
  if (!(x.start < x.end && y.start < y.end && x.end <= y.start + 1e-9)) fail(`${name}: bad times ${JSON.stringify(parts)}`)
  if (stars(x, words, bleeped) + stars(y, words, bleeped) !== stars(cue, words, bleeped)) fail(`${name}: a bleeped word is not hidden: ${maskCue(x, words, bleeped)} | ${maskCue(y, words, bleeped)}`)
  const all = maskCue(x, words, bleeped) + ' ' + maskCue(y, words, bleeped)
  if (all.includes(shown)) fail(`${name}: "${shown}" shows: ${all}`)
}
// (a) the start dragged past the word, text edited, the word bleeped
{
  const words = W([['a', 1.0], ['b', 1.5], ['c', 2.0]])
  const cue = { id: 1, start: 2.4, end: 3, ws: 1, we: 3, text: 'a b c d' }
  const b = new Set([2])
  if (stars(cue, words, b) !== 1) fail('a: the original does not mask')
  check('a', cue, words, b, splitCueAtTime(cue, 2.7, words, b), ' c ')
}
// (b) extra text before the words
{
  const words = W([['a', 1.0], ['b', 1.5], ['c', 2.0]])
  const cue = { id: 1, start: 1, end: 3, text: 'xx a b c' }
  const b = new Set([1])
  check('b', cue, words, b, splitCueAtTime(cue, 1.45, words, b), ' b ')
  for (let at = 1; at < cue.text.length - 1; at++) { const r = splitCue(cue, at, words, b); if (r) check('b@' + at, cue, words, b, r, ' b ') }
}
// (c) the edge clamp on the time
{
  const words = W([['a', 1.0], ['b', 1.1], ['c', 2.0]])
  const cue = { id: 1, start: 1, end: 3, text: 'a b c' }
  const b = new Set([1])
  const r = splitCue(cue, 2, words, b)
  check('c', cue, words, b, r, 'b ')
  if (r[0].end !== 1.2) fail('c: the time was not held back from the edge: ' + r[0].end)
  const r2 = splitCue(cue, 4, words, new Set([2]))
  check('c2', cue, words, new Set([2]), r2, 'c')
}
// every split of a windowed cue, every bleeped word
{
  const words = W([['aa', 1.0], ['bb', 1.4], ['cc', 1.9], ['dd', 2.4]])
  for (const [start, end] of [[1, 3], [1.5, 3], [1, 2], [2.2, 3]]) for (let k = 0; k < 4; k++) {
    const cue = { id: 1, start, end, ws: 1, we: 3, text: 'aa bb cc dd' }
    for (let at = 1; at < cue.text.length; at++) { const r = splitCue(cue, at, words, new Set([k])); if (r) check(`all ${start}-${end} k${k} at${at}`, cue, words, new Set([k]), r, ['aa', 'bb', 'cc', 'dd'][k]) }
  }
}
// too short: no split; long enough: strictly inside
{
  const words = W([['a', 1.0, 1.1], ['b', 1.15, 1.25]])
  if (splitCue({ id: 1, start: 1, end: 1.3, text: 'a b' }, 2, words) !== null) fail('a cue of 0.3 s was split')
  if (splitCueAtTime({ id: 1, start: 1, end: 1.39, text: 'a b' }, 1.2, words) !== null) fail('a cue of 0.39 s was split')
  const r = splitCue({ id: 1, start: 1, end: 1.4, text: 'a b' }, 2, words)
  if (!r || !(r[0].start < r[0].end && r[1].start < r[1].end)) fail('0.4 s cue: ' + JSON.stringify(r))
}
console.log('PASS')
