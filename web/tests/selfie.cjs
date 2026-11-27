// Selfie detection (a talking head: MediaPipe's BlazeFace on every frame), in the browser:
//  - the ready screen: four modes, selfie's warning (Hebrew; English on a phone), kept in view with the settings folded, the summary line;
//  - a run on two talking-head clips (one stored sideways, like a phone's): the model finds a face on (nearly) every frame,
//    tracks them, is not reduced, and the notice stays in the editor (Hebrew; English on a phone as a pill over the video);
//    the time of the pass against normal detection's on the same clip;
//  - "blur and save straight" stops at the check screen, with the warning, and saves nothing;
//  - the template editor's mode kept in the template (selfie: true, sensitive and fast false for older versions) and chosen again from it;
//  - a higher sensitivity on a clip with small faces: extra detections found, no fewer face tracks than the default.
// Run: node tests/selfie.cjs <url> <talking head> <talking head, stored sideways> <clip with small faces> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, head, sideways, crowd, out] = process.argv
const WARN_HE = 'מצב סלפי מהיר מאוד, אבל מיועד לאדם אחד קרוב למצלמה. פנים קטנות או רחוקות ברקע עלולות לא להיטשטש. צפו בתוצאה לפני שמשתפים.'
const WARN_EN = "Selfie mode is very fast, but it's made for one person close to the camera. Small or distant faces in the background may not be blurred. Watch the result before you share it."
const PILL_EN = 'Selfie mode: small or distant faces in the background may not be blurred. Watch the result.'

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const logs = []
  const fail = (m) => { throw new Error(m) }
  try {
    const page = async (locale, phone) => {
      const p = await (await b.newContext(phone ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale } : { viewport: { width: 1366, height: 860 }, locale })).newPage()
      p.results = []
      p.selfie = []
      p.on('console', (m) => {
        logs.push(`[${m.type()}] ${m.text()}`)
        if (m.text().startsWith('ONIT analyze: result ')) p.results.push(m.text())
        if (m.text().startsWith('ONIT analyze: selfie frames ')) p.selfie.push(m.text())
      })
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      await p.goto(url)
      return p
    }
    const ready = async (p, file, mode, sens = 0) => {
      await p.setInputFiles('.hero input[type=file]', file)
      await p.waitForSelector('.ready')
      await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
      if (!(await p.$('.detect-controls'))) await p.click('.blur-settings')
      await p.click(`.detect-mode .mode-${mode}`)
      await p.evaluate((v) => {
        const r = document.querySelector('input.sensitivity')
        r.value = String(v)
        r.dispatchEvent(new Event('input', { bubbles: true }))
      }, sens)
    }
    /** The pass's result line (mode, tracks, reduced, sensitivity, extra) and, for selfie, its frame counts. */
    const result = (p) => {
      const line = p.results[p.results.length - 1] ?? fail('no result line')
      const m = /tracks (\d+), reduced (\w+), .*; D ([\d.]+)\/s.*; mode (\w+), sensitivity ([\d.]+), extra (\d+)$/.exec(line) ?? fail('result line: ' + line)
      const s = /selfie frames (\d+), with a face (\d+), most faces on a frame (\d+), detect avg ([\d.]+) ms/.exec(p.selfie[p.selfie.length - 1] ?? '')
      return { tracks: Number(m[1]), reduced: m[2] === 'true', rate: Number(m[3]), mode: m[4], sensitivity: Number(m[5]), extra: Number(m[6]), frames: s && Number(s[1]), withFace: s && Number(s[2]), most: s && Number(s[3]), avgMs: s && Number(s[4]), line }
    }
    /** Starts the analysis and waits for the editor; the time it took (ms). */
    const run = async (p) => {
      const t0 = Date.now()
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs, .no-faces', { timeout: 600000 })
      return Date.now() - t0
    }

    // ---- the ready screen (Hebrew): four modes, selfie's warning, the summary
    let p = await page('he-IL')
    await ready(p, head, 'normal')
    let ui = await p.evaluate(() => ({
      modes: [...document.querySelectorAll('.detect-mode button')].map((e) => [e.textContent.trim(), e.getAttribute('aria-checked')]),
      warning: document.querySelector('.selfie-warning')?.textContent.trim() ?? null,
      fastWarning: document.querySelector('.fast-warning')?.textContent.trim() ?? null,
    }))
    if (JSON.stringify(ui.modes) !== JSON.stringify([['מהיר', 'false'], ['רגיל', 'true'], ['מורחב', 'false'], ['סלפי', 'false']]) || ui.warning !== null || ui.fastWarning !== null) fail('the ready screen, normal: ' + JSON.stringify(ui))
    await p.click('.detect-mode .mode-selfie')
    ui = await p.evaluate(() => ({
      on: document.querySelector('.detect-mode .mode-selfie').getAttribute('aria-checked'),
      warning: document.querySelector('.selfie-warning')?.textContent.trim() ?? null,
      summary: document.querySelector('.fold-summary')?.textContent.trim(),
    }))
    if (ui.on !== 'true' || ui.warning !== WARN_HE || !ui.summary.endsWith('זיהוי סלפי')) fail('the ready screen, selfie: ' + JSON.stringify(ui))
    await p.screenshot({ path: `${out}/0_ready_selfie.png`, fullPage: true })
    await p.click('.blur-settings')
    if ((await p.evaluate(() => document.querySelector('.selfie-warning-folded')?.textContent.trim())) !== WARN_HE) fail('the selfie warning with the settings folded')
    await p.click('.blur-settings')
    console.log('ready screen:', ui.summary)

    // ---- a selfie run on a talking head: a face on every frame, not reduced; the notice stays in the editor
    const tSelfie = await run(p)
    let r = result(p)
    console.log(`selfie run: ${r.line}`)
    if (r.mode !== 'selfie' || r.reduced || r.sensitivity !== 0 || r.extra !== 0) fail('a selfie run: ' + r.line)
    if (!r.frames || r.withFace < 0.98 * r.frames) fail(`a face on only ${r.withFace} of ${r.frames} frames`)
    if (r.tracks < 1 || r.tracks > 3) fail('tracks: ' + r.tracks)
    let notice = await p.evaluate(() => document.querySelector('.detect-selfie')?.textContent.trim() ?? null)
    if (notice !== WARN_HE || (await p.$('.detect-reduced')) || (await p.$('.detect-fast'))) fail('the editor after a selfie run: ' + notice)
    await p.click('.tabs button[data-g=sound]')
    await p.waitForTimeout(300)
    if ((await p.evaluate(() => document.querySelector('.detect-selfie')?.textContent.trim())) !== WARN_HE) fail('the selfie notice went away with a tab')
    await p.screenshot({ path: `${out}/1_editor_selfie.png` })
    console.log(`selfie: ${r.withFace}/${r.frames} frames with a face, ${r.tracks} tracks, ${r.avgMs} ms a detection, ${(1000 / r.avgMs).toFixed(0)} detections/s, ${(tSelfie / 1000).toFixed(1)} s from the tap to the editor`)

    // ---- normal detection on the same clip: slower, no selfie notice
    p = await page('he-IL')
    await ready(p, head, 'normal')
    const tNormal = await run(p)
    const n = result(p)
    if (n.mode !== 'normal' || (await p.$('.detect-selfie'))) fail('a normal run: ' + n.line)
    console.log(`normal: ${(tNormal / 1000).toFixed(1)} s from the tap to the editor (selfie ${(tSelfie / 1000).toFixed(1)} s), ${n.tracks} tracks`)

    // ---- English on a phone, a clip stored sideways: the warning, then the notice as a pill over the video
    p = await page('en-US', true)
    await ready(p, sideways, 'selfie')
    if ((await p.evaluate(() => document.querySelector('.selfie-warning')?.textContent.trim())) !== WARN_EN) fail('the English warning')
    if (await p.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)) fail('the ready screen scrolls sideways on a phone')
    await p.screenshot({ path: `${out}/2_ready_phone_en.png`, fullPage: true })
    await run(p)
    r = result(p)
    if (r.mode !== 'selfie' || !r.frames || r.withFace < 0.98 * r.frames || r.tracks < 1) fail('the sideways clip: ' + JSON.stringify(r))
    const pill = await p.evaluate(() => document.querySelector('.detect-selfie-pill')?.textContent.trim() ?? null)
    if (pill !== PILL_EN) fail('the English pill: ' + pill)
    await p.screenshot({ path: `${out}/3_editor_phone_en.png` })
    console.log(`phone en, sideways: ${r.withFace}/${r.frames} frames with a face, ${r.tracks} tracks, pill`)

    // ---- "blur and save straight" in selfie mode: stops at the check screen, with the warning, and saves nothing
    p = await page('he-IL')
    await ready(p, head, 'selfie')
    await p.click('.blur-save')
    await p.click('.export-go')
    await p.waitForSelector('.check-faces', { timeout: 600000 })
    const check = await p.evaluate(() => ({ notice: document.querySelector('.check-faces .detect-selfie')?.textContent.trim(), exporting: !!document.querySelector('.result, .live') }))
    if (check.notice !== WARN_HE || check.exporting) fail('the check screen after a selfie run: ' + JSON.stringify(check))
    await p.screenshot({ path: `${out}/4_check_selfie.png` })
    console.log('save straight: the check screen')

    // ---- the template editor: selfie kept in the template's file (older versions read normal), chosen again from it
    p = await page('he-IL')
    await p.waitForTimeout(600)
    await p.click('.settings-btn')
    await p.waitForSelector('.tpl-item[data-tpl="look.clean"]')
    await p.click('.tpl-item[data-tpl="look.clean"] .tpl-edit')
    await p.waitForSelector('.tpl-sheet .tpl-detect')
    await p.click('.tpl-detect .mode-selfie')
    if ((await p.evaluate(() => document.querySelector('.tpl-detect .selfie-warning')?.textContent.trim())) !== WARN_HE) fail('the template editor has no selfie warning')
    await p.click('.tpl-save')
    await p.waitForSelector('.tpl-sheet', { state: 'detached' })
    const stored = async () => {
      for (let i = 0; i < 100; i++) {
        const j = await p.evaluate(() => new Promise((resolve) => indexedDB.databases().then((dbs) => { const r = indexedDB.open(dbs.find((x) => x.name.endsWith('-templates')).name); r.onsuccess = () => { const g = r.result.transaction('builtin').objectStore('builtin').get('look.clean'); g.onsuccess = () => { r.result.close(); resolve(g.result?.json ?? null) } } })))
        if (j) return j
        await p.waitForTimeout(100)
      }
      return fail('the template was not stored')
    }
    const json = await stored()
    if (json.blur.selfie !== true || json.blur.sensitive !== false || json.blur.fast !== false) fail('the stored template: ' + JSON.stringify(json.blur))
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.setInputFiles('.hero input[type=file]', head)
    await p.waitForSelector('.ready .tpl-card')
    await p.click('.tpl-card[data-tpl="look.clean"]')
    if (!(await p.$('.detect-controls'))) await p.click('.blur-settings')
    const chosen = await p.evaluate(() => ({ on: document.querySelector('.detect-mode .mode-selfie')?.getAttribute('aria-checked'), summary: document.querySelector('.fold-summary')?.textContent.trim() }))
    if (chosen.on !== 'true' || !chosen.summary.endsWith('זיהוי סלפי')) fail('the template did not bring selfie back: ' + JSON.stringify(chosen))
    // Another mode chosen in the template editor clears it (the key is not left behind).
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.settings-btn')
    await p.click('.tpl-item[data-tpl="look.clean"] .tpl-edit')
    await p.waitForSelector('.tpl-sheet .tpl-detect')
    await p.click('.tpl-detect .mode-fast')
    await p.click('.tpl-save')
    await p.waitForSelector('.tpl-sheet', { state: 'detached' })
    await p.waitForTimeout(300)
    const json2 = await stored()
    if (json2.blur.selfie !== undefined || json2.blur.fast !== true) fail('the template after fast: ' + JSON.stringify(json2.blur))
    console.log('template: selfie kept, brought back, cleared by another mode')

    // ---- the sensitivity on a clip with small faces: extra detections, no fewer face tracks than the default
    const counts = []
    for (const s of [0, 1]) {
      p = await page('he-IL')
      await ready(p, crowd, 'selfie', s)
      await run(p)
      const c = result(p)
      if (c.sensitivity !== s || c.mode !== 'selfie') fail('sensitivity run: ' + c.line)
      counts.push(c)
    }
    if (counts[1].tracks < counts[0].tracks) fail(`sensitivity 1 found fewer faces: ${counts[1].tracks} < ${counts[0].tracks}`)
    if (counts[0].extra !== 0 || counts[1].extra === 0) fail(`extra detections: ${counts[0].extra} at 0, ${counts[1].extra} at 1`)
    console.log(`sensitivity: ${counts[0].tracks} tracks at 0, ${counts[1].tracks} at 1 (${counts[1].extra} extra detections)`)
    console.log('selfie: ok')
  } catch (e) {
    console.error(logs.slice(-40).join('\n'))
    console.error(e)
    process.exitCode = 1
  } finally {
    fs.writeFileSync(`${out}/log.txt`, logs.join('\n'))
    await b.close()
  }
})()
