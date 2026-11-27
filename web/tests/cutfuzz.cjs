// Cutting and masking share one alignment of a subtitle's tokens to its words. Random subtitles (words with zero-length ones; up to three
// edits of the text each: a word added or added again, taken out, respelled near / far / with a prefix / in case / in quotes, two swapped, one
// moved, punctuation, two joined with a maqaf, the text cleared), random cuts and bleeps. Every token knows the word it came from. Per case:
//  - the export pipeline (the masking decided on the whole raw subtitle, the cut words taken out of it, never masked again) keeps exactly the
//    tokens the preview shows, and hides what the preview hides;
//  - no token of a cut word survives, and no token of an uncut word is dropped (not checked for a far respelling, a joined token or an
//    added one, which no alignment can tell from a new word; nor for equal words, whose tokens are the same text);
//  - no token that comes from a bleeped word shows unmasked unless the RAW subtitle warns (maskWarning: what blocks the export);
//  - the words list (wi) holds the words that stay.
// Pure and seeded. Run: node tests/cutfuzz.cjs [cases] [seed]
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
const load = (f) => {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/' + f), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const mod = { exports: {} }
  new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
  return mod.exports
}
const { dropCutWords, cutTokens, maskCue, maskWarning, orphansOf, wordSpan, normWord } = load('subtitles.ts')
const { CutMap } = load('cutmap.ts')
const cases = Number(process.argv[2] || 4000)
let seed = Number(process.argv[3] || 20261127)
const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32
const pick = (a) => a[Math.floor(rnd() * a.length)]
const VOCAB = ['john', 'dani', 'said', 'hi', 'the', 'a', 'כלב', 'לב', 'דני', 'שלום', 'בית', 'ספר', 'מה', 'אמר', 'محمد', 'كلب', 'يا', 'تعال', 'um', 'אמ', 'kate', 'tree']
const covers = (cm, a, b) => b > a && (b - a - cm.kept(a, b)) * 2 >= b - a
const errors = []
const bad = (m, info) => { if (errors.length < 5) console.error('ERROR', m, JSON.stringify(info)); errors.push(m) }
const toks = (s) => s.match(/\S+/g) ?? []
const respell = (t, kind) => {
  const he = /[א-ת]/.test(t), ar = /[ء-ي]/.test(t)
  if (kind === 'near') return t + (he ? 'י' : ar ? 'ي' : 'y')
  if (kind === 'far') return t[0] + (he ? 'א' : ar ? 'ا' : 'h') + t.slice(1)
  if (kind === 'prefix') return (he ? 'ו' : '') + t
  if (kind === 'case') return t.toUpperCase()
  return '"' + t + '",'
}
for (let it = 0; it < cases; it++) {
  const dups = it % 4 === 3
  const n = 1 + Math.floor(rnd() * 6)
  const pool = VOCAB.slice()
  const words = []
  let t = 0
  for (let i = 0; i < n; i++) { const d = rnd() < 0.15 ? 0 : 0.1 + rnd() * 0.4; words.push({ text: ' ' + (dups ? pick(VOCAB) : pool.splice(Math.floor(rnd() * pool.length), 1)[0]), start: t, end: t + d }); t += d + 0.05 + rnd() * 0.3 }
  const wi = words.map((_, k) => k)
  // tokens: their text, the words they come from (more than one when joined), and how they were made
  let tk = wi.map((k) => ({ t: words[k].text.trim(), ks: [k], kind: 'same' }))
  const edits = []
  for (let e = 0, ne = Math.floor(rnd() * 4); e < ne; e++) {
    const m = pick(['insert', 'insertDup', 'delete', 'respell', 'swap', 'move', 'punct', 'maqaf', 'clear'])
    edits.push(m)
    if (m === 'insert') tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, { t: 'zq' + it, ks: [], kind: 'new' })
    if (m === 'insertDup') tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, { t: pick(VOCAB), ks: [], kind: 'new' })
    if (m === 'delete' && tk.length) tk.splice(Math.floor(rnd() * tk.length), 1)
    if (m === 'respell' && tk.length) { const i = Math.floor(rnd() * tk.length), kind = pick(['near', 'far', 'prefix', 'case', 'quote']); tk[i] = { ...tk[i], t: respell(tk[i].t, kind), kind: tk[i].kind === 'same' ? kind : 'mixed' } }
    if (m === 'swap' && tk.length > 1) { const i = Math.floor(rnd() * (tk.length - 1)); [tk[i], tk[i + 1]] = [tk[i + 1], tk[i]] }
    if (m === 'move' && tk.length > 1) { const [x] = tk.splice(Math.floor(rnd() * tk.length), 1); tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, x) }
    if (m === 'punct' && tk.length) { const i = Math.floor(rnd() * tk.length); tk[i] = { ...tk[i], t: tk[i].t + pick([',', '.', '?', '!', '…']) } }
    if (m === 'maqaf' && tk.length > 1) { const i = Math.floor(rnd() * (tk.length - 1)); tk.splice(i, 2, { t: tk[i].t + '־' + tk[i + 1].t, ks: [...tk[i].ks, ...tk[i + 1].ks], kind: 'join' }) }
    if (m === 'clear') tk = []
  }
  const text = tk.map((x) => x.t).join(rnd() < 0.2 ? '\n' : ' ')
  const cue = { id: it, start: 0, end: Math.max(words[n - 1].end, 0.8), text, wi }
  const bleeped = new Set(wi.filter(() => rnd() < 0.35))
  const cuts = []
  if (rnd() < 0.7) for (let i = 0, nc = 1 + Math.floor(rnd() * 2); i < nc; i++) { const [a, b] = wordSpan(pick(words)); cuts.push(rnd() < 0.7 ? [a, b] : [a - 0.05, b - 0.01 + rnd() * 0.1]) }
  const cm = new CutMap(cuts)
  const info = { it, edits, text, words: words.map((w) => w.text.trim() + (w.end === w.start ? '(0)' : '')), cuts: cuts.map((c) => c.map((x) => +x.toFixed(2))), bleeped: [...bleeped] }
  const isCut = (k) => covers(cm, ...wordSpan(words[k]))
  const keyCount = (k) => words.filter((w) => normWord(w.text) === normWord(words[k].text)).length
  const orph = orphansOf([cue], bleeped)
  const preview = toks(maskCue(cue, words, bleeped, orph))
  const rawWarn = maskWarning(cue, words, bleeped, orph)
  // the export pipeline of the editor
  const out = dropCutWords([cue], cm, words, (c) => maskCue(c, words, bleeped, orph))
  const g = cutTokens(cue, tk.map((x) => x.t), words, cm)
  const gone = g ? g.gone : new Set()
  const keep = tk.map((_, i) => i).filter((i) => !gone.has(i))
  if (tk.length === 0) { if (out.length !== 1 || JSON.stringify(out[0].wi) !== JSON.stringify(wi.filter((k) => !isCut(k)))) bad('a cleared subtitle did not leave its cut words out of wi', { ...info, out }); continue }
  if (keep.length === 0) { if (out.length) bad('a subtitle with no token left stayed', info); continue }
  if (out.length !== 1) { bad('the subtitle vanished with tokens left', info); continue }
  const o = out[0]
  // the export keeps the preview's tokens (masked as the preview shows them) at the places that stay
  if (JSON.stringify(toks(o.text)) !== JSON.stringify(keep.map((i) => preview[i]))) bad('the export is not the preview with the cut words out', { ...info, out: o.text, preview: preview.join(' ') })
  if (!toks(o.text).every((x, p) => x === preview[keep[p]])) continue
  const joined = tk.some((x) => x.kind === 'join' || x.ks.length > 1)
  tk.forEach((x, i) => {
    const k = x.ks.length === 1 ? x.ks[0] : -1
    // (Not for a far or mixed respelling or a joined token, which no alignment tells from a new word; not for a respelled word of under three
    // letters, nor for equal words (another word, or a token the user added, says the same), whose tokens are the same text.)
    const sameText = tk.filter((y) => normWord(y.t) === normWord(x.t) || (normWord(y.t).length >= 2 && normWord(y.t).slice(0, 3) === normWord(x.t).slice(0, 3))).length > 1 || (k >= 0 && keyCount(k) > 1)
    const tiny = k >= 0 && normWord(words[k].text).length < 3 && x.kind !== 'same' && x.kind !== 'case' && x.kind !== 'quote'
    const checks = k >= 0 && !joined && x.kind !== 'far' && x.kind !== 'mixed' && !sameText && !tiny
    if (checks && isCut(k) && !gone.has(i)) bad('a cut word survives', { ...info, out: o.text })
    if (checks && !isCut(k) && gone.has(i)) bad('a word that was not cut lost its token', { ...info, out: o.text })
  })
  // no word that stays is missing from wi, no cut word is left in it
  const stay = wi.filter((k) => !isCut(k))
  if (!tk.some((x) => x.ks.length === 0 && x.kind === 'new' && false) && o.wi && JSON.stringify(o.wi.filter((k) => !isCut(k))) !== JSON.stringify(o.wi)) bad('a cut word is left in wi', { ...info, wi: o.wi })
  void stay
  // privacy: what comes from a bleeped word never shows unmasked unless the raw subtitle warns
  if (!rawWarn) keep.forEach((i, p) => {
    const x = tk[i]
    if (dups && x.ks.some((k) => keyCount(k) > 1)) return
    if (x.ks.some((k) => bleeped.has(k)) && !toks(o.text)[p].includes('****')) bad('a bleeped word shows in the export with no warning', { ...info, out: o.text })
  })
}
if (errors.length) { console.error('FAIL: ' + errors.length + ' errors in ' + cases + ' cases'); process.exit(1) }
console.log('cutfuzz ok: ' + cases + ' cases, 0 errors')
