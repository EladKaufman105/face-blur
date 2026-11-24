// Templates and several videos at once: a built-in template picked on the ready screen fills in
// the settings (and the editor asks for the subtitles); a built-in look edited in the settings and
// restored; a template copied, renamed and deleted; two videos processed as a batch with a
// template (one after the other, each saved and kept as a project in one folder), the folder
// renamed, opened and deleted.
// The speech model (Whisper, from Hugging Face) is not downloaded here: its requests are
// blocked, so the subtitle step is asked for (after the question about the download) and then
// reported as not done (that is checked; the subtitles themselves are tests/subs.cjs's).
// Run: node tests/templates.cjs <url> <video with sound> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video1, video2, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', acceptDownloads: true })
  let blocked = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => { blocked++; r.abort() })
  const p = await ctx.newPage()
  const logs = []
  const workers = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  p.on('worker', (w) => workers.push(w.url()))
  const downloads = []
  p.on('download', (d) => downloads.push(d.suggestedFilename()))
  const fail = (msg) => { throw new Error(msg) }
  const ready = async (file) => {
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.setInputFiles('.hero input[type=file]', file)
    await p.waitForSelector('.ready .tpl-card')
  }
  // The keys in one of the template database's stores (the screen changes before the storage does).
  const keys = (store) => p.evaluate((st) => new Promise((resolve) => indexedDB.databases().then((dbs) => { const r = indexedDB.open(dbs.find((x) => x.name.endsWith('-templates')).name); r.onsuccess = () => { const g = r.result.transaction(st).objectStore(st).getAllKeys(); g.onsuccess = () => { r.result.close(); resolve(g.result) } } })), store)
  const stored0 = async (store, n) => { for (let i = 0; i < 100 && (await keys(store)).length !== n; i++) await p.waitForTimeout(100) }
  const summary = () => p.textContent('.fold-summary')
  const openFold = async () => { if ((await p.getAttribute('.blur-settings', 'aria-expanded')) !== 'true') await p.click('.blur-settings') }
  try {
    // ---- the picker on the ready screen
    await ready(video1)
    const cards = await p.evaluate(() => [...document.querySelectorAll('.tpl-card')].map((c) => [c.dataset.tpl, c.querySelector('.tpl-name').textContent]))
    console.log('cards:', JSON.stringify(cards))
    const names = cards.map((c) => c[1])
    if (cards[0][0] !== 'none' || names.slice(1).join() !== 'נקי,בולט,חדשות,מינימלי') fail('the template cards: ' + names)
    if (await p.$('.tpl-card.on[data-tpl]:not([data-tpl="none"])')) fail('a template chosen from the start')
    // Each card is a picture (drawn: not one flat color).
    const drawn = await p.evaluate(() => [...document.querySelectorAll('.tpl-card canvas')].map((c) => {
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
      const seen = new Set()
      for (let i = 0; i < d.length; i += 4 * 97) seen.add((d[i] >> 4) * 256 + (d[i + 1] >> 4) * 16 + (d[i + 2] >> 4))
      return seen.size
    }))
    console.log('colors per card preview:', drawn.join(','))
    if (drawn.some((n) => n < 8)) fail('a template preview is not drawn')
    await p.screenshot({ path: `${out}/1_ready_templates.png` })

    const before = { summary: await summary().catch(() => null) }
    await openFold()
    before.summary = await summary()
    before.strength = await p.inputValue('.fold-body input.strength')
    await p.click('.tpl-card[data-tpl="look.news"]')
    await p.waitForSelector('.tpl-subs input:checked', { state: 'attached' })
    const after = { summary: await summary(), pixels: await p.evaluate(() => document.querySelector('.look-seg .on').textContent) }
    console.log('news applied:', JSON.stringify(before), '→', JSON.stringify(after))
    if (before.summary !== 'טשטוש · זיהוי רגיל' || after.summary !== 'פיקסלים · זיהוי רגיל') fail('the look did not fill in')
    if (await p.$('.tpl-subs input:not(:checked)')) fail('subtitles not switched on')
    // Back to none: the settings are as they were.
    await p.click('.tpl-card[data-tpl="none"]')
    await openFold()
    if ((await summary()) !== before.summary || (await p.inputValue('.fold-body input.strength')) !== before.strength) fail('no template did not restore the settings')
    await p.click('.tpl-card[data-tpl="look.bold"]')
    await p.waitForSelector('.tpl-subs input:checked', { state: 'attached' })
    await openFold()
    if ((await p.inputValue('.fold-body input.strength')) !== '0.85') fail('bold strength ' + (await p.inputValue('.fold-body input.strength')))
    await p.click('.tpl-card[data-tpl="look.news"]')

    // The editor opens on the faces found, the template applied there: its design, and the subtitle step asked for.
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs, .no-faces', { timeout: 240000 })
    // The model is not in the browser: the person is asked first (with its size), nothing downloads before the go-ahead.
    await p.waitForSelector('.speech-ask', { timeout: 120000 })
    const askText = await p.textContent('.speech-ask')
    console.log('speech ask:', askText.replace(/\s+/g, ' ').slice(0, 160))
    if (!/\d+ MB/.test(askText)) fail('the question does not say the size')
    if (blocked !== 0 || workers.some((u) => u.includes('subs.worker'))) fail('the speech model started before the go-ahead')
    await p.screenshot({ path: `${out}/2a_speech_ask.png` })
    await p.click('.speech-go')
    await p.waitForFunction(() => !!document.querySelector('.tpl-note'), null, { timeout: 120000 })
    const asked = workers.filter((u) => u.includes('subs.worker')).length
    const note = await p.textContent('.tpl-note')
    const style = await p.evaluate(() => JSON.parse(localStorage.getItem(Object.keys(localStorage).find((k) => k.endsWith('.substyle')))))
    console.log('editor: subtitle workers', asked, '· blocked model requests', blocked, '· note:', note, '· style:', style.font, style.box, style.effect)
    if (asked < 1) fail('the subtitles were not asked for')
    if (!note.includes('הכתוביות לא נוצרו')) fail('the failed step is not said: ' + note)
    if (style.font !== 'assistant' || style.box !== true) fail('the template design did not reach the editor')
    await p.screenshot({ path: `${out}/2_editor_template.png` })

    // ---- the editor in the settings: a built-in look edited, kept, restored
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.settings-btn')
    await p.waitForSelector('.tpl-item[data-tpl="look.clean"]')
    if ((await p.$$('.tpl-item')).length !== 4) fail('the template list')
    await p.click('.tpl-item[data-tpl="look.clean"] .tpl-edit')
    await p.waitForSelector('.tpl-sheet .tpl-strength')
    await p.click('.tpl-mode button >> nth=1')
    await p.fill('.tpl-strength', '0.2')
    // A category switched off, and back on as it was.
    await p.$eval('.tpl-inc-voice', (e) => e.click())
    if (!(await p.$('[data-cat="voice"].on'))) fail('voice category did not switch on')
    await p.$eval('.tpl-inc-voice', (e) => e.click())
    await p.screenshot({ path: `${out}/3_template_editor.png` })
    await p.click('.tpl-save')
    await p.waitForSelector('.tpl-sheet', { state: 'detached' })
    await stored0('builtin', 1)
    const row = await p.textContent('.tpl-item[data-tpl="look.clean"] .small')
    console.log('clean after edit:', row)
    if (!row.includes('נערכה') || !(await p.$('.tpl-item[data-tpl="look.clean"] .tpl-restore-row'))) fail('edited look not marked')
    // The stored JSON is the app's shape (a pixelated look, the style as a string), and it's kept across a reload.
    await p.goto(url)
    await p.waitForTimeout(600)
    const stored = await p.evaluate(() => new Promise((resolve) => {
      const name = indexedDB.databases ? null : 'onit-templates'
      ;(indexedDB.databases ? indexedDB.databases() : Promise.resolve([{ name }])).then((dbs) => {
        const d = dbs.find((x) => x.name.endsWith('-templates'))
        const r = indexedDB.open(d.name)
        r.onsuccess = () => { const g = r.result.transaction('builtin').objectStore('builtin').get('look.clean'); g.onsuccess = () => resolve(g.result) }
      })
    }))
    console.log('stored:', JSON.stringify(stored.json).slice(0, 220))
    if (stored.json.v !== 1 || stored.json.blur.mode !== 'PIXELATE' || stored.json.group !== 'LOOK' || typeof stored.json.subs.style !== 'string' || JSON.parse(stored.json.subs.style).font !== 'HEEBO' || !stored.json.subs.create) fail('the stored template is not in the app shape')
    await p.setInputFiles('.hero input[type=file]', video1)
    await p.waitForSelector('.ready .tpl-card')
    if (!(await p.$('.tpl-card[data-tpl="look.clean"] .tpl-badge'))) fail('no edited badge on the card')
    await p.click('.tpl-card[data-tpl="look.clean"]')
    await openFold()
    const edited = { summary: await summary(), strength: await p.inputValue('.fold-body input.strength') }
    console.log('clean as edited:', JSON.stringify(edited))
    if (edited.summary !== 'פיקסלים · זיהוי רגיל' || edited.strength !== '0.2') fail('the edit did not reach the ready screen')
    // Restore the original.
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.settings-btn')
    await p.click('.tpl-item[data-tpl="look.clean"] .tpl-restore-row')
    await p.waitForFunction(() => !document.querySelector('.tpl-item[data-tpl="look.clean"] .tpl-restore-row'))
    await stored0('builtin', 0)
    if ((await p.textContent('.tpl-item[data-tpl="look.clean"] .small')).includes('נערכה')) fail('restore left it edited')
    // A copy of a built-in: renamed, then deleted (with a question first).
    await p.click('.tpl-item[data-tpl="look.bold"] .tpl-dup')
    await p.waitForSelector('.tpl-item:not([data-tpl^="look."])')
    const copy = await p.textContent('.tpl-item:not([data-tpl^="look."]) .tpl-item-name')
    await p.click('.tpl-item:not([data-tpl^="look."]) .tpl-rename')
    await p.fill('.tpl-rename-in', 'התבנית שלי')
    await p.click('.tpl-item:not([data-tpl^="look."]) .btn.tonal')
    await p.waitForFunction(() => document.querySelector('.tpl-item:not([data-tpl^="look."]) .tpl-item-name')?.textContent === 'התבנית שלי')
    console.log('copied as:', copy, '→ renamed')
    await p.click('.tpl-item:not([data-tpl^="look."]) .tpl-del')
    await p.click('.tpl-del-yes')
    await p.waitForFunction(() => !document.querySelector('.tpl-item:not([data-tpl^="look."])'))
    await stored0('user', 0)
    await p.reload()
    await p.waitForTimeout(500)
    await p.click('.settings-btn')
    await p.waitForSelector('.tpl-item[data-tpl="look.clean"]')
    if (await p.$('.tpl-item:not([data-tpl^="look."])')) fail('the deleted template came back')
    const restored = await p.evaluate(() => new Promise((resolve) => indexedDB.databases().then((dbs) => { const r = indexedDB.open(dbs.find((x) => x.name.endsWith('-templates')).name); r.onsuccess = () => { const g = r.result.transaction('builtin').objectStore('builtin').getAllKeys(); g.onsuccess = () => resolve(g.result) } })))
    if (restored.length !== 0) fail('a restored look is still stored as edited: ' + restored)

    // ---- two videos at once
    await p.goto(url)
    await p.waitForTimeout(600)
    await p.click('.hero .several')
    await p.waitForSelector('.batch-setup')
    await p.setInputFiles('.batch-input', [video1, video2])
    await p.waitForFunction(() => document.querySelectorAll('.batch-file').length === 2)
    await p.click('.tpl-card[data-tpl="look.minimal"]')
    await p.waitForSelector('.batch-estimate')
    const est = await p.textContent('.batch-estimate')
    const model = await p.textContent('.batch-model').catch(() => null)
    console.log('batch setup: estimate', est, '· model notice:', model)
    if (!model) fail('no notice about the speech model')
    await p.screenshot({ path: `${out}/4_batch_setup.png` })
    const t0 = Date.now()
    await p.evaluate(() => { window.__asks = 0; let open = false; new MutationObserver(() => { const now = !!document.querySelector('.speech-ask'); if (now && !open) window.__asks++; open = now }).observe(document.body, { childList: true, subtree: true }) })
    await p.click('.batch-start')
    await p.waitForSelector('.batch-item')
    // Start was the go-ahead (the size was shown in the setup): no question at the first video.
    await p.waitForFunction(() => !!document.querySelector('.batch-cancel'), null, { timeout: 300000 })
    // The tab must stay open: leaving the page asks first, while it runs.
    const asks = await p.evaluate(() => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented })
    if (!asks) fail('leaving the page does not ask during a batch')
    if (!(await p.$('.batch-stay'))) fail('no notice to keep the tab open')
    // Cancelling asks first; "continue" keeps going.
    await p.click('.batch-cancel')
    await p.waitForSelector('.confirm .batch-cancel-yes')
    await p.click('.confirm .btn.ghost')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/5_batch_running.png` })
    await p.waitForFunction(() => document.querySelector('.batch-title')?.textContent.includes('הסתיים'), null, { timeout: 600000 })
    const items = await p.evaluate(() => [...document.querySelectorAll('.batch-item')].map((i) => ({ status: i.dataset.status, notes: [...i.querySelectorAll('.batch-note')].map((n) => n.textContent), links: i.querySelectorAll('.batch-download').length })))
    console.log('batch took', Math.round((Date.now() - t0) / 1000), 's:', JSON.stringify(items))
    if (items.length !== 2 || items.some((i) => i.status !== 'saved' || i.links !== 1)) fail('the batch items: ' + JSON.stringify(items))
    // A step that did not work out is said on the video, not skipped.
    if (items.some((i) => i.notes.length === 0 || !i.notes[0].includes('שלב בתבנית לא הושלם'))) fail('failed subtitle step not reported')
    if (workers.filter((u) => u.includes('subs.worker')).length < 3) fail('the subtitle step was not asked for in the batch')
    const asksSeen = await p.evaluate(() => window.__asks)
    if (asksSeen !== 0) fail('the model question was asked ' + asksSeen + ' times in the batch')
    if ((await p.textContent('.batch-pct')) !== '100%') fail('overall percent')
    if (await p.evaluate(() => { const e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); return e.defaultPrevented })) fail('still asks to stay when done')
    await p.screenshot({ path: `${out}/6_batch_done.png` })
    // Each saved: a real video (the downloaded list is the way to keep them if the browser blocks the automatic ones).
    for (const k of [0, 1]) {
      const href = await p.getAttribute(`.batch-item >> nth=${k} >> .batch-download`, 'href')
      const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
      const f = `${out}/batch${k}.mp4`
      fs.writeFileSync(f, Buffer.from(bytes))
      const dur = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]).toString())
      console.log('saved', k, ':', bytes.length, 'bytes,', dur.toFixed(1), 's')
      if (!(dur > 3)) fail('saved video ' + k)
    }
    console.log('automatic downloads:', downloads.length)

    // ---- the folder in the projects list
    await p.click('.batch-home')
    await p.waitForSelector('.batch-folder')
    const folder = await p.evaluate(() => ({ count: document.querySelectorAll('.batch-folder').length, text: document.querySelector('.batch-folder .small').textContent, loose: document.querySelectorAll('.projects > .project-list > .project:not(.batch-folder)').length }))
    console.log('folder:', JSON.stringify(folder))
    if (folder.count !== 1 || !folder.text.startsWith('2 ') || folder.loose !== 1) fail('batch folder in the projects (one loose project from the editor above)')
    await p.click('.batch-folder .folder-name')
    await p.waitForFunction(() => document.querySelectorAll('.folder-items .project').length === 2)
    await p.click('.folder-rename')
    await p.fill('.folder-rename-in', 'אצווה לבדיקה')
    await p.click('.folder-rename-ok')
    await p.waitForFunction(() => document.querySelector('.folder-name')?.textContent === 'אצווה לבדיקה')
    await p.screenshot({ path: `${out}/7_batch_folder.png` })
    // One opens in the editor, to keep editing.
    await p.click('.folder-items .project-name >> nth=0')
    await p.waitForSelector('.editor .tabs', { timeout: 60000 })
    const hasTracks = await p.evaluate(() => document.querySelectorAll('.tl-track, .face').length)
    console.log('opened in the editor: faces/tracks elements', hasTracks)
    await p.goto(url)
    await p.waitForSelector('.batch-folder')
    if ((await p.textContent('.folder-name')) !== 'אצווה לבדיקה') fail('folder name not kept')
    // Deleting asks first, then removes the folder's projects.
    await p.click('.folder-delete')
    await p.waitForSelector('.folder-confirm')
    await p.click('.folder-confirm .btn.ghost')
    if (!(await p.$('.batch-folder'))) fail('cancel deleted the folder')
    await p.click('.folder-delete')
    await p.click('.folder-delete-yes')
    await p.waitForFunction(() => !document.querySelector('.batch-folder'))
    await p.reload()
    await p.waitForTimeout(600)
    if ((await p.$$('.project')).length !== 1) fail('projects of the folder are still there')
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
