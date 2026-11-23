// Editing, as in the app: a blur added by hand and followed, split, deleted, undone, ended
// here; a stretch cut by hand and the pauses cut automatically; playback jumps over the cuts; the
// export is shorter by what was cut, its picture and its sound alike.
// Run: node tests/edit.cjs <url> <video with speech> <out dir> [phone]
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, out, device] = process.argv
const PHONE = { viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const p = await (await b.newContext({ ...(device === 'phone' ? PHONE : { viewport: { width: 1366, height: 860 } }), locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const faces = () => p.evaluate(() => [...document.querySelectorAll('.tl-face')].map((e) => [Number(e.dataset.a), Number(e.dataset.b)]))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(400) }
  const toast = () => p.evaluate(() => document.querySelector('.toast')?.textContent ?? '')
  const tap = (sel) => (device === 'phone' ? p.tap(sel) : p.click(sel))
  try {
    await p.goto(url)
    await p.waitForTimeout(1000)
    // The sound tool: the editor opens without searching for faces.
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=1')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(1500)

    // A blur by hand, on the man walking (at 5 s), followed both ways.
    await tap('.tabs button[data-g=faces]')
    await seek(5)
    await tap('.add-blur')
    const box = await p.locator('.screen canvas >> nth=0').boundingBox()
    const t0 = Date.now()
    await p.mouse.click(box.x + box.width * 0.62, box.y + box.height * 0.45)
    await p.waitForSelector('.ctx-bar .progress', { timeout: 20000 }).catch(() => {})
    await p.waitForFunction(() => !document.querySelector('.ctx-bar .progress'), null, { timeout: 300000 })
    const followed = (Date.now() - t0) / 1000
    const msg = await toast()
    let f = await faces()
    console.log('followed in', followed.toFixed(1), 's:', msg, JSON.stringify(f))
    await p.screenshot({ path: `${out}/1_followed.png` })
    if (f.length !== 1) throw new Error('no blur added')
    const [a, z] = f[0]
    if (z - a < 2) throw new Error(`followed only ${(z - a).toFixed(2)} s`)

    // Split in the middle, delete the second part, undo and redo.
    await seek((a + z) / 2)
    await tap('.blur-split')
    await p.waitForTimeout(300)
    if ((await faces()).length !== 2) throw new Error('split')
    await tap('.blur-delete')
    await p.waitForTimeout(300)
    if ((await faces()).length !== 1) throw new Error('delete')
    await p.keyboard.press('Control+z'); await p.waitForTimeout(300)
    const undone = (await faces()).length
    await p.keyboard.press('Control+Shift+z'); await p.waitForTimeout(300)
    const redone = (await faces()).length
    console.log('split → 2, delete → 1, undo →', undone, 'redo →', redone)
    if (undone !== 2 || redone !== 1) throw new Error('undo / redo of blurs')

    // Select it on the timeline, end it 1 s after its start, a bigger size.
    await tap('.tl-face')
    await seek(a + 1)
    await tap('.blur-end')
    await p.waitForTimeout(300)
    f = await faces()
    console.log('ended here:', JSON.stringify(f))
    if (Math.abs(f[0][1] - (a + 1)) > 0.2) throw new Error('end here')
    await p.evaluate(() => { const r = document.querySelector('.ctx-size input'); r.value = '2'; r.dispatchEvent(new Event('input', { bubbles: true })) })
    await seek(a + 0.5)
    await p.screenshot({ path: `${out}/2_blur_edited.png` })

    // A stretch cut by hand (2 s to 4 s), then the pauses.
    await p.keyboard.press('Escape')
    await tap('.tabs button[data-g=edit]')
    await seek(2)
    await tap('.cut-start')
    await seek(4)
    await tap('.cut-end')
    await p.waitForTimeout(300)
    const byHand = Number(await p.getAttribute('.cut', 'data-total'))
    console.log('cut by hand', byHand.toFixed(2), 's')
    if (Math.abs(byHand - 2) > 0.1) throw new Error('cut by hand')
    await tap('.cut-silences')
    await p.waitForFunction(() => !document.querySelector('.cut .spinner'), null, { timeout: 120000 })
    await p.waitForTimeout(300)
    const total = Number(await p.getAttribute('.cut', 'data-total'))
    console.log('silences:', await toast(), '· cut in all', total.toFixed(2), 's')
    await p.screenshot({ path: `${out}/3_cuts.png` })

    // Playing from 1.8 s jumps over the cut.
    await p.evaluate(() => { const v = document.querySelector('video'); v.currentTime = 1.8; v.play() })
    await p.waitForTimeout(1200)
    const after = await p.evaluate(() => { const v = document.querySelector('video'); v.pause(); return v.currentTime })
    console.log('played from 1.8 s for 1.2 s:', after.toFixed(2))
    if (after < 4) throw new Error('playback did not jump over the cut')

    // The export: shorter by what was cut.
    const t1 = Date.now()
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    console.log('export', ((Date.now() - t1) / 1000).toFixed(1), 's')
    await p.screenshot({ path: `${out}/4_done.png` })
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/out.mp4`, Buffer.from(bytes))
    const probe = (f, s, e) => execFileSync('ffprobe', ['-v', 'error', '-select_streams', s, '-show_entries', e, '-of', 'csv=p=0', f]).toString().trim()
    const dur = (f, s) => Number(probe(f, s, 'stream=duration'))
    // (The sample's sound ends before its picture: the cuts are all within it.)
    const vSrc = dur(video, 'v:0'), aSrc = dur(video, 'a:0')
    const vOut = dur(`${out}/out.mp4`, 'v:0'), aOut = dur(`${out}/out.mp4`, 'a:0')
    console.log(`source: video ${vSrc.toFixed(2)} s, sound ${aSrc.toFixed(2)} s; cut ${total.toFixed(2)} s → video ${vOut.toFixed(2)} s, sound ${aOut.toFixed(2)} s`)
    if (Math.abs(vOut - (vSrc - total)) > 0.25) throw new Error('video length after cuts')
    if (Math.abs(aOut - (aSrc - total)) > 0.3) throw new Error('sound length after cuts')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
