// The SRT saved from an edit with cuts: a subtitle partly cut out of the video loses the words that were cut (as the app's
// exportCues), one fully cut is gone, and the times move to the cut video. Pure: no browser.
// Run: node tests/cutsrt.cjs
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
const load = (f) => {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/' + f), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const mod = { exports: {} }
  new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
  return mod.exports
}
const { cutCues } = load('subtitles.ts')
const { CutMap } = load('cutmap.ts')
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const eq = (name, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) fail(`${name}: ${JSON.stringify(got)} (wanted ${JSON.stringify(want)})`) }

const words = [['a', 0], ['b', 1], ['c', 2], ['d', 3]].map(([t, s]) => ({ text: ' ' + t, start: s, end: s + 0.9 }))
const cue = { id: 1, start: 0, end: 3.9, text: 'a b c d', wi: [0, 1, 2, 3] }
const cm = new CutMap([[1, 2]])
const r = cutCues([cue], cm, words)
eq('texts', r.map((c) => c.text), ['a c d'])
eq('end moves', Math.round(r[0].end * 10) / 10, 2.9)
// A translation is not aligned to the words: it goes with them.
eq('tr dropped', cutCues([{ ...cue, tr: 'x y z' }], cm, words)[0].tr, undefined)
// Without the words the text stays as it was.
eq('no words', cutCues([cue], cm).map((c) => c.text), ['a b c d'])
// Text edited so its words no longer match the cue's: kept whole.
eq('edited', cutCues([{ ...cue, text: 'a b c' }], cm, words).map((c) => c.text), ['a b c'])
// Every word cut: the subtitle goes.
eq('all cut', cutCues([{ id: 2, start: 1, end: 1.9, text: 'b', wi: [1] }], new CutMap([[0.9, 2.1]]), words), [])
// No cuts: untouched.
eq('empty', cutCues([cue], new CutMap([]), words), [cue])
console.log('cutsrt ok')
