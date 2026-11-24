// Playback over cuts that sit close together (and one almost touching, and one that runs to the
// end of the video): the player jumps over each, the clock keeps moving forward (no frozen time,
// no flicker between frames from jumping again and again), it never goes back into a cut, and at
// a cut that reaches the end of the video it stops.
// Run: node tests/cutskip.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  const seek = async (t) => { await p.evaluate((t) => { const v = document.querySelector('video'); v.pause(); v.currentTime = t }, t); await p.waitForTimeout(500) }
  try {
    await p.goto(url)
    await p.waitForTimeout(1000)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=1')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.waitForTimeout(1500)
    const dur = await p.evaluate(() => document.querySelector('video').duration)
    await p.click('.tabs button[data-g=edit]')
    // Cuts: four 0.8 s ones 0.2 s apart, two that are one frame apart, and the last second of the video.
    const cuts = [[2.0, 2.8], [3.0, 3.8], [4.0, 4.8], [5.0, 5.8], [7.0, 7.5], [7.52, 8.0], [dur - 1, dur]]
    for (const [a, z] of cuts) {
      await seek(a)
      await p.click('.cut-start')
      await seek(z)
      await p.click('.cut-end')
      await p.waitForTimeout(200)
    }
    const total = Number(await p.getAttribute('.cut', 'data-total'))
    const expected = cuts.reduce((s, [a, z]) => s + (z - a), 0)
    console.log('cut', total.toFixed(2), 's of', expected.toFixed(2), 'in', cuts.length, 'stretches; the video is', dur.toFixed(2), 's')
    if (Math.abs(total - expected) > 0.15) throw new Error('the cuts: ' + total)

    // Play from 1.5 s, watching the clock and the seeks.
    await seek(1.5)
    await p.evaluate((cuts) => {
      const v = document.querySelector('video')
      const s = { samples: [], seeks: 0, cuts }
      window.__cs = s
      v.addEventListener('seeking', () => { s.seeks++ })
      s.timer = setInterval(() => s.samples.push([performance.now(), v.currentTime, v.paused]), 40)
      v.play()
    }, cuts)
    await p.waitForFunction(() => document.querySelector('video').paused, null, { timeout: 60000 })
    await p.waitForTimeout(600)
    const r = await p.evaluate(() => {
      const s = window.__cs
      clearInterval(s.timer)
      const v = document.querySelector('video')
      return { samples: s.samples, seeks: s.seeks, end: v.currentTime, dur: v.duration, paused: v.paused, label: document.querySelector('.time, .timecode')?.textContent ?? null }
    })
    const inCut = (t) => cuts.some(([a, z]) => t >= a + 0.05 && t < z - 0.05)
    let max = 0, back = 0, stall = 0, lastMove = r.samples[0][0], insideCut = 0
    for (const [at, t, paused] of r.samples) {
      if (inCut(t)) insideCut++
      if (t < max - 0.05) back = Math.max(back, max - t)
      if (t > max + 0.001) { max = t; lastMove = at }
      else if (!paused) stall = Math.max(stall, at - lastMove)
    }
    console.log('seeks', r.seeks, '· samples', r.samples.length, '· in a cut in', insideCut, 'samples · longest stall', Math.round(stall), 'ms · furthest back', back.toFixed(3), 's · ended at', r.end.toFixed(2), 'paused', r.paused)
    if (back > 0.05) throw new Error('the clock went back by ' + back.toFixed(2) + ' s')
    if (stall > 700) throw new Error('the clock stood still for ' + Math.round(stall) + ' ms')
    // One jump per cut (a few more for the first play and the end), not a loop.
    if (r.seeks > cuts.length * 2 + 3) throw new Error(r.seeks + ' seeks for ' + cuts.length + ' cuts')
    // At most a frame or two is shown inside a cut each time (the samples are 40 ms apart).
    if (insideCut > 6) throw new Error('playing inside the cuts: ' + insideCut + ' samples')
    if (!r.paused || r.end < dur - 0.15) throw new Error('did not stop at the end: ' + r.end + ' of ' + dur)
    // It really got past every cut: time was seen after each.
    for (const [, z] of cuts.slice(0, -1)) if (!r.samples.some(([, t]) => t >= z && t < z + 1)) throw new Error('never played after the cut that ends at ' + z)
    console.log('OK')
  } catch (e) {
    await p.screenshot({ path: `${out}/failure.png` }).catch(() => {})
    console.log(logs.slice(-15).join('\n'))
    console.error(e)
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
