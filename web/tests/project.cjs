// Subtitles edited as a list (from an SRT file): split at the cursor, joined, found and replaced,
// added and deleted, translated (the browser's translator, stood in for here) and shown with
// their translation; then the project: kept while editing, opened again as it was, renamed,
// deleted.
// Run: node tests/project.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

const SRT = `1
00:00:01,000 --> 00:00:03,000
שלום לכולם וברוכים הבאים

2
00:00:04,000 --> 00:00:06,000
היום נדבר על עריכה

3
00:00:08,000 --> 00:00:09,000
תודה רבה
`

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  // The browser's translator (Chrome's Translator API), stood in for: it marks the language.
  await ctx.addInitScript(() => {
    window.Translator = {
      availability: async () => 'available',
      create: async (o) => ({ translate: async (s) => `[${o.targetLanguage}] ${s}`, destroy() {} }),
    }
  })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const cues = () => p.evaluate(() => [...document.querySelectorAll('.cue-list .clip')].map((c) => [...c.querySelectorAll('textarea')].map((x) => x.value).join(' / ')))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(400) }
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.setInputFiles('.srt-import input', `${out}/in.srt`)
    await p.waitForSelector('.cue-list .clip')
    // The less used tools are folded under "more options" and "fine-tune" (a phone's short screen).
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    console.log('imported:', JSON.stringify(await cues()))
    if ((await cues()).length !== 3) throw new Error('SRT import')

    // Split the first after two words, join it back.
    await p.evaluate(() => { const el = document.querySelector('.cue-list .cue-text'); el.focus(); el.setSelectionRange(10, 10); el.dispatchEvent(new Event('select', { bubbles: true })) })
    await p.click('.cue-split >> nth=0')
    const split = await cues()
    await p.click('.cue-merge >> nth=0')
    const joined = await cues()
    console.log('split:', JSON.stringify(split.slice(0, 2)), '→ joined:', joined[0])
    if (split.length !== 4 || joined.length !== 3) throw new Error('split / join')

    // Find and replace.
    await p.click('text=חפש והחלף')
    await p.fill('.find-in', 'עריכה')
    await p.fill('.repl-in', 'וידאו')
    await p.click('.replace-all')
    const found = await p.textContent('.find-result')
    console.log('replace:', found, '·', (await cues())[1])
    if (!(await cues())[1].includes('וידאו')) throw new Error('replace')

    // One added at 10.5 s, then deleted.
    await seek(10.5)
    await p.click('.cue-add')
    const added = (await cues()).length
    // A subtitle's actions show on the one being edited: tap it first.
    await p.click('.cue-list .cue-text >> nth=3')
    await p.click('.cue-delete')
    console.log('added →', added, 'deleted →', (await cues()).length)
    if (added !== 4 || (await cues()).length !== 3) throw new Error('add / delete')

    // Translated, shown with the text; undone and redone.
    await p.click('.translate-go')
    await p.waitForSelector('.cue-tr')
    const tr = await p.evaluate(() => [...document.querySelectorAll('.cue-tr')].map((x) => x.value))
    const both = await p.evaluate(() => document.querySelector('.sub-show .on')?.textContent)
    console.log('translated:', JSON.stringify(tr), '· shown:', both)
    if (tr.length !== 3 || !tr[0].startsWith('[en]')) throw new Error('translation')
    await seek(1.5)
    await p.screenshot({ path: `${out}/1_translated.png` })
    await p.mouse.click(5, 5)
    await p.keyboard.press('Control+z'); await p.waitForTimeout(300)
    const undone = await p.evaluate(() => document.querySelectorAll('.cue-tr').length)
    await p.keyboard.press('Control+Shift+z'); await p.waitForTimeout(300)
    const redone = await p.evaluate(() => document.querySelectorAll('.cue-tr').length)
    console.log('undo translation →', undone, 'redo →', redone)
    if (undone !== 0 || redone !== 3) throw new Error('undo / redo of the translation')

    // The SRT has both lines.
    await p.click('.export-bar .btn')
    await p.click('.export-sheet .switch')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const srt = await p.evaluate(async () => (await fetch(document.querySelector('.srt-link').href)).text())
    console.log('SRT:', srt.split('\n').slice(0, 4).join(' | '))
    if (!srt.includes('[en] ')) throw new Error('SRT without the translation')
    await p.click('text=חזרה לעריכה')

    // The project: a watermark and a cut, kept; the home screen lists it; opened again as it was.
    await p.click('.tabs button[data-g=mark]')
    await p.fill('.text-in', '@proj')
    await p.click('.tabs button[data-g=edit]')
    await seek(2)
    await p.click('.cut-start')
    await seek(4)
    await p.click('.cut-end')
    await p.waitForTimeout(2500)
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project', { timeout: 10000 })
    const listed = await p.evaluate(() => [...document.querySelectorAll('.project')].map((e) => e.textContent))
    // What it has, as pills: opened from a tool (no face search), so "subtitles only"; and the cut.
    const pills = await p.evaluate(() => [...document.querySelectorAll('.project .ppill')].map((e) => e.textContent))
    console.log('projects:', JSON.stringify(listed), '· pills:', JSON.stringify(pills))
    await p.screenshot({ path: `${out}/2_home.png` })
    if (listed.length !== 1 || !listed[0].includes('speech_two') || JSON.stringify(pills) !== JSON.stringify(['כתוביות בלבד · 3', 'חיתוך אחד'])) throw new Error('project list')
    await p.click('.project-open')
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.click('.tabs button[data-g=subs]')
    const back = await cues()
    await p.click('.tabs button[data-g=mark]')
    const mark = await p.inputValue('.text-in')
    await p.click('.tabs button[data-g=edit]')
    const cut = Number(await p.getAttribute('.cut', 'data-total'))
    console.log('opened again:', back.length, 'subtitles,', back[0], '· watermark', mark, '· cut', cut.toFixed(2))
    if (back.length !== 3 || !back[0].includes('[en]') || mark !== '@proj' || Math.abs(cut - 2) > 0.1) throw new Error('the project was not kept')

    // Renamed, then deleted.
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project')
    await p.click('.project-rename')
    await p.fill('.rename-in', 'הסרטון שלי')
    await p.click('.project .btn.tonal')
    await p.waitForFunction(() => document.querySelector('.project-name')?.textContent === 'הסרטון שלי')
    await p.click('.project-delete')
    await p.click('.project-delete-yes')
    await p.waitForFunction(() => document.querySelectorAll('.project').length === 0)
    console.log('renamed and deleted')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
