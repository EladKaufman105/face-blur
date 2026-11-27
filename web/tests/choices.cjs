// Remembered choices and "Run detection again", the pure parts (no browser): the registry
// (shared/prefs/choices.json), what may be remembered, the list for the settings; and the merge
// of a second detection into the blurs there (Add never fewer, Replace keeps manual and edited
// blurs, the flags never weaker with Add, one id space, frames moved when the runs' frames differ).
// The app's JVM tests (RerunMergeTest, RememberedChoicesTest) check the same rules.
// Run: node tests/choices.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
const C = await import('./src/lib/choicestore.ts')
const G = await import('./src/gen/choices.ts')
const R = await import('./src/lib/rerun.ts')
const fail = (m) => { console.log(JSON.stringify({ fail: m })); process.exit(0) }

// ---- the registry
if (!G.CHOICES.length) fail('no keys')
const rr = C.keyOf(R.RERUN_KEY)
if (!rr || rr.values.map((v) => v.id).join() !== 'add,replace') fail('rerun.merge key')
for (const k of G.CHOICES) {
  if (!k.he || !k.en || k.values.length < 2) fail('a key without labels or values: ' + k.id)
  for (const v of k.values) if (!v.he || !v.en) fail('a value without labels: ' + k.id + '.' + v.id)
}
// ---- what may be remembered
if (JSON.stringify(C.parseRemembered(null)) !== '{}') fail('null')
if (JSON.stringify(C.parseRemembered('not json')) !== '{}') fail('garbage')
if (JSON.stringify(C.parseRemembered('[1]')) !== '{}') fail('array')
if (JSON.stringify(C.parseRemembered('{"rerun.merge":"nope","other":"add"}')) !== '{}') fail('unknown value or key kept')
let m = C.parseRemembered('{"rerun.merge":"replace","x":"y"}')
if (JSON.stringify(m) !== '{"rerun.merge":"replace"}') fail('valid kept: ' + JSON.stringify(m))
if (C.withChoice({}, 'rerun.merge', 'bogus') && Object.keys(C.withChoice({}, 'rerun.merge', 'bogus')).length) fail('bogus value remembered')
if (Object.keys(C.withChoice({}, 'nope', 'add')).length) fail('unknown key remembered')
m = C.withChoice({}, 'rerun.merge', 'add')
if (m['rerun.merge'] !== 'add') fail('with')
const l = C.listRemembered(m)
if (l.length !== 1 || l[0].value.id !== 'add' || l[0].key.id !== 'rerun.merge') fail('list')
if (Object.keys(C.withoutChoice(m, 'rerun.merge')).length) fail('without')
if (m['rerun.merge'] !== 'add') fail('withoutChoice changed its input')
if (C.listRemembered({}).length) fail('empty list')

// ---- the merge
let seed = 7
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296)
const track = (id, first, n, cx, cy, extra = {}) => {
  const boxes = new Float32Array(n * 4)
  for (let k = 0; k < n; k++) boxes.set([cx, cy, 0.1, 0.12], k * 4)
  return { id, first, boxes, best: first, ...extra }
}
const same = (a, b) => a.first === b.first && a.boxes.length === b.boxes.length && a.boxes.every((v, i) => v === b.boxes[i])
const existing = [track(0, 0, 50, 0.2, 0.3), track(1, 10, 30, 0.7, 0.3, { manual: true }), track(2, 0, 40, 0.5, 0.7, { edited: true }), track(3, 5, 20, 0.85, 0.85, { scale: 1.4 }), track(4, 60, 20, 0.4, 0.4)]
const found = [track(0, 0, 50, 0.21, 0.31), track(1, 0, 60, 0.9, 0.1), track(2, 12, 25, 0.5, 0.71), track(3, 70, 10, 0.1, 0.9)]
const off = new Set([4])
// Add: every old track as it was (the same objects), new faces appended, a face that is one there not added.
let a = R.mergeRerun(existing, off, found, 'add')
if (a.removed.length) fail('add removed')
existing.forEach((e, i) => { if (a.tracks[i] !== e) fail('add changed track ' + e.id) })
if (a.added.length !== 2) fail('add added ' + a.added.length)
if (a.tracks.length !== existing.length + 2) fail('add count')
if (new Set(a.tracks.map((t) => t.id)).size !== a.tracks.length) fail('add ids clash')
if (a.added.some((t) => existing.some((e) => e.id === t.id))) fail('add reused an id')
if (a.off.size !== 1 || !a.off.has(4)) fail('add off')
if (a.from.get(a.added[0].id) !== 1 || a.from.get(a.added[1].id) !== 3) fail('add origin: ' + [...a.from])
// Replace: the automatic ones nobody touched go (0), manual / edited / scaled / turned-off ones stay; a face that is one kept is not added.
let r = R.mergeRerun(existing, off, found, 'replace')
if (r.removed.map((t) => t.id).join() !== '0') fail('replace removed ' + r.removed.map((t) => t.id))
for (const id of [1, 2, 3, 4]) if (!r.tracks.some((t) => t.id === id && t === existing.find((e) => e.id === id))) fail('replace lost ' + id)
// found 0 matches the removed track 0 (it is added back as the new run's), 1 and 3 are new, 2 is the kept edited one.
if (r.added.length !== 3) fail('replace added ' + r.added.length)
if (!r.off.has(4)) fail('replace dropped a kept off')
// A turned-off blur that is removed leaves the off set.
r = R.mergeRerun([track(0, 0, 10, 0.5, 0.5)], new Set(), [], 'replace')
if (r.tracks.length !== 0 || r.removed.length !== 1) fail('replace with no faces found')
// Nothing there: add and replace are the same.
a = R.mergeRerun([], new Set(), found, 'add'); r = R.mergeRerun([], new Set(), found, 'replace')
if (a.tracks.length !== 4 || r.tracks.length !== 4) fail('nothing there')
// Random: Add never has fewer, and keeps every old track whole; Replace keeps every manual / edited one.
for (let i = 0; i < 400; i++) {
  const mk = (n, base) => Array.from({ length: n }, (_, k) => track(base + k, Math.floor(rnd() * 40), 5 + Math.floor(rnd() * 40), rnd(), rnd(), rnd() < 0.3 ? { manual: true } : rnd() < 0.2 ? { edited: true } : {}))
  const old = mk(Math.floor(rnd() * 6), 0), fnd = mk(Math.floor(rnd() * 6), 0)
  const offs = new Set(old.filter(() => rnd() < 0.2).map((t) => t.id))
  const ad = R.mergeRerun(old, offs, fnd, 'add')
  if (ad.tracks.length < old.length) fail('add fewer ' + i)
  old.forEach((o, k) => { if (ad.tracks[k] !== o) fail('add changed ' + i) })
  const rp = R.mergeRerun(old, offs, fnd, 'replace')
  for (const o of old) if (R.isEdited(o, offs) && !rp.tracks.includes(o)) fail('replace lost an edited track ' + i)
  if (new Set(rp.tracks.map((t) => t.id)).size !== rp.tracks.length) fail('replace ids clash ' + i)
  if (rp.tracks.some((t) => !t.manual && !t.edited && t.scale === undefined && !offs.has(t.id) && old.includes(t))) fail('replace kept an untouched auto track ' + i)
}

// ---- the flags
const A = (o) => ({ times: new Float64Array(0), width: 1, height: 1, fps: 30, tracks: [], ...o })
let f = R.mergeFlags(A({ fast: true, lowConfidence: 3, sensitivity: 0.5 }), A({ fast: false, reduced: false, lowConfidence: 1, sensitivity: 0.2 }), 'add')
if (!f.fast || f.lowConfidence !== 3 || f.sensitivity !== 0.5) fail('add flags are the stricter: ' + JSON.stringify(f))
f = R.mergeFlags(A({}), A({ selfie: true, reduced: true }), 'add')
if (!f.selfie || !f.reduced) fail('add takes the new run\\'s notices')
f = R.mergeFlags(A({ fast: true, lowConfidence: 3 }), A({ fast: false, selfie: true, lowConfidence: 0 }), 'replace')
if (f.fast || !f.selfie || f.lowConfidence) fail('replace takes the new run\\'s flags: ' + JSON.stringify(f))

// ---- frames
const t1 = Float64Array.from({ length: 100 }, (_, i) => i / 30)
if (!R.sameFrames(t1, Float64Array.from(t1))) fail('same frames')
if (R.sameFrames(t1, t1.subarray(0, 99))) fail('different length is the same frames')
const t2 = Float64Array.from({ length: 50 }, (_, i) => i / 15)
const rs = R.resample(track(0, 20, 31, 0.3, 0.3), t1, t2)
// frames 20..50 at 30 fps = 0.667 s .. 1.667 s = frames 10..25 at 15 fps.
if (rs.first !== 10 || rs.boxes.length / 4 !== 16 || rs.boxes[0] !== Float32Array.of(0.3)[0]) fail('resample ' + rs.first + ' ' + rs.boxes.length / 4)
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('choices: ok')
