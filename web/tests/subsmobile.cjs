// The subtitles flow on a phone (390 x 844, Hebrew), without the speech model (Hugging Face is
// blocked; the subtitles come from an SRT, the one way that needs no download): the one primary
// button is on the first screen, not under the export bar; the look cards are pictures drawn by the
// renderer, in a row that scrolls; one tap applies a look and the video shows it; a subtitle is
// edited where it is (its actions show on the one tapped); nothing overflows sideways; the
// buttons and chips are 44 px tall at least.
// Run: node tests/subsmobile.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const SRT = `1\n00:00:00,500 --> 00:00:02,500\nשלום לכולם ברוכים הבאים\n\n2\n00:00:02,600 --> 00:00:05,000\nזה סרטון לדוגמה\n\n3\n00:00:05,100 --> 00:00:08,000\nעם כתוביות בעברית\n`

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, locale: 'he-IL', hasTouch: true, isMobile: true })
  let hf = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => { hf++; r.abort() })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const fail = (msg) => { throw new Error(msg) }
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(500) }
  // A fingerprint of the lower part of the video's picture (a screenshot: the frame is drawn with WebGL).
  const frame = async () => require('crypto').createHash('md5').update(await p.locator('.view').screenshot()).digest('hex')
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 60000 })
    await p.waitForSelector('.subs-create')

    // ---- the first screen: the primary action is there, whole, above the export bar; nothing scrolls sideways.
    const first = await p.evaluate(() => {
      const r = document.querySelector('.subs-create').getBoundingClientRect()
      const e = document.querySelector('.export-bar').getBoundingClientRect()
      return { top: r.top, bottom: r.bottom, h: r.height, barTop: e.top, vh: innerHeight, sideways: document.documentElement.scrollWidth > innerWidth }
    })
    console.log('first screen:', JSON.stringify(first))
    if (first.top < 0 || first.bottom > first.barTop || first.h < 44) fail('"create subtitles" is not whole on the first screen: ' + JSON.stringify(first))
    if (first.sideways) fail('the page scrolls sideways')
    await p.screenshot({ path: `${out}/1_start.png` })

    // ---- the look cards: drawn, in a row that scrolls, many of them
    await p.waitForSelector('.preset-row .chip.preset canvas')
    if (await p.evaluate(() => scrollY) !== 0) fail('the page jumped when the row of looks showed')
    // The cards are drawn as they come near the screen: go along the row, as a finger does.
    await p.evaluate(async () => {
      const r = document.querySelector('.preset-row')
      const dir = getComputedStyle(r).direction === 'rtl' ? -1 : 1
      for (let x = 0; x <= r.scrollWidth + 300; x += 250) { r.scrollLeft = dir * x; await new Promise((ok) => setTimeout(ok, 120)) }
      r.scrollLeft = 0
    })
    await p.waitForTimeout(1200)
    const cards = await p.evaluate(() => [...document.querySelectorAll('.preset-row .chip.preset')].map((c) => {
      const k = c.querySelector('canvas')
      const d = k.getContext('2d').getImageData(0, 0, k.width, k.height).data
      const seen = new Set()
      for (let i = 0; i < d.length; i += 4 * 61) seen.add((d[i] >> 4) * 256 + (d[i + 1] >> 4) * 16 + (d[i + 2] >> 4))
      return [c.textContent.trim(), seen.size, c.classList.contains('on')]
    }))
    console.log('cards:', cards.length, cards.slice(0, 4).map((c) => c.join(':')).join(' '))
    if (cards.length !== 28) fail('expected the 28 looks as cards, got ' + cards.length)
    if (cards.some((c) => c[1] < 6)) fail('a card is not drawn: ' + JSON.stringify(cards.filter((c) => c[1] < 6)))
    if (cards.filter((c) => c[2]).length !== 1 || !cards[0][2]) fail('the chosen look is not the one highlighted')
    const scrolls = await p.evaluate(() => { const r = document.querySelector('.preset-row'); return r.scrollWidth > r.clientWidth + 50 })
    if (!scrolls) fail('the row of looks does not scroll')

    // ---- the subtitles (from an SRT: no model); the video shows the first at once
    await p.setInputFiles('.srt-import input', `${out}/in.srt`)
    await p.waitForSelector('.cue-list .clip')
    await p.waitForTimeout(800)
    const t0 = await p.evaluate(() => document.querySelector('video').currentTime)
    console.log('video at', t0.toFixed(2), 's after the subtitles came')
    if (t0 < 0.4 || t0 > 2.5) fail('the video was not moved to the first subtitle: ' + t0)
    const classic = await frame()
    await p.screenshot({ path: `${out}/2_cues.png` })

    // ---- one tap on a card: it is the chosen one and the video shows the new look
    await p.click('.preset-row .chip.preset >> text=ויראלי צהוב')
    await p.waitForSelector('.preset-row .chip.preset.on >> text=ויראלי צהוב')
    await seek(t0)
    const yellow = await frame()
    if (yellow === classic) fail('the video does not show the look that was tapped')
    await p.click('.preset-row .chip.preset >> text=רקע לבן')
    await seek(t0)
    const white = await frame()
    if (white === yellow || white === classic) fail('the second look is not shown')
    const kept = await p.evaluate(() => [...document.querySelectorAll('.preset-row .chip.preset.on')].map((c) => c.textContent.trim()))
    if (kept.join() !== 'רקע לבן') fail('highlight: ' + kept)
    await p.screenshot({ path: `${out}/3_look.png` })

    // ---- editing where it is: tap a subtitle, its actions show on it alone
    if (await p.$('.cue-split')) fail('actions shown before a subtitle is tapped')
    await seek(3)
    const before = await frame()
    await p.click('.cue-list .cue-text >> nth=1')
    await p.waitForSelector('.cue-split')
    if ((await p.$$('.cue-split')).length !== 1) fail('actions on more than the tapped subtitle')
    await p.fill('.cue-list .cue-text >> nth=1', 'טקסט חדש')
    const t1 = await p.evaluate(() => document.querySelector('video').currentTime)
    if (t1 < 2.5 || t1 > 5) fail('tapping a subtitle did not bring the video to it: ' + t1)
    await seek(3)
    if ((await frame()) === before) fail('the edited text is not what the video shows')
    // The subtitle being edited is above the export bar (the page leaves room for it).
    await p.waitForTimeout(900)
    const room = await p.evaluate(() => {
      const c = document.querySelector('.clip.open').getBoundingClientRect()
      const e = document.querySelector('.export-bar').getBoundingClientRect()
      return { bottom: c.bottom, barTop: e.top }
    })
    if (room.bottom > room.barTop + 1) fail('the edited subtitle is under the export bar: ' + JSON.stringify(room))
    await p.screenshot({ path: `${out}/4_edit.png` })

    // ---- the rest is folded, and opens; fine-tuning is folded too
    if (await p.isVisible('.sub-fine .fonts')) fail('the fine-tuning is open from the start')
    if (await p.isVisible('.subs-more-body')) fail('"more options" is open from the start')
    await p.click('.subs-more > summary')
    await p.waitForSelector('.subs-more-body .translate-go', { state: 'visible' })
    await p.click('.sub-fine > summary')
    await p.waitForSelector('.sub-fine .fonts', { state: 'visible' })

    // ---- taps: every button and chip on the screen at least 44 px tall
    const small = await p.evaluate(() => [...document.querySelectorAll('.side .btn, .side .chip:not(.preset), .tabs button, .side summary, .cue-list textarea')]
      .filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && e.offsetParent && r.height < 43.5 })
      .map((e) => `${e.className}:${Math.round(e.getBoundingClientRect().height)}`))
    console.log('under 44 px:', JSON.stringify(small))
    if (small.length) fail('targets under 44 px: ' + small.slice(0, 8).join(', '))
    if (await p.evaluate(() => document.documentElement.scrollWidth > innerWidth)) fail('the page scrolls sideways')
    if (hf !== 0) fail('the speech model was requested')
    console.log('PASS')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.slice(-25).join('\n'))
    console.error('FAIL', e.message)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
