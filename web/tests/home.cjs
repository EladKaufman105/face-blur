// The landing page (/home/): it loads whole (every picture, no failed request), its parts work
// (the tour of the tools, the before / after slider, the two trailers, Hebrew / English kept after
// a reload), its links lead to the editor, and nothing is wider than a phone.
// Run: node tests/home.cjs <base url of the built site> <screenshot dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , base, out] = process.argv
const url = new URL('home/', base).href
const fail = (m) => { console.error('FAIL', m); process.exit(1) }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  try {
    for (const [name, opts] of [['desktop', { viewport: { width: 1440, height: 900 } }], ['phone', { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true }]]) {
      const ctx = await b.newContext({ ...opts, locale: 'he-IL' })
      const p = await ctx.newPage()
      const errors = []
      p.on('pageerror', (e) => errors.push('pageerror ' + e.message))
      p.on('console', (m) => { if (m.type() === 'error') errors.push('console ' + m.text()) })
      p.on('response', (r) => { if (r.status() >= 400) errors.push(r.status() + ' ' + r.url()) })
      await p.goto(url, { waitUntil: 'networkidle' })

      if (!(await p.textContent('h1')).includes('עורך וידאו שלם')) fail('the Hebrew headline')
      if ((await p.getAttribute('html', 'dir')) !== 'rtl') fail('Hebrew is right to left')

      // Down the page (the pictures load lazily and parts appear as they come into view).
      await p.evaluate(async () => { for (let y = 0; y < document.body.scrollHeight; y += 500) { scrollTo(0, y); await new Promise((r) => setTimeout(r, 120)) } })
      await p.waitForLoadState('networkidle')
      await p.waitForTimeout(1500)
      const broken = await p.evaluate(() => [...document.images].filter((i) => !i.complete || i.naturalWidth === 0).map((i) => i.getAttribute('src')))
      if (broken.length) fail(`${name}: pictures that didn't load: ${broken.join(', ')}`)
      const hidden = await p.evaluate(() => [...document.querySelectorAll('.reveal')].filter((e) => +getComputedStyle(e).opacity < 0.9).length)
      if (hidden) fail(`${name}: ${hidden} parts still hidden after scrolling past them`)
      const wide = await p.evaluate(() => document.documentElement.scrollWidth - innerWidth)
      if (wide > 0) fail(`${name}: the page is ${wide}px wider than the screen`)

      // The tour: a tool picked shows its screen.
      await p.locator('.tour-list [role=tab]').nth(2).click()
      await p.waitForTimeout(500)
      if ((await p.getAttribute('.shots img.on', 'data-name')) !== 'web-subs') fail(`${name}: the subtitles tab shows another screen`)

      // The before / after slider follows its control.
      await p.evaluate(() => { const r = document.querySelector('#compare input'); r.value = '20'; r.dispatchEvent(new Event('input', { bubbles: true })) })
      if ((await p.evaluate(() => document.getElementById('compare').style.getPropertyValue('--pos'))) !== '20%') fail(`${name}: the slider`)

      // The trailers: the app's one loads when picked.
      await p.click('.seg [data-tr=android]')
      const src = await p.getAttribute('#trailer source', 'src')
      if (!src.endsWith('trailer-android.mp4')) fail(`${name}: the app's trailer isn't picked (${src})`)
      const meta = await p.evaluate(() => new Promise((res) => {
        const v = document.getElementById('trailer')
        if (v.readyState >= 1) return res(v.duration)
        v.addEventListener('loadedmetadata', () => res(v.duration), { once: true }); v.addEventListener('error', () => res(-1), { once: true })
        v.preload = 'metadata'; v.load(); setTimeout(() => res(-2), 15000)
      }))
      if (!(meta > 50)) fail(`${name}: the app's trailer didn't load (${meta})`)

      // The links: the editor and the Android release.
      const editor = await p.evaluate(() => new URL(document.querySelector('.hero .btn.primary').getAttribute('href'), location.href).href)
      if (editor !== new URL('./', base).href) fail(`${name}: the editor link goes to ${editor}`)
      const r = await p.request.get(editor); if (!r.ok() || !(await r.text()).includes('id="app"')) fail(`${name}: the editor page`)
      if (!(await p.getAttribute('.hero .btn.tonal', 'href')).endsWith('/face-blur/releases/latest')) fail(`${name}: the Android link`)

      await p.evaluate(() => scrollTo(0, 0)); await p.waitForTimeout(300)
      await p.screenshot({ path: `${out}/${name}.png` })

      // English, kept after a reload (the same setting as the editor's).
      if (name === 'desktop') {
        await p.click('#lang')
        if (!(await p.textContent('h1')).includes('A complete video editor')) fail('the English headline')
        await p.reload({ waitUntil: 'networkidle' })
        if ((await p.getAttribute('html', 'dir')) !== 'ltr' || !(await p.textContent('h1')).includes('A complete video editor')) fail('English after a reload')
        if ((await p.evaluate(() => localStorage.getItem('onit.lang'))) !== 'en') fail("the editor's language setting")
        await p.screenshot({ path: `${out}/desktop-en.png` })
      }
      if (errors.length) fail(`${name}: ${errors.join(' | ')}`)
      console.log(name, 'ok')
      await ctx.close()
    }
  } finally {
    await b.close()
  }
  console.log('PASS home')
})().catch((e) => fail(e.message))
