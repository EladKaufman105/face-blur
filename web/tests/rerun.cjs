// "Run detection again" and the remembered choices, in the browser:
//  - the faces panel's action opens a sheet (the modes with their warnings, the sensitivity, Run); with blurs there it asks
//    "Add" (recommended) or "Replace" after Run; with a "Remember my choice" box;
//  - Add keeps every blur (a face deleted by hand is found again, a manual blur stays) and is ONE undo step ("Found N more faces · Undo");
//  - Replace takes out the automatic blurs and brings the new run's, the manual one stays ("Replaced: N faces");
//  - a remembered choice means no question the next time; Settings > Remembered choices lists it (in English too), Clear and Clear all
//    bring the question back, and an empty state shows when nothing is remembered;
//  - Stop leaves the blurs as they were;
//  - privacy: a Fast run shows its notice, a later Normal run with Add keeps it (the stricter wins), with Replace the new run's own flags.
// Run: node tests/rerun.cjs <url> <video with a face> <out dir>
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
  const fail = (m) => { throw new Error(m) }
  const list = () => p.evaluate(() => [...document.querySelectorAll('.faces .face')].map((e) => e.title))
  const counts = async () => { const l = await list(); return { manual: l.filter((x) => x.startsWith('טשטוש')).length, auto: l.filter((x) => x.startsWith('פנים')).length, all: l.length } }
  const toast = () => p.evaluate(() => document.querySelector('.rerun-toast span')?.textContent ?? '')
  const idle = () => p.waitForFunction(() => !document.querySelector('.rerun-progress'), null, { timeout: 600000 })
  const asked = () => p.$('.rerun-ask')
  /** Opens the sheet and runs [mode] at [sens]. Resolves once the progress shows (the question, if any, is answered by the caller). */
  const run = async (mode, sens = 0) => {
    await p.click('.rerun-open')
    await p.waitForSelector('.rerun-sheet')
    await p.click(`.rerun-sheet .detect-mode .mode-${mode}`)
    if (sens) await p.evaluate((v) => { const r = document.querySelector('.rerun-sheet input.sensitivity'); r.value = String(v); r.dispatchEvent(new Event('input', { bubbles: true })) }, sens)
    await p.click('.rerun-run')
  }
  const answer = async (what, remember) => {
    await p.waitForSelector('.rerun-ask')
    await p.click(what === 'add' ? '.opt-add' : '.opt-replace')
    if (remember) await p.check('.rerun-remember input')
    await p.click('.rerun-ask-go')
  }
  try {
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready')
    await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 600000 })
    await p.waitForSelector('.faces .face', { timeout: 20000 })
    const first = await counts()
    console.log('first detection:', JSON.stringify(first))
    if (first.auto < 1) fail('no face found to work with')

    // ---- the sheet: the modes (Fast's and Selfie's warnings), the sensitivity, Run; it starts where the video was detected
    await p.click('.rerun-open')
    await p.waitForSelector('.rerun-sheet')
    const sheet = await p.evaluate(() => ({
      title: document.querySelector('.rerun-sheet .sheet-title')?.textContent,
      modes: [...document.querySelectorAll('.rerun-sheet .detect-mode button')].map((e) => [e.textContent.trim(), e.getAttribute('aria-checked')]),
      slider: !!document.querySelector('.rerun-sheet input.sensitivity'),
      run: document.querySelector('.rerun-run')?.textContent,
    }))
    console.log('sheet:', JSON.stringify(sheet))
    if (sheet.title !== 'זהה פנים שוב' || sheet.modes.length !== 4 || !sheet.slider || sheet.run !== 'הרץ') fail('the sheet')
    if (sheet.modes.find((m) => m[1] === 'true')?.[0] !== 'רגיל') fail('the sheet starts on the detection the video had')
    for (const m of ['fast', 'selfie']) {
      await p.click(`.rerun-sheet .detect-mode .mode-${m}`)
      if (!(await p.$('.rerun-sheet .fast-warning'))) fail(m + "'s warning is missing")
    }
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/1_sheet.png` })
    await p.keyboard.press('Escape')
    await p.waitForSelector('.rerun-sheet', { state: 'detached' })

    // ---- a blur by hand (a corner with no face), and a face's blur deleted: Add must bring that face back
    await p.click('.add-blur')
    const box = await p.locator('.screen canvas >> nth=0').boundingBox()
    await p.mouse.click(box.x + box.width * 0.06, box.y + box.height * 0.08)
    await p.waitForFunction(() => !document.querySelector('.ctx-bar .progress'), null, { timeout: 300000 })
    let c = await counts()
    if (c.manual !== 1) fail('no manual blur: ' + JSON.stringify(c))
    const before = c
    await p.click('.tl-face:not(.manual) >> nth=0')
    await p.click('.blur-delete')
    await p.waitForTimeout(300)
    const afterDelete = await counts()
    if (afterDelete.all !== before.all - 1 || afterDelete.auto !== before.auto - 1) fail('delete: ' + JSON.stringify([before, afterDelete]))
    const base = afterDelete
    console.log('before a re-run:', JSON.stringify(base))

    // ---- Run -> the question (Add is first and recommended; Replace warns) -> Add, not remembered
    await run('normal')
    await p.waitForSelector('.rerun-ask')
    const ask = await p.evaluate(() => ({
      title: document.querySelector('.rerun-ask .sheet-title')?.textContent,
      sub: document.querySelector('.rerun-ask .muted')?.textContent,
      first: document.querySelector('.rerun-ask .opt')?.className,
      rec: document.querySelector('.rerun-ask .opt-add .rec')?.textContent,
      warn: document.querySelector('.rerun-ask .opt-replace .fast-warning')?.textContent,
      remember: document.querySelector('.rerun-remember input')?.checked,
      progress: !!document.querySelector('.rerun-progress'),
    }))
    console.log('question:', JSON.stringify(ask))
    if (ask.title !== 'מה לעשות בטשטושים הקיימים?' || !ask.first.includes('on') || ask.rec !== 'מומלץ' || !ask.warn || ask.remember !== false || ask.progress) fail('the question')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/2_question.png` })
    // (Cancel runs nothing.)
    await p.click('.rerun-ask-cancel')
    await p.waitForSelector('.rerun-ask', { state: 'detached' })
    if ((await p.$('.rerun-progress')) || (await counts()).all !== base.all) fail('Cancel changed something')
    await run('normal')
    await answer('add', false)
    await p.waitForSelector('.rerun-progress')
    await idle()
    await p.waitForSelector('.rerun-toast')
    c = await counts()
    const t1 = await toast()
    console.log('add:', JSON.stringify(c), t1)
    if (c.manual !== base.manual || c.auto <= base.auto || c.all < base.all + 1) fail('add: ' + JSON.stringify([base, c]))
    if (!/^נמצאו פנים נוספים/.test(t1) && !/^נמצאו \d+ פנים נוספים$/.test(t1)) fail('the toast: ' + t1)
    await p.screenshot({ path: `${out}/3_added.png` })
    // One undo step, from the toast.
    await p.click('.rerun-undo')
    await p.waitForTimeout(300)
    const undone = await counts()
    if (undone.all !== base.all || undone.auto !== base.auto || undone.manual !== base.manual) fail('undo from the toast: ' + JSON.stringify(undone))
    if (await p.$('.rerun-toast')) fail('the toast stayed after Undo')
    // ... and redo brings it back whole (one step).
    await p.keyboard.press('Control+Shift+z'); await p.waitForTimeout(300)
    if ((await counts()).all !== c.all) fail('redo of the re-run')
    // (Left redone: the blurs are the added state for what follows.)

    // ---- Add again, now with nothing new: no change, no undo step, a word about it
    const added = await counts()
    await run('normal')
    await answer('add', false)
    await idle()
    await p.waitForSelector('.toast:not(.rerun-toast)')
    const noMore = await p.evaluate(() => document.querySelector('.toast:not(.rerun-toast)').textContent)
    const again = await counts()
    console.log('add again:', noMore, JSON.stringify(again))
    if (noMore !== 'לא נמצאו פנים נוספים' || again.all !== c.all) fail('add with nothing new: ' + JSON.stringify([c, again]))

    // ---- Replace, remembered: the automatic blurs are the new run's, the manual one stays
    const preReplace = await counts()
    await run('normal')
    await answer('replace', true)
    await idle()
    await p.waitForSelector('.rerun-toast')
    c = await counts()
    const t2 = await toast()
    console.log('replace:', JSON.stringify(c), t2)
    if (c.manual !== 1 || c.auto < 1 || !/^הוחלף: /.test(t2)) fail('replace: ' + JSON.stringify(c) + t2)
    if (c.auto < first.auto) fail('replace found fewer faces than the first run')
    await p.click('.rerun-undo')
    await p.waitForTimeout(300)
    if ((await counts()).all !== preReplace.all) fail('undo of the replace brings the blurs back whole')
    await p.keyboard.press('Control+Shift+z'); await p.waitForTimeout(300)
    if ((await counts()).all !== c.all) fail('redo of the replace')

    // ---- remembered: no question the next time
    await run('normal')
    await p.waitForSelector('.rerun-progress')
    if (await asked()) fail('asked although a choice is remembered')
    await idle()
    await p.waitForSelector('.rerun-toast')
    if (!/^הוחלף: /.test(await toast())) fail('the remembered replace was not used')

    // ---- Settings: the list, in Hebrew and in English; Clear brings the question back
    await p.click('.settings-btn')
    await p.waitForSelector('.settings-sheet')
    const row = await p.evaluate(() => document.querySelector('.remembered-row')?.textContent.trim())
    console.log('settings row:', row)
    if (!row?.startsWith('בחירות שנשמרו')) fail('no settings row: ' + row)
    await p.click('.remembered-row')
    await p.waitForSelector('.remembered-sheet')
    let item = await p.evaluate(() => [...document.querySelectorAll('.remembered-item')].map((e) => [e.dataset.key, e.querySelector('.remembered-value').textContent, e.querySelector('strong').textContent]))
    console.log('remembered (he):', JSON.stringify(item))
    if (item.length !== 1 || item[0][0] !== 'rerun.merge' || item[0][1] !== 'להחליף את הטשטושים הקיימים') fail('the list')
    await p.screenshot({ path: `${out}/4_remembered_he.png` })
    await p.keyboard.press('Escape')
    await p.waitForSelector('.remembered-sheet', { state: 'detached' })
    await p.click('.lang-seg button >> text=English')
    await p.click('.remembered-row')
    await p.waitForSelector('.remembered-sheet')
    item = await p.evaluate(() => [...document.querySelectorAll('.remembered-item')].map((e) => [e.querySelector('strong').textContent, e.querySelector('.remembered-value').textContent, e.querySelector('button').textContent, document.querySelector('.remembered-clear-all')?.textContent]))
    console.log('remembered (en):', JSON.stringify(item))
    if (item[0]?.[1] !== 'Replace existing blurs' || item[0][2] !== 'Clear' || item[0][3] !== 'Clear all') fail('the list in English: ' + JSON.stringify(item))
    await p.screenshot({ path: `${out}/5_remembered_en.png` })
    await p.click('.remembered-clear')
    await p.waitForSelector('.remembered-empty')
    if ((await p.$('.remembered-item')) || (await p.$('.remembered-clear-all'))) fail('the empty state keeps rows or Clear all')
    const empty = await p.evaluate(() => document.querySelector('.remembered-empty strong').textContent)
    if (empty !== 'No remembered choices') fail('empty state: ' + empty)
    await p.screenshot({ path: `${out}/6_remembered_empty.png` })
    await p.keyboard.press('Escape')
    await p.waitForSelector('.remembered-sheet', { state: 'detached' })
    const sub = await p.evaluate(() => document.querySelector('.remembered-row .muted').textContent)
    if (sub !== 'Asking each time') fail('the row after Clear: ' + sub)
    await p.keyboard.press('Escape')
    await p.waitForSelector('.settings-sheet', { state: 'detached' })
    await p.evaluate(() => localStorage.setItem('onit.lang', 'he'))
    await p.click('.settings-btn')
    await p.click('.lang-seg button >> text=עברית')
    await p.keyboard.press('Escape')
    await p.waitForSelector('.settings-sheet', { state: 'detached' })

    // ---- the question is back; remember Add, then Clear all in Settings
    const nowCount = await counts()
    await run('normal')
    await answer('add', true)
    await idle()
    await p.click('.settings-btn')
    await p.click('.remembered-row')
    await p.waitForSelector('.remembered-item')
    await p.click('.remembered-clear-all')
    await p.waitForSelector('.remembered-empty')
    await p.keyboard.press('Escape')
    await p.keyboard.press('Escape')
    await p.waitForSelector('.settings-sheet', { state: 'detached' })
    await run('normal')
    await p.waitForSelector('.rerun-ask')
    console.log('the question is back after Clear all')

    // ---- Stop: nothing changes
    await p.click('.rerun-ask-go')
    await p.waitForSelector('.rerun-progress')
    const beforeStop = await counts()
    await p.waitForTimeout(1500)
    await p.screenshot({ path: `${out}/7_progress.png` })
    await p.click('.rerun-stop')
    await p.waitForFunction(() => !document.querySelector('.rerun-progress'), null, { timeout: 10000 })
    await p.waitForTimeout(800)
    const afterStop = await counts()
    if (afterStop.all !== beforeStop.all || (await p.$('.rerun-toast'))) fail('Stop changed the blurs: ' + JSON.stringify([beforeStop, afterStop]))
    console.log('stopped:', JSON.stringify(afterStop))

    // ---- privacy: a Fast run says so; Normal with Add keeps the notice (the stricter wins); Normal with Replace is the new run's own
    await run('fast')
    await answer('replace', false)
    await idle()
    await p.waitForTimeout(500)
    if (!(await p.$('.detect-fast'))) fail('no Fast notice after a Fast run')
    await run('normal')
    await answer('add', false)
    await idle()
    await p.waitForTimeout(500)
    if (!(await p.$('.detect-fast'))) fail('Add with Normal weakened the Fast notice')
    await p.screenshot({ path: `${out}/8_fast_notice.png` })
    await run('normal')
    await answer('replace', false)
    await idle()
    await p.waitForTimeout(500)
    if (await p.$('.detect-fast')) fail('Replace with Normal kept a Fast notice')
    // Undoing that Replace brings the project back as it was: the Fast notice with the blurs.
    await p.waitForSelector('.rerun-toast')
    await p.click('.rerun-undo')
    await p.waitForTimeout(400)
    if (!(await p.$('.detect-fast'))) fail('undo of a Replace left the Fast notice off')
    // Export is not possible while a re-run goes on.
    await run('normal')
    await p.waitForSelector('.rerun-ask')
    await p.click('.rerun-ask-go')
    await p.waitForSelector('.rerun-progress')
    if (!(await p.evaluate(() => document.querySelector('.export-bar .btn.primary, .exp-label')?.closest('button')?.disabled))) fail('export is enabled during a re-run')
    await p.click('.rerun-stop')
    await idle()

    const errors = logs.filter((l) => l.startsWith('[pageerror]'))
    if (errors.length) fail('page errors: ' + errors.join('; '))
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.log`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
