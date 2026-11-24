// Speaker detection, as in the app: the sample's first half is one voice (JFK), its second half
// the same pitched up (another speaker); subtitles on the speech (from an SRT), speakers found
// automatically, each in its color.
// Run: node tests/speakers.cjs <url> <speech_two.mp4> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, out] = process.argv

const spans = [[0.3, 2.1], [3.3, 4.3], [5.4, 7.5], [8.2, 11.0], [11.3, 13.1], [14.3, 15.3], [16.4, 18.5], [19.2, 21.3]]
const ts = (t) => new Date(t * 1000).toISOString().slice(11, 23).replace('.', ',')
const SRT = spans.map(([a, b], i) => `${i + 1}\n${ts(a)} --> ${ts(b)}\nמשפט ${i + 1}\n`).join('\n')

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(`${out}/in.srt`, SRT)
  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  try {
    await p.goto(url)
    await p.waitForTimeout(800)
    const chooser = p.waitForEvent('filechooser')
    await p.click('.tool >> nth=0')
    await (await chooser).setFiles(video)
    await p.waitForSelector('.editor .tabs', { timeout: 30000 })
    await p.setInputFiles('.srt-import input', `${out}/in.srt`)
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    await p.waitForSelector('.speakers-go')
    const t0 = Date.now()
    await p.click('.speakers-go')
    await p.waitForSelector('.cue-speaker, .speakers p[style*="primary"]', { timeout: 300000 })
    const found = await p.evaluate(() => [...document.querySelectorAll('.cue-speaker')].map((e) => Number(e.textContent)))
    console.log(`speakers in ${((Date.now() - t0) / 1000).toFixed(1)} s:`, JSON.stringify(found))
    await p.evaluate(() => { const v = document.querySelector('video'); v.currentTime = 12 })
    await p.waitForTimeout(800)
    await p.screenshot({ path: `${out}/speakers.png` })
    const want = [1, 1, 1, 1, 2, 2, 2, 2]
    const right = found.filter((s, i) => s === want[i]).length
    console.log(`${right} of ${want.length} right`)
    if (found.length !== want.length || right < 7) throw new Error('speakers')
    // A tap on a dot changes the speaker.
    await p.click('.cue-speaker >> nth=0')
    const changed = await p.evaluate(() => Number(document.querySelector('.cue-speaker').textContent))
    console.log('first subtitle tapped → speaker', changed)
    if (changed !== 2) throw new Error('changing the speaker')
    // Speaker 2's color chosen in the design (speaker 1 keeps the text color).
    await p.click('.speaker-colors .speaker-dot >> nth=1')
    await p.click('.speaker-swatches .swatch[aria-label="#ff2d55"]')
    const colors = await p.evaluate(() => [...document.querySelectorAll('.cue-speaker')].map((e) => [e.textContent, getComputedStyle(e).backgroundColor]))
    const two = colors.filter(([s]) => s === '2').map(([, c]) => c), one = colors.filter(([s]) => s === '1').map(([, c]) => c)
    console.log('speaker 2 in red:', JSON.stringify([...new Set(two)]), '· speaker 1:', JSON.stringify([...new Set(one)]))
    if (!two.length || two.some((c) => c !== 'rgb(255, 45, 85)') || one.some((c) => c === 'rgb(255, 45, 85)')) throw new Error('speaker colors')
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
