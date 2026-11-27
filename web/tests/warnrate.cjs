// Warning rate of the mask check on realistic small edits of one subtitle (1-2 bleeped words): a subtitle that still hides the bleeped word must
// not be flagged (under 3% for a typo, an ASR fix, an added word and a deleted filler). Pure: no browser. Run: node tests/warnrate.cjs
let seed = 3
const path = require('path'), fs = require('fs'), ts = require('typescript')
const S = (() => { const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/subtitles.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText; const m = { exports: {} }; new Function('module', 'exports', 'require', code)(m, m.exports, () => ({})); return m.exports })()
const rnd = () => { seed |= 0; seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296 }
const pick = (a) => a[Math.floor(rnd() * a.length)]
const SENT = [
  'אז דני אמר לי שהוא יבוא מחר בבוקר', 'אני לא מאמין שיוסי עשה את זה שוב', 'תגיד לאבי שהמפקד מחכה לו בחדר', 'היא אמרה שהבית של משה נמצא ליד הים',
  'ואז הוא צעק עליי מול כל החבר׳ה', 'שמעתי שרונית עברה לגור בתל אביב עם המשפחה', 'בוא נלך לאכול אצל סבתא של נועה', 'זה לא בסדר מה שאמרת לי אתמול בערב',
  'so john told me he would come tomorrow', 'i cannot believe kate did that again', 'tell mike the boss is waiting for him', 'we went to the beach with sarah and her dog',
  'قال محمد إنه سيأتي غدا صباحا', 'يا أحمد تعال هنا بسرعة',
]
const FILL = ['אממ', 'כאילו', 'um', 'like', 'يعني']
const ADD = ['באמת', 'גם', 'really', 'also', 'جدا']
const typo = (w) => { if (w.length < 3) return w + w[0]; const i = 1 + Math.floor(rnd() * (w.length - 2)); return w.slice(0, i) + w[i + 1] + w[i] + w.slice(i + 2) }
const KINDS = {
  'typo fix (other word)': (tk, b) => { const c = tk.map((_, i) => i).filter((i) => !b.has(i)); const i = pick(c); tk[i] = typo(tk[i]) },
  'asr fix: other word replaced': (tk, b) => { const c = tk.map((_, i) => i).filter((i) => !b.has(i)); tk[pick(c)] = pick(['ספר', 'בית', 'house', 'كتاب', 'מחר', 'tomorrow']) },
  'punctuation added': (tk) => { const i = Math.floor(rnd() * tk.length); tk[i] += pick([',', '.', '?', '!']) },
  'punct after bleeped': (tk, b) => { const i = pick([...b]); tk[i] += pick([',', '.', '?', '!']) },
  'word added': (tk) => { tk.splice(Math.floor(rnd() * (tk.length + 1)), 0, pick(ADD)) },
  'word added next to bleeped': (tk, b) => { const i = pick([...b]); tk.splice(i + (rnd() < 0.5 ? 0 : 1), 0, pick(ADD)) },
  'filler deleted (other word)': (tk, b) => { const c = tk.map((_, i) => i).filter((i) => !b.has(i)); tk.splice(pick(c), 1) },
  'capitalize first': (tk) => { tk[0] = tk[0][0].toUpperCase() + tk[0].slice(1) },
  'quote marks around sentence': (tk) => { tk[0] = '"' + tk[0]; tk[tk.length - 1] += '"' },
  'bleeped respelled': (tk, b) => { const i = pick([...b]); tk[i] = typo(tk[i]) },
  'bleeped deleted': (tk, b) => { tk.splice(pick([...b]), 1) },
  'two edits: typo + add': (tk, b) => { KINDS['typo fix (other word)'](tk, b); tk.push(pick(ADD)) },
}
const N = 3000
const LIMIT = ['typo fix (other word)', 'asr fix: other word replaced', 'word added', 'filler deleted (other word)']
for (const [kind, f] of Object.entries(KINDS)) {
  seed = 3; let w = 0
  for (let it = 0; it < N; it++) {
    const s = pick(SENT).split(' ')
    const words = s.map((x, k) => ({ text: ' ' + x, start: k * 0.4, end: k * 0.4 + 0.3 }))
    const nb = rnd() < 0.7 ? 1 : 2
    const bleeped = new Set(); while (bleeped.size < Math.min(nb, s.length - 1)) bleeped.add(Math.floor(rnd() * s.length))
    const tk = s.slice(); f(tk, bleeped)
    const cue = { id: 1, start: 0, end: s.length * 0.4, text: tk.join(' '), wi: words.map((_, k) => k) }
    if (S.maskWarning(cue, words, bleeped, S.orphansOf([cue], bleeped))) w++
  }
  const rate = 100 * w / N
  console.log(kind.padEnd(30), rate.toFixed(1) + '%')
  if (LIMIT.includes(kind) && rate >= 3) { console.error('FAIL warning rate too high for ' + kind); process.exit(1) }
}
console.log('warnrate ok')
