// ONIT's landing page: Hebrew / English, reveals on scroll, the before / after slider, the tour of
// the tools and the trailers. No tracking, nothing sent anywhere.
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
    if (save) { try { localStorage.setItem('onit.lang', l) } catch (e) {} }
  }
  if (d.dataset.lang === 'en') setLang('en')
  $('#lang').addEventListener('click', function () { setLang(d.lang === 'he' ? 'en' : 'he', true) })

  // The logo that suits the theme.
  if (d.dataset.theme === 'light') $$('img[src$="logo-dark.png"]').forEach(function (i) { i.src = i.getAttribute('src').replace('logo-dark', 'logo-light') })

  // ---------------------------------------------------------------- reveals, the bar's line
  if ('IntersectionObserver' in window && !reduce) {
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target) } })
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 })
    $$('.reveal').forEach(function (el) {
      var sib = el.parentElement ? $$(':scope > .reveal', el.parentElement) : []
      el.style.setProperty('--d', Math.min(4, sib.indexOf(el)) * 90 + 'ms')
      io.observe(el)
    })
  } else $$('.reveal').forEach(function (el) { el.classList.add('in') })
  var nav = $('.nav')
  var onScroll = function () { nav.classList.toggle('scrolled', scrollY > 8) }
  addEventListener('scroll', onScroll, { passive: true }); onScroll()

  // ---------------------------------------------------------------- before / after
  var cmp = $('#compare'), range = $('#compare input')
  var setPos = function (v) { cmp.style.setProperty('--pos', v + '%') }
  range.addEventListener('input', function () { swept = true; setPos(range.value) })
  var swept = false
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
  var tabs = $$('.tour-list [role=tab]'), shots = $$('.shots img'), bar = $('.tour .progress')
  var cur = 0, timer = null, auto = !reduce, visible = false
  function show(i, user) {
    cur = i
    tabs.forEach(function (t, j) { t.setAttribute('aria-selected', String(j === i)); t.tabIndex = j === i ? 0 : -1 })
    var name = tabs[i].dataset.img
    shots.forEach(function (s) { s.classList.toggle('on', s.dataset.name === name) })
    if (user) { auto = false; bar.classList.remove('run'); clearTimeout(timer) }
    else restart()
  }
  function restart() {
    clearTimeout(timer); bar.classList.remove('run')
    if (!auto || !visible) return
    void bar.offsetWidth; bar.classList.add('run')
    timer = setTimeout(function () { show((cur + 1) % tabs.length) }, 6000)
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { show(i, true) })
    t.addEventListener('keydown', function (e) {
      var rtl = d.dir === 'rtl', next = { ArrowDown: 1, ArrowUp: -1, ArrowLeft: rtl ? 1 : -1, ArrowRight: rtl ? -1 : 1 }[e.key]
      if (next) { e.preventDefault(); var j = (cur + next + tabs.length) % tabs.length; show(j, true); tabs[j].focus() }
    })
  })
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(function (es) { visible = es[0].isIntersecting; restart() }, { threshold: 0.35 }).observe($('.tour'))
  }

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
