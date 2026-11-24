// The speech model's flow, without downloading a model (Hugging Face is held or blocked here;
// the recognition itself is tests/subs.cjs's): the choice of "faster" / "better quality" with its
// size, kept after a reload; a recognition that makes no progress ends with a clear error and a
// retry (the watchdog), and its worker is gone; Cancel ends the worker at once; a template's
// subtitles ask before downloading, and "no subtitles" says so.
// Run: node tests/subsflow.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , base, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1280, height: 860 }, locale: 'he-IL' })
  // Every request to the model host hangs: a download that never starts.
  let hung = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, () => { hung++ })
  const p = await ctx.newPage()
  const logs = []
  const alive = new Set()
  const closedAt = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  p.on('worker', (w) => {
    if (!w.url().includes('subs.worker')) return
    alive.add(w)
    w.on('close', () => { alive.delete(w); closedAt.push(Date.now()) })
  })
  const fail = (msg) => { throw new Error(msg) }
  const sep = base.includes('?') ? '&' : '?'
  const url = `${base}${sep}subsmodel=base&subswatch=8`
  const openSubs = async (u) => {
    await p.goto(u)
    await p.waitForTimeout(600)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor', { timeout: 60000 })
    await p.waitForSelector('text=צור כתוביות')
  }
  try {
    // ---- the choice: sizes before anything downloads, kept after a reload
    await openSubs(url)
    const chips = await p.evaluate(() => [...document.querySelectorAll('.speech-quality .chip')].map((c) => [c.dataset.q, c.textContent.trim(), c.classList.contains('on')]))
    console.log('quality chips:', JSON.stringify(chips))
    if (chips.length !== 2 || chips.some((c) => !/\d+\s*MB/.test(c[1]))) fail('the choice does not show sizes: ' + JSON.stringify(chips))
    if (hung !== 0) fail('something was requested before the go-ahead')
    const note = await p.textContent('.speech-note')
    if (!/\d+ MB/.test(note)) fail('the note does not say the size: ' + note)
    const other = chips.find((c) => !c[2])[0]
    await p.click(`.speech-quality .chip[data-q=${other}]`)
    await p.waitForSelector(`.speech-quality .chip.on[data-q=${other}]`)
    const stored = await p.evaluate(() => Object.entries(localStorage).filter(([k]) => k.endsWith('.subs.quality')))
    console.log('stored:', JSON.stringify(stored))
    if (stored.length !== 1 || stored[0][1] !== other) fail('the choice was not kept')
    await openSubs(url)
    await p.waitForSelector(`.speech-quality .chip.on[data-q=${other}]`)

    // ---- stuck: the model never arrives. A phase with no percent is shown as such, then an error and a retry.
    await p.click('text=צור כתוביות')
    await p.waitForSelector('.speech-progress')
    const phase = await p.textContent('.speech-progress strong')
    console.log('first phase:', phase)
    await p.waitForSelector('.speech-progress .progress.indet', { timeout: 20000 }).catch(() => fail('no moving bar while loading'))
    await p.waitForSelector('text=נסה שוב', { timeout: 40000 })
    const err = await p.evaluate(() => document.querySelector('.card p[style*="primary"]')?.textContent)
    console.log('error:', err)
    if (!err || !err.includes('נתקע')) fail('the stuck recognition did not say so: ' + err)
    await p.waitForTimeout(500)
    if (alive.size !== 0) fail('a worker is still alive after the failure')
    await p.screenshot({ path: `${out}/1_stalled.png` })

    // ---- retry, then Cancel: the worker ends at once.
    await p.click('text=נסה שוב')
    await p.waitForSelector('.speech-cancel')
    if (alive.size !== 1) fail('expected one worker running, got ' + alive.size)
    const t0 = Date.now()
    await p.click('.speech-cancel')
    for (let i = 0; i < 30 && alive.size > 0; i++) await p.waitForTimeout(100)
    console.log('worker ended', Date.now() - t0, 'ms after Cancel')
    if (alive.size !== 0) fail('Cancel did not end the worker')
    await p.waitForSelector('text=צור כתוביות')
    if (await p.$('.speech-progress')) fail('the progress is still shown after Cancel')

    // ---- a template's subtitles ask first; "no subtitles" says so.
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready .tpl-card')
    await p.click('.tpl-card[data-tpl="look.news"]')
    await p.waitForSelector('.tpl-subs input:checked', { state: 'attached' })
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs, .no-faces', { timeout: 240000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.speech-ask', { timeout: 120000 })
    if (alive.size !== 0) fail('a speech worker started before the go-ahead')
    await p.screenshot({ path: `${out}/2_ask.png` })
    await p.click('.speech-skip')
    await p.waitForFunction(() => !!document.querySelector('.tpl-note'), null, { timeout: 60000 })
    const skipNote = await p.textContent('.tpl-note')
    console.log('declined:', skipNote)
    if (!skipNote.includes('הכתוביות לא נוצרו')) fail('declining is not said: ' + skipNote)
    if (alive.size !== 0 || (await p.$('.speech-ask'))) fail('declined, but a worker or the question is still there')
    console.log('PASS')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.slice(-25).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
