// A software WebGPU adapter (the browser's WebGPU on the CPU: SwiftShader, as headless Chromium has it) is never used for face
// detection, and the CPU workers run instead on a device with cores for them (it used to start on that adapter, find the CPU
// faster after a few frames and go on with one detector on one core: "waves 1/16"). The stats report says why and lists the device: adapter, shader-f16, threads,
// isolation, the detector pool.
// The adapter is made by Chromium's flags (--enable-unsafe-webgpu, SwiftShader); the page is NOT told ?backend=wasm.
// Run: node tests/softgpu.cjs <url, without ?backend> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
// Without a software adapter the test proves nothing: on a developer's machine it skips, in CI (SwiftShader is expected) it fails.
const skip = (why) => { if (process.env.CI) throw new Error(`no software WebGPU adapter in CI: ${why}`); console.log(`SKIP: ${why}`) }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--enable-unsafe-webgpu', '--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist', '--enable-features=Vulkan'] })
  const logs = []
  try {
    const ctx = await b.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US', permissions: ['clipboard-read', 'clipboard-write'] })
    const p = await ctx.newPage()
    p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
    // ?workers=2: two detectors whatever the cores (the cores rule is in devdiag.cjs: with four or more the workers take over by themselves).
    await p.goto(url + (url.includes('?') ? '&' : '?') + 'workers=2')
    // The browser really offers a software adapter here (else this test proves nothing).
    const adapter = await p.evaluate(async () => { const a = await navigator.gpu?.requestAdapter(); return a ? { fallback: a.isFallbackAdapter === true || a.info?.isFallbackAdapter === true, arch: a.info?.architecture ?? '' } : null })
    if (!adapter) return skip('this Chromium has no WebGPU adapter at all')
    if (!adapter.fallback && !/swiftshader|llvmpipe|software/i.test(adapter.arch)) return skip(`the adapter is a real one (${adapter.arch})`)
    const cores = await p.evaluate(() => navigator.hardwareConcurrency)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.analyze .stats-toggle', { timeout: 60000 })
    await p.click('.stats-toggle')
    await p.waitForSelector('[data-testid=stats-panel] .stat[data-cell=detect]', { state: 'visible', timeout: 60000 })
    // The detector started on the CPU at once, with the workers where there are cores for them (no GPU run, no check, no wait).
    await p.waitForFunction(() => document.querySelector('.stat[data-cell=detect] .stat-value')?.textContent.trim().match(/^\d/), null, { timeout: 120000 })
    await p.click('.stats-more-btn')
    await p.waitForSelector('.stat[data-cell=backend]', { state: 'visible', timeout: 3000 })
    const backend = await p.$eval('.stat[data-cell=backend] .stat-value', (e) => e.textContent.trim())
    if (!/^CPU/.test(backend) || /fp16/.test(backend)) throw new Error(`the backend reads "${backend}"`)
    const workers = await p.$eval('.stat[data-cell=workers] .stat-value', (e) => e.textContent.trim())
    await p.click('.stats-copy')
    await p.waitForFunction(() => document.querySelector('.stats-copy svg path')?.getAttribute('d')?.startsWith('M9 16.17'), null, { timeout: 8000 })
    const text = await p.evaluate(() => navigator.clipboard.readText())
    fs.writeFileSync(`${out}/report.txt`, text)
    const pool = /detector_pool: (\d+) running, (\d+) wanted/.exec(text)
    if (!pool) throw new Error('no detector_pool line:\n' + text)
    if (Number(pool[1]) !== 2) throw new Error(`${pool[1]} detectors, 2 asked for (${cores} cores)`)
    for (const k of ['software adapter, not used', 'ep_nodes: no WebGPU session', 'wasm: 1 thread per detector, simd yes', 'cross_origin_isolated: ', 'no gpu: software WebGPU adapter'])
      if (!text.includes(k)) throw new Error(`the report lacks "${k}":\n${text}`)
    await p.waitForSelector('.editor, .card.error', { timeout: 300000 })
    const error = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (error) throw new Error('analysis: ' + error)
    const errs = logs.filter((l) => l.startsWith('[pageerror]'))
    if (errs.length) throw new Error(errs.join('\n'))
    console.log(`PASS: backend "${backend}", workers "${workers}", pool ${pool[1]} of ${pool[2]} wanted on ${cores} cores`)
  } catch (e) {
    console.log(logs.filter((l) => /ONIT/.test(l)).slice(-20).join('\n'))
    throw e
  } finally {
    await b.close()
  }
})().catch((e) => { console.error(e); process.exit(1) })
