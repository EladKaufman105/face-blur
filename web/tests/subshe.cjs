// Hebrew subtitles with ivrit.ai's model (as the app does): the model downloads from Hugging Face
// (760 MB), runs on the CPU here, and the subtitles come back as Hebrew text with word timings
// (the bleeps depend on them: a word can be picked and hidden; the words start in order, spread
// over the clip). Every request of the model goes to the pinned commit, never to `resolve/main`,
// and a second run with every Hugging Face request blocked (the model is in the browser by then)
// works with no request to the Hub at all (offline).
// Run: node tests/subshe.cjs <url> <video with Hebrew speech> <expected words, a regex> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , url, video, expect, out] = process.argv
const PIN = 'c71eadaca74c0923a06632bdaeb7413ff9cff4cf'

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  // A browser profile on disk, not a throwaway context: a throwaway one keeps its storage in memory with a small
  // quota, which can't hold a 760 MB model (the browser's cache would refuse it, and "cached" could never be seen).
  const profile = fs.mkdtempSync(require('os').tmpdir() + '/subshe-profile-')
  const ctx = await chromium.launchPersistentContext(profile, { ...(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {}), viewport: { width: 1366, height: 860 }, locale: 'he-IL' })
  const b = ctx
  const logs = []
  const hub = []
  let offline = false
  let blocked = 0
  await ctx.route(/huggingface\.co|\.hf\.co/, (r) => {
    const u = r.request().url()
    hub.push(`${offline ? 'BLOCKED ' : ''}${u}`)
    if (offline) { blocked++; return r.abort() }
    return r.continue()
  })
  const fail = (m) => { throw new Error(m) }

  // One run: the video in, Hebrew, "better quality" (ivrit.ai), create the subtitles, check them.
  const run = async (label) => {
    const p = await ctx.newPage()
    p.on('console', (m) => logs.push(`[${label}][${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => logs.push(`[${label}][pageerror] ${e.message}`))
    await p.goto(url)
    await p.setInputFiles('.hero input[type=file]', video)
    await p.click('.blur-faces')
    await p.waitForSelector('.editor, .no-faces', { timeout: 900000 })
    if (await p.$('.no-faces')) await p.click('.mark-by-hand')
    await p.waitForSelector('.editor', { timeout: 900000 })
    await p.click('.tabs button[data-g=subs]')
    await p.click('.lang-chips .chip >> text=עברית')
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    await p.click('.speech-quality [data-q=better]')
    const readNote = () => p.evaluate(() => [...document.querySelectorAll('.speech-note')].map((e) => e.textContent).join(' '))
    // Whether the model is already here is asked of the browser's cache a moment after the page shows: wait for it.
    let note = await readNote()
    for (let i = 0; i < 40 && label === 'offline' && !note.includes('כבר במכשיר'); i++) { await p.waitForTimeout(250); note = await readNote() }
    console.log(label, 'note:', note.trim(), JSON.stringify(await p.evaluate(async () => { const e = await navigator.storage.estimate(); const c = await caches.keys(); return { quotaMB: Math.round((e.quota ?? 0) / 1e6), usageMB: Math.round((e.usage ?? 0) / 1e6), caches: c } })))
    if (!note.includes('ivrit.ai')) fail('the Hebrew model is not offered: ' + note)
    if (label === 'first' && !note.includes('763 MB')) fail('its size is not shown: ' + note)
    // The second time the model is in the browser: no download is offered.
    if (label === 'offline' && !note.includes('כבר במכשיר')) fail('the cached model is not seen as cached: ' + note)
    const t0 = Date.now()
    await p.click('text=צור כתוביות')
    let last = ''
    while (Date.now() - t0 < 45 * 60000) {
      if (await p.$('.cue-text')) break
      const err = await p.evaluate(() => document.querySelector('.card p[style*="primary"]')?.textContent)
      if (err) fail(err)
      const s = await p.evaluate(() => [...document.querySelectorAll('.card strong, .card .small')].map((e) => e.textContent).join(' | ').slice(0, 160))
      if (s !== last) { console.log(label, ((Date.now() - t0) / 1000).toFixed(1), 's', s); last = s }
      await p.waitForTimeout(1000)
    }
    const cues = await p.evaluate(() => [...document.querySelectorAll('.cue-text')].map((e) => e.value))
    const ran = logs.filter((l) => l.startsWith(`[${label}]`) && l.includes('ONIT subs:'))
    for (const l of ran) console.log(l)
    console.log(label, 'subtitles', ((Date.now() - t0) / 1000).toFixed(1), 's:', cues.length, 'cues')
    for (const c of cues) console.log('  ' + c)
    if (!ran.some((l) => l.includes('ivrit'))) fail('the recognition did not run on the ivrit.ai model')
    if (!cues.length) fail('no subtitles')
    if (!/[֐-׿]/.test(cues.join(' '))) fail('the subtitles are not Hebrew: ' + cues.join(' '))
    if (!new RegExp(expect).test(cues.join(' '))) fail(`expected /${expect}/ in: ` + cues.join(' '))
    // Word timings: one word to a subtitle (the list shows whole seconds), and the starts rise and spread over the clip (a bleep lands on its word).
    const dur = await p.evaluate(() => document.querySelector('video').duration)
    await p.evaluate(() => document.querySelectorAll('details.subs-more, details.sub-fine').forEach((d) => { d.open = true }))
    await p.click('.split-by button >> nth=0')
    await p.click('.split-cues .chip >> text=/^1$/')
    const starts = await p.evaluate(() => [...document.querySelectorAll('.cue-list .time-btn')].map((e) => e.textContent.trim()))
      .then((l) => l.map((s) => { const m = /^(\d+):(\d+)(?:\.(\d))?/.exec(s); return m ? Number(m[1]) * 60 + Number(m[2]) + Number(m[3] ?? 0) / 10 : NaN }))
    console.log(label, 'word starts:', JSON.stringify(starts), 'clip', dur.toFixed(1), 's')
    if (starts.length < 6 || starts.some(Number.isNaN)) fail('too few timed words: ' + starts.length)
    for (let i = 1; i < starts.length; i++) if (starts[i] < starts[i - 1]) fail('the word starts do not rise: ' + JSON.stringify(starts))
    if (starts[starts.length - 1] - starts[0] < dur * 0.4) fail(`the words are not spread over the clip (${starts[0]} to ${starts[starts.length - 1]} of ${dur}s)`)
    if (starts[starts.length - 1] > dur + 0.5) fail('a word starts after the clip ends')
    await p.click('button:text-is("בחר מילים")')
    const words = await p.evaluate(() => [...document.querySelectorAll('.word-chips .word')].length)
    if (words < 6) fail('too few words to bleep: ' + words)
    await p.screenshot({ path: `${out}/subs_he_${label}.png` })
    await p.close()
  }

  try {
    await run('first')
    const firstMain = hub.filter((u) => u.includes('/resolve/main/'))
    console.log('Hub requests in the first run:', hub.length, '(to resolve/main:', firstMain.length + ')')
    const model = hub.filter((u) => u.includes('ivrit-whisper'))
    if (!model.length) fail('no request for the Hebrew model was seen')
    // All of the model's requests go to the pinned commit (the other models are not used here).
    const loose = model.filter((u) => !u.includes(`/resolve/${PIN}/`))
    if (loose.length) fail('requests that are not pinned: ' + loose.slice(0, 3).join(' '))
    // The model is in the browser now: block the Hub, and do it all again.
    offline = true
    hub.length = 0
    await run('offline')
    console.log('Hub requests in the offline run (all blocked):', hub.length)
    if (hub.some((u) => u.includes('/resolve/main/'))) fail('a request went to resolve/main: ' + hub.filter((u) => u.includes('/resolve/main/')).join(' '))
    if (blocked) fail(`the cached model still asked the Hub ${blocked} times: ` + hub.slice(0, 3).join(' '))
    console.log('PASS')
  } finally {
    fs.writeFileSync(`${out}/console.txt`, logs.join('\n'))
    fs.writeFileSync(`${out}/hub.txt`, hub.join('\n'))
    await ctx.close()
    fs.rmSync(profile, { recursive: true, force: true })
  }
})().catch((e) => {
  console.error('FAIL', e.message)
  process.exit(1)
})
