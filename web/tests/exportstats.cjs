// The export's stats panel: while a video is exported a tap on the percentage opens tiles (encode speed
// with a chart, frames done of the total, what a frame's blur costs with a chart, the encoder, the time),
// "more details" slide open (the sound's steps, the size, the bitrate), an (i) explains a tile and Copy puts
// a report for an agent on the clipboard. The pure part (ExportHistory, the report) first. Phone and desktop;
// Hebrew and English.
// Run: node tests/exportstats.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv
const fail = (m) => { throw new Error(m) }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  // ---- the pure part
  const S = await import('../src/lib/export-stats.ts')
  const facts = { srcWidth: 1920, srcHeight: 1080, width: 1080, height: 1920, codec: 'avc', encoder: 'hardware', bitrate: 8e6, fps: 30, totalFrames: 300, audio: ['copy'] }
  const h = new S.ExportHistory(facts)
  // 30 frames a second at 4 ms of blur each, for 3 s (a sample every 250 ms).
  let last
  for (let i = 0; i <= 12; i++) last = h.add({ atMs: 1000 + i * 250, frames: i * 7.5, blurFrames: i * 7.5, blurMs: i * 7.5 * 4 })
  if (Math.abs(last.fps - 30) > 0.5) fail('fps ' + last.fps)
  if (Math.abs(last.blurMs - 4) > 0.1) fail('blur ' + last.blurMs)
  if (Math.abs(last.fpsAvg - 30) > 0.5) fail('avg ' + last.fpsAvg)
  if (last.fpsHistory.length < 2 || last.fpsHistory.length > 3) fail('history ' + last.fpsHistory.length)
  // Dropped (cut) frames count as frames done but not in the blur's time.
  const g = new S.ExportHistory(facts)
  g.add({ atMs: 0, frames: 0, blurFrames: 0, blurMs: 0 })
  const c = g.add({ atMs: 1500, frames: 60, blurFrames: 30, blurMs: 90 })
  if (Math.abs(c.blurMs - 3) > 0.01 || Math.abs(c.fps - 40) > 0.5) fail(`cut frames: ${c.blurMs} ${c.fps}`)
  // Frames only grow in the history's keep (60).
  const k = new S.ExportHistory(facts)
  for (let i = 0; i < 200; i++) k.add({ atMs: i * 1000, frames: i * 30, blurFrames: i * 30, blurMs: i * 30 })
  if (k.add({ atMs: 200000, frames: 6000, blurFrames: 6000, blurMs: 6000 }).fpsHistory.length !== 60) fail('the history is not kept to 60')
  const report = S.exportReport(last, { app: 'ONIT Web 2.0 (test)', device: ['browser: test'], elapsedSec: 12, percent: 40 })
  for (const key of ['encode_fps:', 'frames:', 'blur_ms_per_frame:', 'encoder: WebCodecs, hardware', 'audio_steps: copy', 'output: 1080x1920, H.264, 8.0 Mbps']) if (!report.includes(key)) fail('the report lacks ' + key)

  const b = await chromium.launch()
  const logs = []
  try {
    for (const [name, viewport, locale, mobile] of [['desktop', { width: 1280, height: 800 }, 'en-US', false], ['phone', { width: 390, height: 844 }, 'he-IL', true]]) {
      const ctx = await b.newContext({ viewport, locale, hasTouch: mobile, isMobile: mobile, deviceScaleFactor: mobile ? 2 : 1, permissions: ['clipboard-read', 'clipboard-write'] })
      const p = await ctx.newPage()
      p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      // ?detect pins a light detection: the layout and the export are tested here, not detection.
      await p.goto(url + (url.includes('?') ? '&' : '?') + 'detect=yunet:0,side:320,rate:2')
      await p.setInputFiles('.hero input[type=file]', video)
      await p.waitForSelector('.ready')
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs', { timeout: 120000 })
      await p.click('.export-bar .btn')
      await p.click('.export-go')
      // The percentage is a button: closed to start with, nothing of the panel is visible.
      await p.waitForSelector('.stats-toggle', { timeout: 60000 })
      if (await p.isVisible('[data-testid=export-stats-panel] .stat')) fail(`${name}: the panel is open before the tap`)
      await p.click('.stats-toggle')
      await p.waitForSelector('[data-testid=export-stats-panel] .stat[data-cell=fps]', { state: 'visible', timeout: 30000 }).catch(async () => {
        // A very fast export may be over: it is retried slower (the next lines need it running).
        fail(`${name}: no stats panel while exporting (done already? ${await p.isVisible('.result')})`)
      })
      const cells = await p.$$eval('.stats-row.big .stat, .stats-row.mid .stat', (e) => e.map((x) => x.dataset.cell))
      if (cells.join() !== 'fps,frames,blur,encoder,time') fail(`${name}: tiles ${cells}`)
      const value = (cell) => p.$eval(`.stat[data-cell=${cell}] .stat-value`, (e) => e.textContent.trim())
      const vals = {}
      for (const cell of ['fps', 'frames', 'blur', 'encoder', 'time']) vals[cell] = await value(cell)
      if (!/^\d/.test(vals.fps) || !/fps/.test(vals.fps)) fail(`${name}: fps "${vals.fps}"`)
      if (!/^\d+\/\d+$/.test(vals.frames)) fail(`${name}: frames "${vals.frames}"`)
      if (!/ms/.test(vals.blur)) fail(`${name}: blur "${vals.blur}"`)
      if (!/H\.264|HEVC/.test(vals.encoder)) fail(`${name}: encoder "${vals.encoder}"`)
      if (!/^\d+:\d\d/.test(vals.time)) fail(`${name}: time "${vals.time}"`)
      console.log(name, JSON.stringify(vals))
      await p.screenshot({ path: `${out}/${name}_1_panel.png` })
      // The numbers move: the frames done grow.
      const f0 = Number(vals.frames.split('/')[0])
      await p.waitForFunction((f) => { const t = document.querySelector('.stat[data-cell=frames] .stat-value'); return t && Number(t.textContent.trim().split('/')[0]) > f }, f0, { timeout: 20000 }).catch(() => {
        if (!f0) fail(`${name}: the frames done did not grow`)
      })
      // An (i): a bubble, inside the screen, gone on a tap elsewhere.
      await p.click('.stat[data-cell=encoder] .stat-label')
      await p.waitForSelector('.stat-tip', { timeout: 3000 })
      const box = await p.$eval('.stat-tip', (e) => { const r = e.getBoundingClientRect(); return [r.left, r.right, innerWidth, e.textContent.trim().length] })
      if (box[0] < -1 || box[1] > box[2] + 1 || box[3] < 10) fail(`${name}: the tip ${box}`)
      await p.click('.live, .card .small', { force: true }).catch(() => {})
      // More details: the sound's steps and the size.
      if (await p.isVisible('.stat[data-cell=audio]')) fail(`${name}: details open before the tap`)
      await p.click('.stats-more-btn')
      await p.waitForSelector('.stat[data-cell=audio]', { state: 'visible', timeout: 3000 })
      const audio = await value('audio'), size = await value('size'), bitrate = await value('bitrate')
      if (audio.length < 3) fail(`${name}: audio "${audio}"`)
      if (!/→/.test(size) || !/Mbps/.test(bitrate)) fail(`${name}: size "${size}" bitrate "${bitrate}"`)
      await p.screenshot({ path: `${out}/${name}_2_details.png` })
      // Copy: the report on the clipboard.
      await p.click('.stats-copy')
      // The report is made with the device's details (asked for first): it lands on the clipboard a moment after the tap.
      await p.waitForFunction(async () => (await navigator.clipboard.readText()).startsWith('# ONIT export stats'), null, { timeout: 10000 }).catch(() => {})
      const text = await p.evaluate(() => navigator.clipboard.readText())
      if (!text.startsWith('# ONIT export stats (web)') || !/encode_fps:/.test(text) || !/audio_steps:/.test(text)) fail(`${name}: the copied report: ${text.slice(0, 120)}`)
      // The percentage closes it again.
      await p.click('.stats-toggle')
      await p.waitForFunction(() => !document.querySelector('[data-testid=export-stats-panel] .stat[data-cell=fps]')?.checkVisibility?.({ checkVisibilityCSS: true }) || document.querySelector('.stats-toggle')?.getAttribute('aria-expanded') === 'false')
      if ((await p.getAttribute('.stats-toggle', 'aria-expanded')) !== 'false') fail(`${name}: the panel did not close`)
      await p.waitForSelector('.result video, .card.error', { timeout: 300000 })
      if (await p.$('.card.error')) fail(`${name}: the export failed: ${await p.textContent('.card.error')}`)
      await ctx.close()
    }
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => { console.error(e); process.exit(1) })
