// The web app's notes from a phone: a template renamed where it is picked (and kept); a template
// with no face blur never starts face detection (one video from the ready screen, and a batch;
// the queue never says it's finding faces); the home screen's "several videos" apart from the
// single video; the template editor's subtitle preview moving (and still with less motion asked
// for); a template's subtitle font drawn in the editor, and kept when a template is saved from
// a video that was opened with no face search.
// Run: node tests/usernotes.cjs <url> <video with sound> <batch video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, video2, out] = process.argv

// A user's template, as the app stores it (blur: none; subtitles in Karantina with a typewriter).
const SEED = { v: 1, id: 'unotes', name: 'התבנית שלי', group: 'USER', blur: { none: true }, subs: { create: false, burn: 'ORIGINAL', style: JSON.stringify({ font: 'KARANTINA', size: 1.5, text: -1, accent: -1, outline: 0.1, outlineColor: -16777216, shadow: true, glow: false, box: false, boxColor: 0, effect: 'TYPEWRITER', position: 0.5 }) } }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/a.srt`, '1\n00:00:01,000 --> 00:00:05,000\nשלום לכולם היום\n')
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const fail = (m) => { throw new Error(m) }
  const newPage = async (opts = {}) => {
    const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', ...opts })
    const p = await ctx.newPage()
    p.logs = []
    p.workers = []
    p.on('console', (m) => p.logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => p.logs.push(`[pageerror] ${e.message}`))
    p.on('worker', (w) => p.workers.push(w.url()))
    await p.addInitScript(() => {
      const d = Object.getOwnPropertyDescriptor(CanvasRenderingContext2D.prototype, 'font')
      window.__fonts = []
      Object.defineProperty(CanvasRenderingContext2D.prototype, 'font', { set(v) { window.__fonts.push(v); d.set.call(this, v) }, get() { return d.get.call(this) } })
    })
    return p
  }
  const seed = (p) => p.evaluate(async (row) => {
    const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /templates/.test(n))
    const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    await new Promise((res) => { const t = db.transaction('user', 'readwrite'); t.objectStore('user').put({ json: row, at: Date.now() }, row.id); t.oncomplete = res })
    db.close()
  }, SEED)
  const stored = (p) => p.evaluate(async () => {
    const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /templates/.test(n))
    const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    const rows = await new Promise((res) => { const g = db.transaction('user').objectStore('user').getAll(); g.onsuccess = () => res(g.result) })
    db.close()
    return rows.map((r) => r.json)
  })
  const home = async (p) => { await p.goto(url); await p.waitForTimeout(800); await seed(p); await p.reload(); await p.waitForTimeout(800) }
  const ready = async (p, file) => { await p.setInputFiles('.hero input[type=file]', file); await p.waitForSelector('.ready .tpl-card') }
  const pageLogs = (p) => console.log(p.logs.filter((l) => !/adapters|GL Driver/.test(l)).slice(-12).join('\n'))
  let cur
  try {
    // ---- the home screen: one video and several are apart
    for (const [w, h] of [[1366, 860], [390, 844]]) {
      const p = (cur = await newPage({ viewport: { width: w, height: h } }))
      await p.goto(url)
      await p.waitForTimeout(800)
      const h0 = await p.evaluate(() => ({
        inHero: !!document.querySelector('.hero .several'), entry: !!document.querySelector('.batch-entry .several'),
        heroPick: !!document.querySelector('.hero input[type=file]:not([multiple])'), heroBatchText: document.querySelector('.hero')?.textContent.includes('כמה סרטונים') ?? true,
        over: document.documentElement.scrollWidth > innerWidth,
      }))
      console.log('home', w, JSON.stringify(h0))
      if (h0.inHero || h0.heroBatchText || !h0.entry || !h0.heroPick) fail('the home screen: batch not apart from the single video ' + JSON.stringify(h0))
      if (h0.over) fail('the home screen is wider than the phone')
      if (w < 500) await p.screenshot({ path: `${out}/home_phone.png`, fullPage: true })
      await p.context().close()
    }

    // ---- a template renamed where it is picked, and kept
    let p = (cur = await newPage())
    await home(p)
    await ready(p, video)
    if (await p.$('.tpl-own')) fail('a rename row before a template of one’s own is picked')
    await p.click('.tpl-card[data-tpl="look.clean"]')
    if (await p.$('.tpl-own')) fail('a built-in look can be renamed here')
    await p.click('.tpl-card[data-tpl="unotes"]')
    await p.click('.tpl-own .tpl-rename')
    await p.fill('.tpl-rename-in', 'השם החדש')
    await p.click('.tpl-rename-save')
    await p.waitForFunction(() => document.querySelector('.tpl-card[data-tpl="unotes"] .tpl-name')?.textContent === 'השם החדש')
    if (!(await p.$('.tpl-card[data-tpl="unotes"].on'))) fail('the renamed template is no longer chosen')
    for (let i = 0; i < 50 && (await stored(p))[0]?.name !== 'השם החדש'; i++) await p.waitForTimeout(100)
    if ((await stored(p))[0]?.name !== 'השם החדש') fail('the new name is not in the browser’s storage')
    await p.reload()
    await p.waitForTimeout(800)
    await ready(p, video)
    if ((await p.textContent('.tpl-card[data-tpl="unotes"] .tpl-name')) !== 'השם החדש') fail('the new name was not kept')
    console.log('renamed in the picker and kept')

    // ---- no face blur: no face detection, from the ready screen
    p.workers.length = 0
    await p.click('.tpl-card[data-tpl="unotes"]')
    const label = await p.textContent('.blur-faces')
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 60000 })
    await p.waitForTimeout(1500)
    console.log('"' + label.trim() + '" → editor; workers:', p.workers.map((u) => u.split('/').pop().split('-')[0]).join(',') || 'none')
    if (p.workers.some((u) => u.includes('analyze.worker'))) fail('faces were looked for with a template that has no face blur')
    if (await p.$('.analyzing')) fail('the analyzing screen')
    // ---- the template's font in the editor (subtitles from an SRT: no speech model here)
    await p.click('.tabs button[data-g=subs]')
    await p.setInputFiles('.srt-import input', `${out}/a.srt`)
    await p.waitForSelector('.cue-list .clip')
    await p.evaluate(() => { window.__fonts.length = 0; const v = document.querySelector('video'); v.pause(); v.currentTime = 3 })
    await p.waitForTimeout(1500)
    const fonts = await p.evaluate(() => [...new Set(window.__fonts)])
    console.log('fonts drawn with:', fonts.join(' | '))
    if (!fonts.some((f) => f.includes('ONIT Karantina'))) fail('the template’s font is not drawn in the editor')
    await p.context().close()

    // ---- the same, as a batch: no detection, the queue never says it looks for faces
    p = (cur = await newPage())
    await home(p)
    await p.click('.several')
    await p.waitForSelector('.batch-setup')
    await p.setInputFiles('.batch-input', [video2])
    await p.waitForFunction(() => document.querySelectorAll('.batch-file').length === 1)
    if (await p.$('.batch-noblur')) fail('the no-blur line before a template is chosen')
    await p.click('.tpl-card[data-tpl="unotes"]')
    await p.waitForSelector('.batch-noblur')
    await p.evaluate(() => { window.__says = new Set(); new MutationObserver(() => document.querySelectorAll('.batch-status').forEach((e) => window.__says.add(e.textContent.replace(/[\d%· ]+$/, '')))).observe(document.body, { childList: true, subtree: true, characterData: true }) })
    p.workers.length = 0
    await p.click('.batch-start')
    await p.waitForFunction(() => document.querySelector('.batch-title')?.textContent.includes('הסתיים'), null, { timeout: 300000 })
    const says = await p.evaluate(() => [...window.__says])
    const status = await p.getAttribute('.batch-item', 'data-status')
    console.log('batch: status', status, '· said', JSON.stringify(says), '· workers', p.workers.map((u) => u.split('/').pop().split('-')[0]).join(','))
    if (status !== 'saved') fail('the batch item: ' + status)
    if (p.workers.some((u) => u.includes('analyze.worker'))) fail('faces were looked for in a batch with no face blur')
    if (says.some((s) => s.includes('מזהה פנים'))) fail('the queue says it finds faces: ' + says)
    if ((await p.textContent('.batch-pct')) !== '100%') fail('percent')
    await p.context().close()

    // ---- the subtitle preview in the template editor moves; still with less motion asked for
    for (const reduced of [false, true]) {
      p = (cur = await newPage({ reducedMotion: reduced ? 'reduce' : 'no-preference' }))
      await home(p)
      await p.click('.settings-btn')
      await p.click('.tpl-item[data-tpl="unotes"] .tpl-edit')
      await p.waitForSelector('.tpl-subs-preview')
      await p.waitForTimeout(300)
      const shots = []
      // (A loop is about 3.5 s: sampled over a whole one.)
      for (let i = 0; i < 16; i++) { shots.push(await p.evaluate(() => { const c = document.querySelector('.tpl-subs-preview'); const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let h = 0; for (let i = 0; i < d.length; i += 17) h = (h * 31 + d[i]) | 0; return h })); await p.waitForTimeout(250) }
      const distinct = new Set(shots).size
      console.log(reduced ? 'less motion' : 'motion', ': distinct pictures', distinct)
      if (!reduced && distinct < 2) fail('the subtitle preview does not move')
      if (reduced && distinct !== 1) fail('the subtitle preview moves with less motion asked for')
      if (!reduced) await p.screenshot({ path: `${out}/template_editor.png` })
      await p.context().close()
    }

    // ---- a template saved from a video opened with no face search keeps its subtitle design, and has no face blur
    p = (cur = await newPage())
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.click('.sub-style .preset >> nth=1')
    await p.waitForTimeout(300)
    await p.click('.save-template-btn')
    await p.waitForSelector('.save-template')
    if (!(await p.isChecked('.tpl-keep-subs input'))) fail('the subtitle design is not kept by default')
    await p.fill('.tpl-name-in', 'מהכלי')
    await p.click('.save-template-go')
    for (let i = 0; i < 50 && (await stored(p)).length === 0; i++) await p.waitForTimeout(100)
    const row = (await stored(p))[0]
    console.log('saved:', JSON.stringify({ name: row?.name, blur: row?.blur, font: row?.subs && JSON.parse(row.subs.style).font }))
    if (!row?.subs || JSON.parse(row.subs.style).font !== 'RUBIK') fail('the saved template has no subtitle design')
    // It leaves the face blur alone (never "off"): a video it is applied to still has its faces blurred.
    if (row.blur) fail('a template from a video with no face search has a face blur part: ' + JSON.stringify(row.blur))
    // Applied to a video with faces: the faces are still looked for (and blurred).
    await p.goto(url)
    await p.waitForTimeout(800)
    await seed(p)
    await p.reload()
    await p.waitForTimeout(800)
    await ready(p, video2)
    const tool = await p.$$eval('.tpl-card', (c) => c.map((x) => [x.dataset.tpl, x.textContent]))
    const toolId = tool.find(([, n]) => n.includes('מהכלי'))[0]
    await p.click(`.tpl-card[data-tpl="${toolId}"]`)
    if ((await p.textContent('.blur-faces')).includes('פתח בעורך')) fail('a template with no face blur part is treated as having none')
    p.workers.length = 0
    await p.click('.blur-faces')
    await p.waitForSelector('.analyzing, .editor .tabs', { timeout: 30000 })
    if (!p.workers.some((u) => u.includes('analyze.worker'))) fail('faces were not looked for with a template that leaves the face blur alone')
    // A template that knowingly has no face blur says so, and does not say "blur" on its buttons.
    await p.goto(url)
    await p.waitForTimeout(800)
    await ready(p, video2)
    await p.click('.tpl-card[data-tpl="unotes"]')
    const off = await p.evaluate(() => ({ badge: document.querySelector('.tpl-card[data-tpl="unotes"] .tpl-badge')?.textContent, go: document.querySelector('.blur-faces').textContent, save: document.querySelector('.blur-save').textContent }))
    console.log('no face blur:', JSON.stringify(off))
    if (off.badge !== 'בלי טשטוש פנים' || off.go.includes('טשטש') || off.save.includes('טשטש')) fail('a template with no face blur is not labelled so: ' + JSON.stringify(off))
    await p.context().close()
    console.log('OK')
  } catch (e) {
    if (cur) { await cur.screenshot({ path: `${out}/failure.png` }).catch(() => {}); pageLogs(cur) }
    console.error(e)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
