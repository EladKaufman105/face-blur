// Hebrew subtitles with ivrit.ai's model (as the app does): the model downloads from Hugging Face
// (760 MB), runs on the CPU here, and the subtitles come back as Hebrew text with word timings
// (the bleeps depend on them: a word can be picked and hidden).
// Run: node tests/subshe.cjs <url> <video with Hebrew speech> <expected words, a regex> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, expect, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {})
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  try {
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 900000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 900000 })
    await p.click('.tabs button[data-g=subs]')
    await p.click('.lang-chips .chip >> text=עברית')
    // "Better quality" in Hebrew is ivrit.ai's model; the size is shown before the download.
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    await p.click('.speech-quality [data-q=better]')
    const note = await p.evaluate(() => [...document.querySelectorAll('.speech-note')].map((e) => e.textContent).join(' '))
    console.log('note:', note.trim())
    if (!note.includes('ivrit.ai') || !note.includes('763 MB')) throw new Error('the Hebrew model and its size are not shown: ' + note)
    const t0 = Date.now()
    await p.click('text=צור כתוביות')
    let last = ''
    while (Date.now() - t0 < 45 * 60000) {
      if (await p.$('.cue-text')) break
      const err = await p.evaluate(() => document.querySelector('.card p[style*="primary"]')?.textContent)
      if (err) throw new Error(err)
      const s = await p.evaluate(() => [...document.querySelectorAll('.card strong, .card .small')].map((e) => e.textContent).join(' | ').slice(0, 160))
      if (s !== last) { console.log(((Date.now() - t0) / 1000).toFixed(1), 's', s); last = s }
      await p.waitForTimeout(1000)
    }
    const cues = await p.evaluate(() => [...document.querySelectorAll('.cue-text')].map((e) => e.value))
    const ran = logs.filter((l) => l.includes('ONIT subs:'))
    for (const l of ran) console.log(l)
    console.log('subtitles', ((Date.now() - t0) / 1000).toFixed(1), 's:', cues.length, 'cues')
    for (const c of cues) console.log('  ' + c)
    if (!ran.some((l) => l.includes('ivrit'))) throw new Error('the recognition did not run on the ivrit.ai model')
    if (!cues.length) throw new Error('no subtitles')
    if (!/[֐-׿]/.test(cues.join(' '))) throw new Error('the subtitles are not Hebrew: ' + cues.join(' '))
    if (!new RegExp(expect).test(cues.join(' '))) throw new Error(`expected /${expect}/ in: ` + cues.join(' '))
    // Word timings: the words are separate and timed (the cues start inside the clip, in order), so a word can be bleeped.
    const times = await p.evaluate(() => [...document.querySelectorAll('.cue-list .time-btn')].map((e) => e.textContent))
    console.log('cue times:', JSON.stringify(times))
    await p.click('button:text-is("בחר מילים")')
    const words = await p.evaluate(() => [...document.querySelectorAll('.word-chips .word')].map((e) => e.textContent))
    console.log('words:', words.length, JSON.stringify(words.slice(0, 12)))
    if (words.length < 6) throw new Error('too few timed words: ' + words.length)
    await p.screenshot({ path: `${out}/subs_he.png` })
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
