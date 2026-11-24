// ONIT's service worker does one thing: it receives a video shared to the installed app (the Web
// Share Target, as sharing from the gallery to the Android app) and hands it to the page. Nothing
// is cached and nothing leaves the device; every other request goes to the network as usual.
//
// The beta web app (beta/ under the release) registers its own copy as sw.js?channel=beta, scoped
// to beta/. The two share Cache Storage (one origin), so each uses its own cache name, and the
// release's worker leaves everything under beta/ alone.
const BETA = new URL(self.location.href).searchParams.get('channel') === 'beta'
const SHARE = BETA ? 'onit-beta-share' : 'onit-share'
const OTHER = BETA ? null : new URL('beta/', self.registration.scope).href

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('fetch', (e) => {
  if (OTHER && e.request.url.startsWith(OTHER)) return
  const url = new URL(e.request.url)
  if (e.request.method !== 'POST' || !url.pathname.endsWith('/share')) return
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
