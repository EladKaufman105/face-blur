// Speech recognition time by thread count: runs the subtitles of a clip and prints the worker's timings. Run: node tests/subsbench.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(m.text()))
  try {
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 900000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 900000 })
    await p.click('.tabs button[data-g=subs]')
    await p.click('.chip >> text=אנגלית')
    await p.click('text=צור כתוביות')
    await p.waitForSelector('.cue-text', { timeout: 20 * 60000 })
    for (const l of logs) if (l.includes('ONIT subs:')) console.log(url.match(/threads=\d+/)?.[0] ?? 'threads=default', l)
  } finally { await b.close() }
})().catch((e) => { console.error('FAIL', e.message); process.exit(1) })
