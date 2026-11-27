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
const { cutCues, dropCutWords, shownText, maskCue, maskWarning, orphansOf } = load('subtitles.ts')
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
// A cut word the user already took out of the text: nothing else goes (the subtitle stays).
eq('edited, cut word taken out', dropCutWords([{ ...ed, text: 'well x bad c dog' }], new CutMap([[0, 0.5]]), ws).map((c) => c.text), ['well x bad c dog'])
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

// ---- one alignment for masking and cutting (round 3 review)
{
  // John is bleeped and cut; the text was edited ("um" taken out, "there" added): no unmasked "john", and "said" is not what goes.
  const w = W([['um', 0, 0.4], ['john', 0.5, 0.9], ['said', 1.0, 1.4], ['hi', 1.5, 1.9]])
  const bl = new Set([1])
  const c = { id: 20, start: 0, end: 1.9, text: 'john said hi there', wi: [0, 1, 2, 3] }
  const out = dropCutWords([c], new CutMap([[0.5, 0.9]]), w)
  eq('john cut', out.map((x) => [x.text, x.wi]), [['said hi there', [0, 2, 3]]])
  const shownOut = maskCue(out[0], w, bl, orphansOf(out, bl))
  if (/john/i.test(shownOut)) fail('john shows after the cut: ' + shownOut)
  // A filler of no length, cut by its 80 ms.
  const z = W([['a', 0, 0.5], ['ummm', 0.6, 0.6], ['b', 0.7, 1.2]])
  eq('zero length', dropCutWords([{ id: 21, start: 0, end: 1.2, text: 'a ummm b', wi: [0, 1, 2] }], new CutMap([[0.6, 0.68]]), z).map((x) => x.text), ['a b'])
  // A word the user took out before the filler cut: the rest stays.
  const h = W([['אממ', 0, 0.4], ['אני', 0.5, 0.9], ['חושב', 1.0, 1.4], ['שכן', 1.5, 1.9]])
  eq('taken out first', dropCutWords([{ id: 22, start: 0, end: 1.9, text: 'אני חושב שכן', wi: [0, 1, 2, 3] }], new CutMap([[0, 0.4]]), h).map((x) => [x.text, x.wi]), [['אני חושב שכן', [1, 2, 3]]])
  // A reordered text: the cut word is found by its text, not by its place.
  const l = W([['כלב', 0, 0.5], ['שלי', 0.6, 1], ['לב', 1.1, 1.5]])
  eq('reordered', dropCutWords([{ id: 23, start: 0, end: 1.5, text: 'לב הכלב שלי טוב', wi: [0, 1, 2] }], new CutMap([[1.1, 1.5]]), l).map((x) => x.text), ['הכלב שלי טוב'])
  // Equal words go in the order they come.
  const d = W([['ספר', 0, 0.5], ['ספר', 0.6, 1.1]])
  eq('duplicates, first cut', dropCutWords([{ id: 24, start: 0, end: 1.1, text: 'ספר ספר', wi: [0, 1] }], new CutMap([[0, 0.5]]), d).map((x) => [x.text, x.wi]), [['ספר', [1]]])
  const d2 = W([['Hi', 0, 0.5], ['hi', 0.6, 1.1]])
  eq('Hi hi, second cut', dropCutWords([{ id: 25, start: 0, end: 1.1, text: 'Hi hi', wi: [0, 1] }], new CutMap([[0.6, 1.1]]), d2).map((x) => [x.text, x.wi]), [['Hi', [0]]])
  // A word respelled in place goes with its cut word.
  eq('respelled in place', dropCutWords([{ id: 26, start: 0, end: 1.9, text: 'um jon said hi', wi: [0, 1, 2, 3] }], new CutMap([[0.5, 0.9]]), w).map((x) => x.text), ['um said hi'])
  // 1500 imported subtitles with recognized words and 2000 cuts: fast.
  const many = [], cs = []
  for (let k = 0; k < 30000; k++) many.push({ text: ' w' + (k % 50), start: k * 0.36, end: k * 0.36 + 0.3 })
  const imp = []
  for (let k = 0; k < 1500; k++) imp.push({ id: k, start: k * 7.2, end: k * 7.2 + 7, text: Array.from({ length: 20 }, (_, j) => 'w' + ((k * 20 + j) % 50)).join(' ') })
  for (let i = 0; i < 2000; i++) cs.push([i * 5.4 + 0.1, i * 5.4 + 0.5])
  const t0 = Date.now()
  dropCutWords(imp, new CutMap(cs), many)
  const ms = Date.now() - t0
  if (ms > 100) fail('1500 imported subtitles took ' + ms + ' ms')
}

// ---- round 5 review: the export is the preview with the cut words taken out; the warnings; retimed and imported subtitles
{
  const exportOf = (cue, w, bl, cut) => dropCutWords([cue], new CutMap([cut]), w, (c) => maskCue(c, w, bl, orphansOf([cue], bl))).map((c) => c.text)
  eq('export, jhon after um cut', exportOf({ id: 30, start: 0, end: 1, text: 'hey um jhon', wi: [0, 1] }, W([['um', 0, 0.3], ['john', 0.5, 0.8]]), new Set([1]), [0, 0.3]), ['hey ****'])
  eq('export, דאני after אמ cut', exportOf({ id: 31, start: 0, end: 1, text: 'טוב אמ דאני', wi: [0, 1] }, W([['אמ', 0, 0.3], ['דני', 0.5, 0.8]]), new Set([1]), [0, 0.3]), ['טוב ****'])
  // A bleeped word that is not its text or a prefix form of it says so (and is hidden where it was found).
  const warn = (text, w, wiN) => maskWarning({ id: 32, start: 0, end: 2, text, wi: wiN }, w, new Set([1]))
  const jw = W([['um', 0, 0.3], ['john', 0.5, 0.8], ['said', 1, 1.3], ['hi', 1.5, 1.8]])
  const dw = W([['אמ', 0, 0.3], ['דני', 0.5, 0.8], ['אמר', 1, 1.3], ['שלום', 1.5, 1.8]])
  for (const [text, w] of [['Jon said hi', jw], ['דאני אמר שלום', dw], ['אמ אמר שלום ודאני', dw], ['said־דניי', dw], ['so he said yes Dny', W([['so', 0, 0.3], ['dani', 0.5, 0.8], ['said', 1, 1.3], ['yes', 1.5, 1.8]])]]) {
    if (!warn(text, w, w.map((_, k) => k))) fail('no warning for ' + text)
  }
  if (!warn('تعال يا محمود', W([['يا', 0, 0.3], ['محمد', 0.5, 0.8], ['تعال', 1, 1.3]]), [0, 1, 2])) fail('no warning for the Arabic respelling')
  // Retimed subtitles and a word of no length at the end.
  const rw = W([['a', 0, 0.5], ['b', 0.6, 1.1], ['secret', 1.2, 1.6]])
  eq('retimed, last word cut', dropCutWords([{ id: 33, start: 0, end: 1.15, text: 'a b secret', wi: [0, 1, 2] }], new CutMap([[1.2, 1.6]]), rw).map((c) => c.text), ['a b'])
  const zw = W([['a', 0, 0.5], ['b', 0.6, 1.1], ['umm', 1.2, 1.2]])
  eq('zero-length last word', dropCutWords([{ id: 34, start: 0, end: 1.2, text: 'a b umm', wi: [0, 1, 2] }], new CutMap([[1.2, 1.28]]), zw).map((c) => c.text), ['a b'])
  // An imported subtitle in another language than the words: kept as it is.
  const ew = W([['hello', 0, 0.3], ['my', 0.5, 0.8], ['dear', 1, 1.3], ['friend', 1.5, 1.8]])
  eq('imported, other language', dropCutWords([{ id: 35, start: 0, end: 2, text: 'שלום לך חבר יקר' }], new CutMap([[0.5, 0.8]]), ew).map((c) => c.text), ['שלום לך חבר יקר'])
  // A cleared subtitle leaves its cut words out of its list.
  eq('cleared', dropCutWords([{ id: 36, start: 0, end: 2, text: '', wi: [0, 1, 2, 3] }], new CutMap([[0.5, 0.8]]), ew).map((c) => c.wi), [[0, 2, 3]])
  // An imported subtitle with no words of its own keeps every word that is not a cut word (no respelling or stretch match).
  const mk = (str) => str.split(' ').map((x, k) => ({ text: ' ' + x, start: k * 0.4, end: k * 0.4 + 0.3 }))
  const imp = (said, text, cut) => { const w = mk(said); const i = said.split(' ').indexOf(cut); return dropCutWords([{ id: 37, start: 0, end: 4, text }], new CutMap([[i * 0.4, i * 0.4 + 0.3]]), w).map((c) => c.text) }
  eq('import ok we went home', imp('ok we went home', 'ok הלכנו הביתה עכשיו', 'went'), ['ok הלכנו הביתה עכשיו'])
  eq('import spanish', imp('i paid 50 dollars for it', 'pagué 50 dólares por eso', 'dollars'), ['pagué 50 dólares por eso'])
  eq('import same language', imp('ok we went home', 'ok we went home', 'went'), ['ok we home'])
  // The translation of a subtitle whose text was masked stays hidden, as in the preview (a translation made from the unmasked text).
  const tw = [{ text: ' דני', start: 0, end: 0.3 }, { text: ' אמר', start: 0.4, end: 0.7 }, { text: ' שלום', start: 0.8, end: 1.1 }]
  const tb = new Set([0])
  for (const cuts of [[], [[5, 6]]]) for (const trm of [undefined, 'דני אמר שלום']) {
    const c = { id: 38, start: 0, end: 1.2, text: 'דני אמר שלום', wi: [0, 1, 2], tr: 'Danny said hello', ...(trm ? { trm } : {}) }
    const orph = orphansOf([c], tb), masked = (x) => maskCue(x, tw, tb, orph)
    const out = dropCutWords([c], new CutMap(cuts), tw, masked).map((x) => shownText(x, x.text, 'both', true))
    if (out[0].includes('Danny')) fail('translation leaked: ' + JSON.stringify(out) + ' trm=' + trm)
  }
}
console.log('cutsrt ok')
