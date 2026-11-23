// No faces found, as the app says it: wider detection offered (once), a blur marked by hand in
// the editor; when saving straight away, no editor to offer.
// Run: node tests/nofaces.cjs <url> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const video = `${out}/bars.mp4`
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24:duration=3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', video])
  const b = await chromium.launch()
  const logs = []
  try {
    const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
    p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
    const buttons = () => p.evaluate(() => [...document.querySelectorAll('.no-faces .actions .btn')].map((e) => e.textContent.trim()))
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.no-faces, .editor', { timeout: 300000 })
    const first = await buttons()
    await p.screenshot({ path: `${out}/1_no_faces.png` })
    await p.click('.retry-wide')
    await p.waitForSelector('.analyze', { timeout: 10000 })
    await p.waitForSelector('.no-faces', { timeout: 300000 })
    const wide = await buttons()
    await p.click('.mark-by-hand')
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    console.log('no faces:', JSON.stringify(first), '→ after wider detection:', JSON.stringify(wide), '→ the editor')
    if (first.join('|') !== 'סמן טשטוש ידנית|נסה עם זיהוי מורחב|חזרה' || wide.join('|') !== 'סמן טשטוש ידנית|חזרה') throw new Error('no faces, reviewing')

    // Saving straight away: no editor to offer.
    const q = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
    await q.goto(url)
    await q.setInputFiles('.hero input[type=file]', video)
    await q.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    await q.click('.blur-save')
    await q.click('.export-go')
    const step = await q.waitForSelector('.analyze strong', { timeout: 30000 }).then(() => q.waitForFunction(() => document.querySelector('.analyze strong')?.textContent.includes('שלב 1'), null, { timeout: 60000 })).then(() => true).catch(() => false)
    await q.waitForSelector('.no-faces', { timeout: 300000 })
    const save = await q.evaluate(() => [...document.querySelectorAll('.no-faces .actions .btn')].map((e) => e.textContent.trim()))
    await q.click('.no-faces .btn.ghost')
    await q.waitForSelector('.ready:not(.no-faces)')
    console.log('saving straight away: step 1 of 2 shown', step, '· buttons', JSON.stringify(save), '→ back to the video')
    if (!step || save.join('|') !== 'נסה עם זיהוי מורחב|חזרה') throw new Error('no faces, saving')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
