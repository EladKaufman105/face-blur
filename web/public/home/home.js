// ONIT's landing page: Hebrew / English, reveals and word-by-word headlines on scroll, counters, a
// moving ribbon, the hero's recording and the app's screens taking turns, the before / after video,
// a recording for each tool, the trailers. No tracking, nothing sent anywhere; all motion stops for
// people who ask their device for less of it.
;(function () {
  var d = document.documentElement
  d.classList.add('js')
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
  var $ = function (s, r) { return (r || document).querySelector(s) }
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)) }

  // ---------------------------------------------------------------- the language
  var EN = {
    skip: 'Skip to content',
    navLabel: 'Navigation',
    navFeatures: 'Features', navPrivacy: 'Privacy', navPlatforms: 'Web & Android', navFaq: 'FAQ',
    openWeb: 'Open in the browser', getAndroid: 'Get it for Android', getApk: 'Download the app',
    eyebrow: 'New: ONIT now runs in the browser',
    heroTitle: 'A complete video editor.<br><span class="g">Nothing goes online.</span>',
    heroLead: 'Automatic face blur, styled subtitles, voice changing and background removal. It all runs on your device: no server, no account, and your video is never uploaded anywhere.',
    heroFine: 'Free · No sign-up · Hebrew and English',
    chip1: '2 faces found and blurred', chip2: '0 files uploaded',
    stat1: 'uploads to a server', stat2: 'subtitle styles', stat3: 'sound effects', stat4: 'platforms: web and Android',
    k1: '01 · PRIVACY',
    cmpTitle: 'Faces found and blurred. <span class="g">Automatically.</span>',
    cmpText: 'ONIT finds every face in the video and follows it through the shot, even when the camera moves. Choose blur, pixels or a solid cover, and leave chosen faces visible.',
    altBefore: 'The original: a room with five people, faces visible', altAfter: 'After ONIT: every face blurred',
    before: 'Original', cmpAria: 'Compare the original and the result', cmpHint: 'Drag to compare',
    k2: '02 · THE TOOLS', toolsLabel: 'Tools',
    featTitle: 'Everything you need to post, <span class="g">in one place.</span>',
    f1t: 'Face detection', f1d: 'A detection model running on your device, on the GPU when it can, that builds a track for each person through the video.',
    f2t: 'Blur and editing', f2d: 'Blur, pixels or a cover. A manual blur that follows anything in the frame by itself, cuts, and removing pauses.',
    f3t: 'Subtitles', f3d: 'Transcribed on the device in Hebrew and English, or imported from SRT. 28 styles, 18 effects, 10 fonts and a color for each speaker.',
    f4t: 'Background & picture', f4d: 'Background removal without a green screen, a green-screen key, camera stabilization and a 9:16 frame that follows the faces.',
    f5t: 'Sound', f5d: 'Noise cleanup, voice changing, bleeping words picked from the subtitles, and 51 sound effects on the timeline.',
    f6t: 'Export', f6d: 'MP4 with a live preview as it exports: aspect ratio, resolution, H.264 or HEVC, or MP3 only. Location and device details stay out of the file.',
    altDetect: 'Faces being detected', altEditor: 'The editor: faces blurred', altSubs: 'Subtitles in the viral yellow style', altBg: 'A purple background behind the people', altSound: 'The sound panel', altExport: 'Exporting with a live preview',
    k3: '03 · NO CLOUD',
    privTitle: 'No server. No upload.<br><span class="g">Everything stays on your device.</span>',
    p1t: 'Your video never leaves the device', p1d: 'Detection, transcription, editing and export all happen in the browser or on the phone. There is no server that receives your files.',
    p2t: 'No account', p2d: 'Open it and start working. No sign-up, no cloud, no subscription.',
    p3t: 'A clean file', p3d: 'Where it was filmed and on what device aren’t kept in the exported file.',
    k4: '04 · WHEREVER YOU ARE',
    platTitle: 'In the browser or as an app. <span class="g">Same ONIT.</span>',
    webT: 'In the browser', webD: 'Nothing to install: on a computer, a phone or a tablet. Install the site as an app and share videos to it from your gallery.', webM: 'Best in an up-to-date Chrome or Edge',
    appT: 'Android', appD: 'A full app for phones and tablets, with a wide editing screen on tablets and Hebrew transcription with a model trained for Hebrew (ivrit.ai).', appM: 'Android 10 or later · APK file',
    tabT: 'And on a tablet, a full editing screen', tabD: 'The player, the timeline and the tools on one screen, with a properties panel for every blur and every subtitle.',
    galleryLabel: 'Screens from the app',
    g1: 'Blur that follows', g2: 'Subtitles', g3: 'Bleeping words', g4: 'Background removal',
    k5: '05 · IN MOTION', trTitle: 'Watch the trailer', trLabel: 'Trailer', trSite: 'The website', trApp: 'The app',
    k6: '06 · HOW IT WORKS', stepsTitle: 'Three steps, <span class="g">that’s it.</span>',
    s1t: 'Pick a video', s1d: 'From your computer or gallery, or drag it into the window.',
    s2t: 'ONIT finds the faces', s2d: 'And blurs them automatically. Change any of them, or leave one visible.',
    s3t: 'Edit and export', s3d: 'Subtitles, sound and background, then an export ready for social media.',
    k7: '07 · QUESTIONS', faqTitle: 'Frequently asked questions',
    q1: 'Is the video really never uploaded?', a1: 'Really. The video is read straight from your device and processed there. The only things downloaded are the site itself and the models it needs.',
    q2: 'How much does it cost?', a2: 'Nothing. No subscription, no ads, no sign-up. The code is on GitHub.',
    q3: 'Which browser should I use?', a3: 'An up-to-date Chrome or Edge, on a computer or on Android. There, face detection runs on the GPU (WebGPU). Without WebGPU it runs on the CPU and takes longer.',
    q4: 'What’s the difference between the site and the app?', a4: 'The same tools and the same design. In the Android app, Hebrew transcription uses a model trained specifically for Hebrew (ivrit.ai).',
    q5: 'How do I install the app?', a5: 'Download the APK from the releases page on GitHub, open it on your phone and allow the install. Android 10 or later (arm64) is required.',
    finalTitle: 'Your next video,<br><span class="g">without exposing anyone.</span>',
    footLine: 'A video editor that works on your device',
    credits: 'Fonts: Allo Hebrew and Rubik (SIL OFL)',
    flow1: 'Your video', flowCloud: 'Server', flow2: 'Your device', flow2s: 'Detect · Transcribe · Export', flow3: 'The finished video',
  }
  var RIBBON = {
    he: [['טשטוש פנים', 'פיקסלים', 'כיסוי מלא', 'מעקב אחרי פנים', 'טשטוש ידני', 'חיתוך', 'הסרת שתיקות', 'ייצוב מצלמה', 'מסגרת 9:16', 'HEVC', 'MP3'],
         ['כתוביות אוטומטיות', '28 סגנונות', '18 אפקטים', 'תרגום', 'זיהוי דוברים', 'שינוי קול', 'ניקוי רעשים', 'צפצוף על מילים', '51 אפקטים קוליים', 'הסרת רקע', 'מסך ירוק']],
    en: [['Face blur', 'Pixels', 'Solid cover', 'Face tracking', 'Manual blur', 'Cuts', 'Pause removal', 'Stabilization', '9:16 frame', 'HEVC', 'MP3'],
         ['Auto subtitles', '28 styles', '18 effects', 'Translation', 'Speaker detection', 'Voice changer', 'Noise cleanup', 'Word bleeping', '51 sound effects', 'Background removal', 'Green screen']],
  }
  var HE = {}
  $$('[data-i18n]').forEach(function (el) { HE[el.dataset.i18n] = el.innerHTML })
  $$('[data-i18n-alt]').forEach(function (el) { HE[el.dataset.i18nAlt] = el.alt })
  $$('[data-i18n-aria]').forEach(function (el) { HE[el.dataset.i18nAria] = el.getAttribute('aria-label') })
  var titles = { he: document.title, en: 'ONIT · A private video editor, in the browser and on Android' }
  var descs = { he: $('meta[name=description]').content, en: 'A video editor that runs entirely on your device: automatic face blur, subtitles, voice changing and background removal. No server, no account, no upload.' }

  function setLang(l, save) {
    var dict = l === 'en' ? EN : HE
    $$('[data-i18n]').forEach(function (el) { var v = dict[el.dataset.i18n]; if (v != null) el.innerHTML = v })
    $$('[data-i18n-alt]').forEach(function (el) { var v = dict[el.dataset.i18nAlt]; if (v != null) el.alt = v })
    $$('[data-i18n-aria]').forEach(function (el) { var v = dict[el.dataset.i18nAria]; if (v != null) el.setAttribute('aria-label', v) })
    d.lang = l; d.dir = l === 'he' ? 'rtl' : 'ltr'; d.dataset.lang = l
    document.title = titles[l]; $('meta[name=description]').content = descs[l]
    var b = $('#lang'); b.textContent = l === 'he' ? 'EN' : 'עב'; b.setAttribute('aria-label', l === 'he' ? 'English' : 'עברית')
    if (save) { try { localStorage.setItem((self.ONIT_STORE || 'onit') + '.lang', l) } catch (e) {} }
    splitAll(); ribbon(l)
  }

  // Headlines in words, each one coming in after the one before (the highlighted run keeps its color).
  function split(el) {
    var walk = function (parent, cls) {
      Array.prototype.slice.call(parent.childNodes).forEach(function (ch) {
        if (ch.nodeType === 3) {
          var frag = document.createDocumentFragment()
          ch.textContent.split(/(\s+)/).forEach(function (w) {
            if (!w) return
            if (/^\s+$/.test(w)) { frag.appendChild(document.createTextNode(w)); return }
            var sp = document.createElement('span'); sp.className = 'w' + (cls ? ' ' + cls : ''); sp.textContent = w; frag.appendChild(sp)
          })
          ch.parentNode.replaceChild(frag, ch)
        } else if (ch.nodeName === 'SPAN' && !ch.classList.contains('w')) {
          walk(ch, ch.className)
          while (ch.firstChild) parent.insertBefore(ch.firstChild, ch)
          parent.removeChild(ch)
        }
      })
    }
    walk(el, '')
    $$('.w', el).forEach(function (w, i) { w.style.setProperty('--i', i) })
  }
  function splitAll() { $$('[data-split]').forEach(split) }

  // The ribbon: what's inside, twice over so it runs on without a seam.
  var DOTS = ['var(--coral)', 'var(--amber)', 'var(--cyan)', 'var(--violet)']
  function ribbon(l) {
    $$('.ribbon .track').forEach(function (t) {
      var items = RIBBON[l][+t.dataset.row]
      t.innerHTML = ''
      for (var k = 0; k < 2; k++) items.forEach(function (x, i) {
        var s = document.createElement('span'); s.textContent = x; s.dir = 'auto'; s.style.setProperty('--c', DOTS[(i + +t.dataset.row) % 4]); t.appendChild(s)
      })
    })
  }
  if (d.dataset.lang === 'en') setLang('en'); else { splitAll(); ribbon('he') }
  $('#lang').addEventListener('click', function () { setLang(d.lang === 'he' ? 'en' : 'he', true) })

  // The logo that suits the theme.
  if (d.dataset.theme === 'light') $$('img[src$="logo-dark.png"]').forEach(function (i) { i.src = i.getAttribute('src').replace('logo-dark', 'logo-light') })

  // ---------------------------------------------------------------- reveals, the bar's line
  if ('IntersectionObserver' in window && !reduce) {
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); count(e.target) } })
    }, { rootMargin: '0px 0px 12% 0px', threshold: 0 })
    $$('.reveal').forEach(function (el) {
      var sib = el.parentElement ? $$(':scope > .reveal', el.parentElement) : []
      el.style.setProperty('--d', Math.min(4, sib.indexOf(el)) * 90 + 'ms')
      io.observe(el)
    })
  } else $$('.reveal').forEach(function (el) { el.classList.add('in') })

  // The numbers count up (and the zero counts down to nothing) as they come into view.
  function count(root) {
    $$('[data-count]', root).forEach(function (b) {
      if (b.dataset.done) return
      b.dataset.done = '1'
      var to = +b.dataset.count, from = b.dataset.from != null ? +b.dataset.from : 0, t0 = performance.now(), dur = 1500
      ;(function step(now) {
        var k = Math.min(1, (now - t0) / dur); k = 1 - Math.pow(1 - k, 4)
        b.textContent = Math.round(from + (to - from) * k)
        if (k < 1) requestAnimationFrame(step)
      })(t0)
    })
  }
  var nav = $('.nav'), line = $('.scrolled-line i'), heroVis = $('.hero-visual'), ticking = false
  var onScroll = function () {
    if (ticking) return
    ticking = true
    requestAnimationFrame(function () {
      ticking = false
      nav.classList.toggle('scrolled', scrollY > 8)
      var max = document.documentElement.scrollHeight - innerHeight
      line.style.setProperty('--p', max > 0 ? Math.min(1, scrollY / max) : 0)
      if (!reduce && scrollY < innerHeight * 1.2) heroVis.style.translate = '0 ' + (scrollY * 0.08).toFixed(1) + 'px'
    })
  }
  addEventListener('scroll', onScroll, { passive: true }); onScroll()

  // ---------------------------------------------------------------- the hero
  // The editor's recording plays while it's on screen; the app's screens take turns.
  var whenSeen = function (el, on, off, th) {
    if (!('IntersectionObserver' in window)) { on(); return }
    new IntersectionObserver(function (es) { es[0].isIntersecting ? on() : off && off() }, { threshold: th || 0.25 }).observe(el)
  }
  var heroVideo = $('.hero-browser video')
  if (!reduce) whenSeen(heroVideo, function () { heroVideo.play().catch(function () {}) }, function () { heroVideo.pause() })
  var cyc = $$('.cycle img'), ci = 0
  if (!reduce && cyc.length > 1) setInterval(function () {
    if (document.hidden) return
    cyc[ci].classList.remove('on'); ci = (ci + 1) % cyc.length; cyc[ci].classList.add('on')
  }, 3000)
  // The hero leans toward the pointer.
  var hero = $('.hero')
  if (!reduce && matchMedia('(hover: hover) and (pointer: fine)').matches) {
    hero.addEventListener('pointermove', function (e) {
      var r = hero.getBoundingClientRect()
      hero.style.setProperty('--px', ((e.clientX - r.left) / r.width * 2 - 1).toFixed(3))
      hero.style.setProperty('--py', ((e.clientY - r.top) / r.height * 2 - 1).toFixed(3))
    })
    hero.addEventListener('pointerleave', function () { hero.style.setProperty('--px', 0); hero.style.setProperty('--py', 0) })
  }

  // A light that follows the pointer over the cards.
  $$('.card, .plat, .steps li, .stats div').forEach(function (c) {
    c.addEventListener('pointermove', function (e) {
      var r = c.getBoundingClientRect()
      c.style.setProperty('--mx', e.clientX - r.left + 'px'); c.style.setProperty('--my', e.clientY - r.top + 'px')
    })
  })

  // ---------------------------------------------------------------- before / after
  // One video holds both, the original over ONIT's export, frame for frame; the canvas shows the
  // original up to the handle and the blurred one after it. The pictures stay as the fallback.
  var cmp = $('#compare'), range = $('#compare input'), cv = $('.cmp-video'), canvas = $('.cmp-canvas'), ctx = canvas.getContext('2d')
  var pos = 50, swept = false
  var draw = function () {
    if (cv.readyState < 2) return
    var W = canvas.width, H = canvas.height, x = Math.round(W * pos / 100)
    ctx.drawImage(cv, 0, 0, W, H, 0, 0, W, H)
    ctx.drawImage(cv, x, H, W - x, H, x, 0, W - x, H)
    cmp.classList.add('live')
  }
  var setPos = function (v) { pos = +v; cmp.style.setProperty('--pos', v + '%'); draw() }
  range.addEventListener('input', function () { swept = true; cmp.classList.add('touched'); setPos(range.value) })
  var loop = function () {
    draw()
    if (cv.paused) return
    if (cv.requestVideoFrameCallback) cv.requestVideoFrameCallback(loop); else requestAnimationFrame(loop)
  }
  cv.addEventListener('playing', loop)
  cv.addEventListener('loadeddata', draw)
  cv.addEventListener('seeked', draw)
  if (!reduce) whenSeen(cmp, function () { cv.preload = 'auto'; cv.play().catch(function () {}) }, function () { cv.pause() }, 0.2)
  if (!reduce && 'IntersectionObserver' in window) {
    var cio = new IntersectionObserver(function (es) {
      if (!es[0].isIntersecting || swept) return
      cio.disconnect()
      // A sweep, once, to show it moves: to the original, to ONIT, back to the middle.
      var t0 = performance.now(), keys = [[0, 50], [700, 22], [1600, 80], [2300, 50]]
      ;(function step(now) {
        if (swept) return
        var t = now - t0, i = 1
        while (i < keys.length - 1 && t > keys[i][0]) i++
        var a = keys[i - 1], b = keys[i], k = Math.min(1, Math.max(0, (t - a[0]) / (b[0] - a[0])))
        k = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2
        var v = a[1] + (b[1] - a[1]) * k
        setPos(v); range.value = v
        if (t < keys[keys.length - 1][0]) requestAnimationFrame(step)
      })(t0)
    }, { threshold: 0.6 })
    cio.observe(cmp)
  }

  // ---------------------------------------------------------------- the tour of the tools
  // A recording for each tool; each plays through, then the next tool's (until one is picked).
  var tabs = $$('.tour-list [role=tab]'), vids = $$('.shots video'), bar = $('.tour .progress i')
  var cur = 0, auto = true, visible = false
  var load = function (v) { var src = v.querySelector('source'); if (!src.src) { src.src = src.dataset.src; v.load() } }
  function show(i, user) {
    cur = i
    tabs.forEach(function (t, j) { t.setAttribute('aria-selected', String(j === i)); t.tabIndex = j === i ? 0 : -1 })
    var name = tabs[i].dataset.img
    vids.forEach(function (v) {
      var on = v.dataset.name === name
      v.classList.toggle('on', on)
      if (!on) v.pause()
    })
    if (user) auto = false
    play()
  }
  function play() {
    var v = vids.filter(function (x) { return x.classList.contains('on') })[0]
    if (!v || reduce || !visible) { if (v) v.pause(); return }
    load(v)
    try { v.currentTime = 0 } catch (e) {}
    v.loop = !auto
    v.play().catch(function () {})
  }
  vids.forEach(function (v) {
    v.addEventListener('ended', function () { if (auto && v.classList.contains('on')) show((cur + 1) % tabs.length) })
    v.addEventListener('timeupdate', function () { if (v.classList.contains('on') && v.duration) bar.style.width = (v.currentTime / v.duration * 100) + '%' })
  })
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { show(i, true) })
    t.addEventListener('keydown', function (e) {
      var rtl = d.dir === 'rtl', next = { ArrowDown: 1, ArrowUp: -1, ArrowLeft: rtl ? 1 : -1, ArrowRight: rtl ? -1 : 1 }[e.key]
      if (next) { e.preventDefault(); var j = (cur + next + tabs.length) % tabs.length; show(j, true); tabs[j].focus() }
    })
  })
  // The next recording is fetched ahead, so the change is instant.
  vids.forEach(function (v, i) { v.addEventListener('playing', function () { var n = vids[(i + 1) % vids.length]; if (n) { var s = n.querySelector('source'); if (!s.src) { s.src = s.dataset.src; n.preload = 'auto'; n.load() } } }) })
  whenSeen($('.tour'), function () { visible = true; play() }, function () { visible = false; vids.forEach(function (v) { v.pause() }) }, 0.3)

  // ---------------------------------------------------------------- the trailers
  var video = $('#trailer'), trTabs = $$('.seg [role=tab]')
  trTabs.forEach(function (t) {
    t.addEventListener('click', function () {
      if (t.getAttribute('aria-selected') === 'true') return
      trTabs.forEach(function (o) { o.setAttribute('aria-selected', String(o === t)) })
      var n = t.dataset.tr, playing = !video.paused
      video.pause()
      video.poster = 'media/trailer-' + n + '.webp'
      video.querySelector('source').src = 'media/trailer-' + n + '.mp4'
      video.load()
      if (playing) video.play().catch(function () {})
    })
  })
})()
