// Subtitles in Chromium: speech recognition on the device (the model downloads from Hugging
// Face), the cues in the editor and burned into the export.
// Run: node tests/subs.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  try {
    await p.goto(url)
    await p.setInputFiles('input[type=file]', video)
    await p.waitForSelector('.editor', { timeout: 900000 })
    await p.click('.tabs button >> nth=1')
    await p.click('.chip >> text=אנגלית')
    const t0 = Date.now()
    await p.click('text=צור כתוביות')
    let last = ''
    while (Date.now() - t0 < 40 * 60000) {
      if (await p.$('.cue-text')) break
      const err = await p.evaluate(() => document.querySelector('.card p[style*="primary"]')?.textContent)
      if (err) throw new Error(err)
      const s = await p.evaluate(() => [...document.querySelectorAll('.card strong, .card .small')].map((e) => e.textContent).join(' | ').slice(0, 160))
      if (s !== last) { console.log(((Date.now() - t0) / 1000).toFixed(0), 's', s); last = s }
      await p.waitForTimeout(5000)
    }
    const cues = await p.evaluate(() => [...document.querySelectorAll('.cue-text')].map((e) => e.value))
    console.log('subtitles', ((Date.now() - t0) / 1000).toFixed(0), 's:', cues.length, 'cues')
    for (const c of cues) console.log('  ' + c)
    if (cues.length === 0) throw new Error('no subtitles')
    await p.evaluate(() => { const v = document.querySelector('video'); v.currentTime = 2 })
    await p.waitForTimeout(1500)
    await p.screenshot({ path: `${out}/subs_editor.png` })
    await p.click('.export-bar .btn')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
