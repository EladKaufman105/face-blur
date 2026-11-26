// Face detection with YuNet against the old web detector, on the same frames of the test-media
// clips: RetinaFace fused with YuNet, the fine pass at 960 px on the CPU (the app's size), against
// RetinaFace alone at 640 px. It shows that YuNet ran and that the new finds no fewer faces (scored
// 0.5 or more) on these clips; it doesn't compare with the app's own results (the fusion's rules
// are checked in tests/fusion.cjs). The new runs twice: in the CPU's detector workers, and as one
// detector with YuNet in a worker of its own (as on the GPU). The pace (the page's ?detect=...) is
// pinned so all runs see the same frames. A run that is reduced says so: see tests/reduced.cjs.
// Run: node tests/detect.cjs <url> <out dir> <rate> <clip>...
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, out, rate, ...clips] = process.argv

async function run(b, clip, detect, extra = '') {
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(m.text()))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  await p.goto(url + (url.includes('?') ? '&' : '?') + 'detect=' + detect + extra)
  await p.setInputFiles('.hero input[type=file]', clip)
  await p.waitForSelector('.ready')
  const t0 = Date.now()
  await p.click('.blur-faces')
  await p.waitForSelector('.editor, .no-faces, .card.error', { timeout: 900000 })
  const ms = Date.now() - t0
  const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent).catch(() => null)
  if (err) throw new Error('analysis: ' + err)
  const line = logs.find((l) => /ONIT analyze: result /.test(l))
  await p.context().close()
  if (!line) throw new Error('no result line for ' + clip + '\n' + logs.join('\n'))
  const m = /detections (\d+), faces (\d+), tracks (\d+), reduced \w+, yunet (\d+)/.exec(line)
  return { detections: +m[1], faces: +m[2], tracks: +m[3], yunet: +m[4], worker: logs.some((l) => /YuNet in a worker/.test(l)), s: ms / 1000 }
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const rows = []
  try {
    for (const clip of clips) {
      // Old: RetinaFace alone at 640 px (what the web did on the CPU). New: with YuNet, at 960 px (the app's).
      const old = await run(b, clip, `yunet:0,side:640,rate:${rate}`)
      const now = await run(b, clip, `yunet:1,side:960,rate:${rate}`)
      // One detector, YuNet in its own worker beside it (the GPU's way, here on the CPU).
      const one = await run(b, clip, `yunet:1,side:960,rate:${rate}`, '&workers=1')
      const row = { clip: clip.split('/').pop(), old, new: now, one }
      rows.push(row)
      console.log(JSON.stringify(row))
      if (old.detections !== now.detections || one.detections !== now.detections) throw new Error(`${row.clip}: not the same frames (${old.detections}, ${now.detections}, ${one.detections})`)
      // YuNet ran in the new runs (its faces were counted before the fusion) and not in the old; in its own worker with one detector.
      if (old.yunet !== 0 || now.yunet === 0 || one.yunet === 0) throw new Error(`${row.clip}: YuNet ran ${now.yunet} / ${one.yunet} / ${old.yunet} faces (new / one detector / old)`)
      if (!one.worker || now.worker) throw new Error(`${row.clip}: YuNet's worker ${one.worker} with one detector, ${now.worker} with the detector workers`)
      if (now.faces < old.faces || one.faces < old.faces) throw new Error(`${row.clip}: the new detector found fewer faces (${now.faces}, ${one.faces} < ${old.faces})`)
    }
    fs.writeFileSync(`${out}/detect.json`, JSON.stringify(rows, null, 1))
    console.log('detect: ok', rows.map((r) => `${r.clip} ${r.old.faces}->${r.new.faces} (one detector ${r.one.faces})`).join(', '))
  } finally {
    await b.close()
  }
})().catch((e) => { console.error('FAIL', e.message); process.exit(1) })
