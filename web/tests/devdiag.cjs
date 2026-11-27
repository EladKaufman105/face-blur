// The device report's pure parts and the rules that put the cores to use: how many CPU detectors run side by side
// (cores, memory, phones; "waves 1/16" was one detector on 16 cores), a software WebGPU adapter is never used, the
// runtime's node placement log read as counts, the pace not counting a detection that compiled shaders, the report's lines.
// Pure: no browser.
// Run: node tests/devdiag.cjs
const path = require('path')
const { execFileSync } = require('child_process')
const script = `
const D = await import('./src/lib/devicediag.ts')
const P = await import('./src/lib/pace.ts')
const fail = (m) => { console.log(JSON.stringify({ fail: m })); process.exit(0) }

// ---- detectors side by side
const sz = (c, m, mob) => D.poolSize(c, m, mob)
if (sz(1, 8) !== 1 || sz(0, 8) !== 1 || sz(NaN, 8) !== 1) fail('one core, one detector')
if (sz(2, 8) !== 2 || sz(2, undefined) !== 2 || sz(3, 8) !== 2) fail('two even on two cores')
if (sz(4, 8) !== 2 || sz(6, 8) !== 4 || sz(8, 8) !== 6 || sz(12, 8) !== 6 || sz(16, 8) !== 6 || sz(64, 8) !== 6) fail('cores minus two, to 6 with the memory: ' + [4, 6, 8, 12, 16, 64].map((c) => sz(c, 8)))
if (sz(16, undefined) !== 2 || sz(16, 2) !== 2 || sz(16, 1) !== 2) fail('memory not given or small: two')
if (sz(16, 4) !== 3 || sz(8, 4) !== 3 || sz(4, 4) !== 2) fail('4 GB: three at most')
if (sz(16, 8, true) !== 4 || sz(8, 8, true) !== 4 || sz(6, 8, true) !== 4 || sz(4, 8, true) !== 2) fail('a phone: four at most')
// never fewer than before this rule (two cores or more: floor(cores / 2), at most 4 with 8 GB, else 2)
for (const cores of [2, 3, 4, 5, 6, 8, 10, 12, 16, 24]) for (const mem of [undefined, 1, 2, 4, 8]) {
  const before = cores > 1 ? Math.min(mem !== undefined && mem >= 8 ? 4 : 2, Math.max(2, Math.floor(cores / 2))) : 1
  if (sz(cores, mem) < before) fail('fewer detectors than before at ' + cores + ' cores, memory ' + mem)
}

// ---- the workers that take over from a GPU: with 4 cores or more
if (D.fallbackPoolSize(2, 2) !== 1 || D.fallbackPoolSize(3, 2) !== 1 || D.fallbackPoolSize(4, 2) !== 2 || D.fallbackPoolSize(16, 6) !== 6 || D.fallbackPoolSize(1, 1) !== 1) fail('the fallback pool')

// ---- a software adapter
if (!D.isSoftwareAdapter({ isFallbackAdapter: true }) || !D.isSoftwareAdapter({ info: { isFallbackAdapter: true } })) fail('a fallback adapter')
if (!D.isSoftwareAdapter({ info: { architecture: 'swiftshader' } }) || !D.isSoftwareAdapter({ info: { description: 'llvmpipe (LLVM 15)' } })) fail('a named software renderer')
if (D.isSoftwareAdapter({ isFallbackAdapter: false, info: { isFallbackAdapter: false } }) || D.isSoftwareAdapter({}) || D.isSoftwareAdapter(null)) fail('a real adapter is not software')
if (D.adapterText({ info: { vendor: 'intel', architecture: 'gen-12lp', device: '', description: 'Intel(R) UHD Graphics' } }) !== 'intel gen-12lp Intel(R) UHD Graphics' || D.adapterText({}) !== '') fail('the adapter text')

// ---- the runtime's node placement lines
const lines = [
  '2026-11-27 14:51:05.314523 [V:onnxruntime:, session_state.cc:1584 VerifyEachNodeIsAssignedToAnEp]  Node(s) placed on [CPUExecutionProvider]. Number of nodes: 18',
  '2026-11-27 14:51:05.319843 [V:onnxruntime:, session_state.cc:1584 VerifyEachNodeIsAssignedToAnEp]  Node(s) placed on [WebGpuExecutionProvider]. Number of nodes: 123',
  'something else',
]
const n = D.placementCounts(lines)
if (n.gpu !== 123 || n.cpu !== 18) fail('placement counts ' + JSON.stringify(n))
if (D.placementCounts([]).gpu !== null || D.placementCounts(['x']).cpu !== null) fail('no placement lines: null')
// The capture takes the runtime's log out of the console and passes the rest on.
{
  const seen = [], passed = []
  const keep = console.error
  console.error = (...a) => passed.push(a.join(' '))
  const r = await D.capturePlacement(seen, async () => {
    console.error(lines[0]); console.error('[V:onnxruntime:, other.cc:1] chatter'); console.error('our own message')
    return 7
  })
  const restored = console.error
  console.error = keep
  if (r !== 7 || seen.length !== 1 || passed.join('|') !== 'our own message') fail('capturePlacement: ' + JSON.stringify({ r, seen, passed }))
  if (restored === keep) fail('capturePlacement did not hook')
}

// ---- the pace doesn't count a detection that compiled shaders (a size step, the first frames)
{
  const pc = new P.Pacer(3)
  const got = []
  const add = (t) => { const w = pc.add(t); if (w) got.push(w) }
  for (let k = 0; k < 3; k++) add({ ms: 300, rfMs: 280, fineMs: 270 })
  // a size step: its first detection takes 1.4 s more, and doesn't count
  add({ ms: 1700, rfMs: 1680, fineMs: 1670, cold: true })
  for (let k = 0; k < 4; k++) add({ ms: 300, rfMs: 280, fineMs: 270 })
  if (got.length !== 1 || !(Math.abs(got[0].recentMs - 300) < 1e-9)) fail('a cold detection counted: ' + JSON.stringify(got))
  // a cold detection never makes the next window slower, nor ends a warm-up early
  const p2 = new P.Pacer(3)
  for (let k = 0; k < 10; k++) if (p2.add({ ms: 900, rfMs: 800, fineMs: 700, cold: true })) fail('cold detections made a window')
  for (let k = 0; k < 3; k++) if (p2.add({ ms: 300, rfMs: 280, fineMs: 270 })) fail('the warm-up did not hold')
}

// ---- the report's lines: the GPU's find, threads, isolation, the detectors
{
  const gpu = { adapter: 'intel gen-12lp', f16: true, software: false, nodesGpu: 123, nodesCpu: 18, model: 'fp16', firstRunMs: 2310.4 }
  const diag = { gpu, threads: 1, simd: true, isolated: true, pool: 2, poolWanted: 2, cores: 2, memoryGB: 4 }
  const out = D.diagLines(diag).join('\\n')
  for (const k of ['webgpu: intel gen-12lp', 'shader_f16: yes (model fp16)', 'ep_nodes: 123 on the GPU, 18 on the CPU', 'gpu_first_run_ms: 2310', 'wasm: 1 thread per detector, simd yes', 'cross_origin_isolated: yes', 'detector_pool: 2 running, 2 wanted (2 cores, memory >= 4 GB)'])
    if (!out.includes(k)) fail('the report lacks "' + k + '": ' + out)
  const pending = D.diagLines({ ...diag, gpu: { ...gpu, nodesGpu: null, nodesCpu: null } }).join('\\n')
  if (!pending.includes('ep_nodes: not measured yet')) fail('pending nodes: ' + pending)
  const none = D.diagLines({ ...diag, gpu: null, isolated: false, pool: 1, memoryGB: null }).join('\\n')
  if (!none.includes('webgpu: not available') || !none.includes('cross_origin_isolated: no') || !none.includes('memory not given')) fail('the report without a GPU: ' + none)
  const soft = D.diagLines({ ...diag, gpu: { ...gpu, software: true, nodesGpu: null, nodesCpu: null, firstRunMs: null } }).join('\\n')
  if (!soft.includes('software adapter, not used') || !soft.includes('ep_nodes: no WebGPU session') || !soft.includes('gpu_first_run_ms: -')) fail('the report with a software adapter: ' + soft)
}
console.log(JSON.stringify({ ok: true }))
`
const r = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..'), stdio: ['ignore', 'pipe', 'inherit'] }).toString().trim().split('\n').pop())
if (!r.ok) { console.error('FAIL', r.fail); process.exit(1) }
console.log('devdiag: ok')
