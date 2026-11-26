// A template's translation, as the app's: the template editor's "Add a translation" switch (and,
// with it, the four choices of what the video shows), the switch shown on the ready screen with
// the template, and the translation made in automatic processing - "save straight away" (a batch of
// one) and the editor the template opens - or a note when the browser has no translator.
// No model is downloaded: the speech worker is replaced by one that answers with fixed words, and
// the browser's translator (Chrome's Translator API, with no language packs in a test browser) by a
// stub - or taken away, as in a browser that has none.
// Run: node tests/tpltranslate.cjs <url> <video with speech> <out dir>
const { chromium } = require('playwright')
const fs = require('fs')
const [, , base, video, out] = process.argv

// Two sentences (a pause between them): two subtitles.
const WORDS = [
  { start: 0.4, end: 0.8, text: 'שלום' }, { start: 0.9, end: 1.3, text: 'לכולם' },
  { start: 2.6, end: 3.0, text: 'מה' }, { start: 3.1, end: 3.6, text: 'שלומכם' },
]
const FAKE_WORKER = `self.onmessage = () => {
  self.postMessage({ type: 'audio' })
  self.postMessage({ type: 'transcribe', progress: 0.5 })
  self.postMessage({ type: 'done', words: ${JSON.stringify(WORDS)}, device: 'fake', ms: { load: 1, recognize: 1, first: 0, decode: 0, after: 0, tokens: 0 } })
}`
// The browser's translator: each text marked with its target language; every call is recorded.
const STUB = () => {
  window.__tr = []
  globalThis.Translator = {
    availability: async () => 'available',
    create: async (o) => {
      window.__tr.push({ create: [o.sourceLanguage, o.targetLanguage] })
      return { translate: async (s) => { window.__tr.push({ text: s }); return `[${o.targetLanguage}] ${s}` }, destroy() {} }
    },
  }
}
const SEED = { v: 1, id: 'tptr', name: 'עם תרגום', group: 'USER', blur: { none: true }, subs: { create: true, language: 'he', translate: false, burn: 'ORIGINAL', saveSrt: false } }

;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const b = await chromium.launch()
  const logs = []
  let cur = null
  const fail = (m) => { throw new Error(m) }
  const newPage = async (translator) => {
    const ctx = await b.newContext({ viewport: { width: 1280, height: 860 }, locale: 'he-IL', acceptDownloads: true })
    await ctx.route(/huggingface\.co|\.hf\.co/, (r) => r.abort())
    await ctx.route(/subs\.worker[^/]*\.js(\?.*)?$/, (r) => r.fulfill({ status: 200, contentType: 'text/javascript', body: FAKE_WORKER }))
    await ctx.addInitScript(translator ? STUB : () => { delete globalThis.Translator })
    const p = await ctx.newPage()
    p.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
    p.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
    return p
  }
  /** The template seeded in the browser's store (as the settings would keep it). */
  const seed = (p, row) => p.evaluate(async (row) => {
    await new Promise((r) => setTimeout(r, 300))
    const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /templates/.test(n))
    const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    await new Promise((res) => { const t = db.transaction('user', 'readwrite'); t.objectStore('user').put({ json: row, at: Date.now() }, row.id); t.oncomplete = res })
    db.close()
  }, row)
  const stored = (p) => p.evaluate(async () => {
    const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /templates/.test(n))
    const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    const v = await new Promise((res) => { const g = db.transaction('user').objectStore('user').get('tptr'); g.onsuccess = () => res(g.result) })
    db.close()
    return v?.json ?? null
  })
  /** The subtitles of every project kept in the browser. */
  const projectCues = (p) => p.evaluate(async () => {
    const name = (await indexedDB.databases()).map((d) => d.name).find((n) => /projects/.test(n))
    if (!name) return []
    const db = await new Promise((res, rej) => { const r = indexedDB.open(name); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    const all = await new Promise((res) => { const g = db.transaction('state').objectStore('state').getAll(); g.onsuccess = () => res(g.result) })
    db.close()
    return all.map((s) => (s?.cues ?? []).map((c) => ({ text: c.text, tr: c.tr, trm: c.trm })))
  })
  const sep = base.includes('?') ? '&' : '?'
  const url = `${base}${sep}subswatch=30`
  /** "Save straight away" from the ready screen with the template chosen; the queue's item when it ends. */
  const saveStraight = async (p) => {
    await p.click('.blur-save')
    await p.click('.export-go')
    const end = '.batch-item[data-status="saved"], .batch-item[data-status="failed"], .batch-item[data-status="nofaces"]'
    // The speech model is not in the browser: asked first (the fake worker "downloads" nothing).
    await p.waitForSelector(`.speech-ask, ${end}`, { timeout: 120000 })
    if (await p.$('.speech-ask')) await p.click('.speech-go')
    await p.waitForSelector(end, { timeout: 300000 })
    return p.evaluate(() => ({ status: document.querySelector('.batch-item').dataset.status, notes: [...document.querySelectorAll('.batch-note')].map((e) => e.textContent) }))
  }

  try {
    // ---- the template editor: "Add a translation" under "create subtitles", and what the video shows with it
    {
      const p = (cur = await newPage(true))
      await p.goto(url)
      await p.waitForTimeout(800)
      await seed(p, SEED)
      await p.reload()
      await p.waitForTimeout(800)
      await p.click('.settings-btn')
      await p.waitForSelector('.tpl-item[data-tpl="tptr"]')
      await p.click('.tpl-item[data-tpl="tptr"] .tpl-edit')
      await p.waitForSelector('.tpl-sheet .tpl-translate')
      if ((await p.$$('.tpl-burn button')).length !== 2) fail('with no translation the video has two choices')
      await p.click('.tpl-translate')
      await p.waitForFunction(() => document.querySelectorAll('.tpl-burn button').length === 4)
      await p.click('.tpl-burn button[data-burn="BOTH"]')
      await p.screenshot({ path: `${out}/1_editor.png` })
      // Off and on again: a choice that needs the translation goes back to the spoken text.
      await p.click('.tpl-translate')
      await p.waitForFunction(() => document.querySelectorAll('.tpl-burn button').length === 2)
      await p.click('.tpl-translate')
      if (!(await p.$('.tpl-burn button[data-burn="ORIGINAL"].on'))) fail('switched off, the video should show the spoken text')
      await p.click('.tpl-burn button[data-burn="BOTH"]')
      await p.click('.tpl-save')
      await p.waitForSelector('.tpl-sheet', { state: 'detached' })
      const j = await stored(p)
      console.log('stored:', JSON.stringify(j?.subs))
      if (j?.subs?.translate !== true || j?.subs?.burn !== 'BOTH') fail('the translation was not saved: ' + JSON.stringify(j?.subs))

      // ---- the ready screen: the template's translation shown (and can be left out); saved straight away, translated
      await p.goto(url)
      await p.waitForTimeout(800)
      await p.setInputFiles('.hero input[type=file]', video)
      await p.waitForSelector('.ready .tpl-card[data-tpl="tptr"]')
      if (await p.$('.tpl-translate-run')) fail('a translation switch with no template')
      await p.click('.tpl-card[data-tpl="tptr"]')
      await p.waitForSelector('.tpl-translate-run input:checked', { state: 'attached' })
      const text = await p.textContent('.tpl-translate-run')
      if (!text.includes('אוטומטי')) fail('the switch does not say where it translates to: ' + text)
      await p.screenshot({ path: `${out}/2_ready.png` })
      const r = await saveStraight(p)
      const tr = await p.evaluate(() => window.__tr)
      const cues = (await projectCues(p)).flat()
      console.log('saved straight:', JSON.stringify(r), '· translator:', JSON.stringify(tr), '· cues:', JSON.stringify(cues))
      if (r.status !== 'saved') fail('not saved: ' + JSON.stringify(r))
      if (r.notes.length) fail('notes: ' + r.notes.join(' | '))
      if (!tr.some((x) => x.create && x.create[0] === 'he' && x.create[1] === 'en')) fail('not translated from Hebrew to English')
      if (cues.length !== 2 || cues.some((c) => c.tr !== `[en] ${c.text}` || c.trm !== c.text)) fail('the subtitles are not translated: ' + JSON.stringify(cues))
      await p.screenshot({ path: `${out}/3_saved.png` })

      // ---- the editor the template opens (no face blur: straight there) translates too
      await p.goto(url)
      await p.waitForTimeout(800)
      if ((await projectCues(p)).length !== 1) fail('one project before the editor')
      await p.setInputFiles('.hero input[type=file]', video)
      await p.click('.ready .tpl-card[data-tpl="tptr"]')
      await p.click('.blur-faces')
      await p.waitForSelector('.editor .tabs', { timeout: 60000 })
      if (await p.$('.speech-ask')) await p.click('.speech-go')
      await p.waitForFunction(() => window.__tr.some((x) => x.text), null, { timeout: 60000 })
      // The project keeps them, with the translation (saved as it's edited): a second project translated, besides the one saved straight.
      const translated = async () => (await projectCues(p)).filter((cs) => cs.length === 2 && cs.every((c) => c.tr))
      let ed = []
      for (let i = 0; i < 40 && ed.length < 2; i++) {
        await p.waitForTimeout(500)
        ed = await translated()
      }
      ed = ed[1] ?? []
      console.log('editor cues:', JSON.stringify(ed))
      if (ed.length !== 2 || ed.some((c) => c.tr !== `[en] ${c.text}`)) fail('the editor did not translate the subtitles: ' + JSON.stringify(ed))
      if (await p.$('.tpl-note')) {
        const n = await p.textContent('.tpl-note')
        if (n.includes('תרגום')) fail('a translation note: ' + n)
      }
      await p.screenshot({ path: `${out}/4_editor.png` })
      await p.context().close()
    }

    // ---- a browser with no translator: said on the ready screen, and the video is saved with a note
    {
      const p = (cur = await newPage(false))
      await p.goto(url)
      await p.waitForTimeout(800)
      await seed(p, { ...SEED, subs: { ...SEED.subs, translate: true, burn: 'BOTH' } })
      await p.reload()
      await p.waitForTimeout(800)
      await p.setInputFiles('.hero input[type=file]', video)
      await p.click('.ready .tpl-card[data-tpl="tptr"]')
      await p.waitForSelector('.tpl-translate-run')
      const text = await p.textContent('.tpl-translate-run')
      if (!text.includes('לא מתרגם')) fail('no word that this browser does not translate: ' + text)
      const r = await saveStraight(p)
      console.log('no translator:', JSON.stringify(r))
      if (r.status !== 'saved') fail('not saved: ' + JSON.stringify(r))
      if (!r.notes.some((n) => n.includes('התרגום לא נוצר'))) fail('no note that the translation was not made: ' + JSON.stringify(r.notes))
      const cues = (await projectCues(p)).flat()
      if (cues.length !== 2 || cues.some((c) => c.tr)) fail('the subtitles should be kept, untranslated: ' + JSON.stringify(cues))
      await p.screenshot({ path: `${out}/5_no_translator.png` })
      await p.context().close()
    }
    console.log('PASS')
  } catch (e) {
    console.log('FAIL', e.message)
    if (cur) await cur.screenshot({ path: `${out}/fail.png` }).catch(() => {})
    console.log(logs.slice(-30).join('\n'))
    process.exitCode = 1
  } finally {
    await b.close()
  }
})()
