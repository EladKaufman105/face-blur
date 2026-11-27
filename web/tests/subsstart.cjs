// How soon a subtitles job shows life, with no model (Hugging Face is stood in for by a local stub,
// whose model file answers after a delay): the sound is read and reported beside the model's request,
// not before it. Prints when the model was first asked for, the phases shown, and the time to the first
// number on the screen. Fails when the model is asked for only after the sound is read.
// Run: node tests/subsstart.cjs <url> <video with speech> <out dir> [strict]
const { chromium } = require('playwright')
const fs = require('fs')
const [, , base, video, out, strict] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1280, height: 860 }, locale: 'he-IL' })
  let t0 = 0
  const reqs = []
  await ctx.route(/huggingface\.co|\.hf\.co/, async (r) => {
    const u = r.request().url()
    reqs.push([Date.now() - t0, u.split('/').pop()])
    // Every answer comes after 2.5 s (a slow line); the model's files with a body that is no model.
    await new Promise((ok) => setTimeout(ok, 2500))
    if (/\.onnx(_data)?$/.test(u)) { return r.fulfill({ status: 200, contentType: 'application/octet-stream', body: Buffer.alloc(4e6) }).catch(() => {}) }
    return r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }).catch(() => {})
  })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(m.text()))
  const sep = base.includes('?') ? '&' : '?'
  await p.goto(`${base}${sep}subsmodel=base&subswatch=60`)
  await p.waitForTimeout(600)
  const chooser = p.waitForEvent('filechooser')
  await p.click('.tool >> nth=0')
  await (await chooser).setFiles(video)
  await p.waitForSelector('.editor', { timeout: 60000 })
  await p.waitForSelector('text=צור כתוביות')
  await p.waitForTimeout(500)
  t0 = Date.now()
  await p.click('text=צור כתוביות')
  const seen = []
  let firstNumber = null
  let last = ''
  while (Date.now() - t0 < 6000) {
    const s = await p.evaluate(() => document.querySelector('.speech-progress')?.innerText.replace(/\s+/g, ' ') ?? '')
    if (s && s !== last) { seen.push([Date.now() - t0, s]); last = s; if (firstNumber === null && /\d+%|MB/.test(s)) firstNumber = Date.now() - t0 }
    await p.waitForTimeout(50)
  }
  console.log('model requests (ms after the click):', JSON.stringify(reqs.slice(0, 6)))
  const firstModel = reqs.length ? reqs[0][0] : null
  console.log('first model request at', firstModel, 'ms; first number on screen at', firstNumber, 'ms')
  for (const [t, s] of seen.slice(0, 40)) console.log('  ', t, s)
  for (const l of logs) if (l.includes('ONIT subs phases')) console.log(l)
  await p.screenshot({ path: `${out}/start.png` })
  await b.close()
  if (strict) {
    if (firstModel === null || firstModel > 2500) throw new Error('the model was asked for late: ' + firstModel)
    if (firstNumber === null || firstNumber > 2500) throw new Error('no number on screen in the first 2.5 seconds: ' + firstNumber)
    // The sound's progress shows on the screen while the model is still being fetched (every answer takes 2.5 s, so the model
    // is not ready by then). Judged by what was seen and requested, not by one line holding both texts at one instant.
    const sound = seen.find(([, s]) => /מכין את הקול… \d+%/.test(s))
    if (!sound) throw new Error('the sound\'s progress never showed')
    if (firstModel > sound[0] + 2500) throw new Error('the model was asked for long after the sound began: ' + firstModel + ' vs ' + sound[0])
  }
})().catch((e) => { console.error(e); process.exit(1) })
