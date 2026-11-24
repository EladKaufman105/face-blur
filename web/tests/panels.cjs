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

async function open(b, viewport, mobile) {
  const ctx = await b.newContext({ viewport, locale: 'he-IL', hasTouch: mobile, isMobile: mobile, deviceScaleFactor: mobile ? 2 : 1 })
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
  const p = await ctx.newPage()
  await p.goto(url)
  await p.waitForTimeout(800)
  await p.setInputFiles('.hero input[type=file]', video)
  await p.waitForSelector('.ready')
  await p.click('.blur-faces')
  await p.waitForSelector('.editor .tabs', { timeout: 120000 })
  await p.waitForTimeout(800)
  return p
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch()
  try {
    // ---- the phone
    const p = await open(b, { width: 390, height: 844 }, true)
    const check = async (label) => {
      const m = await p.evaluate(() => {
        const r = (s) => document.querySelector(s)?.getBoundingClientRect()
        const panel = r('.panel'), body = r('.panel-body'), screen = r('.screen'), tabs = r('.tabs')
        const bodyEl = document.querySelector('.panel-body')
        const small = [...document.querySelectorAll('.panel button, .panel label.btn, .tabs button')].filter((e) => { const k = e.getBoundingClientRect(); return k.width > 0 && k.height > 0 && k.height < 43.5 && !e.closest('.preset') && !e.matches('.face, .swatch, .speaker-dot, .time-btn') }).map((e) => e.className + ':' + Math.round(e.getBoundingClientRect().height))
        return {
          sideways: document.documentElement.scrollWidth > innerWidth, vh: innerHeight,
          panelH: panel?.height, tabsTop: tabs?.top, screenTop: screen?.top, screenBottom: screen?.bottom, screenH: screen?.height,
          bodyEmpty: !bodyEl || bodyEl.children.length === 0 || !(bodyEl.textContent.trim() || bodyEl.querySelector('input, canvas, img, button')),
          moreOptions: [...document.querySelectorAll('.subs-more')].map((d) => d.querySelector('.subs-more-body')?.children.length ?? 0),
          small, panelOver: bodyEl ? bodyEl.scrollWidth > bodyEl.clientWidth + 1 && !bodyEl.querySelector('.chips, .faces, .swatches, .preset-row, .scroll-row') : false,
        }
      })
      console.log(label, JSON.stringify({ ...m, small: m.small.length }))
      if (m.sideways) fail(label + ': the page scrolls sideways')
      if (m.panelH > m.vh * 0.45) fail(`${label}: the panel is ${m.panelH} px of ${m.vh}`)
      if (m.screenTop < 0 || m.screenBottom > m.tabsTop || m.screenH < 120) fail(`${label}: the video is not in view: ${JSON.stringify(m)}`)
      if (m.bodyEmpty) fail(label + ': the panel is empty')
      if (m.moreOptions.some((n) => n === 0)) fail(label + ': "more options" is empty')
      if (m.small.length) fail(`${label}: targets under 44 px: ${m.small.slice(0, 6)}`)
      await p.screenshot({ path: `${out}/${label.replace(/[^\w]+/g, '_')}.png` })
    }
    const groups = ['faces', 'edit', 'picture', 'sound', 'mark', 'subs']
    for (const g of groups) {
      await p.click(`.tabs button[data-g=${g}]`)
      await p.waitForTimeout(300)
      const subs = await p.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
      if (g === 'mark' && subs.length) fail('the watermark has tools of its own')
      if (g !== 'mark' && subs.length < 2) fail(g + ': no tools to pick from')
      await check(g)
      for (const sub of subs) {
        await p.click(`.panel-head [data-sub=${sub}]`)
        await p.waitForTimeout(300)
        await check(`${g}/${sub}`)
        if (g === 'subs' && sub === 'style') {
          for (const sp of await p.$$eval('.subtabs.inner [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
            await p.click(`.subtabs.inner [data-sub=${sp}]`)
            await p.waitForTimeout(300)
            await check(`subs/style/${sp}`)
          }
        }
      }
    }
    // with subtitles (an SRT): the list shows, and the tools in "create and translate" are there
    await p.click('.tabs button[data-g=subs]')
    await p.click('.panel-head [data-sub=make]')
    await p.setInputFiles('.srt-import input', `${out}/in.srt`)
    await p.waitForSelector('.panel-head [data-sub=list]')
    for (const sub of ['make', 'list', 'style']) {
      await p.click(`.panel-head [data-sub=${sub}]`)
      await p.waitForTimeout(300)
      await check(`subs-with-cues/${sub}`)
    }
    if (await p.$('.subs-more')) fail('"more options" on a phone')
    // the list's tools (find and replace, SRT, how they are cut) are in the list; translation, speakers and delete in create
    await p.click('.panel-head [data-sub=list]')
    for (const sel of ['.replace-all, .panel-body .btn:has-text("חפש והחלף")', '.panel-body .btn:has-text("הורד SRT")']) if (!(await p.$(sel))) fail('missing in the list: ' + sel)
    await p.click('.panel-head [data-sub=make]')
    if (!(await p.$('.panel-body .translate-go, .panel-body .translate-unsupported'))) fail('no translation in create and translate')
    if (!(await p.$('.panel-body .btn:has-text("מחק כתוביות")'))) fail('no "delete subtitles" in create and translate')
    await p.context().close()

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
