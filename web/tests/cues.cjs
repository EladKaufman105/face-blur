// A subtitle on the timeline, as in the app: a tap selects it (and goes to it), its bar offers
// Start here, End here, Split, Merge, Edit text and Delete, its edges are dragged to retime it, and
// every change is undone in one step. Its words stay where they were said, so a bleeped word stays
// bleeped (and masked in the subtitle) when the edges move past it. On a desktop and on a phone.
// The words come from a saved project (the speech model is not needed), as in subsimport.cjs.
// Run: node tests/cues.cjs <url> <video with speech> <out dir> [phone]
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out, device] = process.argv
const PHONE = device === 'phone'
const A = `1\n00:00:01,000 --> 00:00:03,000\nשלום לכולם\n\n2\n00:00:04,000 --> 00:00:06,000\nהיום נדבר\n\n3\n00:00:08,000 --> 00:00:09,000\nתודה רבה\n`
const WORDS = [['שלום', 1, 1.5], ['לכולם', 1.6, 2.5], ['היום', 4, 4.5], ['נדבר', 4.6, 5.5], ['תודה', 8, 8.4], ['רבה', 8.5, 9]].map(([text, start, end]) => ({ text: ' ' + text, start, end }))

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/a.srt`, A)
  const b = await chromium.launch()
  const ctx = await b.newContext(PHONE ? { viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'he-IL' } : { viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const fail = (m) => { throw new Error(m) }
  const cues = () => p.evaluate(() => [...document.querySelectorAll('.tl-cue')].map((e) => ({ id: Number(e.dataset.id), a: Number(e.dataset.a), b: Number(e.dataset.b), text: e.textContent, on: e.classList.contains('on') })))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(400) }
  const near = (x, y, tol = 0.06) => Math.abs(x - y) <= tol
  const tap = (sel) => (PHONE ? p.tap(sel) : p.click(sel))
  const timeline = async () => { if (PHONE && (await p.$('.panel:not([hidden])'))) await tap('.panel-close') }
  const undo = async () => { await tap('.edit-tools .icon-btn >> nth=' + ((await p.$('.safe-toggle')) ? 1 : 0)) }
  const sideways = () => p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  const select = async (k) => { await tap(`.tl-cue >> nth=${k}`); await p.waitForTimeout(300) }
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.setInputFiles('.srt-import input', `${out}/a.srt`)
    await p.waitForSelector('.cue-list .clip')
    await p.waitForTimeout(2500)
    await p.click(PHONE ? '.back-btn' : '.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project', { timeout: 10000 })
    // Give the saved project words and a bleeped word (the first).
    await p.evaluate(async (words) => {
      const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /projects/.test(n))
      const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
      await new Promise((res, rej) => {
        const t = db.transaction('state', 'readwrite')
        const s = t.objectStore('state')
        const c = s.openCursor()
        c.onsuccess = () => { const cur = c.result; if (!cur) return; cur.update({ ...cur.value, words, bleeped: [0] }); cur.continue() }
        t.oncomplete = res; t.onerror = () => rej(t.error)
      })
      db.close()
    }, WORDS)
    await p.reload()
    await p.waitForSelector('.project')
    await p.click('.project-open')
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(1200)
    await timeline()
    await p.waitForSelector('.tl-cue')
    let c = await cues()
    if (c.length !== 3) fail('the timeline has ' + c.length + ' subtitles')
    if (c.some((x) => x.on)) fail('a subtitle is selected before any tap')
    if (await p.$('.cue-bar')) fail('the subtitle bar shows with nothing selected')
    // The bleeped word's mark is on the timeline, and the first subtitle shows it masked.
    const mark0 = await p.evaluate(() => { const e = document.querySelector('.tl-bleep'); return e ? { l: e.getBoundingClientRect().left + document.querySelector('.tl-scroll').scrollLeft, w: e.getBoundingClientRect().width } : null })
    if (!mark0) fail('no bleep mark on the timeline')
    if (!c[0].text.includes('****')) fail('the bleeped word is not masked in the subtitle: ' + c[0].text)

    // A tap selects it, shows its bar and goes to it.
    await select(0)
    c = await cues()
    if (!c[0].on || c[1].on) fail('the tap did not select the first subtitle only')
    await p.waitForSelector('.cue-bar')
    const t1 = await p.evaluate(() => document.querySelector('video').currentTime)
    if (!near(t1, 1.01, 0.1)) fail('did not go to the subtitle: ' + t1)
    for (const k of ['cue-start', 'cue-end', 'cue-split', 'cue-merge', 'cue-edit', 'cue-delete']) if (!(await p.$('.cue-bar .' + k))) fail('no ' + k + ' in the bar')
    if ((await p.$$('.tl-cue.on .tl-handle')).length !== 2) fail('no handles on the selected subtitle')
    if (PHONE && (await sideways()) > 1) fail('the page is sideways with the bar: ' + (await sideways()))
    await p.screenshot({ path: `${out}/1_selected.png` })

    // Start here / End here (one undo step each).
    await seek(0.5)
    await tap('.cue-start')
    await p.waitForTimeout(200)
    c = await cues()
    if (!near(c[0].a, 0.5, 0.05) || !near(c[0].b, 3, 0.05)) fail('Start here: ' + JSON.stringify(c[0]))
    await seek(3.5)
    await tap('.cue-end')
    c = await cues()
    if (!near(c[0].b, 3.5, 0.05)) fail('End here: ' + JSON.stringify(c[0]))
    // The neighbour (from 4 s) is not run into.
    await seek(5)
    await tap('.cue-end')
    c = await cues()
    if (!(c[0].b <= 4 + 0.001)) fail('the subtitle grew into the next one: ' + c[0].b)
    await undo()
    c = await cues()
    if (!near(c[0].b, 3.5, 0.05)) fail('undo of End here: ' + c[0].b)
    await undo()
    await undo()
    c = await cues()
    if (!near(c[0].a, 1, 0.05) || !near(c[0].b, 3, 0.05)) fail('undo did not bring back the times: ' + JSON.stringify(c[0]))
    if (!c[0].on) fail('undo dropped the selection')

    // Bleeps follow their words: the edge moved past the bleeped word (1.0-1.5 s) leaves it bleeped and masked.
    await seek(1.8)
    await tap('.cue-start')
    c = await cues()
    if (!near(c[0].a, 1.8, 0.05)) fail('start past the word: ' + c[0].a)
    if (!c[0].text.includes('****') || c[0].text.includes('שלום')) fail('the bleeped word shows in the subtitle after its start moved: ' + c[0].text)
    const mark1 = await p.evaluate(() => { const e = document.querySelector('.tl-bleep'); return e ? { l: e.getBoundingClientRect().left + document.querySelector('.tl-scroll').scrollLeft, w: e.getBoundingClientRect().width } : null })
    if (!mark1 || !near(mark1.l, mark0.l, 3) || !near(mark1.w, mark0.w, 1)) fail('the bleep mark moved with the subtitle: ' + JSON.stringify([mark0, mark1]))
    await undo()

    // An edge dragged: one undo step for the whole drag.
    await p.locator('.tl-cue.on').scrollIntoViewIfNeeded()
    const h = await p.locator('.tl-cue.on .tl-handle.end').boundingBox()
    const before = (await cues())[0]
    await p.mouse.move(h.x + h.width / 2, h.y + h.height / 2)
    await p.mouse.down()
    await p.mouse.move(h.x + h.width / 2 + 20, h.y + h.height / 2, { steps: 4 })
    await p.mouse.move(h.x + h.width / 2 + 40, h.y + h.height / 2, { steps: 4 })
    await p.mouse.up()
    await p.waitForTimeout(300)
    c = await cues()
    if (!(c[0].b > before.b + 0.15 && c[0].b <= 4.001)) fail(`dragging the end: ${before.b} -> ${c[0].b}`)
    if (!near(c[0].a, before.a, 0.001)) fail('dragging the end moved the start')
    const dragged = c[0].b
    await undo()
    c = await cues()
    if (!near(c[0].b, before.b, 0.001)) fail(`one undo did not undo the whole drag: ${dragged} -> ${c[0].b}`)
    // The start dragged back (earlier), not past 0.
    const hs = await p.locator('.tl-cue.on .tl-handle.start').boundingBox()
    await p.mouse.move(hs.x + hs.width / 2, hs.y + hs.height / 2)
    await p.mouse.down()
    await p.mouse.move(hs.x + hs.width / 2 - 25, hs.y + hs.height / 2, { steps: 5 })
    await p.mouse.up()
    c = await cues()
    if (!(c[0].a < before.a - 0.1 && c[0].a >= 0)) fail(`dragging the start: ${before.a} -> ${c[0].a}`)
    await undo()

    // Split at the playhead (at the word nearest to it), then merge it back.
    await seek(1.7)
    await tap('.cue-split')
    await p.waitForTimeout(300)
    c = await cues()
    if (c.length !== 4) fail('split: ' + c.length + ' subtitles')
    if (c[0].text.includes('לכולם') || !c[1].text.includes('לכולם')) fail('split in the wrong place: ' + c.map((x) => x.text))
    if (!near(c[0].b, 1.6, 0.05) || !near(c[1].a, 1.6, 0.05)) fail('the split time: ' + JSON.stringify(c.slice(0, 2)))
    if (!c[0].on) fail('the first part is not selected after the split')
    await tap('.cue-merge')
    await p.waitForTimeout(300)
    c = await cues()
    if (c.length !== 3 || !near(c[0].b, 3, 0.05) || !c[0].text.includes('לכולם') || !c[0].text.includes('****')) fail('merge: ' + JSON.stringify(c[0]) + ' of ' + c.length)
    // The last one merges with the one before it.
    await select(2)
    await tap('.cue-merge')
    await p.waitForTimeout(300)
    c = await cues()
    if (c.length !== 2 || !near(c[1].a, 4, 0.05) || !near(c[1].b, 9, 0.05)) fail('merge of the last one: ' + JSON.stringify(c))
    await undo()
    c = await cues()
    if (c.length !== 3) fail('undo of the merge: ' + c.length)

    // Edit text: the properties (text, times) and a typed change.
    await select(1)
    // A wide screen shows the properties at once; on a phone "Edit text" opens them in a sheet (and a second tap closes them).
    if (PHONE) await tap('.cue-edit')
    else if (!(await p.$('.cue-props'))) fail('no properties under the bar on a wide screen')
    else { await tap('.cue-edit'); if (await p.$('.cue-props')) fail('Edit text did not close the properties'); await tap('.cue-edit') }
    await p.waitForSelector(PHONE ? '.cue-sheet .cue-prop-text' : '.cue-props .cue-prop-text')
    const ta = PHONE ? '.cue-sheet .cue-prop-text' : '.cue-props .cue-prop-text'
    if ((await p.inputValue(ta)) !== 'היום נדבר') fail('the text field has the wrong text')
    if (!/4:0\d\.\d.*6:0|0:04\.0.*0:06\.0/.test((await p.textContent(PHONE ? '.cue-sheet' : '.cue-props')).replace(/\s/g, ''))) fail('the times are not in the properties: ' + (await p.textContent(PHONE ? '.cue-sheet' : '.cue-props')))
    await p.fill(ta, 'היום נדבר מחדש')
    await p.waitForTimeout(300)
    c = await cues()
    if (c[1].text !== 'היום נדבר מחדש') fail('the typed text is not on the timeline: ' + c[1].text)
    await p.screenshot({ path: `${out}/2_props.png` })
    if (PHONE) { await tap('.cue-sheet .btn.primary'); await p.waitForSelector('.cue-sheet', { state: 'detached' }) }

    // Delete, and undo; Esc leaves the selection.
    await tap('.cue-delete')
    await p.waitForTimeout(300)
    c = await cues()
    if (c.length !== 2 || (await p.$('.cue-bar'))) fail('delete: ' + c.length)
    await undo()
    c = await cues()
    if (c.length !== 3) fail('undo of the delete: ' + c.length)
    await select(1)
    await p.keyboard.press('Escape')
    await p.waitForTimeout(200)
    if (await p.$('.cue-bar')) fail('Esc did not leave the selection')
    // A blur, a sound and a subtitle are never selected together: the close button leaves it too.
    await select(2)
    await tap('.cue-bar .icon-btn:last-child')
    await p.waitForTimeout(200)
    if (await p.$('.cue-bar')) fail('the close button did not leave the selection')
    console.log('PASS')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.slice(-15).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
