// The landing page (/home/): it loads whole (every picture, no failed request), its videos play
// (the editor's recording in the hero, the before / after drawn from its video, each tool's
// recording), its parts work (the tour, the slider, the trailers, Hebrew / English kept after a
// reload), its links lead to the editor, nothing is wider than a phone, and with less motion asked
// for, nothing moves.
// Run: node tests/home.cjs <base url of the built site> <screenshot dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , base, out] = process.argv
const url = new URL('home/', base).href
const fail = (m) => { console.error('FAIL', m); process.exit(1) }

// playedFor(page, selector, seconds): how much of the video plays, waiting up to 15 s for that much (a loop
// back to the start isn't counted).
const playedFor = (page, sel, want) => page.evaluate(([sel, want]) => new Promise((res) => {
  const v = document.querySelector(sel), t0 = performance.now()
  let last = v.currentTime, played = 0
  const tick = () => {
    const t = v.currentTime
    if (t > last) played += t - last
    last = t
    if (played > want || performance.now() - t0 > 15000) res({ played, paused: v.paused, readyState: v.readyState, waited: Math.round(performance.now() - t0) })
    else setTimeout(tick, 100)
  }
  tick()
}), [sel, want])

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
      // The hero's recording runs: half a second of it plays (within 15 s: a busy machine may
      // start or decode it late; a loop back to the start isn't counted).
      const hero = await playedFor(p, '.hero-browser video', 0.5)
      if (!(hero.played > 0.5)) fail(`${name}: the hero's recording doesn't play (${JSON.stringify(hero)})`)
      if ((await p.getAttribute('html', 'dir')) !== 'rtl') fail('Hebrew is right to left')

      // Down the page (the pictures load lazily and parts appear as they come into view). Each step
      // lands at once (the page scrolls smoothly, which a busy machine animates late, skipping
      // past parts) and waits for a frame drawn there: a part scrolled past between two frames is
      // never seen, and stays hidden, as it should.
      await p.evaluate(async () => {
        const frame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
        for (let y = 0; y < document.body.scrollHeight; y += 500) { scrollTo({ top: y, behavior: 'instant' }); await frame(); await new Promise((r) => setTimeout(r, 120)) }
      })
      await p.waitForLoadState('networkidle')
      await p.waitForTimeout(1500)
      // (Up to 15 s more for the last pictures and fade-ins on a busy machine; then the same checks.)
      await p.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0) && [...document.querySelectorAll('.reveal')].every((e) => +getComputedStyle(e).opacity >= 0.9), null, { timeout: 15000 }).catch(() => {})
      const broken = await p.evaluate(() => [...document.images].filter((i) => !i.complete || i.naturalWidth === 0).map((i) => i.getAttribute('src')))
      if (broken.length) fail(`${name}: pictures that didn't load: ${broken.join(', ')}`)
      const hidden = await p.evaluate(() => [...document.querySelectorAll('.reveal')].filter((e) => +getComputedStyle(e).opacity < 0.9).length)
      if (hidden) fail(`${name}: ${hidden} parts still hidden after scrolling past them`)
      const wide = await p.evaluate(() => document.documentElement.scrollWidth - innerWidth)
      if (wide > 0) fail(`${name}: the page is ${wide}px wider than the screen`)

      // The tour: a tool picked plays its recording.
      await p.evaluate(() => document.querySelector('.tour').scrollIntoView({ block: 'center' }))
      await p.locator('.tour-list [role=tab]').nth(2).click()
      // (It loads when picked: up to 15 s on a busy machine, then the same check.)
      await p.waitForFunction(() => { const v = document.querySelector('.shots video.on'); return v && v.dataset.name === 'web-subs' && !v.paused && v.currentTime > 0.3 }, null, { timeout: 15000 }).catch(() => {})
      const tour = await p.evaluate(() => { const v = document.querySelector('.shots video.on'); return [v.dataset.name, v.paused, v.currentTime] })
      if (tour[0] !== 'web-subs' || tour[1] || !(tour[2] > 0.3)) fail(`${name}: the subtitles recording (${tour})`)

      // The before / after: drawn from its video, and the slider moves the line.
      await p.evaluate(() => document.getElementById('compare').scrollIntoView({ block: 'center' }))
      await p.waitForFunction(() => document.getElementById('compare').classList.contains('live'), null, { timeout: 15000 }).catch(() => fail(`${name}: the before / after video never drew`))
      await p.evaluate(() => { const r = document.querySelector('#compare input'); r.value = '20'; r.dispatchEvent(new Event('input', { bubbles: true })) })
      if ((await p.evaluate(() => document.getElementById('compare').style.getPropertyValue('--pos'))) !== '20%') fail(`${name}: the slider`)
      // Left of the line is the original, right of it the blurred one: the halves differ there.
      const halves = await p.evaluate(() => {
        const c = document.querySelector('.cmp-canvas'), x = c.getContext('2d')
        const v = document.querySelector('.cmp-video'); v.pause()
        x.drawImage(v, 0, 0, 1280, 720, 0, 0, 1280, 720)
        const a = x.getImageData(0, 0, 1280, 720).data
        x.drawImage(v, 0, 720, 1280, 720, 0, 0, 1280, 720)
        const b = x.getImageData(0, 0, 1280, 720).data
        let diff = 0; for (let i = 0; i < a.length; i += 16) diff += Math.abs(a[i] - b[i])
        return diff / (a.length / 16)
      })
      if (!(halves > 0.5)) fail(`${name}: the before / after halves are the same (${halves})`)

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
    // Less motion: nothing plays by itself, everything is shown.
    const ctx = await b.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce', locale: 'he-IL' })
    const p = await ctx.newPage()
    await p.goto(url, { waitUntil: 'networkidle' })
    await p.waitForTimeout(1200)
    const still = await p.evaluate(() => ({ hero: document.querySelector('.hero-browser video').paused, hidden: [...document.querySelectorAll('.reveal, .w')].filter((e) => +getComputedStyle(e).opacity < 0.9).length }))
    if (!still.hero || still.hidden) fail(`less motion: ${JSON.stringify(still)}`)
    await ctx.close()
    console.log('less motion ok')
  } finally {
    await b.close()
  }
  console.log('PASS home')
})().catch((e) => fail(e.message))
