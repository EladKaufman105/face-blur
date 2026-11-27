// The SRT is no switch in the export sheet: "Save subtitles (SRT)" is in the subtitles panel while editing (and on the done
// screen, tests/style.cjs). The file has the MASKED text (a bleeped word is ****); the unmasked one, for editing, is a second
// choice that asks first. The words come from a saved project (a bleeped first word), as in maskwarn.cjs.
// Run: node tests/srtsave.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const A = `1\n00:00:01,000 --> 00:00:03,000\nשלום לכולם\n\n2\n00:00:04,000 --> 00:00:06,000\nהיום נדבר\n`
const WORDS = [['שלום', 1, 1.5], ['לכולם', 1.6, 2.5], ['היום', 4, 4.5], ['נדבר', 4.6, 5.5]].map(([text, start, end]) => ({ text: ' ' + text, start, end }))

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/a.srt`, A)
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', acceptDownloads: true })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const fail = (m) => { throw new Error(m) }
  const download = async (sel) => {
    const [d] = await Promise.all([p.waitForEvent('download'), p.click(sel)])
    return fs.readFileSync(await d.path(), 'utf8')
  }
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
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project', { timeout: 10000 })
    await p.evaluate(async (words) => {
      const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /projects/.test(n))
      const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
      await new Promise((res, rej) => {
        const t = db.transaction('state', 'readwrite')
        const c = t.objectStore('state').openCursor()
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
    await p.click('.tabs button[data-g=subs]')
    console.log(await p.evaluate(() => [!!document.querySelector('.cue-list'), !!document.querySelector('.srt-tools'), !!document.querySelector('.srt-save'), document.querySelectorAll('.tl-cue').length]))
    await p.waitForSelector('.srt-save')

    // The export sheet has no SRT switch.
    await p.click('.export-bar .btn')
    await p.waitForSelector('.export-go')
    if (await p.$('.export-sheet .switch, .export-srt')) fail('the export sheet still has an SRT switch')
    await p.press('body', 'Escape')
    await p.waitForSelector('.export-go', { state: 'detached' })

    // The main action: the masked file.
    const masked = await download('.srt-save')
    console.log('masked:', JSON.stringify(masked))
    if (!masked.includes('-->') || !masked.includes('**** לכולם') || masked.includes('שלום')) fail('the saved SRT is not masked: ' + masked)
    // The unmasked one, for editing: second choice, asks first, has every word.
    if (!(await p.$('.srt-download'))) fail('no unmasked choice with a bleeped word')
    await p.click('.srt-download')
    await p.waitForSelector('.srt-ask')
    const full = await download('.srt-ask-yes')
    if (!full.includes('שלום לכולם')) fail('the unmasked SRT lacks the word: ' + full)
    await p.screenshot({ path: `${out}/srt.png` })

    // With the mask off the main action would show the bleeped word: it asks first, as the unmasked choice does, and the
    // unmasked choice (the same file) is not offered twice.
    await p.click('.mask-subs .switch')
    await p.waitForTimeout(300)
    if (await p.$('.srt-download')) fail('the unmasked choice is offered next to a main action that is unmasked too')
    let early = false
    p.once('download', () => { early = true })
    await p.click('.srt-save')
    await p.waitForSelector('.srt-ask')
    await p.waitForTimeout(400)
    if (early) fail('the SRT with the bleeped words downloaded without asking')
    const shown = await download('.srt-ask-yes')
    if (!shown.includes('שלום לכולם')) fail('the confirmed SRT lacks the bleeped word: ' + shown)

    // The done screen, with the mask off and a bleeped word: the same ask, no download before the confirm.
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 600000 })
    if (await p.$('.card.error')) fail('the export failed')
    if (await p.$('a.srt-link:not(.srt-shows)')) fail('the done screen offers the SRT without asking')
    await p.waitForSelector('.srt-shows')
    let early2 = false
    p.once('download', () => { early2 = true })
    await p.click('.srt-shows')
    await p.waitForSelector('.srt-ask')
    await p.waitForTimeout(400)
    if (early2) fail('the done screen saved the SRT with the bleeped words without asking')
    const shown2 = await download('.srt-ask-yes')
    if (!shown2.includes('שלום לכולם')) fail('the confirmed done-screen SRT lacks the bleeped word: ' + shown2)
    console.log('PASS')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.filter((l) => !/adapters|GL Driver/.test(l)).slice(-10).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
