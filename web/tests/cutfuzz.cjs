// Cutting and masking share one alignment of a subtitle's tokens to its words. Random subtitles (words with zero-length ones, one edit of
// the text each: nothing, an added word, a word taken out, a word respelled in place, two swapped, one moved, punctuation), random cuts
// and bleeps; each case checks, on the cue after the cut words are dropped:
//  - no token of a cut word survives, and no token of an uncut word (or one the user added) is dropped;
//  - no token of a bleeped word shows unmasked without maskWarning;
//  - the words list (wi) holds exactly the words that stay.
// Pure and seeded: it runs the same every time. Run: node tests/cutfuzz.cjs [cases] [seed]
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
const load = (f) => {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/' + f), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const mod = { exports: {} }
  new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
  return mod.exports
}
const { dropCutWords, maskCue, maskWarning, orphansOf, wordSpan } = load('subtitles.ts')
const { CutMap } = load('cutmap.ts')
const cases = Number(process.argv[2] || 4000)
let seed = Number(process.argv[3] || 20261127)
const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32
const pick = (a) => a[Math.floor(rnd() * a.length)]
const VOCAB = ['john', 'damn', 'כלב', 'שלום', 'בית', 'ספר', 'מה', 'hi', 'גדול', 'kate', 'פרח', 'סוס', 'dog', 'cat', 'ice', 'bye', 'tree', 'um', 'אמ', 'לב']
const covers = (cm, a, b) => b > a && (b - a - cm.kept(a, b)) * 2 >= b - a
const errors = []
const bad = (m, info) => { if (errors.length < 5) console.error('ERROR', m, JSON.stringify(info)); errors.push(m) }
const toks = (s) => s.match(/\S+/g) ?? []
for (let it = 0; it < cases; it++) {
  const n = 2 + Math.floor(rnd() * 6)
  const vocab = VOCAB.slice().sort(() => rnd() - 0.5).slice(0, n)
  const words = []
  let t = 0
  vocab.forEach((v) => { const d = rnd() < 0.2 ? 0 : 0.1 + rnd() * 0.4; words.push({ text: ' ' + v, start: t, end: t + d }); t += d + 0.05 + rnd() * 0.3 })
  // tokens with the word they come from (-1: added by the user)
  let tk = words.map((w, k) => ({ t: w.text.trim(), k }))
  const mode = pick(['none', 'insert', 'delete', 'respell', 'swap', 'move', 'punct'])
  if (mode === 'insert') tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, { t: 'zzz' + it, k: -1 })
  if (mode === 'delete' && tk.length > 1) tk.splice(Math.floor(rnd() * tk.length), 1)
  if (mode === 'respell') { const i = Math.floor(rnd() * tk.length); tk[i] = { t: 'qxq' + it, k: tk[i].k } }
  if (mode === 'swap' && tk.length > 1) { const i = Math.floor(rnd() * (tk.length - 1)); [tk[i], tk[i + 1]] = [tk[i + 1], tk[i]] }
  if (mode === 'move' && tk.length > 2) { const [x] = tk.splice(Math.floor(rnd() * tk.length), 1); tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, x) }
  if (mode === 'punct') { const i = Math.floor(rnd() * tk.length); tk[i] = { ...tk[i], t: tk[i].t + pick([',', '.', '?', '!']) } }
  const text = tk.map((x) => x.t).join(rnd() < 0.2 ? '\n' : ' ')
  const wi = words.map((_, k) => k)
  const cue = { id: it, start: 0, end: t, text, wi }
  const cuts = []
  for (let i = 0, nc = 1 + Math.floor(rnd() * 2); i < nc; i++) { const w = pick(words); const [a, b] = wordSpan(w); cuts.push(rnd() < 0.7 ? [a, b] : [a - 0.05, b - 0.01 + rnd() * 0.1]) }
  const cm = new CutMap(cuts)
  const bleeped = new Set(wi.filter(() => rnd() < 0.3))
  const info = { it, mode, text, words: words.map((w) => w.text.trim()), cuts, bleeped: [...bleeped] }
  const out = dropCutWords([cue], cm, words)
  const isCut = (x) => x.k >= 0 && covers(cm, ...wordSpan(words[x.k]))
  const allCut = tk.length > 0 && tk.every(isCut)
  if (out.length === 0) { if (!allCut && tk.length > 0) bad('a cue with uncut words was dropped', info); continue }
  const o = out[0]
  const left = toks(o.text)
  // which original tokens are left: the output's tokens are a subsequence of the original ones (their texts are distinct)
  const alive = new Set()
  let j = 0
  tk.forEach((x, i) => { if (j < left.length && left[j] === x.t) { alive.add(i); j++ } })
  if (j !== left.length) { bad('the output holds a token that was not in the text', { ...info, out: o.text }); continue }
  tk.forEach((x, i) => {
    if (isCut(x) && alive.has(i)) bad('a cut word survives', { ...info, out: o.text })
    if (!isCut(x) && !alive.has(i)) bad('a word that was not cut lost its token', { ...info, out: o.text })
  })
  // the words list holds the words that stay
  const stay = wi.filter((k) => !covers(cm, ...wordSpan(words[k])))
  if (JSON.stringify(o.wi) !== JSON.stringify(stay)) bad('wi is not the words that stay', { ...info, wi: o.wi })
  // a bleeped word's token never shows unmasked without a warning
  const orph = orphansOf(out, bleeped)
  const masked = toks(maskCue(o, words, bleeped, orph))
  const warn = maskWarning(o, words, bleeped, orph)
  const left2 = toks(o.text)
  tk.forEach((x, i) => {
    if (!alive.has(i) || x.k < 0 || !bleeped.has(x.k)) return
    const p = [...alive].indexOf(i)
    if (!masked[p].includes('****') && !warn) bad('a bleeped word shows with no warning', { ...info, out: o.text, masked: masked.join(' ') })
  })
}
if (errors.length) { console.error('FAIL: ' + errors.length + ' errors in ' + cases + ' cases'); process.exit(1) }
console.log('cutfuzz ok: ' + cases + ' cases, 0 errors')
