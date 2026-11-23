// Sounds imported from the device, as the app's "mine": a file that isn't sound is refused, one
// that is joins the list (kept in the browser), is placed on the video and mixed into the export
// (here a video without sound gets a sound track for it), and can be deleted.
// Run: node tests/mysounds.cjs <url> <video without sound> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync, spawnSync } = require('child_process')
const [, , url, video, out] = process.argv

/** RMS level (dB) of [f]'s sound from [a] to [b] seconds. */
const level = (f, a, b) => {
  const log = spawnSync('ffmpeg', ['-hide_banner', '-nostats', '-ss', String(a), '-t', String(b - a), '-i', f, '-af', 'astats=metadata=0', '-f', 'null', '-']).stderr.toString()
  const m = [...log.matchAll(/RMS level dB: (-?[\d.]+|-inf)/g)].pop()
  return m ? (m[1] === '-inf' ? -200 : Number(m[1])) : null
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=1000:duration=3', '-c:a', 'aac', `${out}/My Tone.m4a`])
  fs.writeFileSync(`${out}/broken.mp3`, Buffer.from('this is not a sound file at all'))
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(400) }
  const open = async () => {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=1')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.click('.mine-chip')
  }
  const mine = () => p.evaluate(() => [...document.querySelectorAll('.sound.mine .name')].map((e) => e.textContent))
  try {
    await open()
    if (!(await p.evaluate(() => document.body.textContent.includes('כאן יופיעו צלילים שתייבא')))) throw new Error('the empty list says nothing')
    await p.setInputFiles('input[accept="audio/*"]', `${out}/broken.mp3`)
    await p.waitForFunction(() => document.body.textContent.includes('לא הצלחתי לקרוא את הקובץ'), null, { timeout: 10000 })
    await p.setInputFiles('input[accept="audio/*"]', `${out}/My Tone.m4a`)
    await p.waitForFunction(() => document.querySelectorAll('.sound.mine').length === 1, null, { timeout: 10000 })
    const list = await mine()
    await seek(2)
    await p.click('.sound.mine .name')
    const clip = await p.evaluate(() => [...document.querySelectorAll('.clips .clip strong')].map((e) => e.textContent))
    await p.screenshot({ path: `${out}/1_mine.png` })
    console.log('mine:', JSON.stringify(list), '· placed:', JSON.stringify(clip))
    if (list[0] !== 'My Tone' || clip[0] !== 'My Tone') throw new Error('import')

    // Mixed into the export: silence, then the tone from 2 s.
    await p.click('.export-bar .btn')
    await p.click('.export-go')
    await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/out.mp4`, Buffer.from(bytes))
    const before = level(`${out}/out.mp4`, 0.2, 1.8), during = level(`${out}/out.mp4`, 2.3, 4.7), after = level(`${out}/out.mp4`, 5.5, 9)
    console.log('exported sound (dB): before', before, '· the tone', during, '· after', after)
    if (!(during > -30 && before < -50 && after < -50)) throw new Error('the imported sound in the export')

    // Kept in the browser; deleted.
    await open()
    const kept = await mine()
    await p.click('.sound.mine .small-icon')
    await p.waitForFunction(() => document.querySelectorAll('.sound.mine').length === 0)
    await open()
    const gone = await mine()
    console.log('after a reload:', JSON.stringify(kept), '→ deleted:', JSON.stringify(gone))
    if (kept[0] !== 'My Tone' || gone.length !== 0) throw new Error('kept / deleted')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
