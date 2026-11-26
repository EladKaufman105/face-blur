// Which speech model the subtitles tab offers, without downloading any (Hugging Face is blocked
// here): on a computer Hebrew gets ivrit.ai's model (as the app does) with its size shown before
// anything downloads; on a phone or another weak device "better quality" stays the small
// multilingual model and ivrit.ai is a named choice with a memory warning (a stored "better" never
// becomes it); the other languages keep the multilingual Whisper; no request goes to Hugging Face
// just from looking.
// Run: node tests/subsplan.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
// ivrit.ai's q4 encoder and decoder (bytes) plus the tokenizer and config files: 763 MB.
const IVRIT_MB = 763

async function openSubs(ctx, weak, logs) {
  const p = await ctx.newPage()
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  await p.goto(url)
  await p.setInputFiles('.hero input[type=file]', video)
  await p.click('.blur-faces')
  await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
  if (await p.$('.no-faces')) await p.click('.mark-by-hand')
  await p.waitForSelector('.editor', { timeout: 120000 })
  await p.click('.tabs button[data-g=subs]')
  await p.waitForSelector('.speech-note', { state: 'attached' })
  // The choice of quality and the note are folded under "more options".
  await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
  return p
}

async function scenario(b, weak, logs) {
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  // The device the page sees: a weak one (2 cores) or a computer (8 cores, 8 GB).
  await ctx.addInitScript((w) => {
    Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => (w ? 2 : 8) })
    Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 })
  }, weak)
  let hf = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => { hf++; r.abort() })
  const tag = weak ? 'weak' : 'computer'
  let p = await openSubs(ctx, weak, logs)
  const note = () => p.evaluate(() => [...document.querySelectorAll('.speech-note')].map((e) => e.textContent).join(' '))
  const chips = () => p.evaluate(() => [...document.querySelectorAll('.speech-quality .chip')].map((c) => [c.dataset.q, c.textContent.trim()]))
  // The note follows the choices a moment after the click: wait for it to settle on what is expected (or time out and say what it was).
  const settled = async (ok) => { for (let i = 0; i < 40; i++) { const n = await note(); if (ok(n)) return n; await p.waitForTimeout(150) } return note() }
  const click = (sel) => p.click(sel)
  const mb = (n) => n.includes(`${IVRIT_MB} MB`)
  const ivrit = (n) => n.includes('ivrit.ai')
  await click('.lang-chips .chip >> text=עברית')
  await click('.speech-quality [data-q=better]')
  let n = await settled((x) => x.length > 0)
  console.log(tag, 'hebrew, better:', JSON.stringify(await chips()))
  if (weak) {
    // "Better quality" stays the small multilingual model; the Hebrew model is its own, named choice.
    n = await settled((x) => !ivrit(x))
    if (ivrit(n) || mb(n)) throw new Error('weak: "better" became the Hebrew model: ' + n)
    const cs = await chips()
    if (!cs.some(([q, l]) => q === 'hebrew' && l.includes(`${IVRIT_MB} MB`))) throw new Error('weak: no Hebrew model choice with its size: ' + JSON.stringify(cs))
    await click('.speech-quality [data-q=hebrew]')
    n = await settled(ivrit)
    if (!ivrit(n) || !mb(n)) throw new Error('weak: the Hebrew choice is not ivrit.ai: ' + n)
    if (!(await p.$('.speech-memory'))) throw new Error('weak: no memory warning on the Hebrew model')
    // A stored "better" (from before) is never read as the Hebrew model.
    await click('.speech-quality [data-q=better]')
    await p.reload()
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 120000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.speech-note', { state: 'attached' })
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    n = await note()
    if (ivrit(n)) throw new Error('weak: a stored "better" became the Hebrew model after a reload: ' + n)
  } else {
    n = await settled(ivrit)
    if (!ivrit(n) || !mb(n)) throw new Error('computer: Hebrew is not ivrit.ai with its size: ' + n)
    if ((await chips()).some(([q]) => q === 'hebrew')) throw new Error('computer: a separate Hebrew choice is not needed')
    await click('.speech-quality [data-q=fast]')
    n = await settled((x) => !ivrit(x))
    if (ivrit(n)) throw new Error('computer: "faster" is still the Hebrew model: ' + n)
    await click('.speech-quality [data-q=better]')
    n = await settled(ivrit)
    if (!ivrit(n)) throw new Error('computer: "better quality" in Hebrew is not ivrit.ai: ' + n)
  }
  // Arabic, Persian, English: the multilingual Whisper, never the Hebrew model.
  for (const label of ['ערבית', 'פרסית', 'אנגלית']) {
    await click(`.lang-chips .chip >> text=${label}`)
    n = await settled((x) => !ivrit(x))
    console.log(tag, label + ':', n.trim())
    if (ivrit(n) || mb(n)) throw new Error(tag + ': ' + label + ' got the Hebrew model: ' + n)
  }
  if (!weak) {
    // Back to Hebrew: the choice is kept for the next visit.
    await click('.lang-chips .chip >> text=עברית')
    await p.reload()
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 120000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.speech-note', { state: 'attached' })
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    n = await settled(ivrit)
    if (!ivrit(n)) throw new Error('Hebrew was not kept after a reload: ' + n)
  }
  if (hf) throw new Error(tag + ': ' + hf + ' requests to Hugging Face before the person asked for the subtitles')
  await ctx.close()
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {})
  const logs = []
  try {
    await scenario(b, false, logs)
    await scenario(b, true, logs)
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
