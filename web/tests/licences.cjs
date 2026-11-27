// "Open-source licences" (the footer's link): it lists the models the web app runs and the libraries and fonts it is made of,
// each opens its notice and, on request, the full licence text; everything comes from the site itself (nothing is fetched from
// elsewhere, so it works offline); Escape closes it.
// Run: node tests/licences.cjs <url> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1000, height: 800 }, locale: 'he-IL' })
  const p = await ctx.newPage()
  const logs = []
  const outside = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  // Any request that leaves the site is refused and counted: the licence screen must need none.
  const origin = new URL(url).origin
  await ctx.route('**/*', (route) => {
    if (route.request().url().startsWith(origin)) return route.continue()
    outside.push(route.request().url())
    return route.abort()
  })
  const titles = () => p.$$eval('.licence-row .licence-title', (els) => els.map((e) => e.textContent))
  const open = async (title) => {
    await p.click(`.licence-row:has-text("${title}")`)
    await p.waitForSelector('.licence-detail')
  }
  const fullText = async () => {
    await p.click('.licence-toggle')
    await p.waitForSelector('.licence-text')
    return p.textContent('.licence-text')
  }
  const back = async () => {
    await p.click('.licences-sheet .icon-btn[aria-label="חזרה"]')
    await p.waitForSelector('.licence-row')
  }
  try {
    await p.goto(url)
    await p.click('.licences-link')
    await p.waitForSelector('.licences-sheet .licence-row')
    const heading = await p.textContent('.licences-sheet .sheet-title')
    if (heading !== 'רישיונות קוד פתוח') throw new Error(`heading: ${heading}`)
    const all = await titles()
    console.log('listed:', all.length, JSON.stringify(all))
    for (const want of ['RetinaFace', 'YuNet', 'PP-HumanSeg', 'BlazeFace', 'WeSpeaker', 'ONNX Runtime', 'Transformers.js', 'Mediabunny', 'Preact', 'LAME', 'Heebo', 'Rubik']) {
      if (!all.some((x) => x.includes(want))) throw new Error(`not listed: ${want}`)
    }
    if (all.some((x) => x.includes('LR-ASPP') || x.includes('LiteRT') || x.includes('Media3'))) throw new Error('Android-only entries listed on the web')
    if (all.filter((x) => x.endsWith('(font)')).length !== 10) throw new Error('fonts')
    await p.screenshot({ path: `${out}/1_list.png` })

    // A model: its notice, then the full MIT text from the site.
    await open('RetinaFace')
    const notice = await p.textContent('.licence-notice')
    if (!notice.includes('Yakhyokhuja Valikhujaev') || !notice.includes('MIT')) throw new Error(`notice: ${notice}`)
    if ((await p.$('.licence-text')) !== null) throw new Error('the full text is open before it was asked for')
    const mit = await fullText()
    if (!mit.includes('Permission is hereby granted') || !mit.includes('WITHOUT WARRANTY OF ANY KIND')) throw new Error('MIT text')
    await p.screenshot({ path: `${out}/2_model.png` })
    await back()

    // Attribution licence: CC BY 4.0, with the change note.
    await open('WeSpeaker')
    if (!(await p.textContent('.licence-notice')).includes('Modified')) throw new Error('the change note of the CC BY model')
    if (!(await fullText()).includes('Creative Commons Attribution 4.0 International')) throw new Error('CC BY text')
    await back()
    // A library under MPL-2.0 and a font under the OFL (its own file beside the font).
    await open('Mediabunny')
    if (!(await fullText()).includes('Mozilla Public License Version 2.0')) throw new Error('MPL text')
    await back()
    await open('Heebo')
    if (!(await fullText()).includes('SIL OPEN FONT LICENSE')) throw new Error('OFL text')
    await back()
    // Hiding the text works.
    await open('Preact')
    await fullText()
    await p.click('.licence-toggle')
    if ((await p.$('.licence-text')) !== null) throw new Error('hide')
    await back()

    if (outside.length) throw new Error(`requests left the site: ${outside.join(', ')}`)
    await p.keyboard.press('Escape')
    await p.waitForSelector('.licences-sheet', { state: 'detached' })
    const errors = logs.filter((l) => l.startsWith('[pageerror]'))
    if (errors.length) throw new Error(errors.join('\n'))
    console.log('licences: ok')
  } catch (e) {
    await p.screenshot({ path: `${out}/fail.png` }).catch(() => {})
    console.log(logs.join('\n'))
    console.error(e)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
