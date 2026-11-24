// A sound step that fails never lets the original voice out: a template with a voice change (and
// no face blur, so the editor opens straight away) is applied while the audio worker is made to
// fail (its script is replaced by one that answers every request with an error). In the editor the
// export stays disabled, an error with "try again" and "remove the voice change" shows, and
// removing the voice change enables the export. In a batch the item fails, with its reason, and
// nothing is exported or saved.
// Run: node tests/soundfail.cjs <url> <video with sound> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', acceptDownloads: true })
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
  // The audio worker, failing: every request is answered with an error that is not "no sound".
  await ctx.route(/audio\.worker[^/]*\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: "self.onmessage = (e) => self.postMessage({ type: 'error', id: e.data.id, code: 'failed', message: 'forced failure' })" }))
  await ctx.addInitScript(() => {
    // Whether the editor's export button was ever enabled while watched.
    window.__watch = false
    window.__enabled = false
    const tick = () => {
      const bt = document.querySelector('.export-bar .btn.primary')
      if (window.__watch && bt && !bt.disabled) window.__enabled = true
      requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  })
  const p = await ctx.newPage()
  const logs = []
  const workers = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  p.on('worker', (w) => workers.push(w.url()))
  const downloads = []
  p.on('download', (d) => downloads.push(d.suggestedFilename()))
  const fail = (msg) => { throw new Error(msg) }
  try {
    // A built-in look edited into: no face blur, no subtitles, a deep voice.
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.settings-btn')
    await p.waitForSelector('.tpl-item[data-tpl="look.clean"]')
    await p.click('.tpl-item[data-tpl="look.clean"] .tpl-edit')
    await p.waitForSelector('.tpl-sheet')
    await p.$eval('.tpl-blur-none input', (e) => e.click())
    await p.$eval('.tpl-inc-subs', (e) => { if (e.checked) e.click() })
    await p.$eval('.tpl-inc-voice', (e) => { if (!e.checked) e.click() })
    await p.click('.tpl-voice .chip >> nth=1')
    await p.click('.tpl-save')
    await p.waitForSelector('.tpl-sheet', { state: 'detached' })

    // ---- the editor
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready .tpl-card')
    await p.click('.tpl-card[data-tpl="look.clean"]')
    await p.evaluate(() => { window.__watch = true })
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 60000 })
    await p.waitForSelector('.sound-error[data-hard="1"]', { timeout: 30000 })
    if (!(await p.isDisabled('.export-bar .btn.primary'))) fail('export enabled after the sound failed')
    // "Try again" fails again, still blocked.
    await p.click('.sound-retry')
    await p.waitForSelector('.sound-error[data-hard="1"]', { timeout: 30000 })
    if (!(await p.isDisabled('.export-bar .btn.primary'))) fail('export enabled after the retry failed')
    if (!(await p.textContent('.tpl-note').catch(() => '')).includes('שינוי הקול נכשל')) fail('the template banner does not say the sound failed')
    if (await p.evaluate(() => window.__enabled)) fail('export was enabled at some point while the voice change was not applied')
    await p.screenshot({ path: `${out}/1_editor_failed.png` })
    // Removing the voice change is the way on.
    await p.evaluate(() => { window.__watch = false })
    await p.click('.sound-drop')
    await p.waitForFunction(() => !document.querySelector('.sound-error') && !document.querySelector('.export-bar .btn.primary').disabled, null, { timeout: 15000 })
    console.log('editor: blocked while failed, enabled after removing the voice change')

    // ---- a batch
    const before = workers.filter((u) => u.includes('export.worker')).length
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.hero .several')
    await p.waitForSelector('.batch-setup')
    await p.setInputFiles('.batch-input', [video])
    await p.waitForFunction(() => document.querySelectorAll('.batch-file').length === 1)
    await p.click('.tpl-card[data-tpl="look.clean"]')
    await p.waitForSelector('.batch-estimate')
    await p.click('.batch-start')
    await p.waitForFunction(() => document.querySelector('.batch-title')?.textContent.includes('הסתיים'), null, { timeout: 120000 })
    const items = await p.evaluate(() => [...document.querySelectorAll('.batch-item')].map((i) => ({ status: i.dataset.status, error: i.querySelector('.batch-error')?.textContent ?? '', links: i.querySelectorAll('.batch-download').length })))
    console.log('batch:', JSON.stringify(items))
    if (items.length !== 1 || items[0].status !== 'failed' || !items[0].error.includes('שינוי הקול נכשל') || items[0].links !== 0) fail('the batch item: ' + JSON.stringify(items))
    if (workers.filter((u) => u.includes('export.worker')).length !== before) fail('a failed item was exported')
    if (downloads.length) fail('a file was downloaded: ' + downloads)
    await p.screenshot({ path: `${out}/2_batch_failed.png` })
    console.log('OK')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.slice(-25).join('\n'))
    console.error(e)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
