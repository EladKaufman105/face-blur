// The editor as the app's, part three: the watermark's logo kept between visits (and a template's
// logo shown), the template editor asking before it drops unsaved changes (and an "Edit" next to
// the chosen template on the ready screen), the safe zone's view aid for 9:16 (a switch, a chooser
// of three logos, the mock over the video, never in the export), and the thin scrubber over a
// phone's open panel. Also checks the pure parts of the low-confidence flag and the safe zone.
// Run: node tests/parity2.cjs <url> <landscape video> <portrait 9:16 video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const zlib = require('zlib')
const { execFileSync } = require('child_process')
const path = require('path')
const [, , url, video, portrait, out] = process.argv
const PHONE = { viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true }
const TABLET = { viewport: { width: 1024, height: 768 }, hasTouch: true }

/** A solid RGBA picture as a PNG file. */
function png(w, h, [r, g, b, a]) {
  const crc = (buf) => { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } let x = 0xffffffff; for (const v of buf) x = t[(x ^ v) & 255] ^ (x >>> 8); return (x ^ 0xffffffff) >>> 0 }
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]) }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: w }, () => [r, g, b, a]).flat())])
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const logoFile = `${out}/logo.png`
  fs.writeFileSync(logoFile, png(64, 32, [255, 0, 0, 255]))
  const logoUrl = 'data:image/png;base64,' + fs.readFileSync(logoFile).toString('base64')
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const fail = (m) => { throw new Error(m) }
  let cur
  const newPage = async (opts = {}) => {
    const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', ...opts })
    const p = await ctx.newPage()
    p.logs = []
    p.on('console', (m) => p.logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => p.logs.push(`[pageerror] ${e.message}`))
    return p
  }
  /** A tool of the home screen (0 subtitles, 1 sound, 2 watermark, 3 MP3) on [file]: the editor opens with no face search. */
  const tool = async (p, k, file) => {
    const chooser = p.waitForEvent('filechooser')
    await p.click(`.tool >> nth=${k}`)
    await (await chooser).setFiles(file)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(1200)
  }
  const tap = (p, sel, touch) => (touch ? p.tap(sel) : p.click(sel))
  /** How many pixels of the editor's overlay (over the video) are not clear, and a signature of them. */
  const overlay = (p) => p.evaluate(() => {
    const c = document.querySelectorAll('.view canvas')[2]
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
    let n = 0, sig = 0
    for (let i = 3; i < d.length; i += 4 * 7) if (d[i] > 20) { n++; sig = (sig * 31 + d[i - 3] + 3 * d[i - 2] + 7 * d[i - 1] + 13 * d[i]) >>> 0 }
    return { n, sig, w: c.width, h: c.height }
  })
  try {
    // ---- the pure parts: the low-confidence flag, the safe zone's rule
    const script = `
      const [lc, sz] = await Promise.all([import('./src/lib/lowconf.ts'), import('./src/lib/safezone.ts')]);
      const box = (cx, cy, w, h) => [cx, cy, w, h]
      const track = (first, boxes) => ({ id: 0, first, boxes: Float32Array.from(boxes.flat()), best: first })
      const det = (cx, cy, w, h, score, verify = 0) => ({ cx, cy, w, h, score, verify })
      const tracks = [track(0, [box(.5, .5, .2, .3), box(.5, .5, .2, .3)]), track(0, [box(.2, .2, .1, .1)]), track(0, [box(.8, .5, .2, .3)]), track(0, [box(.1, .9, .1, .1)])]
      const dets = new Map([[0, [det(.5, .5, .2, .3, .95), det(.2, .2, .1, .1, .5, .85), det(.8, .5, .2, .3, .6, .3), det(.1, .1, .1, .1, .99)]], [1, [det(.5, .5, .2, .3, .7)]]])
      const out = {
        low: lc.lowConfidenceTracks(tracks, dets),
        lowManual: lc.lowConfidenceTracks([{ ...tracks[3], manual: true }], dets),
        none: lc.lowConfidenceTracks([], dets),
        vertical: sz.appliesTo('9:16', 1920, 1080), landscape: sz.appliesTo('original', 1920, 1080), square: sz.appliesTo('1:1', 1080, 1080), fourFive: sz.appliesTo('4:5', 1080, 1350),
        shot916: sz.appliesTo('original', 1080, 1920), shot45: sz.appliesTo('original', 1080, 1350), shotTall: sz.appliesTo('original', 1080, 2400), shotEdge: sz.appliesTo('original', 530, 1000), shotNo: sz.appliesTo('original', 0, 0),
        tiktok: sz.zoneOf('tiktok'), reels: sz.zoneOf('reels'), shorts: sz.zoneOf('shorts'),
      }
      console.log(JSON.stringify(out))`
    const u = JSON.parse(execFileSync(process.execPath, ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script], { cwd: path.join(__dirname, '..') }).toString())
    console.log('pure:', JSON.stringify(u))
    // 1: a clear detection (.95); 2: only a weak one .5 but re-checked .85 (clear); 3: weak (.6, .3): low; 4: a detection far from its box (not inside): low. The manual one is not counted.
    if (u.low !== 2) fail('low-confidence tracks: ' + u.low)
    if (u.lowManual !== 0 || u.none !== 0) fail('a manual blur or nothing is flagged')
    if (!u.vertical || u.landscape || u.square || u.fourFive) fail('the safe zone applies to the wrong export frames')
    if (!u.shot916 || u.shot45 || u.shotTall || !u.shotEdge || u.shotNo) fail('the safe zone applies to the wrong shot frames')
    if (Math.abs(u.tiktok.top - 240 / 1920) > 1e-6 || Math.abs(u.tiktok.bottom - 660 / 1920) > 1e-6 || Math.abs(u.tiktok.left - 120 / 1080) > 1e-6 || Math.abs(u.tiktok.right - 300 / 1080) > 1e-6
      || Math.abs(u.reels.top - 0.14) > 1e-6 || Math.abs(u.reels.bottom - 0.35) > 1e-6 || Math.abs(u.reels.left - 0.06) > 1e-6 || Math.abs(u.reels.right - 0.06) > 1e-6
      || Math.abs(u.shorts.top - 288 / 1920) > 1e-6 || Math.abs(u.shorts.bottom - 672 / 1920) > 1e-6 || Math.abs(u.shorts.left - 48 / 1080) > 1e-6 || Math.abs(u.shorts.right - 192 / 1080) > 1e-6) fail('the zones are not the verified numbers (docs/parity.md, shared/catalog/safezones.json): ' + JSON.stringify([u.tiktok, u.reels, u.shorts]))

    // ---- the watermark's logo is kept between visits
    {
      const p = (cur = await newPage())
      await p.goto(url)
      await p.waitForTimeout(800)
      await tool(p, 2, video)
      await p.waitForSelector('.card input[type=file][accept="image/*"]', { state: 'attached' })
      await p.setInputFiles('.card input[type=file][accept="image/*"]', logoFile)
      await p.waitForSelector('img[src^="data:image/png"]')
      await p.waitForTimeout(600)
      // The picture is in the browser's storage (IndexedDB), the other choices in localStorage.
      const kept = await p.evaluate(async () => {
        const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /-logo$/.test(n))
        if (!name) return null
        const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
        const v = await new Promise((res) => { const g = db.transaction('logo').objectStore('logo').get('last'); g.onsuccess = () => res(g.result) })
        db.close()
        return { logo: typeof v === 'string' ? v.slice(0, 22) : null, flag: JSON.parse(localStorage.getItem('onit.watermark') ?? 'null') }
      })
      console.log('kept:', JSON.stringify(kept))
      if (!kept || kept.logo !== 'data:image/png;base64,' || kept.flag?.logo !== true || kept.flag?.image !== null) fail('the logo is not kept: ' + JSON.stringify(kept))
      // The preview draws it (the red picture, at the corner).
      const o1 = await p.evaluate(() => { const c = document.querySelectorAll('.view canvas')[2]; const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let red = 0; for (let i = 0; i < d.length; i += 4) if (d[i] > 150 && d[i + 1] < 90 && d[i + 3] > 150) red++; return red })
      if (o1 < 200) fail('the logo is not drawn in the preview: ' + o1)
      // A new visit: the logo is there with no choosing (the tab, the preview), and it comes back when "Text" and "Logo" are toggled.
      await p.reload()
      await p.waitForTimeout(1000)
      await tool(p, 2, video)
      if (!(await p.$('img[src^="data:image/png"]'))) fail('the logo is gone after a reload')
      const o2 = await p.evaluate(() => { const c = document.querySelectorAll('.view canvas')[2]; const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let red = 0; for (let i = 0; i < d.length; i += 4) if (d[i] > 150 && d[i + 1] < 90 && d[i + 3] > 150) red++; return red })
      if (o2 < 200) fail('the logo is not drawn after a reload: ' + o2)
      await p.click('.seg button >> nth=0')
      if (await p.$('img[src^="data:image/png"]')) fail('"Text" still shows the logo')
      await p.click('.seg button >> nth=1')
      await p.waitForSelector('img[src^="data:image/png"]', { timeout: 3000 })
      // A project opened again has it too.
      await p.waitForTimeout(2500)
      await p.click('.editor .btn.ghost >> nth=0')
      await p.waitForSelector('.project', { timeout: 10000 })
      await p.reload()
      await p.waitForSelector('.project')
      await p.click('.project-open')
      await p.waitForSelector('.editor .tabs', { timeout: 30000 })
      await p.click('.tabs button[data-g=mark]')
      await p.waitForSelector('img[src^="data:image/png"]', { timeout: 5000 })
      await p.screenshot({ path: `${out}/1_logo.png` })
      await p.context().close()
    }

    // ---- a template's logo is shown in the editor it opens, and the template editor asks before it drops changes
    {
      const p = (cur = await newPage())
      const SEED = { v: 1, id: 'tplogo', name: 'עם לוגו', group: 'USER', blur: { none: true }, export: { watermark: JSON.stringify({ text: '', image: true, corner: 'BOTTOM_RIGHT', size: 2, opacity: 1, stamp: 0, font: 'RUBIK', color: -1, look: 'SHADOW', weight: 0, round: 50, imageData: logoUrl }) } }
      await p.goto(url)
      await p.waitForTimeout(800)
      await p.evaluate(async (row) => {
        await new Promise((r) => setTimeout(r, 300))
        const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /templates/.test(n))
        const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
        await new Promise((res) => { const t = db.transaction('user', 'readwrite'); t.objectStore('user').put({ json: row, at: Date.now() }, row.id); t.oncomplete = res })
        db.close()
      }, SEED)
      await p.reload()
      await p.waitForTimeout(800)
      await p.setInputFiles('.hero input[type=file]', video)
      await p.waitForSelector('.ready .tpl-card[data-tpl="tplogo"]')
      if (await p.$('.tpl-edit-chosen')) fail('an Edit button with no template chosen')
      await p.click('.tpl-card[data-tpl="tplogo"]')
      await p.waitForSelector('.tpl-edit-chosen')
      // The chosen template's "Edit" opens the editor: a change, then Esc, asks; "Keep editing" keeps it all.
      await p.click('.tpl-edit-chosen')
      await p.waitForSelector('.tpl-sheet .tpl-name-in')
      await p.fill('.tpl-name-in', 'עם לוגו חדש')
      await p.keyboard.press('Escape')
      await p.waitForSelector('.tpl-leave')
      await p.screenshot({ path: `${out}/2_leave.png` })
      if (!(await p.$('.tpl-sheet'))) fail('the editor closed under the question')
      await p.click('.tpl-leave-keep')
      if (await p.$('.tpl-leave')) fail('the question stays after "keep editing"')
      if ((await p.inputValue('.tpl-name-in')) !== 'עם לוגו חדש') fail('the change was lost by "keep editing"')
      // A click on the backdrop asks too; Esc answers it as "keep editing".
      await p.mouse.click(5, 5)
      await p.waitForSelector('.tpl-leave')
      await p.keyboard.press('Escape')
      await p.waitForFunction(() => !document.querySelector('.tpl-leave'))
      if (!(await p.$('.tpl-sheet'))) fail('Esc on the question closed the editor')
      // Cancel asks as well, with changes.
      await p.click('.tpl-sheet .btn.ghost:has-text("ביטול")')
      await p.waitForSelector('.tpl-leave')
      await p.click('.tpl-leave-keep')
      // "Discard changes" closes and nothing is saved.
      await p.click('.tpl-x')
      await p.waitForSelector('.tpl-leave')
      await p.click('.tpl-leave-discard')
      await p.waitForSelector('.tpl-sheet', { state: 'detached' })
      if ((await p.textContent('.tpl-card[data-tpl="tplogo"] .tpl-name')).trim() !== 'עם לוגו') fail('a discarded change was saved')
      // No change: it closes at once.
      await p.click('.tpl-edit-chosen')
      await p.waitForSelector('.tpl-sheet')
      await p.waitForTimeout(400)
      await p.keyboard.press('Escape')
      await p.waitForSelector('.tpl-sheet', { state: 'detached', timeout: 3000 })
      // "Save" in the question saves, and the screen takes the template again.
      await p.click('.tpl-edit-chosen')
      await p.waitForSelector('.tpl-sheet .tpl-name-in')
      await p.fill('.tpl-name-in', 'עם לוגו חדש')
      await p.mouse.click(5, 5)
      await p.waitForSelector('.tpl-leave')
      await p.click('.tpl-leave-save')
      await p.waitForSelector('.tpl-sheet', { state: 'detached' })
      await p.waitForFunction(() => document.querySelector('.tpl-card[data-tpl="tplogo"] .tpl-name')?.textContent.trim() === 'עם לוגו חדש')
      if (!(await p.$('.tpl-card.on[data-tpl="tplogo"]'))) fail('the template is not chosen after saving')
      // The template (no face blur) opens the editor, with its logo shown.
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs', { timeout: 30000 })
      await p.waitForTimeout(1500)
      const red = await p.evaluate(() => { const c = document.querySelectorAll('.view canvas')[2]; const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0; for (let i = 0; i < d.length; i += 4) if (d[i] > 150 && d[i + 1] < 90 && d[i + 3] > 150) n++; return n })
      if (red < 200) fail("the template's logo is not shown in the editor: " + red)
      await p.context().close()
    }

    // ---- the batch's setup has the same "Edit", and the settings' editor asks too
    {
      const p = (cur = await newPage())
      await p.goto(url)
      await p.waitForTimeout(800)
      await p.click('.settings-btn')
      await p.waitForSelector('.tpl-item[data-tpl="look.clean"]')
      await p.click('.tpl-item[data-tpl="look.clean"] .tpl-edit')
      await p.waitForSelector('.tpl-sheet .tpl-strength')
      await p.fill('.tpl-strength', '0.15')
      await p.mouse.click(5, 5)
      await p.waitForSelector('.tpl-leave')
      await p.click('.tpl-leave-discard')
      await p.waitForSelector('.tpl-sheet', { state: 'detached' })
      if (await p.$('.tpl-item[data-tpl="look.clean"] .tpl-restore-row')) fail('a discarded edit was kept')
      await p.goto(url)
      await p.waitForTimeout(800)
      await p.click('.batch-entry .several, .several >> nth=0').catch(() => {})
      if (!(await p.$('.batch-setup'))) await p.click('text=כמה סרטונים').catch(() => {})
      await p.waitForSelector('.batch-setup', { timeout: 5000 })
      await p.waitForSelector('.tpl-card[data-tpl="look.clean"]')
      await p.click('.tpl-card[data-tpl="look.clean"]')
      await p.click('.tpl-edit-chosen')
      await p.waitForSelector('.tpl-sheet .tpl-strength')
      await p.fill('.tpl-strength', '0.15')
      await p.click('.tpl-save')
      await p.waitForSelector('.tpl-sheet', { state: 'detached' })
      await p.waitForSelector('.tpl-card.on[data-tpl="look.clean"]')
      await p.context().close()
    }

    // ---- the safe zone: only for a 9:16 frame
    {
      const p = (cur = await newPage())
      await p.goto(url)
      await p.waitForTimeout(800)
      await tool(p, 0, video)
      if (await p.$('.safe-toggle')) fail('the safe zone switch shows on a landscape video')
      if (await p.$('.safe-float')) fail('the chooser is there on a landscape video')
      // A vertical export (9:16 in the Picture group) brings it.
      await p.click('.tabs button[data-g=picture]')
      await p.click('.frame-aspect button >> nth=1')
      await p.waitForSelector('.safe-toggle')
      await p.click('.frame-aspect button >> nth=2')
      await p.waitForSelector('.safe-toggle', { state: 'detached' })
      await p.click('.frame-aspect button >> nth=1')
      await p.waitForSelector('.safe-toggle')
      // Off: nothing over the video (the 9:16 frame's shade aside) and no chooser.
      const off = await overlay(p)
      if ((await p.getAttribute('.safe-toggle', 'aria-pressed')) !== 'false') fail('the switch is on from the start')
      if (await p.isVisible('.safe-pill')) fail('the chooser shows with the switch off')
      const sized = () => p.evaluate(() => { const r = (s) => { const k = document.querySelector(s).getBoundingClientRect(); return [Math.round(k.width), Math.round(k.height)] }; return { screen: r('.screen'), view: r('.screen .view') } })
      const before = await sized()
      await p.click('.safe-toggle')
      await p.waitForSelector('.safe-float.open .safe-pill', { state: 'visible' })
      await p.waitForTimeout(400)
      // The chooser floats over the video: the video keeps its whole size (no strip above it).
      const after = await sized()
      if (JSON.stringify(before) !== JSON.stringify(after)) fail(`the video changed size with the chooser: ${JSON.stringify(before)} -> ${JSON.stringify(after)}`)
      const on = await overlay(p)
      if (!(on.n > off.n + 200)) fail(`the mock is not drawn: ${off.n} -> ${on.n}`)
      await p.screenshot({ path: `${out}/3_safe_tiktok.png` })
      p.safe = { tiktok: on }
      for (const plat of ['reels', 'shorts']) {
        await p.click(`.safe-seg[data-platform=${plat}]`)
        await p.waitForTimeout(500)
        if ((await p.getAttribute(`.safe-seg[data-platform=${plat}]`, 'aria-checked')) !== 'true') fail('the platform was not chosen: ' + plat)
        const o = await overlay(p)
        if (o.sig === p.safe.tiktok.sig || o.sig === (p.safe.reels?.sig ?? -1)) fail('the mock of ' + plat + ' looks like another platform\'s')
        p.safe[plat] = o
        await p.screenshot({ path: `${out}/3_safe_${plat}.png` })
      }
      // The pill is a small one over the top of the video (inside its box, near its top edge, centred on it), three icon buttons (a logo each, no text).
      const geo = await p.evaluate(() => {
        const r = (s) => document.querySelector(s).getBoundingClientRect()
        const view = r('.screen .view'), pill = r('.safe-pill')
        const segs = [...document.querySelectorAll('.safe-seg')].map((e) => ({ svg: !!e.querySelector('svg path'), text: e.textContent.trim(), h: e.getBoundingClientRect().height, label: e.getAttribute('aria-label') }))
        const hi = r('.safe-hi'), chosen = r('.safe-seg.on')
        return { view: [view.left, view.top, view.right, view.bottom], pill: [pill.left, pill.top, pill.right, pill.bottom], pillW: pill.width, segs, hiX: hi.left + hi.width / 2, onX: chosen.left + chosen.width / 2, stripGone: !document.querySelector('.safe-strip') }
      })
      console.log('geometry:', JSON.stringify(geo))
      if (!geo.stripGone) fail('the old strip is still there')
      if (geo.pill[0] < geo.view[0] - 1 || geo.pill[2] > geo.view[2] + 1 || geo.pill[1] < geo.view[1] - 1 || geo.pill[3] > geo.view[1] + 60) fail('the pill is not over the top of the video: ' + JSON.stringify([geo.pill, geo.view]))
      if (geo.pillW > 123 || geo.pillW < 90) fail('the pill is not small: ' + geo.pillW)
      if (Math.abs((geo.pill[0] + geo.pill[2]) / 2 - (geo.view[0] + geo.view[2]) / 2) > 2) fail('the pill is not centred on the video')
      if (geo.segs.length !== 3 || geo.segs.some((s) => !s.svg || s.text !== '' || !s.label)) fail('the chooser buttons are not three bare logos: ' + JSON.stringify(geo.segs))
      if (Math.abs(geo.hiX - geo.onX) > 2) fail('the highlight is not under the chosen logo')
      // A tap on the pill does not play or select on the video under it.
      const t0 = await p.evaluate(() => document.querySelector('video').paused)
      await p.click('.safe-seg[data-platform=tiktok]')
      if ((await p.evaluate(() => document.querySelector('video').paused)) !== t0) fail('a tap on the chooser played / paused the video')
      // The mock's own top row (its tabs) is below the pill, not under it: the top rows of the overlay, in the pill's strip, hold nothing but the search / menu icons at the far side.
      const clash = await p.evaluate(() => {
        const c = document.querySelectorAll('.view canvas')[2], v = document.querySelector('.screen .view').getBoundingClientRect(), pl = document.querySelector('.safe-pill').getBoundingClientRect()
        const k = c.width / v.width
        const x0 = Math.floor((pl.left - v.left) * k), x1 = Math.ceil((pl.right - v.left) * k), y1 = Math.floor((pl.bottom - v.top) * k)
        const d = c.getContext('2d').getImageData(x0, 0, x1 - x0, y1).data
        let bright = 0
        for (let i = 0; i < d.length; i += 4) if (d[i + 3] > 200 && d[i] > 200 && d[i + 1] > 200) bright++
        return bright
      })
      if (clash > 30) fail('the mock draws its own top UI under the pill (' + clash + ' bright pixels)')
      // The mock is only drawn in the editor: the export's code does not have it.
      const dist = path.join(__dirname, '..', 'dist', 'assets')
      const src = (re) => fs.readdirSync(dist).filter((f) => re.test(f)).map((f) => fs.readFileSync(path.join(dist, f), 'utf8')).join('')
      if (src(/^export\.worker.*\.js$/).includes('@username')) fail('the safe zone mock is in the export worker')
      if (!src(/^index-.*\.js$/).includes('@username')) fail('the mock is not in the editor')
      // Stays on after the chooser is out of the way: closing it is not turning the zone off (the overlay stays until the switch is).
      if ((await p.getAttribute('.safe-toggle', 'aria-pressed')) !== 'true') fail('the switch lost its state')
      // Off again: the mock and the chooser go.
      await p.click('.safe-toggle')
      await p.waitForFunction(() => !document.querySelector('.safe-float')?.classList.contains('open'))
      await p.waitForTimeout(900)
      const off2 = await overlay(p)
      if (!(off2.n < on.n - 200)) fail(`the mock stays with the switch off: ${off.n} / ${on.n} / ${off2.n}`)
      await p.context().close()
    }

    // ---- the safe zone stays on, per project: kept with the project, back as it was when it is opened again (and never for another project)
    {
      const p = (cur = await newPage())
      await p.goto(url)
      await p.waitForTimeout(800)
      await tool(p, 0, portrait)
      await p.waitForSelector('.safe-toggle')
      await p.click('.safe-toggle')
      await p.click('.safe-seg[data-platform=shorts]')
      await p.waitForTimeout(2600)
      await p.click('.editor .btn.ghost >> nth=0')
      await p.waitForSelector('.project', { timeout: 10000 })
      await p.click('.project-open')
      await p.waitForSelector('.editor .tabs', { timeout: 30000 })
      await p.waitForSelector('.safe-toggle')
      if ((await p.getAttribute('.safe-toggle', 'aria-pressed')) !== 'true') fail('the safe zone was not kept with the project')
      if ((await p.getAttribute('.safe-seg[data-platform=shorts]', 'aria-checked')) !== 'true') fail('the platform was not kept with the project')
      await p.waitForSelector('.safe-float.open .safe-pill', { state: 'visible' })
      await p.waitForTimeout(600)
      if ((await overlay(p)).n < 200) fail('no mock over the reopened project')
      // Turned off, kept off.
      await p.click('.safe-toggle')
      await p.waitForTimeout(2600)
      await p.click('.editor .btn.ghost >> nth=0')
      await p.waitForSelector('.project', { timeout: 10000 })
      await p.click('.project-open')
      await p.waitForSelector('.safe-toggle')
      if ((await p.getAttribute('.safe-toggle', 'aria-pressed')) !== 'false') fail('the safe zone came back on after it was turned off')
      await p.context().close()
    }

    // ---- a video shot at 9:16 has it as it is; a phone's strip gives way to an open panel, the overlay stays
    {
      const p = (cur = await newPage(PHONE))
      await p.goto(url)
      await p.waitForTimeout(800)
      await tool(p, 0, portrait)
      await p.waitForSelector('.safe-toggle')
      await p.tap('.panel-close')
      const side = () => p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
      const topBar = await p.evaluate(() => [...document.querySelectorAll('.top-row button')].map((e) => { const r = e.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right)] }))
      if (topBar.some(([l, r]) => l < -1 || r > 394)) fail('the top bar overflows with the switch: ' + JSON.stringify(topBar))
      await p.tap('.safe-toggle')
      await p.waitForSelector('.safe-float.open .safe-pill', { state: 'visible' })
      if ((await side()) > 1) fail('sideways with the chooser')
      await p.waitForTimeout(500)
      const o = await overlay(p)
      if (o.n < 200) fail('no mock over the portrait video')
      await p.tap('.safe-seg[data-platform=shorts]')
      await p.waitForTimeout(400)
      await p.screenshot({ path: `${out}/4_phone_safe.png` })
      // A panel open in the timeline's place: the chooser is hidden, the zone stays on (for the platform chosen last) and the switch still says so.
      await p.tap('.tabs button[data-g=sound]')
      await p.waitForFunction(() => getComputedStyle(document.querySelector('.safe-float')).display === 'none')
      const o2 = await overlay(p)
      if (o2.n < 200) fail('the mock went with the chooser')
      if ((await p.getAttribute('.safe-toggle', 'aria-pressed')) !== 'true') fail('the switch lost its state with the panel open')
      await p.waitForTimeout(300)
      await p.screenshot({ path: `${out}/4_phone_panel.png` })

      // ---- the thin scrubber over the phone's open panel
      await p.waitForSelector('.panel:not([hidden]) .mini-scrubber')
      const t0 = await p.evaluate(() => document.querySelector('video').currentTime)
      await p.fill('.mini-scrubber', '6')
      await p.waitForTimeout(600)
      const t1 = await p.evaluate(() => document.querySelector('video').currentTime)
      if (Math.abs(t1 - 6) > 0.3) fail(`the scrubber did not move the video: ${t0} -> ${t1}`)
      if (!(await p.$('.panel:not([hidden])'))) fail('the panel closed with the scrubber')
      const val = await p.inputValue('.mini-scrubber')
      if (Math.abs(Number(val) - 6) > 0.4) fail('the scrubber does not show the time: ' + val)
      // It follows the video too (a seek from elsewhere), and a tap on it pauses a playing video.
      await p.evaluate(() => { document.querySelector('video').currentTime = 3 })
      await p.waitForTimeout(500)
      if (Math.abs(Number(await p.inputValue('.mini-scrubber')) - 3) > 0.4) fail('the scrubber did not follow the video')
      const box = await p.locator('.mini-scrubber').boundingBox()
      if (box.height < 20 || box.width < 300) fail('the scrubber is not usable: ' + JSON.stringify(box))
      if ((await side()) > 1) fail('sideways with the scrubber')
      // Closed: the timeline is back, and the scrubber is gone.
      await p.tap('.panel-close')
      await p.waitForTimeout(300)
      if (await p.$('.mini-scrubber')) fail('the scrubber stays with the panel closed')
      await p.context().close()
    }
    // A tablet and a desktop have the timeline always in view: no scrubber.
    for (const [name, opts] of [['tablet', TABLET], ['desktop', {}]]) {
      const p = (cur = await newPage(opts))
      await p.goto(url)
      await p.waitForTimeout(800)
      await tool(p, 0, video)
      if (await p.$('.mini-scrubber')) fail(`a scrubber on a ${name}`)
      await p.context().close()
    }
    console.log('PASS')
  } catch (e) {
    await cur?.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log((cur?.logs ?? []).filter((l) => !/adapters|GL Driver/.test(l)).slice(-12).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
