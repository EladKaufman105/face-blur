// A subtitle edited so that a bleeped word of it is in none of its words: the editor says so (an alert, a mark on the
// timeline), exporting asks first, and A subtitle deleted leaves
// its bleeped words (they stay bleeped) which the subtitles tab lists. The words come from a saved project, as in cues.cjs.
// Run: node tests/maskwarn.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out, device] = process.argv
const PHONE = false
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
    await p.waitForSelector('.tl-cue.warn', { state: 'detached', timeout: 1000 }).catch(() => {})
    if (await p.$('.mask-warn')) fail('a warning with nothing edited')
    // Edited so the bleeped word (the first) is gone and the count no longer fits: a warning.
    await select(0)
    await p.waitForSelector('.cue-props .cue-prop-text')
    await p.fill('.cue-props .cue-prop-text', 'ברכה לכולם ועוד')
    await p.waitForSelector('.mask-warn', { timeout: 3000 })
    if ((await p.$$('.tl-cue.warn')).length !== 1) fail('the subtitle is not marked on the timeline')
    if (!(await p.$('.cue-props .cue-mask-warn'))) fail('no warning in the subtitle bar')
    // Typing the word back (anywhere) masks it by itself and takes the warning away.
    await p.fill('.cue-props .cue-prop-text', 'ברכה שלום לכולם ועוד')
    await p.waitForFunction(() => !document.querySelector('.mask-warn'), null, { timeout: 3000 })
    if (!(await cues())[0].text.includes('ברכה ****')) fail('the retyped word is not masked: ' + (await cues())[0].text)
    // The warning again, and the export asks first.
    await p.fill('.cue-props .cue-prop-text', 'ברכה לכולם ועוד')
    await p.waitForSelector('.mask-warn')
    await p.click('.export-bar .btn')
    await p.waitForSelector('.export-go')
    if (!(await p.$eval('.export-go', (e) => e.disabled))) fail('exporting is not blocked with a subtitle that may show a bleeped word')
    await p.check('.mask-warn-ok')
    if (await p.$eval('.export-go', (e) => e.disabled)) fail('confirming does not unblock the export')
    await p.press('body', 'Escape')
    await p.waitForSelector('.export-go', { state: 'detached' })
    await p.screenshot({ path: `${out}/1_warn.png` })
    // Undo takes the edit back, and the warning with it.
    await p.fill('.cue-props .cue-prop-text', 'שלום לכולם')
    await p.waitForFunction(() => !document.querySelector('.mask-warn'))
    // Deleting the subtitle with the bleeped word leaves the bleep: the notice, and the tab lists it.
    await select(0)
    await tap('.cue-delete')
    await p.waitForSelector('.toast')
    if (!/מצופצפת/.test(await p.textContent('.toast'))) fail('no notice of the bleep that stays: ' + (await p.textContent('.toast')))
    await tap('.tabs button[data-g=subs]')
    await p.click('.panel-head [data-sub=bleep]').catch(() => {})
    await p.waitForSelector('.orphan-bleeps', { timeout: 5000 })
    await p.click('.orphan-clear')
    await p.waitForSelector('.orphan-bleeps', { state: 'detached' })
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
