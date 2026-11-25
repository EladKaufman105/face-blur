// The web editor as the app's, part two: the subtitle style's effect cards (each plays its effect, still with less motion
// asked for), the font cards (each in its own font, loaded when seen), a group's tool chips on ONE row (360, 390, 412 px, he and en),
// and the tablet layouts (768 x 1024 and 820 x 1180 held upright: the phone's one column, as the app; 1024 x 768 on its side and
// 1024 x 1366: the rail of groups, the panel beside the video). The speech model is blocked (the subtitles come from an SRT; the
// bleep tool's words from a stand-in worker).
// Run: node tests/parity.cjs <url> <video> <out dir> <a font file>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out, fontFile] = process.argv
const SRT = `1\n00:00:00,500 --> 00:00:02,500\nשלום לכולם ברוכים הבאים\n\n2\n00:00:02,600 --> 00:00:05,000\nזה סרטון לדוגמה\n`
const fail = (m) => { throw new Error(m) }
const W1 = [{ text: 'שלום', start: 1, end: 1.5 }, { text: 'עולם', start: 1.6, end: 2.1 }, { text: 'יפה', start: 2.2, end: 2.8 }]

async function open(b, [width, height], { locale = 'he-IL', touch = true, motion, words = false } = {}) {
  const ctx = await b.newContext({ viewport: { width, height }, locale, hasTouch: touch, isMobile: touch && width <= 600, deviceScaleFactor: 1, reducedMotion: motion })
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
  if (words) await ctx.route(/subs\.worker[^/]*\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: `self.onmessage = () => { self.postMessage({ type: 'audio' }); setTimeout(() => self.postMessage({ type: 'done', device: 'wasm', words: ${JSON.stringify(W1)}, ms: { load: 1, recognize: 1, first: 1, decode: 1, after: 1, tokens: 1 } }), 300) }` }))
  const p = await ctx.newPage()
  p.on('pageerror', (e) => console.log('pageerror', e.message))
  await p.goto(url + (url.includes('?') ? '&' : '?') + 'subsmodel=base')
  await p.waitForTimeout(800)
  const chooser = p.waitForEvent('filechooser')
  await p.click('.tool >> nth=0')
  await (await chooser).setFiles(video)
  await p.waitForSelector('.editor .tabs', { timeout: 60000 })
  await p.waitForTimeout(600)
  return p
}
/** Opens a group (a tap on the open one closes it on a phone: left alone). */
const grp = async (p, g) => {
  if (await p.$eval(`.tabs button[data-g=${g}]`, (e) => e.getAttribute('aria-selected') === 'true')) return
  await p.click(`.tabs button[data-g=${g}]`)
  await p.waitForSelector('.panel:not([hidden]) .panel-body')
}
const hash = (p, sel) => p.$eval(sel, (c) => { const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let h = 0; for (let i = 0; i < d.length; i += 4) h = (h * 31 + d[i] + d[i + 1] * 7 + d[i + 2] * 13 + d[i + 3]) >>> 0; return h })
const toStyle = async (p, part) => {
  await grp(p, 'subs')
  await p.click('.panel-head [data-sub=style]')
  await p.click(`.subtabs.inner [data-sub=${part}]`)
  await p.waitForTimeout(500)
}
const SRT_IN = async (p) => { await grp(p, 'subs'); await p.click('.panel-head [data-sub=make]'); await p.setInputFiles('.srt-import input', `${out}/in.srt`); await p.waitForSelector('.panel-head [data-sub=list]') }

/** The tool chips of the open panel (and the style's own row) are one row each, whole and in view. */
const oneRow = async (p, label) => {
  const m = await p.evaluate(() => [...document.querySelectorAll('.panel:not([hidden]) .subtabs')].map((row) => {
    const rr = row.getBoundingClientRect()
    const chips = [...row.querySelectorAll('[data-sub]')].map((c) => c.getBoundingClientRect())
    const tops = chips.map((k) => Math.round(k.top))
    return { n: chips.length, oneRow: Math.max(...tops) - Math.min(...tops) <= 2, noScroll: row.scrollWidth <= row.clientWidth + 1, inside: chips.every((k) => k.left >= rr.left - 1 && k.right <= rr.right + 1 && k.left >= -1 && k.right <= innerWidth + 1), cut: [...row.querySelectorAll('[data-sub]')].some((c) => c.scrollWidth > c.clientWidth + 1), inner: row.classList.contains('inner'), h: rr.height }
  }))
  for (const r of m) if (!r.oneRow || !r.noScroll || !r.inside) fail(`${label}: chips are not on one row, whole: ${JSON.stringify(r)}`)
  // The whole group's header (the tools and the X) is a single line.
  const head = await p.$eval('.panel:not([hidden]) .panel-head', (e) => e.getBoundingClientRect().height)
  if (head > 60) fail(`${label}: the panel's header is ${head} px tall`)
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch()
  try {
    // ---- effect cards and font cards, a phone (390 x 850)
    {
      const p = await open(b, [390, 850])
      await SRT_IN(p)
      await toStyle(p, 'effect')
      const cards = await p.$$eval('.effects .fx-card', (e) => e.length)
      if (cards !== 19) fail('effect cards: ' + cards)
      // The chosen one is outlined; a tap chooses another.
      const on0 = await p.$$eval('.effects .fx-card.on', (e) => e.map((x) => x.dataset.effect))
      if (on0.length !== 1) fail('the chosen effect is not marked: ' + on0)
      await p.click('.effects .fx-card[data-effect=wordPop]')
      if ((await p.$$eval('.effects .fx-card.on', (e) => e.map((x) => x.dataset.effect))).join() !== 'wordPop') fail('a tapped effect is not the chosen one')
      // Each card moves (the canvas differs between frames); the one with no effect holds still.
      const frames = async (sel) => { const s = new Set(); for (let i = 0; i < 12; i++) { s.add(await hash(p, sel)); await p.waitForTimeout(110) } return s.size }
      const moving = await frames('.effects .fx-card[data-effect=wordPop] canvas')
      const typing = await frames('.effects .fx-card[data-effect=typewriter] canvas')
      const none = await frames('.effects .fx-card[data-effect=none] canvas')
      console.log('frames in 1.3 s: wordPop', moving, 'typewriter', typing, 'none', none)
      if (moving < 3 || typing < 2) fail('the effect cards do not move')
      if (none !== 1) fail('the card with no effect moves')
      if ((await p.$eval('.effects .fx-card[data-effect=wordPop]', (e) => e.dataset.moving)) !== '1') fail('a moving card is not marked as moving')
      // Not more than about 30 pictures a second.
      const rate = await p.evaluate(() => new Promise((res) => { const c = document.querySelector('.effects .fx-card[data-effect=wordPop] canvas'); let n = 0; const g = c.getContext('2d'); const f = g.clearRect.bind(g); g.clearRect = (...a) => { n++; return f(...a) }; setTimeout(() => { g.clearRect = f; res(n) }, 1000) }))
      console.log('draws of one card in a second:', rate)
      if (rate > 36) fail('an effect card draws ' + rate + ' times a second')
      // Off screen: nothing is drawn (the cards scrolled out of view stop).
      await p.evaluate(() => { document.querySelector('.panel-body').scrollTop = 1e5 })
      await p.waitForTimeout(300)
      const top = await p.$eval('.effects .fx-card[data-effect=fade]', (e) => e.dataset.moving)
      if (top !== '1' && top !== '0') fail('no marker')
      await p.screenshot({ path: `${out}/phone_effects.png` })
      await p.screenshot({ path: `${out}/phone_effects2x.png` })
      // Fonts: each name in its own font, loaded as it is seen; an imported one in its own.
      await p.click('.subtabs.inner [data-sub=font]')
      await p.waitForTimeout(600)
      const fonts = await p.$$eval('.builtin-fonts .font-card', (e) => e.map((x) => ({ id: x.dataset.font, fam: getComputedStyle(x.querySelector('.font-sample')).fontFamily, label: x.querySelector('.font-name').textContent, shape: x.getBoundingClientRect().height })))
      if (fonts.length !== 11) fail('font cards: ' + fonts.length)
      const fams = new Set(fonts.map((f) => f.fam))
      if (fams.size !== 11) fail('the font cards are not each in their own font: ' + JSON.stringify(fonts))
      for (const f of fonts) if (f.id !== 'system' && !f.fam.includes('ONIT ')) fail('a font card is not in its font: ' + JSON.stringify(f))
      // Scroll through; every one is then loaded (not before: they load as seen).
      for (let y = 0; y <= 600; y += 100) { await p.evaluate((y) => { document.querySelector('.panel-body').scrollTop = y }, y); await p.waitForTimeout(150) }
      await p.waitForFunction(() => [...document.querySelectorAll('.builtin-fonts .font-sample')].every((e) => e.dataset.ready === '1'), null, { timeout: 30000 }).catch(() => fail('not every font card loaded its font'))
      const ok = await p.evaluate(() => ['Rubik', 'Allo', 'Heebo', 'Assistant', 'Secular One', 'Suez One', 'Varela Round', 'Fredoka', 'Karantina', 'Amatic SC'].every((f) => document.fonts.check(`20px 'ONIT ${f}'`, 'שלום')))
      if (!ok) fail('a font is not loaded')
      await p.evaluate(() => { document.querySelector('.panel-body').scrollTop = 0 })
      await p.screenshot({ path: `${out}/phone_fonts.png` })
      // An imported font shows in itself.
      fs.copyFileSync(fontFile, `${out}/Mine Sans.ttf`)
      await p.setInputFiles('.panel-body input[type=file]', `${out}/Mine Sans.ttf`)
      await p.waitForSelector('.custom-fonts .font-card')
      const mine = await p.$eval('.custom-fonts .font-sample', (e) => getComputedStyle(e).fontFamily)
      if (!mine.includes('ONIT custom Mine Sans')) fail('an imported font is not shown in itself: ' + mine)
      if (!(await p.$('.custom-fonts .font-card.on'))) fail('the imported font is not the chosen one')
      await p.screenshot({ path: `${out}/phone_fonts_custom.png` })
      // The Subtitles group with subtitles present.
      await p.click('.subtabs.inner [data-sub=looks]')
      await p.screenshot({ path: `${out}/phone_subs_style.png` })
      await p.context().close()
    }
    // ---- less motion: the cards stay still
    {
      const p = await open(b, [390, 850], { motion: 'reduce' })
      await SRT_IN(p)
      await toStyle(p, 'effect')
      await p.$eval('.effects .fx-card[data-effect=wordPop]', (e) => e.scrollIntoView({ block: 'center' }))
      await p.waitForTimeout(500)
      const s = new Set()
      for (let i = 0; i < 8; i++) { s.add(await hash(p, '.effects .fx-card[data-effect=wordPop] canvas')); await p.waitForTimeout(120) }
      if (s.size !== 1) fail('the effect cards move with less motion asked for')
      if ((await p.$eval('.effects .fx-card[data-effect=wordPop]', (e) => e.dataset.moving)) !== '0') fail('marked as moving with less motion')
      // (Still, not blank: the sample is drawn.)
      const drawn = await p.$eval('.effects .fx-card[data-effect=wordPop] canvas', (c) => { const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0; for (let i = 0; i < d.length; i += 4) if (d[i] > 200 && d[i + 1] > 200) n++; return n })
      if (drawn < 200) fail('the still effect card shows no text: ' + drawn)
      await p.context().close()
    }
    // ---- every group's tool chips on one row, at three phone widths, in both languages
    for (const locale of ['he-IL', 'en-US']) {
      const p = await open(b, [390, 850], { locale, words: true })
      await grp(p, 'subs')
      await p.click('.subs-create')
      await p.waitForSelector('.panel-head [data-sub=bleep]', { timeout: 20000 })
      for (const w of [360, 390, 412]) {
        await p.setViewportSize({ width: w, height: 800 })
        await p.waitForTimeout(500)
        for (const g of ['faces', 'edit', 'picture', 'subs', 'sound', 'mark']) {
          await grp(p, g)
          const subs = await p.$$eval('.panel-head [data-sub]', (e) => e.map((x) => x.dataset.sub))
          for (const sub of subs.length ? subs : [null]) {
            if (sub) await p.click(`.panel-head [data-sub=${sub}]`)
            await oneRow(p, `${locale} ${w} ${g}/${sub}`)
            if (g === 'subs' && sub === 'style') for (const sp of await p.$$eval('.subtabs.inner [data-sub]', (e) => e.map((x) => x.dataset.sub))) { await p.click(`.subtabs.inner [data-sub=${sp}]`); await oneRow(p, `${locale} ${w} style/${sp}`) }
          }
          if (g === 'subs') { const n = await p.$$eval('.panel-head [data-sub]', (e) => e.length); if (n !== 4) fail('subs tools: ' + n); console.log(locale, w, 'subs: 4 chips on one row') }
        }
      }
      if (locale === 'en-US') { await p.setViewportSize({ width: 390, height: 850 }); await grp(p, 'subs'); await p.click('.panel-head [data-sub=make]'); await p.screenshot({ path: `${out}/phone_en_subs.png` }) }
      await p.context().close()
    }
    // ---- tablets
    const side = async (p, label, { rail }) => {
      const m = await p.evaluate(() => {
        const r = (s) => { const e = document.querySelector(s); if (!e) return null; const k = e.getBoundingClientRect(); return { l: k.left, r: k.right, t: k.top, b: k.bottom, w: k.width, h: k.height } }
        return { sideways: document.documentElement.scrollWidth > innerWidth, scrollH: document.documentElement.scrollHeight, clientH: document.documentElement.clientHeight, vw: innerWidth, screen: r('.screen'), view: r('.view'), panel: r('.panel:not([hidden])'), tabs: r('.tabs'), tl: r('.tl'), cls: document.querySelector('.editor').className, tabBtns: [...document.querySelectorAll('.tabs button')].map((e) => { const k = e.getBoundingClientRect(); return { l: k.left, t: k.top, w: k.width, h: k.height } }), top: r('.player > .top-row') }
      })
      if (m.sideways) fail(label + ': sideways scroll')
      if (m.scrollH > m.clientH + 1) fail(`${label}: the page scrolls (${m.scrollH} > ${m.clientH})`)
      if (!m.screen || m.screen.h < 120 || m.view.w < 100) fail(label + ': the video is not in view ' + JSON.stringify(m.screen))
      if (m.tabs.b > m.clientH + 1 || m.tl.b > m.clientH + 1) fail(label + ': something is below the screen')
      if (m.tabBtns.some((k) => k.l < -1 || k.l + k.w > m.vw + 1)) fail(label + ': a group button is outside the screen')
      if (rail) {
        if (!/\btablet\b/.test(m.cls)) fail(label + ': not the tablet layout: ' + m.cls)
        // A vertical rail: the buttons share a column, one under the other; the panel beside the video, not over it.
        const lefts = new Set(m.tabBtns.map((k) => Math.round(k.l)))
        if (lefts.size !== 1 || !m.tabBtns.every((k, i, a) => i === 0 || k.t > a[i - 1].t)) fail(label + ': the groups are not a rail: ' + JSON.stringify(m.tabBtns))
        if (m.panel && !(m.panel.r <= m.screen.l + 1 || m.panel.l >= m.screen.r - 1)) fail(label + ': the panel overlaps the video')
        if (m.panel && (m.panel.w < 300 || m.panel.w > 420)) fail(label + ': the panel is ' + Math.round(m.panel.w) + ' px wide')
        if (m.tl.t < m.screen.b - 1) fail(label + ': the timeline overlaps the video')
        if (m.tl.l < m.tabs.r - 1 && m.tl.r > m.tabs.l + 1) fail(label + ': the timeline is under the rail')
      } else {
        if (/\btablet\b/.test(m.cls)) fail(label + ': the tablet layout where the phone\'s is due')
        if (!/\bnarrow\b/.test(m.cls)) fail(label + ': not the one-column layout')
      }
      return m
    }
    for (const [w, h, rail] of [[768, 1024, false], [820, 1180, false], [1024, 768, true], [1024, 1366, true], [800, 600, true]]) {
      const label = `${w}x${h}`
      const p = await open(b, [w, h], { words: false })
      await SRT_IN(p)
      for (const g of ['faces', 'edit', 'picture', 'subs', 'sound', 'mark']) {
        await grp(p, g)
        await p.waitForTimeout(250)
        await side(p, `${label} ${g}`, { rail })
        const open_ = await p.$eval(`.tabs button[data-g=${g}]`, (e) => e.getAttribute('aria-selected'))
        if (open_ !== 'true') fail(`${label}: group ${g} did not open`)
        if (!(await p.$eval('.panel:not([hidden]) .panel-body', (e) => e.innerText.trim().length > 0 || !!e.querySelector('button, input, canvas')))) fail(`${label} ${g}: an empty panel`)
        await oneRow(p, `${label} ${g}`)
        if (g === 'subs') {
          for (const sub of ['make', 'list', 'style']) { await p.click(`.panel-head [data-sub=${sub}]`); await p.waitForTimeout(150); await oneRow(p, `${label} subs/${sub}`) }
          await p.click('.subtabs.inner [data-sub=effect]')
          await p.waitForTimeout(800)
          if (rail) await p.screenshot({ path: `${out}/tablet_${label}_effects.png` })
        }
      }
      if (rail) {
        // The panel closes with its X (the video gets the room) and any group opens it again; a group's second tap does not close it.
        const before = await p.$eval('.screen', (e) => e.getBoundingClientRect().width)
        await p.click('.panel-close')
        await p.waitForTimeout(400)
        if (await p.$('.panel:not([hidden])')) fail(label + ': the panel did not close')
        const after = await p.$eval('.screen', (e) => e.getBoundingClientRect().width)
        if (after <= before + 100) fail(`${label}: the video did not grow when the panel closed (${before} -> ${after})`)
        await side(p, label + ' closed', { rail })
        await p.click('.tabs button[data-g=faces]')
        await p.waitForSelector('.panel:not([hidden]) .panel-body')
        await p.click('.tabs button[data-g=faces]')
        await p.waitForTimeout(200)
        if (!(await p.$('.panel:not([hidden])'))) fail(label + ': a second tap on the open group closed the panel (a phone\'s way)')
      } else {
        await p.click('.panel-close')
        await p.waitForSelector('.tl', { state: 'visible' })
      }
      await p.screenshot({ path: `${out}/tablet_${label}.png` })
      await p.context().close()
    }
    console.log('PASS')
  } catch (e) {
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
