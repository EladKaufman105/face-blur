// Bleeped words never show in the subtitles. A subtitle holds its words by index (as the app: its text's words); the bleeped
// ones are masked by place while the text is the words said, by text (and place, when they still fit) once it was edited, and
// a subtitle that may still show one says so (maskWarning), which blocks the export until it is fixed or confirmed.
// Covers split, merge, retime, delete, text edits, translation and undo, in random sequences. Pure: no browser.
// Run: node tests/splitmask.cjs [rounds]
const path = require('path')
const fs = require('fs')
const ts = require('typescript')
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/subtitles.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', 'require', code)(mod, mod.exports, () => ({}))
const S = mod.exports
const { splitCue, splitCueAtTime, maskCue, maskWarning, mergeCues, retimeCue, cueRoom, wordsToCues, migrateCues, normWord, shownText, trVisible, cueWi, orphansOf, unownedIn, alignCue } = S
const W = (list) => list.map(([text, start, end]) => ({ text: ' ' + text, start, end: end ?? start + 0.2 }))
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const eq = (name, got, want) => { if (JSON.stringify(got) !== JSON.stringify(want)) fail(`${name}: ${JSON.stringify(got)} (wanted ${JSON.stringify(want)})`) }
const toks = (s) => s.match(/\S+/g) ?? []

// ---- the words of a subtitle are made with it
{
  const words = W([['you', 1.0], ['[music]', 1.2], ['bastard', 1.55], ['ok.', 1.6], ['next', 1.6]])
  const cues = wordsToCues(words)
  eq('wordsToCues', cues.map((c) => [c.text, c.wi]), [['you bastard ok.', [0, 2, 3]], ['next', [4]]])
  // (Whisper gives "ok." and "next" the same start: the words are the subtitle's, not the time's.)
  const b = new Set([2])
  eq('bleep from the chips', maskCue(cues[0], words, b), 'you **** ok.')
  const p = splitCue(cues[0], cues[0].text.indexOf('ok.'), words, b)
  eq('rep2 split', p.map((h) => [h.text, h.wi, maskCue(h, words, b)]), [['you bastard', [0, 2], 'you ****'], ['ok.', [3], 'ok.']])
  const q = splitCueAtTime(cues[0], 1.45, words, b)
  eq('rep2 playhead split', q.map((h) => maskCue(h, words, b)), ['you', '**** ok.'])
}
// ---- sentence ends, as the app's (SubLanguages.endsSentence): . ? ! … and the Arabic question mark and full stop
{
  const words = W([['هل', 1.0], ['أنت؟', 1.2], ['نعم۔', 1.4], ['تمام.', 1.6], ['שלום!', 1.8], ['טוב…', 2.0], ['ok?', 2.2], ['x', 2.4]])
  eq('sentence ends', wordsToCues(words).map((c) => c.text), ['هل أنت؟', 'نعم۔', 'تمام.', 'שלום!', 'טוב…', 'ok?', 'x'])
}
// ---- saved projects: the words a subtitle had by time are kept once
{
  const words = W([['a', 1.0], ['b', 1.5], ['[x]', 1.7], ['c', 2.0]])
  const old = [{ id: 1, start: 2.4, end: 3, ws: 1, we: 3, text: 'a b c d' }, { id: 2, start: 4, end: 5, text: 'zz' }]
  const m = migrateCues(old, words)
  eq('migrate', m.map((c) => [c.wi, 'ws' in c]), [[[0, 1, 3], false], [[], false]])
  if (migrateCues(m, words) !== m) fail('migrating twice changed the list')
}
// ---- text edits (probe 1, 1b, 2): the word stays masked, or the subtitle says it may show it
{
  const w = W([['what', 0], ['the', 0.5], ['fork', 1]])
  eq('1 edited text', maskCue({ id: 1, start: 0, end: 2, text: 'what the fuck', wi: [0, 1, 2] }, w, new Set([2])), 'what the ****')
  const w2 = W([['כלב', 0], ['רע', 0.5]]), b = new Set([0])
  for (const t of ['הכלב רע', 'כלבים רע', 'כֶּלֶב רע', 'כלב׳ רע', 'כ​לב רע', 'כלב־־רע', 'ו״כלב רע', 'כלב/רע', 'ש־הכלב רע', 'וכשהכלב רע', 'כלב רע רע רע', 'רע']) {
    const c = { id: 1, start: 0, end: 2, text: t, wi: [0, 1] }
    const m = maskCue(c, w2, b)
    if (!m.includes('****') && !maskWarning(c, w2, b)) fail(`1b ${t}: shows the word and says nothing: ${m}`)
  }
  const cases = [['fuck,', 'Fuck,'], ['‏כלב', 'כלב'], ['כלב‏', '‏כלב‏'], ['🤬', '🤬'], ['fuck🤬', 'fuck🤬'], ['f*ck', 'f*ck'], ['ו-כלב', 'ו-כלב'], ['ו־כלב', 'ו־כלב'], ['é', 'é'], ['- fuck', '- fuck'], ['دڵ', 'دڵ'], ['والله', 'والله'], ['می‌خواهم', 'می‌خواهم'], ['İstanbul', 'İstanbul'], ['ß', 'SS']]
  for (const [word, tok] of cases) {
    const c = { id: 1, start: 0, end: 2, text: tok + ' x', wi: [0, 1] }
    const m = maskCue(c, W([[word, 0], ['x', 1]]), new Set([0]))
    if (!m.startsWith('****') && !/^[^\p{L}\p{N}]*\*\*\*\*/u.test(m)) fail(`2 ${JSON.stringify(word)} / ${JSON.stringify(tok)}: ${m}`)
    if (!m.endsWith(' x')) fail('2 over-masked: ' + m)
  }
  // niqqud and a zero-width space are the same word
  eq('normWord', [normWord('כֶּלֶב'), normWord('כ​לב'), normWord('"Damn,"')], ['כלב', 'כלב', 'damn'])
}
// ---- whole tokens (not "cl****ic"), duplicates by place, over-masking after an edit
{
  const words = W([['classic', 1.0], ['ass', 1.5]])
  eq('classic ass', maskCue({ id: 1, start: 1, end: 2, text: 'classic ass', wi: [0, 1] }, words, new Set([1])), 'classic ****')
  const w = W([['x', 0], ['a', 0.5], ['b', 1], ['x', 1.5]])
  const c = { id: 1, start: 0, end: 2, text: 'x a b x', wi: [0, 1, 2, 3] }
  eq('4 one of two equal words', maskCue(c, w, new Set([0])), '**** a b x')
  const rs = []
  for (let at = 1; at < c.text.length; at++) { const r = splitCue(c, at, w, new Set([0])); if (r) rs.push(r.map((h) => maskCue(h, w, new Set([0]))).join('|')) }
  if (rs.some((x) => !x.startsWith('****') || x.split('****').length !== 2)) fail('4 splits: ' + rs)
  if (rs.length < 3) fail('4 duplicate-word splits were refused: ' + rs.length)
  const ed = { ...c, text: 'x a b x c' }
  eq('4 edited: every equal token', maskCue(ed, w, new Set([0])), '**** a b **** c')
}
// ---- a bleeped word that is in no token: position when the count fits, else a warning
{
  const w = W([['a', 0], ['bad', 0.5], ['c', 1]])
  const fits = { id: 1, start: 0, end: 2, text: 'a worse c', wi: [0, 1, 2] }
  eq('renamed word, same count', [maskCue(fits, w, new Set([1])), maskWarning(fits, w, new Set([1]))], ['a **** c', false])
  const lost = { id: 1, start: 0, end: 2, text: 'a worse c d', wi: [0, 1, 2] }
  eq('renamed word, other count', [maskCue(lost, w, new Set([1])), maskWarning(lost, w, new Set([1]))], ['a **** c d', true])  // (paired in place: hidden, and said)
  if (maskWarning({ ...lost, text: 'a bad c d' }, w, new Set([1]))) fail('a warning with the word in the text')
  if (maskWarning(lost, w, new Set())) fail('a warning with nothing bleeped')
}
// ---- merge keeps every word and never refuses; delete keeps the words (their bleeps stay)
{
  const words = W([['hello', 1.0], ['there', 1.3], ['the', 2.0], ['dog', 2.3], ['damn', 3.0], ['the', 3.3], ['cat', 3.6]])
  const A = { id: 1, start: 1, end: 1.8, text: 'hello there', wi: [0, 1] }, C = { id: 3, start: 3, end: 3.9, text: 'damn the cat', wi: [4, 5, 6] }
  const m = mergeCues(A, C)
  eq('merge', [m.text, m.wi, maskCue(m, words, new Set([4]))], ['hello there damn the cat', [0, 1, 4, 5, 6], 'hello there **** the cat'])
  const words2 = W([['hello', 1.0], ['-', 1.9], ['damn', 2.0], ['-', 2.2], ['ok', 2.4]])
  const m2 = mergeCues({ id: 1, start: 1, end: 1.8, text: 'hello', wi: [0] }, { id: 2, start: 2, end: 2.8, text: 'damn ok', wi: [2, 4] })
  eq('merge across noise', maskCue(m2, words2, new Set([2])), 'hello **** ok')
}
// ---- the translation never gives a word away
{
  const c = { id: 1, start: 0, end: 2, text: 'a bad c', wi: [0, 1, 2], tr: 'א רע ג' }
  const m = 'a **** c'
  eq('tr of the raw text, hidden', shownText(c, m, 'both'), m)
  eq('tr of the masked text, shown', shownText({ ...c, tr: 'א **** ג', trm: m }, m, 'both'), m + '\nא **** ג')
  eq('tr with no bleeps', shownText(c, 'a bad c', 'both'), 'a bad c\nא רע ג')
  eq('tr after the text changed', trVisible({ ...c, tr: 'x', trm: m }, 'a ****  c'), false)
  const j = mergeCues({ ...c, tr: 'א **** ג', trm: m }, { id: 2, start: 2, end: 3, text: 'd', wi: [3], tr: 'ד' })
  eq('merge joins the translations with their sources', [j.tr, j.trm], ['א **** ג ד', 'a **** c d'])
  eq('masking off shows it', shownText(c, 'a bad c', 'tr', false), 'א רע ג')
}
// ---- a bleeped word respelled and moved: said so, not masked by a guess
{
  const w = W([['so', 1], ['Dani', 1.3], ['said', 1.6], ['yes', 1.9]])
  const c = wordsToCues(w)[0], b = new Set([1])
  eq('orig', [maskCue(c, w, b), maskWarning(c, w, b)], ['so **** said yes', false])
  eq('respelled in place', [maskCue({ ...c, text: 'so Danny said yes' }, w, b), maskWarning({ ...c, text: 'so Danny said yes' }, w, b)], ['so **** said yes', false])
  const moved = { ...c, text: 'Danny said yes, okay' }
  eq('respelled and moved', [maskCue(moved, w, b), maskWarning(moved, w, b)], ['**** said yes, okay', true])  // (Danny is aligned to Dani by text and hidden, and said)
  eq('moved, other text', maskWarning({ ...c, text: 'Today I met Danny' }, w, b), true)
  const w2 = W([['I', 1], ['can', 1.2], ['not', 1.4], ['tell', 1.6], ['Dani', 1.8]]), b2 = new Set([4])
  const c2 = wordsToCues(w2)[0]
  eq('cannot', maskWarning({ ...c2, text: 'I cannot tell Danny, ok' }, w2, b2), true)
}
// ---- a subtitle made by hand over a bleeped word whose subtitle was deleted
{
  const w = W([['hello.', 1], ['you', 3], ['are', 3.3], ['bad.', 3.6]])
  const cues = wordsToCues(w), b = new Set([3])
  const left = [cues[0]], orph = orphansOf(left, b)
  eq('orphan', orph, [3])
  const typed = { id: 999, start: 3, end: 4.2, text: 'you are bad.', wi: [] }
  eq('typed over the orphan', [maskCue(typed, w, b, orph), maskWarning(typed, w, b, orph)], ['you are ****.', false])
  eq('typed without the word', maskWarning({ ...typed, text: 'you are' }, w, b, orph), true)
  eq('typed far from it', maskCue({ ...typed, start: 10, end: 11 }, w, b, orph), 'you are bad.')
  eq('made by hand: the free words are its', unownedIn(left, w, 3, 4.2), [1, 2, 3])
  const owned = { ...typed, wi: unownedIn(left, w, 3, 4.2) }
  eq('hand-made with its words', [maskCue(owned, w, b, orphansOf([...left, owned], b)), maskWarning(owned, w, b)], ['you are ****.', false])
}
// ---- migration: a word goes to one subtitle only; an empty one has no warning
{
  const w = W([['done.', 2.0, 2.3], ['Next', 2.005], ['one', 2.3]])
  const cues = wordsToCues(w)
  const m = migrateCues(cues.map(({ wi, ...c }) => c), w)
  const all = m.flatMap((c) => c.wi)
  if (new Set(all).size !== all.length) fail('a word in two subtitles after migrating: ' + JSON.stringify(m.map((c) => c.wi)))
  const empty = { id: 5, start: 0, end: 1, text: '', wi: [0] }
  eq('empty cue', maskWarning(empty, w, new Set([0])), false)
}

// ---- too short; strictly inside; retime clamps
{
  const words = W([['a', 1.0, 1.1], ['b', 1.15, 1.25]])
  if (splitCue({ id: 1, start: 1, end: 1.3, text: 'a b', wi: [0, 1] }, 2, words) !== null) fail('a cue of 0.3 s was split')
  if (splitCueAtTime({ id: 1, start: 1, end: 1.39, text: 'a b', wi: [0, 1] }, 1.2, words) !== null) fail('a cue of 0.39 s was split')
  const r = splitCue({ id: 1, start: 1, end: 1.4, text: 'a b', wi: [0, 1] }, 2, words)
  if (!r || !(r[0].start < r[0].end && r[1].start < r[1].end)) fail('0.4 s cue: ' + JSON.stringify(r))
  const c = { id: 1, start: 2, end: 2.05, text: 'x', wi: [] }
  if (retimeCue(c, true, 0.5, 2.0, 3).start < 2.0) fail('retime start under lo')
  if (retimeCue(c, false, 9, 0, 2.06).end > 2.06) fail('retime end over hi')
  eq('retime keeps the words', retimeCue({ ...c, wi: [3, 4] }, true, 2.0, 0, 9).wi, [3, 4])
}

// ---- random sequences: split (caret, playhead), merge, retime, delete, bleep (from the chips), text edit, translate, undo.
// A leak: a bleeped word shown in a subtitle (its place's token while the text is as said, any token that says it once edited,
// its text in the translation) with no warning on that subtitle.
{
  const rounds = Number(process.argv[2] || 5000)
  let seed = Number(process.argv[3] || 20260101)
  const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32
  const pick = (a) => a[Math.floor(rnd() * a.length)]
  const vocab = ['bad', 'ass', 'a', 'the', 'cat', 'Damn', 'damn,', 'ok', 'כלב', 'והכלב', 'הכלב', 'שלום', 'לא', 'ולא', 'x', 'classic', '🤬']
  const ops = { hand: 0, respell: 0, split: 0, splitT: 0, merge: 0, retime: 0, bleep: 0, del: 0, edit: 0, tr: 0, undo: 0, refused: 0, warned: 0 }
  let leaks = 0
  const edits = [
    (t) => t.replace(/\S+/, (w) => w + 's'), (t) => 'so ' + t, (t) => t + ' extra', (t) => t.replace(/\S+/, 'fine'),
    (t) => t.replace(/ /, '  '), (t) => t.replace(/\S+/, (w) => 'ה' + w), (t) => t.replace(/\S+/, (w) => w.split('').join('​')),
    (t) => t.replace(/\S+/, (w) => w + 'ְ'), (t) => t.split(' ').reverse().join(' '), (t) => t.toUpperCase(), (t) => t.split(' ').slice(1).join(' ') || 'zz',
  ]
  const check = (cues, words, b, log, round) => {
    for (const x of cues) {
      const wi = cueWi(x)
      const mine = wi.filter((k) => b.has(k))
      const orph0 = orphansOf(cues, b).filter((k) => words[k].start >= x.start - 0.01 && words[k].start < x.end + 0.01)
      if (!mine.length && !orph0.length) continue
      const orph = orphansOf(cues, b)
      const m = maskCue(x, words, b, orph), warn = maskWarning(x, words, b, orph)
      const tk = toks(x.text), mt = toks(m)
      const exact = tk.length === wi.length && wi.every((k, p) => normWord(tk[p]) === normWord(words[k].text) || (normWord(tk[p]) === '' && tk[p] === words[k].text.trim()))
      const bad = []
      // a bleeped word with no subtitle that starts inside this one: its text must not show
      if (!warn && orph0.length) { const keys = new Set(orph0.map((k) => normWord(words[k].text)).filter(Boolean)); mt.forEach((t) => { if (keys.has(normWord(t))) bad.push('orphan ' + t) }) }
      // a word respelled and moved: the respelled token shows only with a warning
      if (!warn && x._re && b.has(x._rek) && cueWi(x).includes(x._rek) && mt.includes(x._re)) bad.push('respelled ' + x._re)
      if (exact) wi.forEach((k, p) => { if (b.has(k) && !mt[p].includes('****')) bad.push(`place ${p}`) })
      else if (!warn) {
        const keys = new Set(mine.map((k) => normWord(words[k].text)).filter(Boolean))
        if (mine.length) mt.forEach((t, p) => { if (keys.has(normWord(t))) bad.push(`token ${t}`) })
        // the token aligned to a bleeped word (by text, in order) is hidden
        const al = alignCue(tk, wi, words)
        wi.forEach((k, q) => { if (b.has(k) && al.word[q] >= 0 && !mt[al.word[q]].includes('****')) bad.push(`aligned ${q}`) })
      }
      // the translation: shown only when it was made from this masked text, or nothing is masked
      const shown = shownText(x, m, 'both')
      if (x.tr && shown.includes(x.tr) && m !== (x.trm ?? x.text)) bad.push('translation')
      if (x.tr && x.trm === undefined && m !== x.text && shown.includes('\n')) bad.push('translation of the raw text')
      if (bad.length) { leaks++; if (leaks <= 3) console.log('LEAK', bad, { round, cue: x, masked: m, warn, words: words.map((w) => w.text.trim()), b: [...b], log }) }
      if (warn) ops.warned++
    }
  }
  for (let round = 0; round < rounds && leaks < 5; round++) {
    const n = 3 + Math.floor(rnd() * 12)
    let t = 0.5
    const words = []
    for (let i = 0; i < n; i++) { t += rnd() < 0.2 ? 0 : rnd() < 0.5 ? 0.005 + rnd() * 0.02 : 0.05 + rnd() * 0.6; words.push({ text: ' ' + pick(vocab), start: +t.toFixed(3), end: +(t + 0.15).toFixed(3) }) }
    let cues = wordsToCues(words, rnd() < 0.5 ? 0 : 2 + Math.floor(rnd() * 3))
    let b = new Set()
    const stack = []
    const log = []
    const dur = t + 2
    const push = () => stack.push([cues, b])
    for (let step = 0; step < 14; step++) {
      const sorted = [...cues].sort((a, c) => a.start - c.start)
      if (!sorted.length) break
      const c = pick(sorted), ci = sorted.indexOf(c), r = rnd()
      if (r < 0.2) {
        const tk = [...c.text.matchAll(/\S+/g)]
        if (tk.length < 2) continue
        const at = rnd() < 0.7 ? tk[1 + Math.floor(rnd() * (tk.length - 1))].index : Math.floor(rnd() * c.text.length)
        const p = splitCue(c, at, words, b, orphansOf(cues, b))
        log.push(['split', c.id, at, !!p])
        if (!p) { ops.refused++; continue }
        push(); ops.split++; cues = cues.flatMap((x) => (x.id === c.id ? p : [x]))
      } else if (r < 0.32) {
        const tm = c.start + rnd() * (c.end - c.start)
        const p = splitCueAtTime(c, tm, words, b, orphansOf(cues, b))
        log.push(['splitT', c.id, tm, !!p])
        if (!p) { ops.refused++; continue }
        push(); ops.splitT++; cues = cues.flatMap((x) => (x.id === c.id ? p : [x]))
      } else if (r < 0.44) {
        const nx = sorted[ci + 1]
        if (!nx) continue
        log.push(['merge', c.id, nx.id])
        push(); ops.merge++; cues = cues.filter((x) => x.id !== nx.id).map((x) => (x.id === c.id ? mergeCues(c, nx) : x))
      } else if (r < 0.54) {
        const [lo, hi] = cueRoom(cues, c, dur), st = rnd() < 0.5, at = (st ? c.start : c.end) + (rnd() - 0.5) * 2
        log.push(['retime', c.id, st, at])
        push(); ops.retime++; cues = cues.map((x) => (x.id === c.id ? retimeCue(x, st, at, lo, hi) : x))
      } else if (r < 0.6) {
        log.push(['del', c.id]); push(); ops.del++; cues = cues.filter((x) => x.id !== c.id)
      } else if (r < 0.74) {
        const held = cueWi(c)
        if (!held.length) continue
        const k = pick(held)
        log.push(['bleep', c.id, k]); push(); ops.bleep++
        b = new Set(b); if (b.has(k)) b.delete(k); else b.add(k)
      } else if (r < 0.8) {
        // a subtitle made by hand over a bleeped word that has none, typed as said or not; sometimes it is given the free words
        const orph = orphansOf(cues, b)
        if (!orph.length) continue
        const k = pick(orph), start = words[k].start - 0.3, end = words[k].end + 0.5
        const free = unownedIn(cues, words, start, end)
        const text = free.length && rnd() < 0.7 ? free.map((j) => words[j].text.trim()).join(' ') : pick(edits)(words[k].text.trim() + ' ' + pick(vocab))
        log.push(['hand', k, text]); push(); ops.hand++
        cues = [...cues, { id: 90000 + round * 100 + step, start, end, text, wi: rnd() < 0.5 ? free : [] }]
      } else if (r < 0.86) {
        // a bleeped word respelled, in place or with the text moved along
        const bk = cueWi(c).filter((k) => b.has(k))
        const tk = toks(c.text)
        if (!bk.length || tk.length !== cueWi(c).length || tk.length < 2 || !cueWi(c).every((k, i) => normWord(tk[i]) === normWord(words[k].text) || (normWord(tk[i]) === '' && tk[i] === words[k].text.trim()))) continue
        const kk = pick(bk), p = cueWi(c).indexOf(kk), moved = rnd() < 0.6
        const re = tk[p].replace(/[^\p{L}\p{N}]+$/u, '') + 'y'
        const nt = tk.map((x, i) => (i === p ? re : x))
        const text = (moved ? [...nt.slice(1), 'okay'] : nt).join(' ')
        log.push(['respell', c.id, text, moved]); push(); ops.respell++
        cues = cues.map((x) => (x.id === c.id ? { ...x, text, _re: re, _rek: kk } : x))
      } else if (r < 0.9) {
        const text = pick(edits)(c.text)
        log.push(['edit', c.id, text]); push(); ops.edit++; cues = cues.map((x) => (x.id === c.id ? { ...x, text, _re: undefined } : x))
      } else if (r < 0.95) {
        // translated from the masked text (as the tab does), or an old translation of the raw text
        const fromMasked = rnd() < 0.6, src = fromMasked ? maskCue(c, words, b, orphansOf(cues, b)) : c.text
        log.push(['tr', c.id, fromMasked]); push(); ops.tr++
        cues = cues.map((x) => (x.id === c.id ? (fromMasked ? { ...x, tr: '«T» ' + src, trm: src } : { ...x, tr: '«T» ' + src, trm: undefined }) : x))
      } else if (stack.length) {
        log.push(['undo']); ops.undo++; ;[cues, b] = stack.pop()
      }
      check(cues, words, b, log, round)
    }
  }
  console.log('sequences:', JSON.stringify(ops), 'leaks', leaks)
  if (leaks) fail(leaks + ' leaks')
  if (ops.split + ops.splitT < rounds || ops.merge < rounds / 4 || ops.edit < rounds / 4 || ops.hand < 100 || ops.respell < 100 || ops.warned < 10) fail('the random test did too little: ' + JSON.stringify(ops))
}
console.log('PASS')
