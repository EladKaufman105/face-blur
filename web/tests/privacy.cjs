// Where a video was filmed, and the phone that filmed it, don't go out with it (as the app's
// Mp4Privacy): three videos carrying a location - the QuickTime way (keys: ISO 6709, make, model)
// the 3GPP way (udta/loci) and the phones' way (udta/©xyz) - are exported, and neither the place nor the phone is in the file.
// Run: node tests/privacy.cjs <url> <video> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const { execFileSync } = require('child_process')
const [, , url, video, out] = process.argv

const PLACE = '+32.0853+034.7818/'

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const keys = `${out}/geo_keys.mp4`, udta = `${out}/geo_udta.mp4`
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', video, '-t', '5', '-c', 'copy', '-movflags', 'use_metadata_tags',
    '-metadata', `com.apple.quicktime.location.ISO6709=${PLACE}`, '-metadata', 'com.apple.quicktime.make=ONITTESTMAKE', '-metadata', 'com.apple.quicktime.model=ONITTESTMODEL', keys])
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', video, '-t', '5', '-c', 'copy', '-metadata', `location=${PLACE}`, udta])
  // As phones write it (Android's MediaRecorder): udta/©xyz, added at the end of moov/udta (both last in the file).
  const xyz = `${out}/geo_xyz.mp4`
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', video, '-t', '5', '-c', 'copy', '-metadata', 'title=hello', xyz])
  {
    const d = fs.readFileSync(xyz)
    const box = (at) => [d.readUInt32BE(at), d.toString('latin1', at + 4, at + 8)]
    let at = 0, moov = -1
    while (at + 8 <= d.length) { const [size, type] = box(at); if (type === 'moov') moov = at; at += size }
    let udta = -1
    for (let q = moov + 8; q < moov + box(moov)[0];) { const [size, type] = box(q); if (type === 'udta') udta = q; q += size }
    if (moov < 0 || udta < 0 || moov + box(moov)[0] !== d.length || udta + box(udta)[0] !== d.length) throw new Error('cannot add ©xyz')
    const text = Buffer.from(PLACE)
    const item = Buffer.alloc(12 + text.length)
    item.writeUInt32BE(item.length, 0); item.write('\u00a9xyz', 4, 'latin1'); item.writeUInt16BE(text.length, 8); item.writeUInt16BE(0x15c7, 10); text.copy(item, 12)
    d.writeUInt32BE(box(moov)[0] + item.length, moov)
    d.writeUInt32BE(box(udta)[0] + item.length, udta)
    fs.writeFileSync(xyz, Buffer.concat([d, item]))
  }
  const leaks = (f) => {
    const d = fs.readFileSync(f)
    return ['32.0853', 'ONITTESTMAKE', 'ONITTESTMODEL', 'loci', '©xyz'].filter((s) => d.includes(Buffer.from(s, s.startsWith('©') ? 'latin1' : 'utf8')))
  }
  for (const [f, want] of [[keys, 3], [udta, 1], [xyz, 2]]) if (leaks(f).length < want) throw new Error('the test video has no location: ' + f)

  const b = await chromium.launch()
  const p = await (await b.newContext({ viewport: { width: 1366, height: 860 }, locale: 'he-IL' })).newPage()
  const logs = []
  p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
  p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
  try {
    for (const [name, f] of [['keys', keys], ['udta', udta], ['xyz', xyz]]) {
      await p.goto(url)
      await p.waitForTimeout(800)
      const chooser = p.waitForEvent('filechooser')
      await p.click('.tool >> nth=0')
      await (await chooser).setFiles(f)
      await p.waitForSelector('.editor .tabs', { timeout: 30000 })
      await p.click('.export-bar .btn')
      await p.click('.export-go')
      await p.waitForSelector('.result video, .card.error', { timeout: 900000 })
      const err = await p.evaluate(() => document.querySelector('.card.error')?.textContent)
      if (err) throw new Error('export: ' + err)
      const href = await p.getAttribute('a[download]', 'href')
      const bytes = await p.evaluate(async (h) => Array.from(new Uint8Array(await (await fetch(h)).arrayBuffer())), href)
      const o = `${out}/out_${name}.mp4`
      fs.writeFileSync(o, Buffer.from(bytes))
      const left = leaks(o)
      console.log(name, 'in:', JSON.stringify(leaks(f)), '→ exported:', JSON.stringify(left))
      if (left.length) throw new Error('the export still says where it was filmed / on what')
    }
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    await b.close()
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
