// The editor's panels on a phone, as the app's: one top bar, the video, the transport, then the timeline
// or, in its place, the open group's panel (a bar of its tools as chips, one tool's controls, a close
// button), with the bar of groups at the foot; the video stays in view; nothing scrolls sideways; every tool of every group can be opened and has something in
// it; the targets are 44 px; "more options" is never an empty fold (on a wide screen it holds
// something or is not there). The speech model is blocked (the subtitles come from an SRT).
// Run: node tests/panels.cjs <url> <video> <out dir> [a video with at least 3 face tracks, for the crowded case; default: the first video]
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const SRT = `1\n00:00:00,500 --> 00:00:02,500\nשלום לכולם ברוכים הבאים\n\n2\n00:00:02,600 --> 00:00:05,000\nזה סרטון לדוגמה\n\n3\n00:00:05,100 --> 00:00:08,000\nעם כתוביות בעברית\n`
const fail = (m) => { throw new Error(m) }
// Two voices (the sample's first half is one voice, its second half the same pitched up), for the speaker search.
const SPANS = [[0.3, 2.1], [3.3, 4.3], [5.4, 7.5], [8.2, 11.0], [11.3, 13.1], [14.3, 15.3], [16.4, 18.5], [19.2, 21.3]]
const ts = (t) => new Date(t * 1000).toISOString().slice(11, 23).replace('.', ',')
const SPK_SRT = SPANS.map(([a, b], i) => `${i + 1}\n${ts(a)} --> ${ts(b)}\nמשפט ${i + 1}\n`).join('\n')
/** A group's button on a phone: it opens the group's panel in the timeline's place; the open group's own button closes it, so an open group is left alone. */
const grp = async (p, g) => {
  if (await p.$eval(`.tabs button[data-g=${g}]`, (e) => e.getAttribute('aria-selected') === 'true')) return
  await p.click(`.tabs button[data-g=${g}]`)
  await p.waitForSelector('.panel:not([hidden]) .panel-body')
}

const closePanel = async (p) => {
  if (await p.$('.panel:not([hidden])')) await p.click('.panel-close')
  await p.waitForSelector('.tl', { state: 'visible' })
  await p.waitForTimeout(200)
}
/** Taps the video on a grid until a blur is selected (its bar shows). */
const tapSelect = async (p) => {
  // Paused, at a moment when a blur is on the video (the middle of the first blur's stretch).
  const mid = await p.$$eval('.tl-face', (e) => (Number(e[0].dataset.a) + Number(e[0].dataset.b)) / 2)
  await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, mid)
  await p.waitForTimeout(400)
  const k = await p.$eval('.screen .view', (e) => { const r = e.getBoundingClientRect(); return [r.left, r.top, r.width, r.height] })
  for (let j = 1; j < 12; j++) for (let i = 1; i < 12; i++) {
    await p.evaluate(() => document.querySelector('video').pause())
    await p.touchscreen.tap(k[0] + (k[2] * i) / 12, k[1] + (k[3] * j) / 12)
    if (await p.waitForSelector('.ctx-bar', { timeout: 500 }).then(() => true, () => false)) return true
  }
  return false
}
/** The phone editor with no panel open: the timeline has the room, the video keeps a third of the screen, the bar of groups is on screen, nothing scrolls. */
const checkTimeline = async (p, label, { minTl = 149 } = {}) => {
  const m = await p.evaluate(() => {
    const r = (s) => document.querySelector(s)?.getBoundingClientRect()
    const tl = r('.tl'), screen = r('.screen'), tabs = r('.tabs')
    return { vh: innerHeight, clientH: document.documentElement.clientHeight, scrollH: document.documentElement.scrollHeight, sideways: document.documentElement.scrollWidth > innerWidth, tlH: tl?.height ?? 0, tlTop: tl?.top, screenH: screen?.height, screenBottom: screen?.bottom, tabsTop: tabs?.top, tabsBottom: tabs?.bottom, panel: !!document.querySelector('.panel:not([hidden])'), lanes: document.querySelectorAll('.tl-lane, .tl-strip').length, tlBottom: tl?.bottom, header: getComputedStyle(document.querySelector('header.top')).display !== 'none', selected: [...document.querySelectorAll('.tabs [aria-selected=true]')].length }
  })
  console.log(label, JSON.stringify({ screenH: Math.round(m.screenH), tlH: Math.round(m.tlH), lanes: m.lanes }))
  if (m.panel) fail(label + ': a panel is open')
  if (m.selected) fail(label + ': a group looks open')
  if (m.header) fail(label + ': the site header shows in the phone editor')
  if (m.sideways) fail(label + ': the page scrolls sideways')
  if (m.scrollH > m.clientH + 0.5) fail(`${label}: the page scrolls (${m.scrollH} > ${m.clientH})`)
  if (m.tabsBottom > m.clientH + 0.5) fail(`${label}: the bar of groups is off the screen`)
  if (m.screenH < m.vh * 0.3 - 1) fail(`${label}: the video is ${Math.round(m.screenH)} px of ${m.vh}`)
  if (m.tlH < minTl) fail(`${label}: the timeline is ${Math.round(m.tlH)} px`)
  if (m.screenBottom > m.tlTop + 1 || m.tlBottom > m.tabsTop + 1) fail(label + ': the timeline overlaps its neighbours')
  if (m.lanes < 3) fail(label + ': the timeline has no lanes')
}

/** A short screen (a small phone, or the keyboard up): nothing over anything else; the bar, the transport, the panel with its header and chips and its close button, and the bar of groups all in view. */
const checkShort = async (p, label, panel) => {
  const m = await p.evaluate(() => {
    const r = (s) => { const e = document.querySelector(s); if (!e) return null; const k = e.getBoundingClientRect(); return { top: k.top, bottom: k.bottom, left: k.left, right: k.right, h: k.height } }
    const pn = r('.panel:not([hidden])')
    return { vw: innerWidth, vh: innerHeight, clientH: document.documentElement.clientHeight, scrollH: document.documentElement.scrollHeight, sideways: document.documentElement.scrollWidth > innerWidth, bar: r('.player > .top-row'), screen: r('.screen'), transport: r('.transport'), tl: r('.tl'), panel: pn, tabs: r('.tabs'), bodyH: r('.panel:not([hidden]) .panel-body')?.h ?? 0, ctx: r('.ctx-bar'), closeHit: (() => { const c = document.querySelector('.panel:not([hidden]) .panel-close'); if (!c) return null; const k = c.getBoundingClientRect(); const at = document.elementFromPoint(k.left + k.width / 2, k.top + k.height / 2); return !!at && c.contains(at) })(), close: r('.panel:not([hidden]) .panel-close'), head: r('.panel:not([hidden]) .panel-head'), chips: [...document.querySelectorAll('.panel:not([hidden]) .panel-head [data-sub]')].map((e) => { const k = e.getBoundingClientRect(); return { top: k.top, bottom: k.bottom, left: k.left, right: k.right } }) }
  })
  console.log(label, JSON.stringify({ video: Math.round(m.screen.h), panel: m.panel && Math.round(m.panel.h), tl: m.tl && Math.round(m.tl.h) }))
  const e = 1.5
  if (m.sideways || m.scrollH > m.clientH + 0.5) fail(label + ': the page scrolls')
  if (m.tabs.bottom > m.clientH + 0.5 || m.tabs.top < 0) fail(label + ': the bar of groups is off the screen: ' + JSON.stringify(m.tabs))
  if (m.bar.top < -e || m.bar.bottom > m.screen.top + e) fail(label + ': the top bar and the video overlap')
  if (m.screen.h < 40) fail(label + ': the video is ' + m.screen.h)
  if (m.screen.bottom > m.transport.top + e) fail(label + ': the video and the transport overlap')
  if (m.transport.bottom > m.tabs.top + e) fail(label + ': the transport is under the bar of groups')
  if (panel) {
    if (!m.panel) fail(label + ': no panel')
    if (m.panel.top < m.transport.bottom - e) fail(`${label}: the panel covers the transport (${m.panel.top} < ${m.transport.bottom})`)
    if (m.panel.bottom > m.tabs.top + e) fail(label + ': the panel is under the bar of groups')
    if (m.ctx && m.ctx.bottom > m.panel.top + e) fail(`${label}: the panel reaches the blur's bar (${m.ctx.bottom} > ${m.panel.top})`)
    if (m.ctx && m.ctx.top < m.transport.bottom - e) fail(label + ': the blur\'s bar covers the transport')
    if (m.bodyH < 40) fail(`${label}: the panel body is ${Math.round(m.bodyH)} px: nothing to see`)
    if (!m.closeHit) fail(label + ': the close button is covered at its center')
    if (m.close.top < m.panel.top - e || m.close.bottom > m.panel.bottom + e) fail(label + ': the close button is cut: ' + JSON.stringify([m.close, m.panel]))
    if (m.chips.some((c) => c.top < m.panel.top - e || c.bottom > m.panel.bottom + e || c.right > m.vw + e || c.left < -e)) fail(label + ': the panel\'s tool chips are cut')
  } else if (m.tl.top < m.transport.bottom - e || m.tl.bottom > m.tabs.top + e) fail(label + ': the timeline overlaps its neighbours')
}

async function open(b, viewport, mobile, locale = 'he-IL', tool, file = video, audioFail = false) {
  const ctx = await b.newContext({ viewport, locale, hasTouch: mobile, isMobile: mobile, deviceScaleFactor: mobile ? 2 : 1 })
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
  // A sound step that fails (a hard failure: the voice change cannot be exported).
  if (audioFail) await ctx.route(/audio\.worker[^/]*\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: "self.onmessage = (e) => self.postMessage({ type: 'error', id: e.data.id, code: 'failed', message: 'forced failure' })" }))
  const p = await ctx.newPage()
  await p.goto(url)
  await p.waitForTimeout(800)
  if (tool !== undefined) {
    // A tool from the home screen: an editor with no face detection (nothing blurred, no look to set).
    const chooser = p.waitForEvent('filechooser')
    await p.click(`.tool >> nth=${tool}`)
    await (await chooser).setFiles(file)
  } else {
    await p.setInputFiles('.hero input[type=file]', file)
    await p.waitForSelector('.ready')
    await p.click('.blur-faces')
  }
  await p.waitForSelector('.editor .tabs', { timeout: 120000 })
  await p.waitForTimeout(800)
  return p
}

const mkCheck = (p) => async (label) => {
    const m = await p.evaluate(() => {
      const r = (s) => document.querySelector(s)?.getBoundingClientRect()
      const panel = r('.panel:not([hidden])'), body = r('.panel:not([hidden]) .panel-body'), screen = r('.screen'), tabs = r('.tabs')
      const bodyEl = document.querySelector('.panel-body')
      const chipRows = [...document.querySelectorAll('.panel .subtabs')].map((row) => {
        const rr = row.getBoundingClientRect()
        // The style tool's own five tabs may scroll by a pixel or two on a font that measures wider; the tool chips never.
        return { hidden: row.scrollWidth > row.clientWidth + (row.classList.contains('inner') ? 8 : 1), cut: [...row.querySelectorAll('[data-sub]')].filter((c) => { const k = c.getBoundingClientRect(); const inside = Math.max(0, Math.min(k.right, rr.right) - Math.max(k.left, rr.left)); return inside < k.width * 0.95 || k.left < 0 || k.right > innerWidth }).map((c) => c.textContent) }
      })
      const lastBottom = Math.max(...[...document.querySelectorAll('.player > *')].filter((e) => !e.matches('.screen') && e.getBoundingClientRect().height > 0).map((e) => e.getBoundingClientRect().bottom))
      const topRow = (() => { const kids = [...document.querySelectorAll('.player > .top-row > *, .player > .top-row .export-bar > *')].map((e) => e.getBoundingClientRect()).filter((k) => k.width > 0); const back = document.querySelector('.player > .top-row > .back-btn')?.getBoundingClientRect(); const bar = document.querySelector('.player > .top-row')?.getBoundingClientRect(); return { out: kids.some((k) => k.left < -0.5 || k.right > document.documentElement.clientWidth + 0.5), oneLine: !!back && !!bar && bar.height < 60 && kids.every((k) => k.height < 60), siteHeader: getComputedStyle(document.querySelector('header.top')).display !== 'none' } })()
    const small = [...document.querySelectorAll('.panel:not([hidden]) button, .panel:not([hidden]) label.btn, .tabs button, .top-row button, .transport button')].filter((e) => { const k = e.getBoundingClientRect(); return k.width > 0 && k.height > 0 && k.height < 43.5 && !e.closest('.preset') && !e.matches('.face, .swatch, .speaker-dot, .time-btn') }).map((e) => e.className + ':' + Math.round(e.getBoundingClientRect().height))
      return {
        sideways: document.documentElement.scrollWidth > innerWidth, vh: innerHeight, alert: !!document.querySelector('.side > .sound-error, .side > .tpl-run'), clientH: document.documentElement.clientHeight, scrollH: document.documentElement.scrollHeight, tabsBottom: tabs?.bottom,
        panelH: panel?.height, bodyH: body?.height, tlShown: !!document.querySelector('.tl') && getComputedStyle(document.querySelector('.tl')).visibility !== 'hidden' && getComputedStyle(document.querySelector('.tl')).display !== 'none', ctx: !!document.querySelector('.ctx-bar'), panelTop2: panel?.top, tabsTop: tabs?.top, screenTop: screen?.top, screenBottom: screen?.bottom, screenH: screen?.height,
        bodyEmpty: !bodyEl || bodyEl.children.length === 0 || !(bodyEl.innerText.trim() || [...bodyEl.querySelectorAll('input, canvas, img, button')].some((e) => e.offsetParent !== null)),
        moreOptions: [...document.querySelectorAll('.subs-more')].map((d) => d.querySelector('.subs-more-body')?.children.length ?? 0),
        topRow, chipRows, playerBottom: lastBottom, panelTop: panel?.top, tablist: [...document.querySelectorAll('.panel [role=tablist]')].every((t) => t.getAttribute('aria-label')), tabs: [...document.querySelectorAll('.panel [role=tab]')].every((t) => t.getAttribute('aria-controls') === 'panel-body' && (t.tabIndex === 0) === (t.getAttribute('aria-selected') === 'true')), tabpanel: document.querySelector('.panel-body')?.getAttribute('role'), small, panelOver: bodyEl ? [...bodyEl.querySelectorAll('*')].some((e) => { if (e.closest('.chips, .faces, .swatches, .preset-row, .scroll-row, .word-chips, .tl-scroll')) return false; const k = e.getBoundingClientRect(); const bb = bodyEl.getBoundingClientRect(); return k.width > 0 && (k.right > bb.right + 1 || k.left < bb.left - 1) }) : false,
      }
    })
    console.log(label, JSON.stringify({ sideways: m.sideways, panelH: Math.round(m.panelH), screenH: Math.round(m.screenH), rows: m.chipRows.length }))
    if (m.chipRows.some((r) => r.hidden || r.cut.length)) fail(`${label}: tool chips hidden or cut: ${JSON.stringify(m.chipRows)}`)
    if (m.topRow.out || !m.topRow.oneLine || m.topRow.siteHeader) fail(`${label}: the top row overflows or wraps: ${JSON.stringify(m.topRow)}`)
  if (m.playerBottom > m.panelTop + 1) fail(`${label}: the panel covers the timeline: ${m.playerBottom} > ${m.panelTop}`)
    if (m.tabpanel !== 'tabpanel' || !m.tablist || !m.tabs) fail(`${label}: tabs are not marked up as tabs: ${JSON.stringify([m.tabpanel, m.tablist, m.tabs])}`)
    if (m.sideways) fail(label + ': the page scrolls sideways')
    if (m.panelH > m.vh * 0.5) fail(`${label}: the panel is ${m.panelH} px of ${m.vh}`)
    // The panel takes the timeline's place: the timeline is hidden, and the panel's body has room for a tool.
    if (m.tlShown) fail(label + ': the timeline shows with a panel open')
    const wantBody = m.alert ? 100 : m.ctx ? (m.vh >= 740 ? 140 : 100) : m.vh >= 740 ? 215 : 170
    if (m.bodyH < wantBody) fail(`${label}: the panel body is ${Math.round(m.bodyH)} px (wanted ${wantBody}) at ${m.vh}`)
    if (!m.alert && !m.ctx && m.screenH < m.vh * 0.3 - 1) fail(`${label}: the video is ${Math.round(m.screenH)} px of ${m.vh}`)
    if (m.screenTop < 0 || m.screenBottom > m.tabsTop || m.screenH < (m.alert || m.ctx ? 40 : 90)) fail(`${label}: the video is not in view: ${JSON.stringify(m)}`)
    if (m.bodyEmpty) fail(label + ': the panel is empty')
    if (m.moreOptions.some((n) => n === 0)) fail(label + ': "more options" is empty')
    if (m.tabsBottom > m.clientH + 0.5 || m.scrollH > m.clientH + 0.5) fail(`${label}: the bar of groups is off the screen: ${JSON.stringify([m.tabsBottom, m.scrollH, m.clientH])}`)
    if (m.panelOver) fail(label + ': the panel body scrolls sideways')
    if (m.small.length) fail(`${label}: targets under 44 px: ${m.small.slice(0, 6)}`)
    await p.screenshot({ path: `${out}/${label.replace(/[^\w]+/g, '_')}.png` })
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
      const check = mkCheck(p)
      // A tool opens at its top, whatever the one before was scrolled to.
      const where = () => p.evaluate(() => [...document.querySelectorAll('.tabs [aria-selected=true], .panel [role=tab][aria-selected=true]')].map((e) => e.dataset.g || e.dataset.sub).join('/'))
      const go = async (sel, label) => {
        await p.evaluate(() => { const e = document.querySelector('.panel-body'); if (e) e.scrollTop = 1e5 })
        const before = await where()
        const g = /^\.tabs button\[data-g=(\w+)\]$/.exec(sel)
        if (g) await grp(p, g[1])
        else await p.click(sel)
        await p.waitForTimeout(300)
        const top = await p.evaluate(() => document.querySelector('.panel-body')?.scrollTop ?? 0)
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
      // A blur selected: on the timeline (no panel) its action bar shows under the timeline; a group's
      // panel clears the selection (as the app's groups do), and one tapped on the video with a panel
      // open (the manual blur's way) keeps its bar over the panel's place.
      const withBlurSelected = async () => {
        await closePanel(p)
        await p.click('.tl-face >> nth=0')
        await p.waitForSelector('.ctx-bar', { timeout: 5000 })
        await checkTimeline(p, 'timeline with a blur selected')
        await grp(p, 'subs')
        if (await p.$('.ctx-bar')) fail('a group opened with the blur still selected')
        for (const g of ['faces', 'subs']) {
          await grp(p, g)
          if (!(await tapSelect(p))) fail(`no blur could be selected on the video with the ${g} panel open`)
          for (const sub of await p.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
            await p.click(`.panel-head [data-sub=${sub}]`)
            await p.waitForTimeout(200)
            await check(`blur selected + ${g}/${sub}`)
            if (g === 'subs' && sub === 'style') for (const sp of await p.$$eval('.subtabs.inner [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
              await p.click(`.subtabs.inner [data-sub=${sp}]`)
              await p.waitForTimeout(200)
              await check(`blur selected + subs/style/${sp}`)
            }
          }
        }
        await grp(p, 'faces')
        const room = await p.evaluate(() => ({ bottom: document.querySelector('.tabs').getBoundingClientRect().bottom, vh: document.documentElement.clientHeight }))
        if (room.bottom < room.vh - 8) fail('the editor does not fit the screen: ' + JSON.stringify(room))
      }
      await sweep()
      // The arrow keys move between tools, and do not also step the video.
      await grp(p, 'faces')
      await p.focus('.panel-head [role=tab][aria-selected=true]')
      const t0 = await p.evaluate(() => document.querySelector('video').currentTime)
      const on0 = await p.evaluate(() => document.querySelector('.panel-head [role=tab][aria-selected=true]')?.dataset.sub)
      await p.keyboard.press('ArrowRight')
      await p.waitForTimeout(300)
      const arrow = await p.evaluate(() => ({ t: document.querySelector('video').currentTime, on: document.querySelector('.panel-head [role=tab][aria-selected=true]')?.dataset.sub }))
      if (arrow.t !== t0) fail('an arrow key on a tool also stepped the video: ' + JSON.stringify([t0, arrow]))
      if (arrow.on === on0) fail('the arrow key did not move to another tool')
      // with subtitles (an SRT): the list shows, and the tools in "create and translate" are there
      await grp(p, 'subs')
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
      // 412 x 915 is the roomy one: Hebrew only (the shards are timed).
      for (const vp of [{ width: 360, height: 740 }, { width: 412, height: 915 }, { width: 390, height: 664 }].filter((v) => he || v.width !== 412)) {
        await p.setViewportSize(vp)
        await p.waitForTimeout(500)
        console.log('==', vp.width, 'x', vp.height, locale)
        await sweep()
        await withBlurSelected()
      }
      await p.context().close()
    }
    for (const locale of ['he-IL', 'en-US']) await phone({ width: 390, height: 844 }, locale)

    // ---- the smallest phone with more than the usual on it: several face tracks (a longer timeline), and a failed sound step (an alert over the panel): the bar of groups stays whole on the screen
    {
      const q = await open(b, { width: 360, height: 740 }, true, 'he-IL', undefined, process.argv[5] || video)
      const chk = mkCheck(q)
      const tracks = await q.$$eval('.tl-face', (e) => e.length)
      console.log('face tracks:', tracks)
      if (tracks < 3) fail('the many-tracks case needs a video with at least 3 face tracks: ' + tracks)
      for (const g of ['faces', 'subs', 'sound']) {
        await grp(q, g)
        for (const sub of await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
          await q.click(`.panel-head [data-sub=${sub}]`)
          await q.waitForTimeout(250)
          await chk(`many tracks ${g}/${sub}`)
        }
      }
      // subtitles in, a blur selected, every style tab, on the two small screens
      await grp(q, 'subs')
      await q.click('.panel-head [data-sub=make]')
      await q.setInputFiles('.srt-import input', `${out}/in.srt`)
      await q.waitForSelector('.panel-head [data-sub=list][aria-selected=true]', { timeout: 5000 })
      for (const vp of [{ width: 360, height: 740 }, { width: 390, height: 664 }]) {
        await q.setViewportSize(vp)
        await q.waitForTimeout(400)
        for (const g of ['faces', 'subs']) {
          await grp(q, g)
          if (!(await tapSelect(q))) fail(`no blur could be selected on the video with the ${g} panel open`)
          for (const sub of await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
            await q.click(`.panel-head [data-sub=${sub}]`)
            await q.waitForTimeout(200)
            await chk(`${vp.width}x${vp.height} 4 tracks + SRT + blur selected ${g}/${sub}`)
            if (g === 'subs' && sub === 'style') for (const sp of await q.$$eval('.subtabs.inner [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
              await q.click(`.subtabs.inner [data-sub=${sp}]`)
              await q.waitForTimeout(200)
              await chk(`${vp.width}x${vp.height} 4 tracks + SRT + blur selected subs/style/${sp}`)
            }
          }
        }
      }
      await q.context().close()
      const r = await open(b, { width: 360, height: 740 }, true, 'he-IL', undefined, video, true)
      await grp(r, 'sound')
      await r.click('.panel-head [data-sub=voice]')
      await r.click('.panel-body .chips .chip >> nth=1')
      await r.waitForSelector('.sound-error[data-hard="1"]', { timeout: 30000 })
      const chk2 = mkCheck(r)
      await chk2('sound error sound/voice')
      for (const g of ['faces', 'subs']) { await grp(r, g); await r.waitForTimeout(250); await chk2('sound error ' + g) }
      await r.click('.panel-head [data-sub=style]')
      await chk2('sound error subs/style')
      await r.screenshot({ path: `${out}/sound_error_style.png` })
      await r.setViewportSize({ width: 390, height: 664 })
      await r.waitForTimeout(400)
      for (const g of ['sound', 'faces', 'subs']) {
        await grp(r, g)
        for (const sub of await r.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))) {
          await r.click(`.panel-head [data-sub=${sub}]`)
          await r.waitForTimeout(200)
          await chk2(`390x664 sound error ${g}/${sub}`)
        }
      }
      await r.screenshot({ path: `${out}/sound_error_664.png` })
      await r.context().close()
    }

    // ---- an editor opened with no face detection: no empty "look" tool, the rest as usual
    for (const locale of ['he-IL', 'en-US']) {
      const q = await open(b, { width: 360, height: 740 }, true, locale, 0)
      const subs = await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
      console.log('no detection', locale, subs.join())
      await grp(q, 'faces')
      const faceSubs = await q.$$eval('.panel-head [data-sub]', (els) => els.map((e) => e.dataset.sub))
      if (faceSubs.includes('look')) fail('the look tool is offered with no faces to look at')
      for (const sub of faceSubs) {
        await q.click(`.panel-head [data-sub=${sub}]`)
        const filled = await q.evaluate(() => (document.querySelector('.panel-body')?.textContent.trim().length ?? 0) > 0 || !!document.querySelector('.panel-body button'))
        if (!filled) fail('empty panel: faces/' + sub)
      }
      await q.context().close()
    }

    // ---- the phone's states at four sizes, in Hebrew and English: the editor opens on the timeline (with
    // all its lanes), a group's panel takes its place, the close button and a second tap on the group
    // bring it back; the video keeps a third of the screen; the bar of groups never leaves the screen.
    for (const locale of ['he-IL', 'en-US']) {
      const p = await open(b, { width: 390, height: 780 }, true, locale)
      // With faces found, the editor opens on the faces' list (the face tiles are in view at once); closed, the timeline is there.
      await p.waitForSelector('.panel:not([hidden]) .face', { state: 'visible', timeout: 5000 })
      await closePanel(p)
      await checkTimeline(p, 'the timeline once the first panel is closed')
      await grp(p, 'subs')
      await p.click('.panel-head [data-sub=make]')
      await p.setInputFiles('.srt-import input', `${out}/in.srt`)
      await p.waitForSelector('.panel-head [data-sub=list][aria-selected=true]', { timeout: 5000 })
      await closePanel(p)
      // The timeline keeps following the video while a panel hides it: closing the panel leaves the video where it was,
      // and a seek made with the panel open shows on the timeline when it is back.
      {
        const state = () => p.evaluate(() => ({ t: document.querySelector('video').currentTime, sl: document.querySelector('.tl-scroll').scrollLeft }))
        const seekTo = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(600) }
        const dur = await p.evaluate(() => document.querySelector('video').duration)
        await seekTo(dur * 0.85)
        const a = await state()
        const pps = a.sl / a.t
        await grp(p, 'faces')
        await p.waitForTimeout(300)
        await p.click('.panel-close')
        await p.waitForTimeout(600)
        const b2 = await state()
        if (Math.abs(b2.t - a.t) > 0.05) fail(`opening and closing a panel moved the video: ${a.t} -> ${b2.t}`)
        if (Math.abs(b2.sl / pps - b2.t) > 0.1) fail(`the timeline is not at the playhead after a panel: ${b2.sl / pps} vs ${b2.t}`)
        await grp(p, 'faces')
        await seekTo(dur * 0.3)
        await p.click('.panel-close')
        await p.waitForTimeout(600)
        const c = await state()
        if (Math.abs(c.sl / pps - c.t) > 0.1) fail(`the timeline did not follow a seek made in the panel: ${c.sl / pps} vs ${c.t}`)
        await seekTo(0)
      }
      const chk = mkCheck(p)
      for (const vp of [{ width: 390, height: 780 }, { width: 390, height: 664 }, { width: 360, height: 740 }, { width: 412, height: 915 }]) {
        const label = `${vp.width}x${vp.height} ${locale}`
        await p.setViewportSize(vp)
        await p.waitForTimeout(500)
        await checkTimeline(p, `${label} timeline`)
        // every group opens its panel in the timeline's place
        for (const g of ['faces', 'edit', 'picture', 'subs', 'sound', 'mark']) {
          await grp(p, g)
          await chk(`${label} ${g}`)
        }
        await grp(p, 'subs')
        await p.click('.panel-head [data-sub=style]')
        await chk(`${label} subs/style`)
        const body = await p.$eval('.panel-body', (e) => e.getBoundingClientRect().height)
        if (vp.height === 780 && body < 220) fail(`${label}: the style panel's body is ${Math.round(body)} px`)
        // the close control, and a second tap on the open group, bring the timeline back
        await p.click('.panel-close')
        await p.waitForSelector('.tl', { state: 'visible' })
        await checkTimeline(p, `${label} closed with the button`)
        if ((await p.evaluate(() => document.activeElement?.dataset?.g)) !== 'subs') fail(label + ': the focus is not on the group after the close button')
        await p.click('.tabs button[data-g=sound]')
        await p.waitForSelector('.panel:not([hidden])')
        await p.click('.tabs button[data-g=sound]')
        await p.waitForSelector('.panel:not([hidden])', { state: 'detached' })
        await checkTimeline(p, `${label} closed with the group`)
        // the settings are in the top bar (the site header is not shown)
        await p.click('.top-row .settings-open')
        await p.waitForSelector('.settings-sheet')
        await p.waitForTimeout(300)
        await p.keyboard.press('Escape')
        await p.waitForSelector('.settings-sheet', { state: 'detached' })
        // a blur on the timeline is selected as before; its bar fits under the timeline
        await p.click('.tl-face >> nth=0')
        await p.waitForSelector('.ctx-bar', { timeout: 5000 })
        await checkTimeline(p, `${label} blur selected`, { minTl: 100 })
        await p.keyboard.press('Escape')
        if (vp.width === 390 && vp.height === 780) await p.screenshot({ path: `${out}/states_${locale}.png` })
      }

      // Short screens: a small phone, and a tall one with the keyboard up.
      for (const vp of [{ width: 375, height: 548 }, { width: 390, height: 500 }, { width: 390, height: 400 }]) {
        await p.setViewportSize(vp)
        await p.waitForTimeout(500)
        const label = `${vp.width}x${vp.height} ${locale}`
        await closePanel(p)
        await checkShort(p, label + ' timeline', false)
        for (const g of ['faces', 'subs', 'sound']) {
          await grp(p, g)
          await checkShort(p, `${label} ${g}`, true)
        }
        await grp(p, 'subs')
        await p.click('.panel-head [data-sub=style]')
        await checkShort(p, `${label} subs/style`, true)
        await p.screenshot({ path: `${out}/short_${vp.width}x${vp.height}_${locale}.png` })
        // a blur selected on the video with a panel open: its bar and the panel do not overlap
        for (const g of ['faces', 'subs']) {
          await grp(p, g)
          if (!(await tapSelect(p))) fail(`${label}: no blur could be selected with the ${g} panel open`)
          await checkShort(p, `${label} blur selected + ${g}`, true)
          if (g === 'subs') { await p.click('.panel-head [data-sub=style]'); await checkShort(p, `${label} blur selected + subs/style`, true) }
          if (g === 'faces') await p.screenshot({ path: `${out}/short_selected_${vp.width}x${vp.height}_${locale}.png` })
          await p.keyboard.press('Escape')
        }
      }
      // The BETA mark in the bar (forced here: this build is the release): the bar still fits, every target 44 px, Export in view.
      await p.setViewportSize({ width: 390, height: 780 })
      await closePanel(p)
      await p.evaluate(() => { const row = document.querySelector('.top-row'); row.classList.add('beta'); const s = document.createElement('span'); s.className = 'beta-mini'; s.textContent = 'BETA'; row.insertBefore(s, row.querySelector('.grow')) })
      for (const w of [320, 360, 390, 412]) {
        await p.setViewportSize({ width: w, height: 740 })
        await p.waitForTimeout(400)
        const r = await p.evaluate(() => {
          const row = document.querySelector('.top-row').getBoundingClientRect()
          const kids = [...document.querySelectorAll('.top-row button, .top-row .beta-mini')].map((e) => { const k = e.getBoundingClientRect(); return { n: e.className, w: k.width, h: k.height, l: k.left, r: k.right } })
          return { sideways: document.documentElement.scrollWidth > innerWidth, kids, row: [row.left, row.right], vw: innerWidth }
        })
        console.log('beta bar', w, locale, JSON.stringify(r.kids.map((k) => Math.round(k.w))))
        if (r.sideways) fail(`the beta bar scrolls the page sideways at ${w} (${locale})`)
        if (r.kids.some((k) => k.l < -0.5 || k.r > r.vw + 0.5)) fail(`the beta bar is cut at ${w} (${locale}): ${JSON.stringify(r.kids)}`)
        if (r.kids.some((k) => !/beta-mini/.test(k.n) && (k.w < 43.5 || k.h < 43.5))) fail(`a target in the bar is under 44 px at ${w} (${locale}): ${JSON.stringify(r.kids)}`)
        await p.screenshot({ path: `${out}/beta_bar_${w}_${locale}.png` })
      }
      await p.context().close()
    }

    // ---- jobs that outlive a group switch (a phone, Hebrew): speakers found while another group (or another tool) is open are
    // applied and the busy mark goes; the notice of bleeps the new subtitles lost is there when the subtitles are back.
    {
      const openToolPhone = async (fake) => {
        const ctx = await b.newContext({ viewport: { width: 390, height: 780 }, locale: 'he-IL', hasTouch: true, isMobile: true })
        await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
        let runs = 0
        const W1 = [{ text: 'שלום', start: 1, end: 1.5 }, { text: 'עולם', start: 1.6, end: 2.1 }, { text: 'יפה', start: 2.2, end: 2.8 }]
        const W2 = [{ text: 'בוקר', start: 5, end: 5.5 }, { text: 'טוב', start: 5.6, end: 6.1 }]
        // The worker may be cached by the browser: it asks the page's side which run it is (a counter the test keeps).
        if (fake) {
          await ctx.route(/\/__run$/, (r) => r.fulfill({ contentType: 'text/plain', body: String(++runs) }))
          await ctx.route(/subs\.worker[^/]*\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: `const W = [${JSON.stringify(W1)}, ${JSON.stringify(W2)}]
self.onmessage = async () => { const n = Number(await (await fetch('/__run')).text()); if (n === 3) { setTimeout(() => self.postMessage({ type: 'error', code: 'failed', message: 'boom' }), 1500); return } self.postMessage({ type: 'audio' }); const tk = setInterval(() => self.postMessage({ type: 'tick' }), 500); setTimeout(() => { clearInterval(tk); self.postMessage({ type: 'done', device: 'wasm', words: W[n === 1 ? 0 : 1], ms: { load: 1, recognize: 1, first: 1, decode: 1, after: 1, tokens: 1 } }) }, n === 1 ? 800 : 6000) }` }))
        }
        const p = await ctx.newPage()
        await p.goto(url + (url.includes('?') ? '&' : '?') + 'subsmodel=base')
        await p.waitForTimeout(800)
        const chooser = p.waitForEvent('filechooser')
        await p.click('.tool >> nth=0')
        await (await chooser).setFiles(video)
        await p.waitForSelector('.editor .tabs', { timeout: 60000 })
        await p.waitForSelector('.panel:not([hidden]) .panel-body')
        return p
      }
      const busyDot = '.tabs [data-g=subs] .dot.busy'
      // speakers
      {
        const p = await openToolPhone(false)
        fs.writeFileSync(`${out}/spk.srt`, SPK_SRT)
        await p.setInputFiles('.srt-import input', `${out}/spk.srt`)
        await p.waitForSelector('.panel-head [data-sub=list][aria-selected=true]', { timeout: 5000 })
        await p.click('.panel-head [data-sub=make]')
        await p.click('.speakers-go')
        await grp(p, 'faces')
        await p.waitForSelector(busyDot, { state: 'attached', timeout: 5000 }).catch(() => fail('no busy mark on the Subtitles group while speakers are found'))
        await grp(p, 'sound')
        await p.waitForSelector(busyDot, { state: 'detached', timeout: 300000 }).catch(() => fail('the busy mark stays after the group switch'))
        await grp(p, 'subs')
        await p.click('.panel-head [data-sub=list]')
        const sp = await p.$$eval('.cue-speaker', (e) => e.map((x) => Number(x.textContent)))
        console.log('speakers after a group switch:', JSON.stringify(sp))
        if (sp.length !== 8 || sp[0] !== 1 || sp[7] !== 2) fail('the speakers were not applied after a group switch: ' + JSON.stringify(sp))
        // a tool switch inside Subtitles
        await p.click('.cue-speaker >> nth=0')
        if ((await p.$eval('.cue-speaker', (e) => Number(e.textContent))) !== 2) fail('the speaker did not change by hand')
        await p.click('.panel-head [data-sub=make]')
        await p.click('.speakers-go')
        await p.click('.panel-head [data-sub=list]')
        await p.waitForSelector(busyDot, { state: 'attached', timeout: 5000 }).catch(() => fail('no busy mark while speakers are found (tool switch)'))
        await p.waitForSelector(busyDot, { state: 'detached', timeout: 300000 }).catch(() => fail('the busy mark stays after the tool switch'))
        const again = await p.$eval('.cue-speaker', (e) => Number(e.textContent))
        if (again !== 1) fail('the speakers found during a tool switch were not applied: ' + again)
        await p.context().close()
      }
      // the bleeps a new recognition loses
      {
        const p = await openToolPhone(true)
        await p.click('.subs-create')
        await p.waitForSelector('.panel-head [data-sub=list]', { timeout: 20000 })
        await p.click('.panel-head [data-sub=bleep]')
        await p.click('.panel-body .btn:has-text("בחר מילים")')
        await p.click('.word >> nth=1')
        await p.click('.panel-body .btn:has-text("סיום")')
        await p.click('.panel-head [data-sub=make]')
        await p.click('.panel-body .btn:has-text("מחק כתוביות")')
        await p.click('.subs-create')
        // The Style tool open, then another group, when the recognition ends: the notice is still seen, in whichever group.
        await p.click('.panel-head [data-sub=style]')
        await grp(p, 'faces')
        await p.waitForSelector(busyDot, { state: 'attached', timeout: 5000 }).catch(() => fail('no busy mark while the subtitles are made'))
        await p.waitForSelector(busyDot, { state: 'detached', timeout: 30000 }).catch(() => fail('the busy mark stays after the recognition'))
        await p.waitForSelector('.lost-bleeps', { state: 'visible', timeout: 5000 }).catch(() => fail('the lost-bleeps notice is not seen in the other group the recognition ended in'))
        // the export sheet says it too
        await p.click('.export-bar .btn')
        await p.waitForSelector('.export-sheet .lost-note', { state: 'visible', timeout: 5000 }).catch(() => fail('the export sheet does not say bleeped words were lost'))
        await p.keyboard.press('Escape')
        await p.waitForSelector('.export-sheet', { state: 'detached', timeout: 5000 })
        await grp(p, 'subs')
        if (!(await p.isVisible('.lost-bleeps'))) fail('the lost-bleeps notice is gone in the Subtitles group')
        // an error that comes while another group is open marks the Subtitles group, and the make tool shows it
        await p.click('.panel-head [data-sub=make]')
        await p.click('.panel-body .btn:has-text("מחק כתוביות")')
        await p.click('.subs-create')
        await grp(p, 'sound')
        await p.waitForSelector('.tabs [data-g=subs] .dot.alert', { state: 'attached', timeout: 15000 }).catch(() => fail('no alert mark on the Subtitles group after an error in another group'))
        await grp(p, 'subs')
        await p.waitForSelector('.panel:not([hidden]) :text("boom")', { timeout: 5000 }).catch(() => fail('the error is not shown in the make tool'))
        if (await p.$('.tabs [data-g=subs] .dot.alert')) fail('the alert mark stays after the group was opened')
        await p.screenshot({ path: `${out}/lost_bleeps.png` })
        await p.context().close()
      }
    }

    // ---- a wide screen: everything as before, and "more options" holds something
    const w = await open(b, { width: 1366, height: 860 }, false)
    await w.click('.tabs button[data-g=subs]')
    const wide = async (label) => {
      const n = await w.evaluate(() => [...document.querySelectorAll('.subs-more')].map((d) => d.querySelector('.subs-more-body')?.textContent.trim().length ?? 0))
      console.log('wide', label, JSON.stringify(n))
      if (n.some((x) => x === 0)) fail('wide ' + label + ': "more options" is empty')
      if (await w.$('.panel:not([hidden])')) fail('the phone panel on a wide screen')
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
