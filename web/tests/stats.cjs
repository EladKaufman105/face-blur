// The analysis stats panel: a tap on the percentage while faces are found opens tiles with charts
// that keep updating, "more details" slides open, an (i) explains a tile, and Copy puts a report for
// an agent on the clipboard. Phone width and desktop; Hebrew and English; light and dark.
// Run: node tests/stats.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const logs = []
  try {
    // Phone first: Hebrew, dark; then the desktop in English, light.
    for (const [name, viewport, locale, scheme] of [['phone', { width: 390, height: 844 }, 'he-IL', 'dark'], ['desktop', { width: 1280, height: 800 }, 'en-US', 'light']]) {
      const ctx = await b.newContext({ viewport, locale, colorScheme: scheme, permissions: ['clipboard-read', 'clipboard-write'] })
      const p = await ctx.newPage()
      await p.addInitScript((th) => { try { localStorage.setItem('onit.theme', th); localStorage.setItem('onit-beta.theme', th) } catch {} }, scheme)
      p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
      p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
      await p.goto(url)
      await p.setInputFiles('.hero input[type=file]', video)
      await p.click('.blur-faces')
      await p.waitForSelector('.analyze .stats-toggle', { timeout: 60000 })
      // Closed to start with: nothing of it is visible.
      if (await p.isVisible('[data-testid=stats-panel] .stat')) throw new Error(`${name}: the panel is open before the tap`)
      await p.click('.stats-toggle')
      await p.waitForSelector('[data-testid=stats-panel] .stat[data-cell=detect]', { state: 'visible', timeout: 30000 })
      const cells = await p.$$eval('.stats-row.big .stat, .stats-row.mid .stat', (e) => e.map((x) => x.dataset.cell))
      if (cells.join() !== 'detect,wait,frame,resolution,sampling') throw new Error(`${name}: tiles ${cells}`)
      if (await p.isVisible('.stat[data-cell=backend]')) throw new Error(`${name}: details open before the tap`)
      // The charts fill as samples come (about one a second) and the values are numbers.
      const chart = (c) => p.$eval(`.stat[data-cell=${c}] .line`, (e) => e.getAttribute('d')).catch(() => null)
      await p.waitForFunction(() => document.querySelector('.stat[data-cell=detect] .line')?.getAttribute('d'), null, { timeout: 40000 })
      const first = await chart('detect')
      await p.waitForFunction((d) => document.querySelector('.stat[data-cell=detect] .line')?.getAttribute('d') !== d, first, { timeout: 20000 })
      const second = await chart('detect')
      const value = (c) => p.$eval(`.stat[data-cell=${c}] .stat-value`, (e) => e.textContent.trim())
      const vals = {}
      for (const c of ['detect', 'wait', 'frame', 'resolution', 'sampling']) vals[c] = await value(c)
      for (const [c, v] of Object.entries(vals)) if (!/^\d/.test(v)) throw new Error(`${name}: ${c} shows "${v}"`)
      if (!/px/.test(vals.resolution)) throw new Error(`${name}: resolution "${vals.resolution}"`)
      await p.screenshot({ path: `${out}/${name}_1_panel.png` })

      // An (i): a bubble with one line, gone on a tap elsewhere.
      await p.click('.stat[data-cell=detect] .stat-label')
      await p.waitForSelector('.stat-tip', { timeout: 3000 })
      const tip = await p.$eval('.stat-tip', (e) => e.textContent.trim())
      if (tip.length < 10) throw new Error(`${name}: tip "${tip}"`)
      const box = await p.$eval('.stat-tip', (e) => { const r = e.getBoundingClientRect(); return [r.left, r.right, innerWidth] })
      if (box[0] < -1 || box[1] > box[2] + 1) throw new Error(`${name}: the tip is outside the screen ${box}`)
      await p.screenshot({ path: `${out}/${name}_2_tip.png` })
      await p.click('.stage')
      await p.waitForSelector('.stat-tip', { state: 'detached', timeout: 3000 })

      // More details slide open.
      await p.click('.stats-more-btn')
      await p.waitForSelector('.stat[data-cell=backend]', { state: 'visible', timeout: 3000 })
      await p.waitForTimeout(500)
      const detail = {}
      for (const c of ['backend', 'measured', 'gap', 'strength', 'workers', 'fine', 'coarse', 'gpu', 'check', 'crops', 'decode', 'cuts', 'merge']) detail[c] = await value(c)
      if (!/CPU|GPU/.test(detail.backend)) throw new Error(`${name}: backend "${detail.backend}"`)
      // What detection really covered: D (a second, the fewest in 2 s), G (frames, seconds), S (px, YuNet).
      if (!/^[\d.]+ \/s · min [\d.—]+$/.test(detail.measured) || !/^\d+ \S+ · [\d.]+ s$/.test(detail.gap) || !/^\d+ px · (YuNet|\S+ YuNet)$/.test(detail.strength)) throw new Error(`${name}: measured ${JSON.stringify([detail.measured, detail.gap, detail.strength])}`)
      await p.screenshot({ path: `${out}/${name}_3_details.png` })
      await p.screenshot({ path: `${out}/${name}_3_details_full.png`, fullPage: true })

      // Copy: the report on the clipboard.
      await p.click('.stats-copy')
      await p.waitForFunction(() => document.querySelector('.stats-copy')?.textContent.trim().length > 0 && document.querySelector('.stats-copy svg path')?.getAttribute('d')?.startsWith('M9 16.17'), null, { timeout: 8000 })
      const text = await p.evaluate(() => navigator.clipboard.readText())
      fs.writeFileSync(`${out}/${name}_report.txt`, text)
      for (const k of ['# ONIT face detection stats', 'app: ONIT Web', 'browser: ', 'device: ', 'webgpu_adapter: ', 'elapsed: ', 'backend: ', 'sampling_detections_per_s: now', 'resolution_px: ', 'detect_ms: now ', 'wait_ms_per_frame: now', 'frame_ms: now', 'workers: ', 'measured_detections_per_s: ', 'measured_max_gap: ', 'measured_strength: ', 'per_detection_ms: fine', 'per_frame_ms: decode_wait'])
        if (!text.includes(k)) throw new Error(`${name}: the report lacks "${k}":\n${text}`)
      if (!/\nvideo: \d+x\d+, [\d.]+ fps, \d+:\d\d\n/.test(text)) throw new Error(`${name}: video line:\n${text}`)
      if (!/detect_ms: now [\d.]+, avg [\d.]+, min [\d.]+, max [\d.]+/.test(text)) throw new Error(`${name}: detect_ms line:\n${text}`)
      // Close it again.
      await p.click('.stats-toggle')
      await p.waitForFunction(() => !document.querySelector('.stat[data-cell=detect]') || getComputedStyle(document.querySelector('.stats')?.closest('.sfold')).visibility === 'hidden', null, { timeout: 3000 })
      console.log(`${name}: tiles ${cells.join(',')} · detect chart changed ${first !== second} · values ${JSON.stringify(vals)} · details ${JSON.stringify(detail)} · report ${text.split('\n').length} lines`)
      await ctx.close()
    }
    const errs = logs.filter((l) => l.startsWith('[pageerror]'))
    if (errs.length) throw new Error(errs.join('\n'))
    console.log('PASS')
  } catch (e) {
    console.log(logs.slice(-30).join('\n'))
    throw e
  } finally {
    await b.close()
  }
})().catch((e) => { console.error(e); process.exit(1) })
