// The picture tools, as in the app: a green screen replaced by a color; a 9:16 frame; a shaky
// video steadied; the people found and the background behind them replaced.
// Run: node tests/picture.cjs <url> <green screen> <1080p video> <shaky video> <video with people> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, green, wide, shaky, people, out] = process.argv

const probe = (f, args) => execFileSync('ffprobe', ['-v', 'error', ...args, '-of', 'csv=p=0', f]).toString().trim()
const frames = (f) => Number(probe(f, ['-count_frames', '-select_streams', 'v:0', '-show_entries', 'stream=nb_read_frames']))
/** The color of pixel (x, y) at [t] seconds. */
const pixel = (f, t, x, y) => [...execFileSync('ffmpeg', ['-v', 'error', '-ss', String(t), '-i', f, '-frames:v', '1', '-vf', `format=rgb24,crop=1:1:${x}:${y}`, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])]
const near = (a, b, tol) => a.every((v, i) => Math.abs(v - b[i]) <= tol)

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const open = async (file) => {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=2')
    await (await chooser).setFiles(file)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(800)
  }
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(500) }
  const exportTo = async (name) => {
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/${name}`, Buffer.from(bytes))
    return `${out}/${name}`
  }
  try {
    // A green screen behind a box: the green becomes #ff4d6d, the box stays.
    await open(green)
    await p.click('.tabs button[data-g=picture]')
    await p.click('.bg-key button >> nth=1')
    await p.click('.bg-fill button >> nth=2')
    await p.click('.swatch >> nth=3')
    await seek(1)
    await p.screenshot({ path: `${out}/1_green.png` })
    const g = await exportTo('green.mp4')
    const corner = pixel(g, 1, 8, 8), box = pixel(g, 1, 200, 150)
    console.log('green screen: corner', corner, 'box', box)
    if (!near(corner, [255, 77, 109], 40)) throw new Error('the screen was not replaced')
    if (!near(box, [220, 220, 220], 40)) throw new Error('the box was changed')

    // 9:16 out of 1920 x 1080 (no faces searched: the middle).
    await open(wide)
    await p.click('.tabs button[data-g=picture]')
    await p.click('.frame-aspect button >> nth=1')
    await p.waitForTimeout(500)
    await p.screenshot({ path: `${out}/2_frame.png` })
    const v = await exportTo('vertical.mp4')
    const size = probe(v, ['-select_streams', 'v:0', '-show_entries', 'stream=width,height'])
    console.log('9:16:', size)
    if (size !== '608,1080') throw new Error('9:16 size')

    // Steadied (medium).
    await open(shaky)
    await p.click('.tabs button[data-g=edit]')
    const t0 = Date.now()
    await p.click('.stabilize button >> nth=2')
    await p.waitForFunction(() => !document.querySelector('.picture .busy'), null, { timeout: 300000 })
    const info = await p.evaluate(() => [...document.querySelectorAll('.picture p')].map((e) => e.textContent).join(' | '))
    console.log(`camera measured in ${((Date.now() - t0) / 1000).toFixed(1)} s:`, info)
    await seek(2)
    await p.screenshot({ path: `${out}/3_steady.png` })
    const s = await exportTo('steady.mp4')
    console.log('steadied:', frames(s), 'frames of', frames(shaky))
    if (Math.abs(frames(s) - frames(shaky)) > 1) throw new Error('steadied frame count')

    // The people found; a green background behind them.
    await open(people)
    await p.click('.tabs button[data-g=picture]')
    const t1 = Date.now()
    await p.click('.find-people')
    await p.waitForSelector('.people-ready, .toast', { timeout: 1200000 })
    console.log(`people found in ${((Date.now() - t1) / 1000).toFixed(1)} s`)
    if (!(await p.$('.people-ready'))) throw new Error('people: ' + (await p.evaluate(() => document.querySelector('.toast')?.textContent)))
    await p.click('.bg-fill button >> nth=2')
    await p.click('.swatch >> nth=0')
    await seek(5)
    await p.screenshot({ path: `${out}/4_people.png` })
    const pp = await exportTo('people.mp4')
    const wall = pixel(pp, 5, 30, 20)
    console.log('people: the wall at 5 s is now', wall)
    if (!near(wall, [0, 177, 64], 60)) throw new Error('the background was not replaced')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
