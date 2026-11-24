// Cross-origin isolation (WebAssembly threads): GitHub Pages sends no COOP / COEP headers, so the
// service worker adds them. The first visit reloads once (never in a loop); after that the page, its
// workers and a threaded WebAssembly runtime are isolated; the speech model's download (cross-origin) still works.
// Run: node tests/coi.cjs <url without ?coi=0> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {})
  const ctx = await b.newContext({ viewport: { width: 1000, height: 700 } })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  let navs = 0
  p.on('framenavigated', (f) => { if (f === p.mainFrame()) navs++ })
  const check = (ok, what) => { console.log(ok ? 'ok  ' : 'FAIL', what); if (!ok) { fs.writeFileSync(`${out}/coi.log`, logs.join('\n')); process.exitCode = 1 } }
  try {
    // First visit: no worker yet. The page reloads once and is isolated.
    await p.goto(url)
    await p.waitForSelector('.hero input[type=file]', { state: 'attached', timeout: 30000 })
    const info = () => p.evaluate(async () => ({
      iso: self.crossOriginIsolated, sab: typeof SharedArrayBuffer !== 'undefined', ctl: !!navigator.serviceWorker.controller,
      guard: sessionStorage.getItem('onit.isolation.reload') !== null,
    }))
    let s = await info()
    check(s.iso && s.sab && s.ctl, 'first visit: isolated, SharedArrayBuffer, controlled ' + JSON.stringify(s))
    check(navs === 2, `first visit reloaded once (navigations: ${navs})`)
    await p.waitForTimeout(2500)
    check(navs === 2, `no reload loop (navigations after 2.5 s: ${navs})`)
    // A module worker of the app (served through the service worker) starts under the page's COEP.
    const worker = await p.evaluate(async () => {
      const src = await (await fetch('./')).text()
      const js = src.match(/assets\/index-[\w-]+\.js/)[0]
      const code = await (await fetch(js)).text()
      const name = code.match(/assets\/(thumbs\.worker-[\w-]+\.js)/)?.[1] ?? code.match(/(thumbs\.worker-[\w-]+\.js)/)?.[1]
      const w = new Worker(new URL('assets/' + name, document.baseURI), { type: 'module' })
      return await new Promise((r) => { w.onerror = (e) => r('error: ' + e.message); setTimeout(() => r('started'), 1500) })
    })
    check(worker === 'started', 'a module worker starts: ' + worker)
    // Threads: a shared WebAssembly memory (what a threaded runtime needs) exists in a worker made from a blob (it inherits the isolation).
    const inWorker = await p.evaluate(() => new Promise((r) => {
      const w = new Worker(URL.createObjectURL(new Blob(['postMessage([self.crossOriginIsolated, new WebAssembly.Memory({initial:1,maximum:2,shared:true}).buffer instanceof SharedArrayBuffer])'])))
      w.onmessage = (e) => r(e.data)
      setTimeout(() => r('timeout'), 3000)
    }))
    check(Array.isArray(inWorker) && inWorker[0] && inWorker[1], 'a worker is isolated and has shared memory ' + JSON.stringify(inWorker))
    // A cross-origin CORS download works under the page's policy (the models come from Hugging Face's CDN).
    const cors = await p.evaluate(async () => { try { const r = await fetch('https://raw.githubusercontent.com/EladKaufman105/face-blur/develop/README.md', { cache: 'no-store' }); return r.ok ? 'ok' : 'status ' + r.status } catch (e) { return 'blocked: ' + e.message } })
    check(cors === 'ok', 'cross-origin fetch: ' + cors)
    // A second visit in the same browser: isolated from the first response, no reload.
    const p2 = await ctx.newPage()
    let navs2 = 0
    p2.on('framenavigated', (f) => { if (f === p2.mainFrame()) navs2++ })
    await p2.goto(url)
    await p2.waitForSelector('.hero input[type=file]', { state: 'attached', timeout: 30000 })
    await p2.waitForTimeout(1500)
    const iso2 = await p2.evaluate(() => self.crossOriginIsolated)
    check(iso2 && navs2 === 1, `second visit: isolated at once, no reload (navigations: ${navs2})`)
    // Without the worker (?coi=0) nothing changes: not isolated, no reload.
    const p3 = await (await b.newContext()).newPage()
    await p3.goto(url + (url.includes('?') ? '&' : '?') + 'coi=0')
    await p3.waitForSelector('.hero input[type=file]', { state: 'attached', timeout: 30000 })
    await p3.waitForTimeout(1500)
    check(!(await p3.evaluate(() => self.crossOriginIsolated)), '?coi=0: not isolated (the old behavior)')
    await p.screenshot({ path: `${out}/coi.png` })
  } catch (e) {
    console.error('FAIL', e)
    fs.writeFileSync(`${out}/coi.log`, logs.join('\n'))
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
