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

// ---- the review's repros
const W = (list) => list.map(([t, a, b]) => ({ text: ' ' + t, start: a, end: b }))
const ws = W([['a', 0, 0.5], ['b', 0.6, 1.1], ['c', 1.2, 1.7], ['d', 1.8, 2.3]])
// An edited subtitle "well a bad c dog" (its words a b c d): the cut word is found by its text, the others stay.
const ed = { id: 5, start: 0, end: 2.3, text: 'well a bad c dog', wi: [0, 1, 2, 3] }
eq('edited, a cut', dropCutWords([ed], new CutMap([[0, 0.5]]), ws).map((c) => [c.text, c.wi]), [['well bad c dog', [1, 2, 3]]])
// A cut word that is nowhere in the edited text: the whole subtitle goes rather than keep it.
eq('edited, cut word gone from the text', dropCutWords([{ ...ed, text: 'well x bad c dog' }], new CutMap([[0, 0.5]]), ws), [])
// An imported subtitle with no words, nothing recognized there: the time is spread over the words; only a word wholly cut goes.
eq('imported, spread', dropCutWords([{ id: 6, start: 0, end: 1.5, text: 'hello there friend' }], new CutMap([[0, 0.5]]), []).map((c) => c.text), ['there friend'])
// A silence cut across the middle of an imported subtitle: no word is wholly inside it, nothing goes.
eq('imported, silence', dropCutWords([{ id: 7, start: 0, end: 4, text: 'Hello everyone, welcome back' }], new CutMap([[1.2, 2.8]]), []).map((c) => c.text), ['Hello everyone, welcome back'])
// With words recognized there, those cut are found by text; a cut in a pause leaves the subtitle as it is.
const rec = W([['hello', 0, 0.5], ['everyone', 0.6, 1.2], ['welcome', 2.8, 3.3], ['back', 3.4, 3.9]])
const imp = { id: 8, start: 0, end: 4, text: 'Hello everyone, welcome back' }
eq('imported, pause', dropCutWords([imp], new CutMap([[1.3, 2.7]]), rec), [imp])
eq('imported, a recognized word cut', dropCutWords([imp], new CutMap([[0.55, 1.25]]), rec).map((c) => c.text), ['Hello welcome back'])
// Most of a word's span, not its middle: 60 % cut goes, 40 % stays.
eq('most of the span', dropCutWords([cue], new CutMap([[1, 1.6]]), words).map((c) => c.text), ['a c d'])
eq('less than half', dropCutWords([cue], new CutMap([[1, 1.3]]), words).map((c) => c.text), ['a b c d'])
// Line breaks the user typed stay.
const br = { id: 9, start: 0, end: 3.9, text: 'a b\nc d', wi: [0, 1, 2, 3] }
eq('line break', dropCutWords([br], new CutMap([[0, 0.9]]), words).map((c) => c.text), ['b\nc d'])
eq('line break kept in the middle', dropCutWords([br], new CutMap([[3, 3.9]]), words).map((c) => c.text), ['a b\nc'])
console.log('cutsrt ok')
