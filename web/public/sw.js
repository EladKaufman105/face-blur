// ONIT's service worker does two things. It receives a video shared to the installed app (the Web
// Share Target, as sharing from the gallery to the Android app) and hands it to the page. And it
// makes the page cross-origin isolated: GitHub Pages can't send the COOP / COEP headers, so this
// adds them to the page and to the workers' scripts (see lib/isolation.ts). Isolation is what lets
// WebAssembly use several threads (speech recognition is about as many times faster as the phone has
// big cores). Nothing is cached and nothing leaves the device; every other request goes to the
// network as usual.
//
// The beta web app (beta/ under the release) registers its own copy as sw.js?channel=beta, scoped
// to beta/. The two share Cache Storage (one origin), so each uses its own cache name, and the
// release's worker leaves everything under beta/ alone.
const BETA = new URL(self.location.href).searchParams.get('channel') === 'beta'
const SHARE = BETA ? 'onit-beta-share' : 'onit-share'
// The page says which COEP its browser knows (?coep=). Only "credentialless" (Chromium) isolates:
// it lets the cross-origin downloads (the speech models) work without the host's cooperation.
// Every other value, or none, is "off": the worker then only serves the share target and touches no
// other request (as before isolation existed; WebKit and Firefox are left alone on purpose).
const COEP = new URL(self.location.href).searchParams.get('coep') === 'credentialless' ? 'credentialless' : 'off'
const OTHER = BETA ? null : new URL('beta/', self.registration.scope).href

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('fetch', (e) => {
  if (OTHER && e.request.url.startsWith(OTHER)) return
  const url = new URL(e.request.url)
  if (e.request.method !== 'POST' || !url.pathname.endsWith('/share')) return isolate(e)
  e.respondWith((async () => {
    const back = new URL('./', self.registration.scope)
    try {
      const form = await e.request.formData()
      const file = [...form.getAll('video'), ...form.getAll('file')].find((f) => f && typeof f === 'object' && 'size' in f)
      if (file) {
        const cache = await caches.open(SHARE)
        await cache.put('shared-video', new Response(file, { headers: { 'content-type': file.type || 'video/mp4', 'x-name': encodeURIComponent(file.name || 'video.mp4') } }))
        back.searchParams.set('shared', '1')
      }
    } catch {}
    return Response.redirect(back.href, 303)
  })())
})

/** The page and the workers' scripts of this site get the isolation headers; nothing else needs them. */
function isolate(e) {
  const r = e.request
  if (COEP !== 'credentialless' || (r.cache === 'only-if-cached' && r.mode !== 'same-origin')) return
  if (!['document', 'worker', 'sharedworker'].includes(r.destination)) return
  if (new URL(r.url).origin !== self.location.origin) return
  e.respondWith(fetch(r).then((res) => {
    // Redirects and errors pass as they are (the redirect's target is fetched, and isolated, next).
    if (res.status === 0 || res.type === 'opaque' || res.type === 'opaqueredirect') return res
    const headers = new Headers(res.headers)
    headers.set('Cross-Origin-Opener-Policy', 'same-origin')
    headers.set('Cross-Origin-Embedder-Policy', COEP)
    headers.set('Cross-Origin-Resource-Policy', 'same-origin')
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers })
  }))
}
