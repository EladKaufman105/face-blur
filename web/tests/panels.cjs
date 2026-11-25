// The editor's panels on a phone (390 x 844), as the app's: each group opens one compact panel
// (a bar of its tools as chips, one tool's controls), of a bounded height, so the video stays in
// view; nothing scrolls sideways; every tool of every group can be opened and has something in
// it; the targets are 44 px; "more options" is never an empty fold (on a wide screen it holds
// something or is not there). The speech model is blocked (the subtitles come from an SRT).
// Run: node tests/panels.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const SRT = `1\n00:00:00,500 --> 00:00:02,500\nשלום לכולם ברוכים הבאים\n\n2\n00:00:02,600 --> 00:00:05,000\nזה סרטון לדוגמה\n\n3\n00:00:05,100 --> 00:00:08,000\nעם כתוביות בעברית\n`
const fail = (m) => { throw new Error(m) }

async function open(b, viewport, mobile, locale = 'he-IL', tool) {
  const ctx = await b.newContext({ viewport, locale, hasTouch: mobile, isMobile: mobile, deviceScaleFactor: mobile ? 2 : 1 })
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
  const p = await ctx.newPage()
  await p.goto(url)
  await p.waitForTimeout(800)
  if (tool !== undefined) {
    // A tool from the home screen: an editor with no face detection (nothing blurred, no look to set).
    const chooser = p.waitForEvent('filechooser')
    await p.click(`.tool >> nth=${tool}`)
    await (await chooser).setFiles(video)
  } else {
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready')
    await p.click('.blur-faces')
  }
  await p.waitForSelector('.editor .tabs', { timeout: 120000 })
  await p.waitForTimeout(800)
  return p
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch()
  try {
    // ---- the phone: 360 x 740, 390 x 844 and 412 x 915, in Hebrew and in English
    const phone = async (viewport, locale) => {
      const he = locale === 'he-IL'
      const p = await open(b, viewport, true, locale)
      console.log('==', viewport.width, 'x', viewport.height, locale)
      const check = async (label) => {
        const m = await p.evaluate(() => {
          const r = (s) => document.querySelector(s)?.getBoundingClientRect()
          const panel = r('.panel'), body = r('.panel-body'), screen = r('.screen'), tabs = r('.tabs')
          const bodyEl = document.querySelector('.panel-body')
          const chipRows = [...document.querySelectorAll('.panel .subtabs')].map((row) => {
            const rr = row.getBoundingClientRect()
            return { hidden: row.scrollWidth > row.clientWidth + 1, cut: [...row.querySelectorAll('[data-sub]')].filter((c) => { const k = c.getBoundingClientRect(); const inside = Math.max(0, Math.min(k.right, rr.right) - Math.max(k.left, rr.left)); return inside < k.width * 0.95 || k.left < 0 || k.right > innerWidth }).map((c) => c.textContent) }
          })
          const lastBottom = Math.max(...[...document.querySelectorAll('.player > *')].filter((e) => !e.matches('.screen') && e.getBoundingClientRect().height > 0).map((e) => e.getBoundingClientRect().bottom))
          const topRow = (() => { const kids = [...document.querySelectorAll('.player > .row > *, .player > .row .export-bar > *')].map((e) => e.getBoundingClientRect()).filter((k) => k.width > 0); const back = document.querySelector('.player > .row > .btn')?.getBoundingClientRect(); return { out: kids.some((k) => k.left < -0.5 || k.right > document.documentElement.clientWidth + 0.5), oneLine: !!back && back.height < 56 } })()
        const small = [...document.querySelectorAll('.panel button, .panel label.btn, .tabs button')].filter((e) => { const k = e.getBoundingClientRect(); return k.width > 0 && k.height > 0 && k.height < 43.5 && !e.closest('.preset') && !e.matches('.face, .swatch, .speaker-dot, .time-btn') }).map((e) => e.className + ':' + Math.round(e.getBoundingClientRect().height))
          return {
            sideways: document.documentElement.scrollWidth > innerWidth, vh: innerHeight,
            panelH: panel?.height, tabsTop: tabs?.top, screenTop: screen?.top, screenBottom: screen?.bottom, screenH: screen?.height,
            bodyEmpty: !bodyEl || bodyEl.children.length === 0 || !(bodyEl.textContent.trim() || bodyEl.querySelector('input, canvas, img, button')),
            moreOptions: [...document.querySelectorAll('.subs-more')].map((d) => d.querySelector('.subs-more-body')?.children.length ?? 0),
            topRow, chipRows, playerBottom: lastBottom, panelTop: panel?.top, tablist: [...document.querySelectorAll('.panel [role=tablist]')].every((t) => t.getAttribute('aria-label')), tabs: [...document.querySelectorAll('.panel [role=tab]')].every((t) => t.getAttribute('aria-controls') === 'panel-body' && (t.tabIndex === 0) === (t.getAttribute('aria-selected') === 'true')), tabpanel: document.querySelector('.panel-body')?.getAttribute('role'), small, panelOver: bodyEl ? bodyEl.scrollWidth > bodyEl.clientWidth + 1 && !bodyEl.querySelector('.chips, .faces, .swatches, .preset-row, .scroll-row') : false,
          }
        })
        console.log(label, JSON.stringify({ sideways: m.sideways, panelH: Math.round(m.panelH), screenH: Math.round(m.screenH), rows: m.chipRows.length }))
        if (m.chipRows.some((r) => r.hidden || r.cut.length)) fail(`${label}: tool chips hidden or cut: ${JSON.stringify(m.chipRows)}`)
        if (m.topRow.out || !m.topRow.oneLine) fail(`${label}: the top row overflows or wraps: ${JSON.stringify(m.topRow)}`)
      if (m.playerBottom > m.panelTop + 1) fail(`${label}: the panel covers the timeline: ${m.playerBottom} > ${m.panelTop}`)
        if (m.tabpanel !== 'tabpanel' || !m.tablist || !m.tabs) fail(`${label}: tabs are not marked up as tabs: ${JSON.stringify([m.tabpanel, m.tablist, m.tabs])}`)
        if (m.sideways) fail(label + ': the page scrolls sideways')
        if (m.panelH > m.vh * 0.45) fail(`${label}: the panel is ${m.panelH} px of ${m.vh}`)
        if (m.screenTop < 0 || m.screenBottom > m.tabsTop || m.screenH < 90) fail(`${label}: the video is not in view: ${JSON.stringify(m)}`)
        if (m.bodyEmpty) fail(label + ': the panel is empty')
        if (m.moreOptions.some((n) => n === 0)) fail(label + ': "more options" is empty')
        if (m.panelOver) fail(label + ': the panel body scrolls sideways')
        if (m.small.length) fail(`${label}: targets under 44 px: ${m.small.slice(0, 6)}`)
        await p.screenshot({ path: `${out}/${label.replace(/[^\w]+/g, '_')}.png` })
      }
      // A tool opens at its top, whatever the one before was scrolled to.
      const where = () => p.evaluate(() => [...document.querySelectorAll('.tabs [aria-selected=true], .panel [role=tab][aria-selected=true]')].map((e) => e.dataset.g || e.dataset.sub).join('/'))
      const go = async (sel, label) => {
        await p.evaluate(() => { const e = document.querySelector('.panel-body'); if (e) e.scrollTop = 1e5 })
        const before = await where()
        await p.click(sel)
        await p.waitForTimeout(300)
        const top = await p.evaluate(() => document.querySelector('.panel-body').scrollTop)
        if ((await where()) !== before && top !== 0) fail(`${label}: opened scrolled (${top} px)`)
      }
      const sweep = async () => {
        for (const g of ['faces', 'edit', 'picture', 'sound', 'mark', 'subs']) {
          await go(`.tabs button[data-g=${g}]`, g)
          const subs = await p.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
          if (g === 'mark' && subs.length) fail('the watermark has tools of its own')
          if (g !== 'mark' && subs.length < 2) fail(g + ': no tools to pick from')
          await check(g)
          for (const sub of subs) {
            await go(`.panel-head [data-sub=${sub}]`, `${g}/${sub}`)
            await check(`${g}/${sub}`)
            if (g === 'subs' && sub === 'style') {
              for (const sp of await p.$$eval('.subtabs.inner [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
                await go(`.subtabs.inner [data-sub=${sp}]`, `subs/style/${sp}`)
                await check(`subs/style/${sp}`)
              }
            }
          }
        }
      }
      const withBlurSelected = async () => {
        await p.click('.tabs button[data-g=faces]')
        await p.click('.tl-face >> nth=0')
        await p.waitForSelector('.ctx-bar', { timeout: 5000 })
        await check('faces with a blur selected')
        const room = await p.evaluate(() => ({ bottom: document.querySelector('.tabs').getBoundingClientRect().bottom, vh: document.documentElement.clientHeight }))
        if (room.bottom < room.vh - 8) fail('the editor does not fit the screen: ' + JSON.stringify(room))
        await p.click('.ctx-bar button:last-child')
      }
      await sweep()
      // The arrow keys move between tools, and do not also step the video.
      await p.click('.tabs button[data-g=faces]')
      await p.focus('.panel-head [role=tab][aria-selected=true]')
      const t0 = await p.evaluate(() => document.querySelector('video').currentTime)
      await p.keyboard.press('ArrowRight')
      await p.waitForTimeout(300)
      const arrow = await p.evaluate(() => ({ t: document.querySelector('video').currentTime, on: document.querySelector('.panel-head [role=tab][aria-selected=true]')?.dataset.sub }))
      if (arrow.t !== t0) fail('an arrow key on a tool also stepped the video: ' + JSON.stringify([t0, arrow]))
      if (arrow.on === 'list') fail('the arrow key did not move to another tool')
      // with subtitles (an SRT): the list shows, and the tools in "create and translate" are there
      await p.click('.tabs button[data-g=subs]')
      await p.click('.panel-head [data-sub=make]')
      await p.setInputFiles('.srt-import input', `${out}/in.srt`)
      await p.waitForSelector('.panel-head [data-sub=list][aria-selected=true]', { timeout: 5000 }).catch(() => fail('the list does not open after an SRT is imported'))
      for (const sub of ['make', 'list', 'style']) {
        await p.click(`.panel-head [data-sub=${sub}]`)
        await p.waitForTimeout(300)
        await check(`subs-with-cues/${sub}`)
      }
      if (await p.$('.subs-more')) fail('"more options" on a phone')
      // the list's tools (find and replace, SRT, how they are cut) are in the list; translation, speakers and delete in create
      await p.click('.panel-head [data-sub=list]')
      if (he) for (const sel of ['.replace-all, .panel-body .btn:has-text("חפש והחלף")', '.panel-body .btn:has-text("הורד SRT")']) if (!(await p.$(sel))) fail('missing in the list: ' + sel)
      await p.click('.panel-head [data-sub=make]')
      if (!(await p.$('.panel-body .translate-go, .panel-body .translate-unsupported'))) fail('no translation in create and translate')
      if (he && !(await p.$('.panel-body .btn:has-text("מחק כתוביות")'))) fail('no "delete subtitles" in create and translate')


      // a blur selected (its bar shows under the timeline): nothing overlaps; the other two phones, one page and one detection
      await withBlurSelected()
      for (const vp of [{ width: 360, height: 740 }, { width: 412, height: 915 }]) {
        await p.setViewportSize(vp)
        await p.waitForTimeout(500)
        console.log('==', vp.width, 'x', vp.height, locale)
        await sweep()
        await withBlurSelected()
      }
      await p.context().close()
    }
    for (const locale of ['he-IL', 'en-US']) await phone({ width: 390, height: 844 }, locale)

    // ---- an editor opened with no face detection: no empty "look" tool, the rest as usual
    for (const locale of ['he-IL', 'en-US']) {
      const q = await open(b, { width: 360, height: 740 }, true, locale, 0)
      const subs = await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
      console.log('no detection', locale, subs.join())
      await q.click('.tabs button[data-g=faces]')
      const faceSubs = await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
      if (faceSubs.includes('look')) fail('the look tool is offered with no faces to look at')
      for (const sub of faceSubs) {
        await q.click(`.panel-head [data-sub=${sub}]`)
        const filled = await q.evaluate(() => (document.querySelector('.panel-body')?.textContent.trim().length ?? 0) > 0 || !!document.querySelector('.panel-body button'))
        if (!filled) fail('empty panel: faces/' + sub)
      }
      await q.context().close()
    }

    // ---- a wide screen: everything as before, and "more options" holds something
    const w = await open(b, { width: 1366, height: 860 }, false)
    await w.click('.tabs button[data-g=subs]')
    const wide = async (label) => {
      const n = await w.evaluate(() => [...document.querySelectorAll('.subs-more')].map((d) => d.querySelector('.subs-more-body')?.textContent.trim().length ?? 0))
      console.log('wide', label, JSON.stringify(n))
      if (n.some((x) => x === 0)) fail('wide ' + label + ': "more options" is empty')
      if (await w.$('.panel')) fail('the phone panel on a wide screen')
    }
    await wide('no subtitles')
    await w.setInputFiles('.srt-import input', `${out}/in.srt`).catch(async () => { await w.click('.subs-more > summary'); await w.setInputFiles('.srt-import input', `${out}/in.srt`) })
    await w.waitForSelector('.cue-list .clip')
    await w.click('.subs-more > summary')
    await w.waitForSelector('.subs-more-body .translate-go, .subs-more-body .translate-unsupported', { state: 'visible' })
    await wide('with subtitles')
    await w.screenshot({ path: `${out}/wide_subs.png` })
    console.log('PASS')
  } catch (e) {
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
