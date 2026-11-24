// Importing an SRT over subtitles that have words and bleeped words: it asks first (and says
// what goes); "keep" changes nothing; "replace" does, and undo brings back the subtitles, their
// words and every bleeped word. The words come from a saved project (the speech model is not
// needed): the project is made, its record given words, and opened again.
// Run: node tests/subsimport.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const A = `1\n00:00:01,000 --> 00:00:03,000\nשלום לכולם\n\n2\n00:00:04,000 --> 00:00:06,000\nהיום נדבר\n\n3\n00:00:08,000 --> 00:00:09,000\nתודה רבה\n`
const B = `1\n00:00:01,000 --> 00:00:03,000\nקובץ חדש\n\n2\n00:00:04,000 --> 00:00:06,000\nעוד שורה\n`
const WORDS = [['שלום', 1, 1.5], ['לכולם', 1.6, 2.5], ['היום', 4, 4.5], ['נדבר', 4.6, 5.5], ['תודה', 8, 8.4], ['רבה', 8.5, 9]].map(([text, start, end]) => ({ text: ' ' + text, start, end }))

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/a.srt`, A)
  fs.writeFileSync(`${out}/b.srt`, B)
  fs.writeFileSync(`${out}/bad.srt`, 'not subtitles')
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const fail = (m) => { throw new Error(m) }
  const cues = () => p.evaluate(() => [...document.querySelectorAll('.cue-list .cue-text')].map((x) => x.value))
  const bleepCount = () => p.evaluate(() => document.querySelector('.card.bleep .section-title span')?.textContent ?? null)
  const openMore = () => p.evaluate(() => { document.querySelector('details.subs-more').open = true })
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.setInputFiles('.srt-import input', `${out}/a.srt`)
    await p.waitForSelector('.cue-list .clip')
    if (await p.$('.srt-confirm')) fail('asked before replacing nothing')
    await p.waitForTimeout(2500)
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project', { timeout: 10000 })
    // Give the saved project words and a bleeped word.
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
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.cue-list .clip')
    if ((await cues()).length !== 3) fail('the project was not opened with its subtitles')
    if (!(await p.$('.card.bleep'))) fail('no words in the project')
    if (!(await bleepCount())?.includes('מילה אחת')) fail('the bleeped word is not there: ' + (await bleepCount()))
    await openMore()

    // A file without subtitles: the error shows, and does not offer to start the recognition.
    await p.setInputFiles('.srt-import input', `${out}/bad.srt`)
    await p.waitForSelector('.srt-error')
    if (await p.$('.subs-start')) fail('the speech card shows over existing subtitles')
    if (await p.$('text=נסה שוב')) fail('"try again" shows for an import error')

    // Asks first, and says what goes.
    await p.setInputFiles('.srt-import input', `${out}/b.srt`)
    await p.waitForSelector('.srt-confirm')
    if (await p.$('.srt-error')) fail('the old error stays')
    const ask = await p.textContent('.srt-confirm')
    console.log('asks:', ask)
    if (!/1/.test(ask)) fail('the question does not say how many bleeped words go')
    if ((await cues()).length !== 3) fail('replaced before the answer')
    // Keep: nothing changes.
    await p.click('.srt-confirm-no')
    if (await p.$('.srt-confirm')) fail('the question stays')
    if ((await cues()).join() !== 'שלום לכולם,היום נדבר,תודה רבה' || !(await bleepCount())?.includes('מילה אחת')) fail('keeping changed something')

    // Replace, then undo.
    await p.setInputFiles('.srt-import input', `${out}/b.srt`)
    await p.waitForSelector('.srt-confirm')
    await p.click('.srt-confirm-yes')
    await p.waitForFunction(() => document.querySelectorAll('.cue-list .cue-text').length === 2)
    if ((await cues()).join() !== 'קובץ חדש,עוד שורה') fail('not replaced: ' + (await cues()))
    if (await p.$('.card.bleep')) fail('the words stay after the replacement')
    await p.mouse.click(5, 5)
    await p.keyboard.press('Control+z')
    await p.waitForFunction(() => document.querySelectorAll('.cue-list .cue-text').length === 3)
    if ((await cues()).join() !== 'שלום לכולם,היום נדבר,תודה רבה') fail('undo: the subtitles did not come back')
    if (!(await bleepCount())?.includes('מילה אחת')) fail('undo: the bleeped word did not come back: ' + (await bleepCount()))
    // And the words: the word chips are there again.
    await p.click('.card.bleep .actions .btn')
    if ((await p.$$('.word-chips .word')).length !== 6) fail('undo: the words did not come back')
    if ((await p.$$('.word-chips .word.on')).length !== 1) fail('undo: the bleep is not on its word')
    // Redo goes again.
    await p.click('.card.bleep .actions .btn')
    await p.mouse.click(5, 5)
    await p.keyboard.press('Control+Shift+z')
    await p.waitForFunction(() => document.querySelectorAll('.cue-list .cue-text').length === 2)
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
