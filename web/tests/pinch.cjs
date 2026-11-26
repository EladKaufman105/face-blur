// Two fingers on the video resize the selected blur (as the app's pinch), in one undo step; the size slider in its bar follows.
// Run: node tests/pinch.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const PHONE = { viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const p = await (await b.newContext({ ...PHONE, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const scale = () => p.evaluate(() => Number(document.querySelector('.ctx-size input')?.value ?? NaN))
  try {
    await p.goto(url)
    await p.waitForTimeout(1000)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=1')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(1500)
    await p.tap('.tabs button[data-g=faces]')
    await p.evaluate(() => { const v = document.querySelector('video'); v.pause(); v.currentTime = 5 })
    await p.waitForTimeout(400)
    await p.tap('.panel-head [data-sub=manual]')
    await p.tap('.add-blur')
    const box = await p.locator('.screen canvas >> nth=0').boundingBox()
    await p.touchscreen.tap(box.x + box.width * 0.62, box.y + box.height * 0.45)
    await p.waitForFunction(() => Number(document.querySelector('.tl-face')?.dataset.b) > 6 && !document.querySelector('.ctx-bar .progress'), null, { timeout: 300000 })
    // The panel is in the timeline's place on a phone: close it, and select the blur from the timeline.
    if (await p.$('.panel:not([hidden])')) await p.tap('.panel-close')
    await p.waitForSelector('.tl-face', { timeout: 10000 })
    if (!(await p.$('.ctx-size input'))) await p.dispatchEvent('.tl-face', 'click')
    await p.waitForTimeout(500)
    await p.waitForSelector('.ctx-size input', { state: 'attached' })
    await p.evaluate(() => { const v = document.querySelector('video'); v.pause(); v.currentTime = 5 })
    await p.waitForTimeout(500)
    const before = await scale()
    const bx = await p.locator('.screen').boundingBox()
    const cx = bx.x + bx.width / 2, cy = bx.y + bx.height / 2
    const cdp = await p.context().newCDPSession(p)
    const pts = (d) => [{ x: cx - d, y: cy, id: 1 }, { x: cx + d, y: cy, id: 2 }]
    const touch = (type, tp) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: tp })
    await touch('touchStart', pts(30))
    for (const d of [40, 55, 70, 85]) { await touch('touchMove', pts(d)); await p.waitForTimeout(40) }
    await touch('touchEnd', [])
    await p.waitForTimeout(300)
    const after = await scale()
    console.log('scale', before, '->', after)
    if (!(after > before * 1.5)) throw new Error(`the pinch did not enlarge the blur: ${before} -> ${after}`)
    // Fingers together make it smaller again, and one undo takes back one whole pinch.
    await touch('touchStart', pts(90))
    for (const d of [70, 50, 35]) { await touch('touchMove', pts(d)); await p.waitForTimeout(40) }
    await touch('touchEnd', [])
    await p.waitForTimeout(300)
    const small = await scale()
    if (!(small < after * 0.7)) throw new Error(`the pinch did not shrink the blur: ${after} -> ${small}`)
    await p.tap('[aria-label="בטל"]')
    await p.waitForTimeout(300)
    if (Math.abs((await scale()) - after) > 0.01) throw new Error('one undo did not take back the second pinch')
    await p.tap('[aria-label="בטל"]')
    await p.waitForTimeout(300)
    if (Math.abs((await scale()) - before) > 0.01) throw new Error('one undo did not take back the first pinch')
    await p.screenshot({ path: `${out}/pinch.png` })
    console.log('PASS')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.filter((l) => !/adapters|GL Driver/.test(l)).slice(-10).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
