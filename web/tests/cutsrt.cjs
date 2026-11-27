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
const { cutCues, dropCutWords, shownText } = load('subtitles.ts')
const { CutMap } = load('cutmap.ts')
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const eq = (name, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) fail(`${name}: ${JSON.stringify(got)} (wanted ${JSON.stringify(want)})`) }

const words = [['a', 0], ['b', 1], ['c', 2], ['d', 3]].map(([t, s]) => ({ text: ' ' + t, start: s, end: s + 0.9 }))
const cue = { id: 1, start: 0, end: 3.9, text: 'a b c d', wi: [0, 1, 2, 3] }
const cm = new CutMap([[1, 2]])
// What the video and the .srt file show (the editor's order: cut words out of the raw cue first, then what shows; the cut times last).
const burn = (cues, show, cuts = cm) => dropCutWords(cues, cuts, words).map((c) => ({ ...c, text: shownText(c, c.text, show, true) }))
const withTr = { ...cue, tr: 'x y z', trm: 'a b c d' }

// Words cut: the subtitle loses them, in the video and in the file, with each way of showing a translation.
eq('src', burn([withTr], 'src').map((c) => c.text), ['a c d'])
eq('both: the translation goes with the cut word', burn([withTr], 'both').map((c) => c.text), ['a c d'])
eq('tr: no garbled translation', burn([withTr], 'tr').map((c) => c.text), ['a c d'])
eq('wi follows the text', dropCutWords([withTr], cm, words)[0].wi, [0, 2, 3])
eq('tr dropped', dropCutWords([withTr], cm, words)[0].tr, undefined)
// The file: the same cues in the cut video's time.
const srt = cutCues(burn([withTr], 'both'), cm)
eq('srt texts', srt.map((c) => c.text), ['a c d'])
eq('end moves', Math.round(srt[0].end * 10) / 10, 2.9)
// A cue without cut words keeps its translation.
eq('untouched', dropCutWords([withTr], new CutMap([[10, 11]]), words), [withTr])
eq('no cuts', dropCutWords([withTr], new CutMap([]), words), [withTr])
// Text edited so its words no longer match the subtitle's words: the cue's time is spread over its words.
eq('edited count', burn([{ ...cue, text: 'a b c' }], 'src').map((c) => c.text), ['a c'])
// Every word cut: the subtitle goes (and one cut out whole).
eq('all cut', burn([{ id: 2, start: 1, end: 1.9, text: 'b', wi: [1] }], 'src', new CutMap([[0.9, 2.1]])), [])
eq('cutCues without words', cutCues([cue], cm).map((c) => c.text), ['a b c d'])
// With words given, cutCues does both (raw cues, as the batch's file).
eq('cutCues with words', cutCues([cue], cm, words).map((c) => c.text), ['a c d'])
console.log('cutsrt ok')
