// Which speech model the subtitles tab offers, without downloading any (Hugging Face is blocked
// here): on a computer Hebrew gets ivrit.ai's model (as the app does) with its size shown before
// anything downloads; on a phone or another weak device "better quality" stays the small
// multilingual model and ivrit.ai is a named choice with a memory warning (a stored "better" never
// becomes it); the other languages keep the multilingual Whisper, also when "Hebrew model" was the
// last choice (it is kept when the language changes: the editor, the batch setup and an automatic
// language that isn't Hebrew all read it as "better quality"); ivrit.ai on one CPU thread says it is
// slow; no request goes to Hugging Face just from looking.
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
  const checked = (root = '') => p.evaluate((r) => document.querySelector(`${r} .speech-quality [aria-checked=true]`)?.dataset.q ?? null, root)
  // The slow hint shows once the device was asked (a moment after the page shows).
  const slowShown = async (want) => { for (let i = 0; i < 40; i++) { const s = !!(await p.$('.speech-slow')); if (s === want) return s; await p.waitForTimeout(150) } return !want }
  const reopen = async () => {
    await p.reload()
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 120000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 120000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.speech-note', { state: 'attached' })
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
  }
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
    // "Hebrew model" is kept when the language changes: any other language is "better quality" (the small multilingual model here), shown as chosen.
    for (const label of ['ערבית', 'אנגלית', 'פרסית']) {
      await click(`.lang-chips .chip >> text=${label}`)
      n = await settled((x) => !ivrit(x))
      console.log(tag, '"Hebrew model" chosen, then', label + ':', n.trim(), '· chosen:', await checked())
      if (ivrit(n) || mb(n)) throw new Error(`weak: with "Hebrew model" chosen, ${label} got the Hebrew model: ` + n)
      if ((await checked()) !== 'better') throw new Error(`weak: with "Hebrew model" chosen, ${label} does not show "better quality" as chosen: ` + (await checked()))
      if (await p.$('.speech-quality [data-q=hebrew]')) throw new Error(`weak: ${label} offers the Hebrew model`)
    }
    await click('.lang-chips .chip >> text=עברית')
    n = await settled(ivrit)
    if (!ivrit(n) || (await checked()) !== 'hebrew') throw new Error('weak: back in Hebrew, the kept "Hebrew model" is not ivrit.ai: ' + n)
    // Automatic, in the English app (automatic is then English): the same.
    await p.evaluate(() => {
      const k = Object.keys(localStorage).find((x) => x.endsWith('.subs.quality'))
      const prefix = k.slice(0, -'subs.quality'.length)
      localStorage.setItem(prefix + 'lang', 'en')
      localStorage.removeItem(prefix + 'subs.lang')
    })
    await reopen()
    await click('.lang-chips .chip >> nth=0')
    n = await settled((x) => !ivrit(x))
    console.log(tag, '"Hebrew model" chosen, English app, automatic:', n.trim(), '· chosen:', await checked())
    if (ivrit(n) || mb(n)) throw new Error('weak: automatic in the English app got the Hebrew model: ' + n)
    if ((await checked()) !== 'better') throw new Error('weak: automatic in the English app does not show "better quality" as chosen')
    await click('.lang-chips .chip >> nth=1')
    n = await settled(ivrit)
    if (!ivrit(n)) throw new Error('weak: Hebrew (English app) with "Hebrew model" kept is not ivrit.ai: ' + n)
    await p.evaluate(() => {
      const k = Object.keys(localStorage).find((x) => x.endsWith('.subs.quality'))
      localStorage.setItem(k.slice(0, -'subs.quality'.length) + 'lang', 'he')
    })
    // The batch setup (and so its videos) reads the same plan: Arabic with "Hebrew model" kept is the multilingual model.
    await p.goto(url)
    await p.click('.several')
    await p.waitForSelector('.batch-setup')
    await p.setInputFiles('.batch-input', [video])
    await p.waitForFunction(() => document.querySelectorAll('.batch-file').length === 1)
    await p.click('.tpl-card[data-tpl="look.minimal"]')
    await p.click('.tpl-lang .chip >> text=עברית')
    await p.waitForSelector('.batch-model')
    if (!(await p.textContent('.batch-model')).includes(`${IVRIT_MB} MB`)) throw new Error('weak batch: Hebrew with "Hebrew model" kept is not the Hebrew model: ' + (await p.textContent('.batch-model')))
    await p.click('.tpl-lang .chip >> text=ערבית')
    let bm = ''
    for (let i = 0; i < 40; i++) { bm = await p.textContent('.batch-model'); if (!bm.includes(`${IVRIT_MB} MB`)) break; await p.waitForTimeout(150) }
    console.log(tag, 'batch, Arabic:', bm, '· chosen:', await checked('.batch-setup'))
    if (bm.includes(`${IVRIT_MB} MB`)) throw new Error('weak batch: Arabic with "Hebrew model" kept downloads the Hebrew model: ' + bm)
    if ((await checked('.batch-setup')) !== 'better') throw new Error('weak batch: Arabic does not show "better quality" as chosen')
    await p.click('.tpl-lang .chip >> text=עברית')
    await p.goto(url)
    await reopen()
    await click('.speech-quality [data-q=hebrew]')
    n = await settled(ivrit)
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
    // This page has no threads (?coi=0) and runs on the CPU (?backend=wasm): ivrit.ai is slow here, and it says so.
    if (!(await slowShown(true))) throw new Error('computer: no "slow on this device" hint for ivrit.ai on one CPU thread')
    await click('.speech-quality [data-q=fast]')
    n = await settled((x) => !ivrit(x))
    if (ivrit(n)) throw new Error('computer: "faster" is still the Hebrew model: ' + n)
    if (await slowShown(false)) throw new Error('computer: the slow hint shows for "faster"')
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
