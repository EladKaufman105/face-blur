// ONIT's service worker does one thing: it receives a video shared to the installed app (the Web
// Share Target, as sharing from the gallery to the Android app) and hands it to the page. Nothing
// is cached and nothing leaves the device; every other request goes to the network as usual.
const SHARE = 'onit-share'

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('fetch', (e) => {
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
