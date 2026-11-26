// Which frames get a detection, measured in the browser (the result's log line: D detections a
// second within shots, the fewest in a 2 s window and the windows under the need, G the longest
// gap, S the fine pass's size and YuNet). A clip re-timed with ffmpeg to 20, 24, 25 (WebM, times
// rounded to the millisecond), 30 and 31 fps, detected at the full rate (pinned with the page's
// ?detect=, so a slow test machine doesn't pace it down): about the full rate (15 a second, or the
// app's every n-th frame: every frame at 20 fps, 15.5 a second at 31), no window under the need,
// no notice. A tune of 5 a second on the 30 fps clip measures 5 and falls short (the measure, not
// only the tune, says so); one of 13 measures 13 and doesn't. See tests/fusion.cjs for the rules.
// Run: node tests/gate.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, out] = process.argv

/** The full rate at [f] fps (lib/pace.ts's fullRate). */
const fullRate = (f) => Math.max(Math.min(f, 15), f / Math.max(1, Math.round(f / 15)))

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const SECS = 3
  const clips = []
  for (const [fps, ext] of [[20, 'mp4'], [24, 'mp4'], [25, 'webm'], [30, 'mp4'], [31, 'mp4']]) {
    const file = `${out}/clip${fps}.${ext}`
    const codec = ext === 'webm' ? ['-c:v', 'libvpx', '-b:v', '1M'] : ['-c:v', 'libx264', '-pix_fmt', 'yuv420p']
    execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', video, '-t', String(SECS), '-vf', `fps=${fps}`, '-an', ...codec, file])
    clips.push({ fps, file })
  }
  const b = await chromium.launch()
  const logs = []
  const rows = []
  try {
    const run = async (file, detect) => {
      const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'en-US' })).newPage()
      const mine = []
      p.on('console', (m) => { mine.push(m.text()); logs.push(m.text()) })
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      await p.goto(`${url}&detect=${detect}`)
      await p.setInputFiles('.hero input[type=file]', file)
      await p.waitForSelector('.ready')
      await p.click('.blur-faces')
      await p.waitForSelector('.editor, .no-faces, .card.error', { timeout: 600000 })
      const notice = await p.evaluate(() => document.querySelector('.detect-reduced')?.textContent?.trim() ?? null)
      await p.context().close()
      const line = mine.find((l) => /ONIT analyze: result /.test(l))
      if (!line) throw new Error('no result line for ' + file)
      const m = /reduced (\w+), yunet \d+; D ([\d.]+)\/s \(window min ([\d.-]+), need ([\d.]+), (\d+) windows, (\d+) short\), G (\d+) frames ([\d.]+) s, cuts (\d+) \((\d+) missed\), S (\d+) px, YuNet (\w+)/.exec(line)
      if (!m) throw new Error('the result line has no measures: ' + line)
      return { reduced: m[1] === 'true', D: +m[2], minWindow: m[3] === '-' ? null : +m[3], need: +m[4], windows: +m[5], short: +m[6], gapFrames: +m[7], gapS: +m[8], cuts: +m[9], missed: +m[10], side: +m[11], yunet: m[12] === 'on', notice }
    }
    // B1. The full rate on every frame rate: no notice, nothing short, about the full rate.
    for (const { fps, file } of clips) {
      const r = await run(file, 'yunet:1,side:960')
      const full = fullRate(fps)
      rows.push({ fps, full, ...r })
      console.log(JSON.stringify({ fps, full, ...r }))
      if (r.notice !== null || r.reduced) throw new Error(`${fps} fps at the full rate: a notice (${r.notice})`)
      if (r.short || r.missed || !r.windows) throw new Error(`${fps} fps: windows ${r.windows}, short ${r.short}, missed cuts ${r.missed}`)
      if (r.D < 0.97 * full || r.D > 1.05 * full + 1 / SECS) throw new Error(`${fps} fps: D ${r.D}, the full rate ${full}`)
      if (r.gapFrames > Math.max(1, Math.round(fps / 15)) + (fps % full ? 1 : 0)) throw new Error(`${fps} fps: a gap of ${r.gapFrames} frames`)
      if (!r.yunet || r.side !== 768) throw new Error(`${fps} fps: S ${r.side} px, YuNet ${r.yunet}`)
    }
    // B3. A tune under the need measures short (and shows the notice); one over it measures its rate and isn't short.
    const c30 = clips.find((c) => c.fps === 30).file
    const slow = await run(c30, 'yunet:1,side:960,rate:5')
    console.log(JSON.stringify({ tune: 5, ...slow }))
    if (Math.abs(slow.D - 5) > 0.3 || !slow.short || slow.notice === null) throw new Error('rate 5 on 30 fps: ' + JSON.stringify(slow))
    const mid = await run(c30, 'yunet:1,side:960,rate:13')
    console.log(JSON.stringify({ tune: 13, ...mid }))
    if (Math.abs(mid.D - 13) > 0.5 || mid.short) throw new Error('rate 13 on 30 fps: ' + JSON.stringify(mid))
    fs.writeFileSync(`${out}/gate.json`, JSON.stringify({ rows, slow, mid }, null, 1))
    console.log('gate: ok')
  } catch (e) {
    console.error('FAIL', e.message)
    console.error(logs.filter((l) => !/Failed to load|GL Driver/.test(l)).slice(-15).join('\n'))
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
