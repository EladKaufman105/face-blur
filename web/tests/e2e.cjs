// Drives the built web app in Chromium: picks a sample video, waits for the editor, turns one
// face off and on, switches the look, exports, and checks the result.
// Run: node tests/e2e.cjs <url> <video> <out dir> [phone | tablet | tablet-portrait]
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, out, device] = process.argv
const DEVICES = {
  phone: { viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
  tablet: { viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1.5, isMobile: true, hasTouch: true },
  'tablet-portrait': { viewport: { width: 800, height: 1280 }, deviceScaleFactor: 1.5, isMobile: true, hasTouch: true },
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const ctx = await b.newContext({ ...(DEVICES[device] ?? { viewport: { width: 1366, height: 860 } }), locale: 'he-IL' })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  try {
    await p.goto(url)
    await p.waitForTimeout(1500)
    await p.screenshot({ path: `${out}/1_home.png` })
    const t0 = Date.now()
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready')
    await p.click('.blur-faces')
    await p.waitForTimeout(4000)
    await p.screenshot({ path: `${out}/2_analyzing.png` })
    await p.waitForSelector('.editor, .card.error', { timeout: 900000 })
    const error = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (error) throw new Error('analysis: ' + error)
    console.log('analysis', ((Date.now() - t0) / 1000).toFixed(1), 's')
    const faces = await p.evaluate(() => document.querySelectorAll('.face').length)
    console.log('faces', faces)
    await p.evaluate(() => { const v = document.querySelector('video'); v.currentTime = v.duration / 2 })
    await p.waitForTimeout(1500)
    await p.screenshot({ path: `${out}/3_editor.png` })
    if (faces > 0) {
      await p.click('.face >> nth=0')
      await p.waitForTimeout(600)
      await p.screenshot({ path: `${out}/4_face_off.png` })
      await p.click('.face >> nth=0')
    }
    await p.click('.seg:not(.tabs) button >> nth=1')
    await p.waitForTimeout(600)
    await p.screenshot({ path: `${out}/5_pixelate.png` })
    await p.click('.seg:not(.tabs) button >> nth=0')
    // A sound effect at the playhead, and a text watermark.
    await p.click('.tabs button[data-g=sound]')
    await p.click('.sound .name >> nth=0')
    await p.waitForSelector('.tl-sound', { timeout: 5000 })
    if (process.env.VOICE) {
      // A voice change (the chip by its English name is not shown: pick by position).
      await p.click(`.chip >> nth=${process.env.VOICE}`)
      await p.waitForFunction(() => !document.body.textContent.includes('מעבד את הקול'), null, { timeout: 120000 })
    }
    await p.waitForTimeout(600)
    await p.screenshot({ path: `${out}/5b_sound.png` })
    await p.click('.tabs button[data-g=mark]')
    await p.fill('.text-in', '@onit')
    await p.waitForTimeout(600)
    await p.screenshot({ path: `${out}/5c_watermark.png` })
    const t1 = Date.now()
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const exportError = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (exportError) throw new Error('export: ' + exportError)
    console.log('export', ((Date.now() - t1) / 1000).toFixed(1), 's')
    await p.waitForTimeout(1500)
    await p.screenshot({ path: `${out}/6_done.png` })
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/out.mp4`, Buffer.from(bytes))
    const count = (f) => Number(execFileSync('ffprobe', ['-v', 'error', '-count_frames', '-select_streams', 'v:0', '-show_entries', 'stream=nb_read_frames', '-of', 'csv=p=0', f]).toString().trim())
    const a = count(video), o = count(`${out}/out.mp4`)
    console.log('frames in', a, 'out', o)
    const audio = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'a', '-show_entries', 'stream=codec_name', '-of', 'csv=p=0', `${out}/out.mp4`]).toString().trim()
    console.log('audio', audio || 'none')
    if (!audio) throw new Error('no sound track with the sound effect')
    if (faces < 1) throw new Error('no faces found')
    if (Math.abs(a - o) > 1) throw new Error(`frame count ${o} != ${a}`)
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
