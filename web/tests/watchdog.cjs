// The subtitles job's stall judgement: a busy worker (creating the model's session blocks it and the sound's ticks) is no stall at the
// sound's limit; a stalled download is not hidden by the sound's ticks; a hung sound is found while the model speaks up. Pure.
// Run: node tests/watchdog.cjs
const fs = require('fs')
const path = require('path')
const ts = require('typescript')
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/watchdog.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', code)(mod, mod.exports)
const { stalled } = mod.exports
const fail = (m) => { console.error('FAIL', m); process.exit(1) }
const S = 1000, A = 90 * S, LOAD = 150 * S, DL = 60 * S
const is = (name, got, want) => { if (!!got !== want) fail(`${name}: ${JSON.stringify(got)}`) }
// Both quiet at 100 s while the model's session is created (load phase): not a stall (the audio limit is 90 s), until the load limit.
is('busy worker at 100 s', stalled('load', true, 100 * S, 100 * S, A, LOAD), false)
is('busy worker at 151 s', stalled('load', true, 151 * S, 151 * S, A, LOAD), true)
// The model spoke 2 s ago but the sound has been quiet for 100 s: the sound is hung.
is('hung sound, model alive', stalled('load', true, 100 * S, 2 * S, A, LOAD), true)
// The sound ticks, the download is quiet for 70 s: stalled download.
is('stalled download, sound alive', stalled('download', true, 1 * S, 70 * S, A, DL), true)
is('download 30 s quiet', stalled('download', true, 1 * S, 30 * S, A, DL), false)
// The sound is done: only the model counts.
is('sound done', stalled('load', false, 500 * S, 10 * S, A, LOAD), false)
// Only the sound is running (its own phase).
is('audio phase quiet 91 s', stalled('audio', true, 91 * S, 0, A, A), true)
is('audio phase quiet 30 s', stalled('audio', true, 30 * S, 0, A, A), false)
// Recognition: the sound's quiet is not judged there.
is('recognize', stalled('recognize', true, 500 * S, 10 * S, A, 120 * S), false)
console.log('watchdog ok')
