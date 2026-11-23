// A video shared to the installed app (as sharing from the gallery to the Android app: the Web
// Share Target, received by the service worker), and one opened with it (a file handler), each
// opens on the ready screen.
// Run: node tests/share.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 393, height: 851 }, isMobile: true, hasTouch: true, locale: 'he-IL' })
  const logs = []
  try {
    // Shared: the system posts the video to ./share, as Android's share sheet does.
    const p = await ctx.newPage()
    p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
    await p.goto(url)
    await p.evaluate(() => navigator.serviceWorker.ready.then(() => true))
    const manifest = await p.evaluate(async () => (await fetch('manifest.webmanifest')).json())
    if (manifest.share_target?.params?.files?.[0]?.name !== 'video' || !manifest.file_handlers?.length) throw new Error('manifest')
    await p.evaluate(() => {
      const f = document.createElement('form')
      f.method = 'post'; f.enctype = 'multipart/form-data'; f.action = 'share'; f.id = 'share-form'
      f.innerHTML = '<input type="file" name="video" id="share-in">'
      document.body.append(f)
    })
    await p.setInputFiles('#share-in', video)
    await Promise.all([p.waitForNavigation(), p.evaluate(() => document.getElementById('share-form').submit())])
    await p.waitForSelector('.ready', { timeout: 30000 })
    await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    const shared = await p.evaluate(() => ({ name: document.querySelector('.ready-name').textContent, url: location.search, chips: [...document.querySelectorAll('.info-chip')].map((e) => e.textContent) }))
    const left = await p.evaluate(async () => !!(await (await caches.open('onit-share')).match('shared-video')))
    await p.screenshot({ path: `${out}/1_shared.png` })
    console.log('shared:', JSON.stringify(shared), '· kept after:', left)
    if (!video.endsWith(shared.name) || shared.url !== '' || shared.chips.length !== 3 || left) throw new Error('share')

    // Opened with the app (a file handler on a computer).
    const q = await ctx.newPage()
    // The browser's own launchQueue (Chromium has one) stood in for, to hand the page a file.
    await q.addInitScript(() => { Object.defineProperty(window, 'launchQueue', { configurable: true, value: { setConsumer: (f) => { window.__launch = f } } }) })
    await q.goto(url)
    await q.waitForFunction(() => !!window.__launch)
    await q.evaluate(() => { const i = document.createElement('input'); i.type = 'file'; i.id = 'launch-in'; document.body.append(i) })
    await q.setInputFiles('#launch-in', video)
    await q.evaluate(() => window.__launch({ files: [{ getFile: async () => document.getElementById('launch-in').files[0] }] }))
    await q.waitForSelector('.ready', { timeout: 30000 })
    const opened = await q.textContent('.ready-name')
    console.log('opened with the app:', opened)
    if (!video.endsWith(opened)) throw new Error('file handler')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
