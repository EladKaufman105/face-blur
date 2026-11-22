// A tool from the home screen: the editor opens without face detection (on the watermark
// tab), faces are found from inside it, the export keeps the watermark, and "back to editing"
// keeps everything.
// Run: node tests/tools.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1280, height: 800 }, locale: 'he-IL' })).newPage()
  try {
    await p.goto(url)
    await p.waitForTimeout(1000)
    await p.screenshot({ path: `${out}/1_home_tools.png` })
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=2')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .text-in', { timeout: 30000 })
    await p.fill('.text-in', '@onit')
    await p.waitForTimeout(800)
    await p.screenshot({ path: `${out}/2_editor_watermark.png` })
    await p.click('.tabs button >> nth=0')
    await p.click('text=זהה פנים')
    await p.waitForSelector('.editor .face', { timeout: 900000 })
    const faces = await p.evaluate(() => document.querySelectorAll('.face').length)
    console.log('faces found from the editor:', faces)
    await p.waitForTimeout(1000)
    await p.screenshot({ path: `${out}/3_editor_faces.png` })
    await p.click('.export-bar .btn')
    await p.waitForSelector('.result video', { timeout: 900000 })
    await p.click('text=חזרה לעריכה')
    await p.click('.tabs button >> nth=3')
    const kept = await p.inputValue('.text-in')
    console.log('watermark after export and back:', kept)
    if (faces < 1) throw new Error('no faces')
    if (kept !== '@onit') throw new Error('the editor lost its state')
    console.log('PASS')
  } finally {
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
