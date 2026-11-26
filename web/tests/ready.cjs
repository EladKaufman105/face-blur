// The ready screen, as the app's: a picked video shows first (its frame, name, size, length,
// frame rate) with one big button; the blur's look and wider detection are folded away; another
// video can replace it; "blur and save" finds the faces and saves straight away, without the
// editor (in the original shape, and in 9:16 following the faces); the look chosen carries on to
// the editor.
// Run: node tests/ready.cjs <url> <video with faces> <another video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, other, out] = process.argv

/** The frame at [t] as gray levels, [w] wide. */
const gray = (f, t, w, h) => execFileSync('ffmpeg', ['-v', 'error', '-ss', String(t), '-i', f, '-frames:v', '1', '-vf', `scale=${w}:${h},format=gray`, '-f', 'rawvideo', '-'], { maxBuffer: 1 << 26 })

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  // Faces to be checked (a lighter detection on a slow machine, or an unsure face) stop "blur and save straight" at a screen of its own: "save anyway" goes on.
  const pastCheck = async () => {
    await p.waitForSelector('.result video, .card.error, .check-faces', { timeout: 900000 })
    if (await p.$('.check-faces')) await p.click('.check-save')
  }
  const save = async (name) => {
    await pastCheck()
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/${name}`, Buffer.from(bytes))
    return `${out}/${name}`
  }
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    // Wider detection is chosen on the ready screen now, not on the home screen.
    if (await p.$('.home-grid .switch')) throw new Error('wider detection still on the home screen')

    // Another video first, then replaced.
    await p.setInputFiles('.hero input[type=file]', other)
    await p.waitForSelector('.ready')
    await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    const first = await p.evaluate(() => ({ name: document.querySelector('.ready-name').textContent, chips: [...document.querySelectorAll('.info-chip')].map((e) => e.textContent) }))
    await p.setInputFiles('.video-card input[type=file]', video)
    await p.waitForFunction((n) => document.querySelector('.ready-name').textContent !== n, first.name)
    await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    const ready = await p.evaluate(() => ({ name: document.querySelector('.ready-name').textContent, chips: [...document.querySelectorAll('.info-chip')].map((e) => e.textContent), frame: document.querySelector('.ready-frame video').videoWidth }))
    console.log('ready:', JSON.stringify(first), '→ replaced:', JSON.stringify(ready))
    if (!ready.name.includes('walk') || ready.chips[0] !== '768×432' || ready.chips[1] !== '0:10' || ready.chips[2] !== '12 fps' || !ready.frame) throw new Error('the ready screen')

    // The blur's settings: folded, they say what's chosen; open, the look changes.
    const folded = await p.textContent('.fold-summary')
    await p.click('.blur-settings')
    await p.click('.look-seg button >> nth=1')
    await p.fill('.fold-body input.padding', '1.9')
    const summary = await p.textContent('.fold-summary')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/1_ready.png` })
    console.log('settings:', folded, '→', summary)
    if (folded !== 'טשטוש · זיהוי רגיל' || summary !== 'פיקסלים · זיהוי רגיל') throw new Error('blur settings')

    // Blur and save: the faces found and saved, no editor on the way.
    const t0 = Date.now()
    await p.click('.blur-save')
    const action = await p.textContent('.export-go')
    if (!action.includes('טשטש ושמור')) throw new Error('the sheet says: ' + action)
    // Watched as it goes: step 1 (faces), step 2 (blurring and saving) with the export shown as it's made.
    await p.evaluate(() => { window.__seen = {}; setInterval(() => { const s = window.__seen; if (document.body.textContent.includes('שלב 1 מתוך 2')) s.one = true; if (document.body.textContent.includes('שלב 2 מתוך 2')) s.two = true; if (document.querySelector('.live:not(.empty) canvas')) s.live = true }, 30) })
    await p.click('.export-go')
    await pastCheck()
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const seen = await p.evaluate(() => window.__seen)
    console.log('seen on the way:', JSON.stringify(seen))
    if (!seen.one || !seen.two || !seen.live) throw new Error('the two steps / the live export')
    const sawEditor = await p.evaluate(() => !!document.querySelector('.editor'))
    const back = await p.evaluate(() => [...document.querySelectorAll('.result ~ * .btn, main .btn')].map((e) => e.textContent.trim()))
    const saved = await save('saved.mp4')
    await p.screenshot({ path: `${out}/2_saved.png` })
    // Where the faces were, the picture differs from the source a lot; elsewhere only a little.
    const W = 192, H = 108
    let most = 0, typical = []
    for (const t of [2, 5, 8]) {
      const a = gray(video, t, W, H), c = gray(saved, t, W, H)
      for (let by = 0; by < H; by += 12) for (let bx = 0; bx < W; bx += 12) {
        let d = 0
        for (let y = by; y < by + 12; y++) for (let x = bx; x < bx + 12; x++) d += Math.abs(a[y * W + x] - c[y * W + x])
        d /= 144
        most = Math.max(most, d)
        typical.push(d)
      }
    }
    typical.sort((x, y) => x - y)
    const median = typical[typical.length >> 1]
    console.log('blurred and saved in', ((Date.now() - t0) / 1000).toFixed(0), 's, no editor:', !sawEditor, '· buttons', JSON.stringify(back), '· change: most', most.toFixed(1), 'median', median.toFixed(1))
    if (sawEditor || back.includes('חזרה לעריכה') || !(most > 15 && median < 4)) throw new Error('blur and save')
    const note = await p.evaluate(() => document.querySelector('.review-note')?.textContent ?? '')
    if (!note.includes('כדאי לעבור על הסרטון')) throw new Error('the note to check the video')
    // Opened in the editor afterwards, with what was found.
    await p.click('.edit-saved')
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    const after = await p.evaluate(() => document.querySelectorAll('.face').length)
    console.log('edited afterwards: faces', after)
    if (after === 0) throw new Error('editing what was saved')
    await p.click('.editor .btn.ghost >> nth=0')
    await p.waitForSelector('.home-grid')

    // Back to the ready screen; then in 9:16, the frame following the faces.
    await p.setInputFiles('.hero input[type=file]', other)
    await p.waitForFunction(() => document.querySelectorAll('.info-chip').length === 3, null, { timeout: 30000 })
    await p.click('.blur-save')
    await p.click('.export-sheet .export-aspect button >> text=9:16')
    await p.click('.export-go')
    const tall = await save('tall.mp4')
    const [tw, th] = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', tall]).toString().trim().split(',').map(Number)
    console.log('9:16:', tw, '×', th)
    if (Math.abs(tw / th - 9 / 16) > 0.02) throw new Error('9:16')

    // The look chosen carries on: blur faces → the editor, with pixels.
    await p.click('.btn.ghost >> text=סרטון חדש')
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready')
    // Back to the home screen, and the video picked again.
    await p.click('.ready-back')
    await p.waitForSelector('.home-grid')
    await p.setInputFiles('.hero input[type=file]', video)
    await p.waitForSelector('.ready')
    await p.click('.blur-faces')
    await p.waitForSelector('.editor .tabs', { timeout: 900000 })
    const mode = await p.evaluate(() => [...document.querySelectorAll('.seg button.on')].map((e) => e.textContent))
    const faces = await p.evaluate(() => document.querySelectorAll('.face').length)
    console.log('the editor, faces', faces, '· look', JSON.stringify(mode))
    if (!mode.includes('פיקסלים') || faces === 0) throw new Error('the look did not carry on')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
