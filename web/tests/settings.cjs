// Settings, as the app's: language, light / dark / the device's, and the color palette - each
// applied at once, kept after a reload.
// Run: node tests/settings.cjs <url> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL', colorScheme: 'dark' })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  /** The accent and the background as drawn: the chosen segment's fill and the page's. */
  const colors = () => p.evaluate(() => ({
    accent: getComputedStyle(document.querySelector('.theme-seg .on')).backgroundColor,
    bg: getComputedStyle(document.body).backgroundColor,
    dir: document.documentElement.dir,
    title: document.querySelector('.sheet-title').textContent,
  }))
  try {
    await p.goto(url)
    await p.click('.settings-btn')
    await p.waitForSelector('.settings-sheet')
    const start = await colors()
    await p.click('.palette[data-p=blue]')
    const blueDark = await colors()
    await p.click('.theme-seg button >> nth=1')
    const blueLight = await colors()
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/1_blue_light.png` })
    await p.click('.palette[data-p=green]')
    const greenLight = await colors()
    await p.click('.lang-seg button >> text=English')
    const en = await colors()
    console.log('ONIT dark', JSON.stringify(start), '→ blue', blueDark.accent, '→ light', JSON.stringify(blueLight), '→ green', greenLight.accent, '→ English', en.title, en.dir)
    if (start.accent !== 'rgb(255, 92, 122)' || blueDark.accent !== 'rgb(157, 176, 255)' || blueLight.accent !== 'rgb(51, 85, 224)' || blueLight.bg !== 'rgb(247, 246, 251)' || greenLight.accent !== 'rgb(31, 122, 77)') throw new Error('palette / theme')
    if (en.title !== 'Settings' || en.dir !== 'ltr') throw new Error('language')
    await p.keyboard.press('Escape')
    await p.waitForSelector('.settings-sheet', { state: 'detached' })

    // Kept: after a reload the page is light, green, in English.
    await p.reload()
    await p.click('.settings-btn')
    const kept = await colors()
    console.log('after a reload:', JSON.stringify(kept))
    if (kept.accent !== 'rgb(31, 122, 77)' || kept.bg !== 'rgb(247, 246, 251)' || kept.title !== 'Settings') throw new Error('not kept')

    // The device's mode follows the device.
    await p.click('.theme-seg button >> nth=2')
    const sysDark = await colors()
    await p.emulateMedia({ colorScheme: 'light' })
    const sysLight = await colors()
    console.log("the device's: dark", sysDark.accent, sysDark.bg, '· light', sysLight.accent, sysLight.bg)
    if (sysDark.accent !== 'rgb(143, 216, 174)' || sysLight.accent !== 'rgb(31, 122, 77)' || sysLight.bg !== 'rgb(247, 246, 251)') throw new Error("the device's mode")

    // On a phone.
    const ph = await (await b.newContext({ viewport: { width: 393, height: 851 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'he-IL' })).newPage()
    await ph.goto(url)
    await ph.click('.settings-btn')
    await ph.waitForSelector('.settings-sheet')
    const over = await ph.evaluate(() => document.documentElement.scrollWidth - innerWidth)
    await ph.waitForTimeout(400)
    await ph.screenshot({ path: `${out}/2_phone.png` })
    console.log('phone overflow', over)
    if (over > 0) throw new Error('phone overflow')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
