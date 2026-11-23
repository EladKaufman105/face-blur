// Subtitle design, as in the app: the ready-made styles, every bundled font, a font imported from
// the device (kept in the browser, and told when it has no Hebrew), the effects (moving while a
// cue is shown, still without one), colors, a box and the height; all of it burned into the
// export as in the preview, and kept with the project.
// Run: node tests/style.cjs <url> <video with speech> <a font with Hebrew> <a font without> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, hebFont, latinFont, out] = process.argv

const SRT = `1
00:00:01,000 --> 00:00:03,000
שלום לכולם וברוכים הבאים

2
00:00:04,000 --> 00:00:06,000
היום נדבר על עריכה
`

/** What the preview's subtitle layer shows: how much is drawn, where (0 top .. 1 bottom), how much is yellow, a fingerprint. */
const layer = () => {
  const c = document.querySelectorAll('.view canvas')[2]
  const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
  let n = 0, y = 0, yellow = 0, dark = 0, hash = 0
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] < 40) continue
    const row = Math.floor(i / 4 / c.width)
    n++; y += row
    if (d[i] > 200 && d[i + 1] > 170 && d[i + 2] < 90) yellow++
    if (d[i] < 40 && d[i + 1] < 40 && d[i + 2] < 40 && d[i + 3] > 150) dark++
    hash = (hash * 31 + d[i] + d[i + 1] * 7 + d[i + 3] * 13 + row) >>> 0
  }
  return { n, y: n ? y / n / c.height : 0, yellow, dark, hash }
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  fs.copyFileSync(hebFont, `${out}/Hebrew Sans.ttf`)
  fs.copyFileSync(latinFont, `${out}/Latin Only.ttf`)
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(450) }
  const on = (sel) => p.evaluate((sel) => [...document.querySelectorAll(sel)].map((e) => e.textContent.trim()), sel)
  const chip = (group, text) => p.click(`.sub-style .${group} >> text="${text}"`)
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.setInputFiles('.srt-import input', `${out}/in.srt`)
    await p.waitForSelector('.sub-style')

    // Every style and font offered, each name in its own font, all loaded.
    const presets = await p.evaluate(() => document.querySelectorAll('.sub-style .presets .chip').length)
    const fonts = await on('.sub-style .fonts .chip')
    await p.waitForFunction(() => ['Rubik', 'Allo', 'Heebo', 'Assistant', 'Secular One', 'Suez One', 'Varela Round', 'Fredoka', 'Karantina', 'Amatic SC'].every((f) => document.fonts.check(`20px 'ONIT ${f}'`, 'שלום')), null, { timeout: 30000 })
    console.log('styles:', presets, '· fonts:', fonts.join(', '), '· all loaded')
    if (presets !== 28 || fonts.length !== 11) throw new Error('styles / fonts offered')

    // A style chosen sets its font, effect and colors.
    await seek(1.6)
    const classic = await p.evaluate(layer)
    await chip('presets', 'טיקטוק')
    await p.waitForTimeout(300)
    const tiktok = { font: await on('.sub-style .fonts .chip.on'), effect: await on('.sub-style .effects .chip.on') }
    console.log('TikTok style →', JSON.stringify(tiktok))
    if (tiktok.font[0] !== 'Rubik' || tiktok.effect[0] !== 'קפיצה') throw new Error('style')
    await chip('presets', 'ויראלי צהוב')
    await seek(1.6)
    const viral = await p.evaluate(layer)
    console.log('classic', JSON.stringify(classic), '→ viral yellow', JSON.stringify(viral))
    await p.screenshot({ path: `${out}/1_viral.png` })
    if (!(viral.yellow > 800 && classic.yellow < 100)) throw new Error('the yellow style is not drawn')

    // A variable font's weight, and Allo's roundness (a copy of the font per roundness).
    await chip('fonts', 'Allo')
    const slide = (sel, v) => p.evaluate(([sel, v]) => { const el = document.querySelector(sel); el.value = String(v); el.dispatchEvent(new Event('input', { bubbles: true })) }, [sel, v])
    await slide('.sub-style .weight-slider', 300); await seek(1.6); const thin = await p.evaluate(layer)
    await slide('.sub-style .weight-slider', 900); await seek(1.6); const thick = await p.evaluate(layer)
    await slide('.sub-style .round-slider', 0)
    await p.waitForFunction(() => document.fonts.check("20px 'ONIT Allo r0'", 'שלום'), null, { timeout: 20000 })
    await seek(1.6); const sharp = await p.evaluate(layer)
    await slide('.sub-style .round-slider', 100)
    await p.waitForFunction(() => document.fonts.check("20px 'ONIT Allo r100'", 'שלום'), null, { timeout: 20000 })
    await seek(1.6); const round = await p.evaluate(layer)
    console.log('Allo: weight 300 →', thin.n, '· 900 →', thick.n, '· roundness 0 / 100:', sharp.hash !== round.hash ? 'differ' : 'same')
    if (!(thick.n > thin.n * 1.1) || sharp.hash === round.hash) throw new Error('weight / roundness')
    await chip('presets', 'ויראלי צהוב')

    // An effect moves while the cue is on screen; without one it holds still.
    await chip('effects', 'בלי')
    await seek(1.1); const still1 = await p.evaluate(layer)
    await seek(1.6); const still2 = await p.evaluate(layer)
    await chip('effects', 'מילים קופצות')
    await seek(1.1); const pop1 = await p.evaluate(layer)
    await seek(1.6); const pop2 = await p.evaluate(layer)
    console.log('no effect:', still1.n, still2.n, '· words pop:', pop1.n, pop2.n)
    if (still1.hash !== still2.hash || pop1.n >= pop2.n * 0.9) throw new Error('effects')
    for (const e of ['מכונת כתיבה', 'מילה מודגשת', 'תיבה על המילה', 'רעידה', "גליץ'", 'ניאון מהבהב', 'סיבוב']) {
      await chip('effects', e)
      await seek(1.6)
      if ((await p.evaluate(layer)).n < 500) throw new Error('effect not drawn: ' + e)
    }
    await chip('effects', 'קפיצה')

    // The height, and a black box behind.
    await chip('positions-seg', 'למעלה'); await seek(1.6); const top = await p.evaluate(layer)
    await chip('positions-seg', 'למטה'); await seek(1.6); const bottom = await p.evaluate(layer)
    await p.check('.sub-style .box-check')
    await seek(1.6)
    const boxed = await p.evaluate(layer)
    console.log('at the top', top.y.toFixed(2), '· at the bottom', bottom.y.toFixed(2), '· dark', bottom.dark, '→ with a box', boxed.dark, '· drawn', bottom.n, '→', boxed.n)
    if (!(top.y < 0.25 && bottom.y > 0.75 && boxed.dark > bottom.dark + 2000 && boxed.n > bottom.n * 1.4)) throw new Error('height / box')

    // Fonts imported: one without Hebrew is added but says so; one with Hebrew is used.
    await p.setInputFiles('.sub-style input[type=file]', `${out}/Latin Only.ttf`)
    await p.waitForFunction(() => document.querySelector('.sub-style p.small')?.textContent.includes('אין בו'))
    await p.setInputFiles('.sub-style input[type=file]', `${out}/Hebrew Sans.ttf`)
    await p.waitForFunction(() => document.querySelector('.sub-style p.small')?.textContent === 'הגופן נוסף.')
    const custom = await on('.sub-style .custom-font.on')
    await seek(1.6)
    const imported = await p.evaluate(layer)
    console.log('imported fonts:', JSON.stringify(await on('.sub-style .custom-font')), '· chosen', JSON.stringify(custom), '· drawn', imported.n, '≠', boxed.n)
    if (!custom[0]?.startsWith('Hebrew Sans') || imported.hash === boxed.hash) throw new Error('imported font')
    await p.screenshot({ path: `${out}/2_imported.png` })
    // Removed again, the other one.
    await p.click('.sub-style .custom-font:not(.on) .link-btn:last-child')
    await p.waitForFunction(() => document.querySelectorAll('.sub-style .custom-font').length === 1)

    // Burned into the export as in the preview: yellow, in the box, at the bottom, while a cue is on.
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/out.mp4`, Buffer.from(bytes))
    const frame = (t) => {
      const raw = execFileSync('ffmpeg', ['-v', 'error', '-ss', String(t), '-i', `${out}/out.mp4`, '-frames:v', '1', '-vf', 'scale=640:-2,format=rgb24', '-f', 'rawvideo', '-'], { maxBuffer: 1 << 26 })
      const w = 640, h = raw.length / 3 / w
      let yellow = 0, low = 0
      for (let i = 0; i < raw.length; i += 3) {
        if (raw[i] > 190 && raw[i + 1] > 160 && raw[i + 2] < 100) { yellow++; if (Math.floor(i / 3 / w) > h * 0.6) low++ }
      }
      return { yellow, low }
    }
    execFileSync('ffmpeg', ['-v', 'error', '-y', '-ss', '1.6', '-i', `${out}/out.mp4`, '-frames:v', '1', `${out}/3_export.png`])
    const at = { pop: frame(1.04), on: frame(1.6), second: frame(5), off: frame(7.5) }
    console.log('exported, yellow pixels:', JSON.stringify(at))
    if (!(at.on.yellow > 300 && at.on.low > at.on.yellow * 0.8 && at.second.yellow > 300 && at.off.yellow < 30 && at.pop.yellow < at.on.yellow)) throw new Error('the exported subtitles')
    await p.click('text=חזרה לעריכה')

    // Not burned in, when so chosen (the SRT keeps them): no subtitle in the picture.
    await p.click('.export-bar .btn')
    await p.click('.burn-seg button >> nth=1')
    await p.click('.export-sheet .switch')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const href2 = await p.getAttribute('a[download]', 'href')
    const bytes2 = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href2)
    fs.writeFileSync(`${out}/out.mp4`, Buffer.from(bytes2))
    const srt = await p.evaluate(async () => (await fetch(document.querySelector('.srt-link').href)).text())
    const bare = frame(1.6)
    console.log('not burned in: yellow', bare.yellow, '· SRT', srt.split('\n').slice(0, 3).join(' | '))
    if (bare.yellow > 30 || !srt.includes('שלום לכולם')) throw new Error('not burning in')
    await p.click('text=חזרה לעריכה')
    await p.click('.export-bar .btn')
    await p.click('.burn-seg button >> nth=0')
    await p.click('.export-sheet .switch')
    await p.keyboard.press('Escape')

    // Kept with the project, the imported font too.
    await p.waitForTimeout(2500)
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.project', { timeout: 10000 })
    await p.reload()
    await p.waitForSelector('.project-open', { timeout: 10000 })
    await p.click('.project-open')
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.click('.tabs button[data-g=subs]')
    await p.waitForSelector('.sub-style')
    const kept = { font: await on('.sub-style .custom-font.on'), effect: await on('.sub-style .effects .chip.on'), box: await p.isChecked('.sub-style .box-check') }
    await p.waitForFunction(() => document.fonts.check("20px 'ONIT custom Hebrew Sans'", 'שלום'), null, { timeout: 10000 })
    console.log('opened again:', JSON.stringify(kept))
    if (!kept.font[0]?.startsWith('Hebrew Sans') || kept.effect[0] !== 'קפיצה' || !kept.box) throw new Error('the style was not kept')

    // The watermark's font, as the app's: Rubik at first, another chosen; one saved before fonts could be chosen stays in Allo.
    await p.click('.tabs button[data-g=mark]')
    await p.fill('.text-in', '@onit שלום')
    const markFont = async () => (await on('.mark-fonts .chip.on'))[0]
    const first = await markFont()
    await seek(1.6); const rubik = await p.evaluate(layer)
    await p.click('.mark-fonts >> text="Karantina"')
    await p.waitForFunction(() => document.fonts.check("20px 'ONIT Karantina'", 'שלום'))
    await seek(1.6); const karantina = await p.evaluate(layer)
    await p.evaluate(() => localStorage.setItem('onit.watermark', JSON.stringify({ text: '@old', position: 'br', size: 1, opacity: 0.8, color: '#ffffff', look: 'shadow', weight: 700 })))
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser2 = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser2).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.click('.tabs button[data-g=mark]')
    const legacy = await markFont()
    console.log('watermark font:', first, '→ Karantina', rubik.hash !== karantina.hash ? 'drawn' : 'NOT drawn', '· an old one:', legacy)
    if (first !== 'Rubik' || rubik.hash === karantina.hash || legacy !== 'Allo') throw new Error('watermark font')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
