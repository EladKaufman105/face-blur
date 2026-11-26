// Detection that ran lighter than the app's is never silent: the editor (and the "no faces" screen)
// keep a notice for as long as the video is open, in Hebrew and in English; a full run has none.
// The reduced mode is forced with the page's ?detect=...,reduced:1 (a test's pace, the same code
// path as a device too slow for the full detection).
// Run: node tests/reduced.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, out] = process.argv
const HE = 'הזיהוי הופחת במכשיר הזה; כדאי לבדוק את הפנים.'
const EN = 'Detection reduced on this device; check faces.'

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const bars = `${out}/bars.mp4`
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24:duration=2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', bars])
  const b = await chromium.launch()
  const logs = []
  const fail = (m) => { throw new Error(m) }
  try {
    const open = async (locale, detect, file, selector) => {
      const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale })).newPage()
      p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      await p.goto(`${url}&detect=${detect}`)
      await p.setInputFiles('.hero input[type=file]', file)
      await p.waitForSelector('.ready')
      await p.click('.blur-faces')
      await p.waitForSelector('.editor, .no-faces, .card.error', { timeout: 600000 })
      if (!(await p.$(selector))) throw new Error(`wanted ${selector}, got ` + (await p.evaluate(() => document.querySelector('main')?.className)))
      return p
    }
    const notice = (p) => p.evaluate(() => document.querySelector('.detect-reduced')?.textContent?.trim() ?? null)

    // A full run (the app's size, YuNet on): no notice.
    let p = await open('he-IL', 'yunet:1,side:960,rate:1.5', video, '.editor')
    if ((await notice(p)) !== null) fail('a full detection shows the reduced notice')
    console.log('full: no notice')

    // Reduced, Hebrew: the notice stays while the editor is used.
    p = await open('he-IL', 'yunet:1,side:640,rate:1.5,reduced:1', video, '.editor')
    if ((await notice(p)) !== HE) fail('Hebrew notice: ' + (await notice(p)))
    await p.click('.tabs button[data-g=sound]')
    await p.waitForTimeout(500)
    if ((await notice(p)) !== HE) fail('the notice went away with a tab')
    await p.screenshot({ path: `${out}/1_reduced_he.png` })
    console.log('reduced he:', await notice(p))

    // English.
    p = await open('en-US', 'yunet:1,side:640,rate:1.5,reduced:1', video, '.editor')
    if ((await notice(p)) !== EN) fail('English notice: ' + (await notice(p)))
    await p.screenshot({ path: `${out}/2_reduced_en.png` })
    console.log('reduced en:', await notice(p))

    // No face found by a reduced detection: the screen says that too (a miss may be the lighter detection's).
    p = await open('he-IL', 'yunet:1,side:640,rate:1.5,reduced:1', bars, '.no-faces')
    if ((await notice(p)) !== HE) fail('no faces: the notice is missing')
    console.log('no faces reduced:', await notice(p))
    p = await open('he-IL', 'yunet:1,side:960,rate:1.5', bars, '.no-faces')
    if ((await notice(p)) !== null) fail('no faces: a notice with a full detection')
    console.log('reduced: ok')
  } catch (e) {
    console.error('FAIL', e.message)
    console.error(logs.filter((l) => !/Failed to load|GL Driver/.test(l)).slice(-15).join('\n'))
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
