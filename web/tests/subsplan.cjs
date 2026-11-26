// Which speech model the subtitles tab offers, without downloading any (Hugging Face is blocked
// here): Hebrew gets ivrit.ai's model (as the app does) with its size shown before anything
// downloads; the other languages keep the multilingual Whisper; "faster" in Hebrew is the small
// multilingual one; no request goes to Hugging Face just from looking.
// Run: node tests/subsplan.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
// ivrit.ai's q4 encoder and decoder (bytes) plus the tokenizer and config files: 763 MB.
const IVRIT_MB = 763

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {})
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  let hf = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => { hf++; r.abort() })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  // The choice of quality and the note are folded under "more options".
  const open = () => p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
  const note = () => p.evaluate(() => [...document.querySelectorAll('.speech-note')].map((e) => e.textContent).join(' '))
  // The note follows the choices a moment after the click: wait for it to settle on what is expected (or time out and say what it was).
  const settled = async (ok) => { for (let i = 0; i < 40; i++) { const n = await note(); if (ok(n)) return n; await p.waitForTimeout(150) } return note() }
  const q = (k) => p.locator(`.speech-quality [data-q=${k}]`).first().textContent()
  try {
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 120000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.speech-note', { state: 'attached' })
    await open()
    // Hebrew: "better quality" is the ivrit.ai model, its size shown (it is the default too on a
    // computer that isn't a phone; this runner's guess can be either, so the choice is made here).
    await p.click('.lang-chips .chip >> text=עברית')
    await p.click('.speech-quality [data-q=better]')
    let n = await settled((x) => x.includes('ivrit.ai'))
    console.log('hebrew:', n.trim(), '|', (await q('better')).trim(), '|', (await q('fast')).trim())
    if (!n.includes('ivrit.ai') || !n.includes(`${IVRIT_MB} MB`)) throw new Error('Hebrew is not ivrit.ai with its size: ' + n)
    // "Faster": the small multilingual model, with its own (smaller) size.
    await p.click('.speech-quality [data-q=fast]')
    n = await settled((x) => !x.includes('ivrit.ai'))
    console.log('hebrew, faster:', n.trim())
    if (n.includes('ivrit.ai') || n.includes(`${IVRIT_MB} MB`)) throw new Error('"faster" is still the Hebrew model: ' + n)
    await p.click('.speech-quality [data-q=better]')
    n = await settled((x) => x.includes('ivrit.ai'))
    if (!n.includes('ivrit.ai')) throw new Error('"better quality" in Hebrew is not ivrit.ai: ' + n)
    // Arabic, Persian, English: the multilingual Whisper, never the Hebrew model.
    for (const label of ['ערבית', 'פרסית', 'אנגלית']) {
      await p.click(`.lang-chips .chip >> text=${label}`)
      n = await settled((x) => !x.includes('ivrit.ai'))
      console.log(label + ':', n.trim())
      if (n.includes('ivrit.ai') || n.includes(`${IVRIT_MB} MB`)) throw new Error(label + ' got the Hebrew model: ' + n)
    }
    // Back to Hebrew: the choice is kept for the next visit.
    await p.click('.lang-chips .chip >> text=עברית')
    await p.reload()
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 120000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.speech-note', { state: 'attached' })
    await open()
    { const n2 = await settled((x) => x.includes('ivrit.ai')); console.log('after reload:', n2.trim(), JSON.stringify(await p.evaluate(() => Object.entries(localStorage).filter(([k]) => /subs/.test(k))))); if (!n2.includes('ivrit.ai')) throw new Error('Hebrew was not kept after a reload') }
    if (hf) throw new Error(hf + ' requests to Hugging Face before the person asked for the subtitles')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
