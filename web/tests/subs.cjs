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
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    // A video without faces says so first; its subtitles are made in the editor all the same.
    await p.waitForSelector('.editor, .no-faces', { timeout: 900000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 900000 })
    await p.click('.tabs button[data-g=subs]')
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
    // Cut by characters (as the app's): no subtitle past 15 characters unless it's one long word; then whole sentences again.
    await p.click('.split-by button >> nth=1')
    await p.click('.split-cues .chip >> text=עד 15')
    const short = await p.evaluate(() => [...document.querySelectorAll('.cue-text')].map((e) => e.value))
    const tooLong = short.filter((s) => s.length > 15 && s.trim().includes(' '))
    console.log('by characters (15):', short.length, 'subtitles · too long:', JSON.stringify(tooLong))
    if (tooLong.length || short.length <= cues.length) throw new Error('split by characters')
    await p.click('.split-cues .chip >> text=משפט שלם')
    // A word bleeped: hidden in the subtitles as ****, or shown as said when that's switched off.
    const layer = () => p.evaluate(() => { const c = document.querySelectorAll('.view canvas')[2]; const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let h = 0; for (let i = 0; i < d.length; i += 4) h = (h * 31 + d[i] + d[i + 3]) >>> 0; return h })
    await p.click('.cue-list .time-btn >> nth=0')
    await p.evaluate(() => document.querySelector('video').pause())
    await p.waitForTimeout(800)
    const plain = await layer()
    await p.click('button:text-is("בחר מילים")')
    await p.click('.word-chips .word >> nth=0')
    await p.click('button:text-is("סיום")')
    await p.waitForTimeout(800)
    const hidden = await layer()
    await p.click('.mask-subs .switch')
    await p.waitForTimeout(500)
    const asSaid = await layer()
    await p.click('.mask-subs .switch')
    console.log('bleeped word in the subtitle: hidden', hidden !== plain, '· as said again', asSaid === plain)
    if (hidden === plain || asSaid !== plain) throw new Error('hiding bleeped words')
    // Cut by the text: the second word of the first sentence.
    await p.click('.tabs button[data-g=edit]')
    await p.click('.cut-cue .word >> nth=1')
    await p.waitForTimeout(300)
    const cut = Number(await p.getAttribute('.cut', 'data-total'))
    console.log('cut by the text:', cut.toFixed(2), 's')
    if (!(cut > 0.05)) throw new Error('cut by the text')
    await p.screenshot({ path: `${out}/cut_by_text.png` })
    await p.click('.export-bar .btn')
    // The subtitles as an SRT file too.
    await p.click('.export-sheet .switch')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const srtHref = await p.getAttribute('.srt-link', 'href')
    const srt = await p.evaluate(async (h) => (await fetch(h)).text(), srtHref)
    fs.writeFileSync(`${out}/subs.srt`, srt)
    console.log('SRT:', srt.split('\n').slice(0, 3).join(' | '))
    if (!srt.includes('-->')) throw new Error('no SRT')
    if (!srt.includes('****')) throw new Error('the bleeped word is in the SRT')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
