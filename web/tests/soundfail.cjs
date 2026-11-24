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
  // The code is 'failed' or 'undecodable' (a sound the browser can't decode: never taken for no sound).
  let mode = 'failed'
  // Mode 'slow': a long job that is alive, answering "started" and then a heartbeat every 0.5 s for 7 s (the page's
  // watchdog is shortened to 2.5 s by ?audiowd), and only then done. 'silent': it says "started" and then nothing.
  const slowBody = (silent) => `self.onmessage = (e) => { const id = e.data.id; self.postMessage({ type: 'started', id }); if (${silent}) return; let n = 0; const h = setInterval(() => { self.postMessage({ type: 'progress', id }); if (++n >= 14) { clearInterval(h); const d = new Float32Array(48000); self.postMessage({ type: 'done', id, rate: 48000, data: d, lufs: null, gainDb: 0 }, [d.buffer]) } }, 500) }`
  await ctx.route(/audio\.worker[^/]*\.js/, (r) => r.fulfill({ contentType: 'text/javascript', body: mode === 'slow' ? slowBody(false) : mode === 'silent' ? slowBody(true) : `self.onmessage = (e) => self.postMessage({ type: 'error', id: e.data.id, code: '${mode}', message: 'forced failure' })` }))
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

    const scenario = async (code) => {
      mode = code
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
      await p.waitForSelector('.sound-error', { state: 'detached', timeout: 10000 })
      await p.waitForSelector('.sound-error[data-hard="1"]', { timeout: 30000 })
      if (!(await p.isDisabled('.export-bar .btn.primary'))) fail('export enabled after the retry failed')
      if (!(await p.textContent('.sound-error')).includes('שינוי הקול נכשל')) fail('the error card does not say the sound failed')
      if (await p.$('.tpl-note')) fail('the failure is said twice (the banner and the card)')
      if (await p.evaluate(() => window.__enabled)) fail('export was enabled at some point while the voice change was not applied')
      await p.screenshot({ path: `${out}/1_editor_failed_${code}.png` })
      // Removing the voice change is the way on.
      await p.evaluate(() => { window.__watch = false })
      await p.click('.sound-drop')
      await p.waitForFunction(() => !document.querySelector('.sound-error') && !document.querySelector('.export-bar .btn.primary').disabled, null, { timeout: 15000 })
      console.log(code + ' editor: blocked while failed, enabled after removing the voice change')

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
      console.log(code + ' batch:', JSON.stringify(items))
      if (items.length !== 1 || items[0].status !== 'failed' || !items[0].error.includes('שינוי הקול נכשל') || items[0].links !== 0) fail('the batch item: ' + JSON.stringify(items))
      if (workers.filter((u) => u.includes('export.worker')).length !== before) fail('a failed item was exported')
      if (downloads.length) fail('a file was downloaded: ' + downloads)
      await p.screenshot({ path: `${out}/2_batch_failed_${code}.png` })

    }
    // A slow job that keeps sending heartbeats never shows the failure card, and exports once done.
    const slow = async () => {
      mode = 'slow'
      await p.goto(url + '&audiowd=2500')
      await p.waitForTimeout(600)
      await p.setInputFiles('.hero input[type=file]', video)
      await p.waitForSelector('.ready .tpl-card')
      await p.click('.tpl-card[data-tpl="look.clean"]')
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs', { timeout: 60000 })
      const t0 = Date.now()
      let sawFail = false, early = false
      while (Date.now() - t0 < 30000) {
        if (await p.$('.sound-error')) sawFail = true
        const dis = await p.isDisabled('.export-bar .btn.primary')
        if (!dis) { early = Date.now() - t0 < 4000 ? true : early; break }
        await p.waitForTimeout(250)
      }
      if (sawFail) fail('the failure card showed for a slow job that was sending heartbeats')
      if (early) fail('export enabled before the slow job was done')
      if (await p.isDisabled('.export-bar .btn.primary')) fail('export still disabled after the slow job was done')
      if (await p.$('.sound-error')) fail('the failure card is showing after the slow job was done')
      console.log('slow: no failure card, export enabled after done in ' + (Date.now() - t0) + ' ms')
    }
    await slow()
    // A worker that says "started" and then goes silent trips the watchdog (hard card, blocked).
    mode = 'silent'
    await p.goto(url + '&audiowd=2500')
    await p.waitForTimeout(600)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready .tpl-card')
    await p.click('.tpl-card[data-tpl="look.clean"]')
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 60000 })
    await p.waitForSelector('.sound-error[data-hard="1"]', { timeout: 15000 })
    if (!(await p.isDisabled('.export-bar .btn.primary'))) fail('export enabled while a silent worker was in flight')
    console.log('silent: watchdog fired, export blocked')
    await scenario('failed')
    await scenario('undecodable')
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
