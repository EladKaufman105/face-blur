// The sound and the export's choices, as in the app: noise lowered, the voice enhanced, the
// networks' loudness; only the sound as MP3 (with a cut); a lower frame rate; the MP3 tool from
// the home screen; a smaller resolution and frame rate on a 1080p60 clip.
// Run: node tests/sound.cjs <url> <video with speech> <short video with speech> <1080p60 video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync, spawnSync } = require('child_process')
const [, , url, speech, short, big, out] = process.argv

const probe = (f, args) => execFileSync('ffprobe', ['-v', 'error', ...args, '-of', 'csv=p=0', f]).toString().trim()
const dur = (f, s) => Number(probe(f, ['-select_streams', s, '-show_entries', 'stream=duration']))
const frames = (f) => Number(probe(f, ['-count_frames', '-select_streams', 'v:0', '-show_entries', 'stream=nb_read_frames']))
const lufs = (f) => {
  const log = spawnSync('ffmpeg', ['-hide_banner', '-nostats', '-i', f, '-af', 'ebur128', '-f', 'null', '-']).stderr.toString()
  const m = [...log.matchAll(/I:\s+(-?[\d.]+) LUFS/g)]
  return m.length ? Number(m[m.length - 1][1]) : NaN
}

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const ctx = await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  const p = await ctx.newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(400) }
  const save = async (name) => {
    await p.waitForSelector('.result video, .result audio, .card.error', { timeout: 900000 })
    const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
    if (err) throw new Error('export: ' + err)
    const href = await p.getAttribute('a[download]', 'href')
    const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
    fs.writeFileSync(`${out}/${name}`, Buffer.from(bytes))
    return `${out}/${name}`
  }
  const tool = async (k, file) => {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click(`.tool >> nth=${k}`)
    await (await chooser).setFiles(file)
  }
  try {
    // Cleanup: medium noise reduction, a clearer voice, the networks' loudness.
    await tool(1, speech)
    await p.waitForSelector('.editor .cleanup', { timeout: 30000 })
    await p.click('.denoise button >> nth=2')
    await p.click('.enhance .switch')
    await p.click('.loud .switch')
    await p.waitForFunction(() => document.querySelector('.loud')?.textContent.includes('dB'), null, { timeout: 120000 })
    await p.waitForFunction(() => !document.body.textContent.includes('מעבד את הקול'), null, { timeout: 120000 })
    console.log('loudness:', await p.evaluate(() => document.querySelector('.loud .muted').textContent))
    await p.screenshot({ path: `${out}/1_cleanup.png` })
    // A cut by hand (2 s to 4 s).
    await p.click('.tabs button[data-g=edit]')
    await seek(2)
    await p.click('.cut-start')
    await seek(4)
    await p.click('.cut-end')

    // Only the sound, as MP3.
    await p.click('.export-bar .btn')
    await p.click('.export-format button >> nth=1')
    await p.screenshot({ path: `${out}/2_sheet_mp3.png` })
    await p.click('.export-go')
    const mp3 = await save('sound.mp3')
    const codec = probe(mp3, ['-select_streams', 'a:0', '-show_entries', 'stream=codec_name'])
    const aSrc = dur(speech, 'a:0'), aOut = Number(probe(mp3, ['-show_entries', 'format=duration']))
    const lIn = lufs(speech), lOut = lufs(mp3)
    console.log(`MP3: ${codec}, ${aOut.toFixed(2)} s (source ${aSrc.toFixed(2)} s, less 2 s cut), loudness ${lIn.toFixed(1)} → ${lOut.toFixed(1)} LUFS`)
    if (codec !== 'mp3') throw new Error('not MP3')
    if (Math.abs(aOut - (aSrc - 2)) > 0.35) throw new Error('MP3 length')
    if (Math.abs(lOut + 14) > 2.5) throw new Error('loudness not near -14 LUFS')

    // Back, a video at 24 fps.
    await p.click('text=חזרה לעריכה')
    await p.click('.export-bar .btn')
    await p.click('.export-format button >> nth=0')
    await p.waitForSelector('.export-fps', { timeout: 10000 })
    await p.click('.export-fps .chip >> text=24 fps')
    await p.click('.export-go')
    const v24 = await save('fps24.mp4')
    const n = frames(v24), vOut = dur(v24, 'v:0')
    console.log(`24 fps: ${n} frames, ${vOut.toFixed(2)} s, sound ${probe(v24, ['-select_streams', 'a:0', '-show_entries', 'stream=codec_name']) || 'none'}`)
    if (Math.abs(n - vOut * 24) > 3 || Math.abs(vOut - 21.5) > 0.3) throw new Error('24 fps export')

    // The MP3 tool from the home screen.
    await tool(3, short)
    const tmp3 = await save('tool.mp3')
    const td = Number(probe(tmp3, ['-show_entries', 'format=duration']))
    console.log(`MP3 tool: ${td.toFixed(2)} s (source ${dur(short, 'a:0').toFixed(2)} s)`)
    if (Math.abs(td - dur(short, 'a:0')) > 0.2) throw new Error('MP3 tool length')
    await p.screenshot({ path: `${out}/3_mp3_tool.png` })

    // 1080p60 → 720p, 30 fps.
    await tool(2, big)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(800)
    await p.click('.export-bar .btn')
    // (The frame rates offered once the video's own is known: 60, 50, 30, 25, 24.)
    await p.waitForSelector('.export-fps .chip >> text=50 fps', { timeout: 10000 })
    await p.click('.export-size .chip >> text=720p')
    await p.click('.export-fps .chip >> nth=2')
    await p.screenshot({ path: `${out}/4_sheet_video.png` })
    await p.click('.export-go')
    const v720 = await save('720p30.mp4')
    const size = probe(v720, ['-select_streams', 'v:0', '-show_entries', 'stream=width,height'])
    const n720 = frames(v720)
    console.log(`720p30: ${size}, ${n720} frames (source ${frames(big)} at 60 fps)`)
    if (size !== '1280,720') throw new Error('720p size')
    if (Math.abs(n720 - frames(big) / 2) > 2) throw new Error('30 fps frame count')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
