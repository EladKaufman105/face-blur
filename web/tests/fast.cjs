// Fast detection and the sensitivity, in the browser:
//  - the ready screen: three modes (fast's warning shows when it is picked), the sensitivity slider and the summary line;
//  - a fast run measured near 5 detections a second (not reduced), its notice kept in the editor (Hebrew; English on a
//    phone as a pill over the video), and "blur and save straight" stopping at the check screen;
//  - a higher sensitivity on a clip with distant faces (classroom, detection pinned at 320 px: many borderline faces): extra
//    detections found, no fewer face tracks than the default.
// Run: node tests/fast.cjs <url> <video> <video with distant faces> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, crowd, out] = process.argv
const WARN_HE = 'זיהוי מהיר בודק פחות פריימים, ולכן מהיר יותר. הוא עלול לפספס לרגע פנים קטנות, רחוקות או בקצה המסך. כדאי לצפות בתוצאה לפני שמשתפים.'
const PILL_EN = 'Fast detection: small or distant faces may be missed for a moment. Watch the result.'

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const logs = []
  const fail = (m) => { throw new Error(m) }
  try {
    const page = async (locale, phone) => {
      const p = await (await b.newContext(phone ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale } : { viewport: { width: 1366, height: 860 }, locale })).newPage()
      p.results = []
      p.on('console', (m) => {
        logs.push(`[${m.type()}] ${m.text()}`)
        if (m.text().startsWith('ONIT analyze: result ')) p.results.push(m.text())
      })
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      await p.goto(url)
      return p
    }
    /** The ready screen with [file], the blur settings open, [mode] and [sens] chosen. */
    const ready = async (p, file, mode, sens) => {
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
    /** The analysis's result line: mode, sensitivity, tracks, reduced and D (detections a second). */
    const result = async (p) => {
      await p.waitForFunction(() => true)
      const line = p.results[p.results.length - 1] ?? fail('no result line')
      const m = /tracks (\d+), reduced (\w+), .*; D ([\d.]+)\/s.*; mode (\w+), sensitivity ([\d.]+), extra (\d+)$/.exec(line) ?? fail('result line: ' + line)
      return { tracks: Number(m[1]), reduced: m[2] === 'true', rate: Number(m[3]), mode: m[4], sensitivity: Number(m[5]), extra: Number(m[6]), line }
    }

    // ---- the ready screen (Hebrew): three modes, fast's warning, the slider and the summary
    let p = await page('he-IL')
    await ready(p, video, 'normal', 0)
    let ui = await p.evaluate(() => ({
      modes: [...document.querySelectorAll('.detect-mode button')].map((e) => [e.textContent.trim(), e.getAttribute('aria-checked')]),
      warning: document.querySelector('.fast-warning')?.textContent.trim() ?? null,
      summary: document.querySelector('.fold-summary')?.textContent.trim(),
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }))
    if (JSON.stringify(ui.modes) !== JSON.stringify([['מהיר', 'false'], ['רגיל', 'true'], ['מורחב', 'false']]) || ui.warning !== null || !ui.summary.endsWith('זיהוי רגיל')) fail('the ready screen, normal: ' + JSON.stringify(ui))
    await p.click('.detect-mode .mode-fast')
    await p.evaluate(() => { const r = document.querySelector('input.sensitivity'); r.value = '0.4'; r.dispatchEvent(new Event('input', { bubbles: true })) })
    ui = await p.evaluate(() => ({ warning: document.querySelector('.fast-warning')?.textContent.trim() ?? null, summary: document.querySelector('.fold-summary')?.textContent.trim(), value: document.querySelector('.sensitivity-value')?.textContent.trim() }))
    if (ui.warning !== WARN_HE || !ui.summary.endsWith('זיהוי מהיר · רגישות 40%') || ui.value !== '40%') fail('the ready screen, fast at 40%: ' + JSON.stringify(ui))
    await p.screenshot({ path: `${out}/0_ready_fast.png`, fullPage: true })
    // Folded, the warning stays in view.
    await p.click('.blur-settings')
    if ((await p.evaluate(() => document.querySelector('.fast-warning-folded')?.textContent.trim())) !== WARN_HE) fail('the fast warning with the settings folded')
    await p.click('.blur-settings')
    console.log('ready screen:', ui.summary)

    // ---- a fast run: about 5 detections a second, not reduced; the notice stays in the editor
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 600000 })
    let r = await result(p)
    if (r.mode !== 'fast' || r.sensitivity !== 0.4 || r.reduced || r.rate < 4.5 || r.rate > 5.6) fail('a fast run: ' + r.line)
    let notice = await p.evaluate(() => document.querySelector('.detect-fast')?.textContent.trim() ?? null)
    if (notice !== WARN_HE || (await p.$('.detect-reduced'))) fail('the editor after a fast run: ' + notice)
    await p.click('.tabs button[data-g=sound]')
    await p.waitForTimeout(300)
    if ((await p.evaluate(() => document.querySelector('.detect-fast')?.textContent.trim())) !== WARN_HE) fail('the fast notice went away with a tab')
    await p.screenshot({ path: `${out}/1_editor_fast.png` })
    console.log(`fast run: ${r.rate}/s, ${r.tracks} tracks, notice kept`)

    // ---- a normal run of the same clip: its full rate, no fast notice
    p = await page('he-IL')
    await ready(p, video, 'normal', 0)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 600000 })
    const n = await result(p)
    // (On a slow machine normal detection may run reduced, down to 8 a second: still well over fast's 5.)
    if (n.mode !== 'normal' || n.sensitivity !== 0 || n.rate < 1.5 * r.rate || (await p.$('.detect-fast'))) fail('a normal run: ' + n.line)
    console.log(`normal run: ${n.rate}/s, ${n.tracks} tracks`)

    // ---- English on a phone: the fast notice as a pill over the video
    p = await page('en-US', true)
    await ready(p, video, 'fast', 0)
    if ((await p.evaluate(() => document.querySelector('.fast-warning')?.textContent.trim())) !== "Fast detection checks fewer frames, so it's quicker. It may briefly miss small or distant faces, or faces at the edge of the frame. Watch the result before you share it.") fail('the English warning')
    if (await p.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)) fail('the ready screen scrolls sideways on a phone')
    await p.screenshot({ path: `${out}/2_ready_phone_en.png`, fullPage: true })
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 600000 })
    const pill = await p.evaluate(() => document.querySelector('.detect-fast-pill')?.textContent.trim() ?? null)
    if (pill !== PILL_EN) fail('the English pill: ' + pill)
    await p.screenshot({ path: `${out}/3_editor_phone_en.png` })
    console.log('phone en: pill')
    // Fast and reduced (forced, as a device too slow would): both said, one under the other, over the video.
    p = await page('en-US', true)
    await p.goto(url + '&detect=yunet:1,side:960,reduced:1')
    await ready(p, video, 'fast', 0)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 600000 })
    const both = await p.evaluate(() => [...document.querySelectorAll('.detect-pills > div')].map((e) => e.className.split(' ')[0]))
    if (JSON.stringify(both) !== JSON.stringify(['detect-reduced', 'detect-fast'])) fail('fast and reduced on a phone: ' + JSON.stringify(both))
    await p.screenshot({ path: `${out}/3b_editor_phone_both.png` })
    console.log('phone: both notices')

    // ---- "blur and save straight" in fast mode: stops at the check screen, with the warning
    p = await page('he-IL')
    await ready(p, video, 'fast', 0)
    await p.click('.blur-save')
    await p.click('.export-go')
    await p.waitForSelector('.check-faces', { timeout: 600000 })
    const check = await p.evaluate(() => ({ notice: document.querySelector('.check-faces .detect-fast')?.textContent.trim(), exporting: !!document.querySelector('.result, .live') }))
    if (check.notice !== WARN_HE || check.exporting) fail('the check screen after a fast run: ' + JSON.stringify(check))
    await p.screenshot({ path: `${out}/4_check_fast.png` })
    console.log('save straight: the check screen')

    // ---- the sensitivity on a clip with distant faces: extra detections, no fewer face tracks than the default
    // Detection pinned (320 px, 15 a second, no pacing), so both runs detect the same frames whatever the machine's timing:
    // the default detections are then the same, and a sensitivity can only add. At 320 px the distant faces are borderline.
    const counts = []
    for (const s of [0, 1]) {
      p = await page('he-IL')
      await p.goto(url + '&detect=yunet:1,side:320,rate:15')
      await ready(p, crowd, 'normal', s)
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs, .no-faces', { timeout: 600000 })
      const c = await result(p)
      if (c.sensitivity !== s || c.mode !== 'normal') fail('sensitivity run: ' + c.line)
      counts.push(c)
    }
    if (counts[1].tracks < counts[0].tracks) fail(`sensitivity 1 found fewer faces: ${counts[1].tracks} < ${counts[0].tracks}`)
    if (counts[0].extra !== 0 || counts[1].extra === 0) fail(`extra detections: ${counts[0].extra} at 0, ${counts[1].extra} at 1`)
    console.log(`sensitivity: ${counts[0].tracks} tracks at 0, ${counts[1].tracks} at 1 (${counts[1].extra} extra detections)`)
    console.log('fast: ok')
  } catch (e) {
    console.error(logs.slice(-40).join('\n'))
    console.error(e)
    process.exitCode = 1
  } finally {
    fs.writeFileSync(`${out}/log.txt`, logs.join('\n'))
    await b.close()
  }
})()
