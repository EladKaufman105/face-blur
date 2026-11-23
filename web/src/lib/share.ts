// A video shared to the installed app (the service worker keeps it for the page, see public/sw.js).

/** The video shared, once (it's removed as it's taken), or null. */
export async function takeShared(): Promise<File | null> {
  try {
    const cache = await caches.open('onit-share')
    const r = await cache.match('shared-video')
    if (!r) return null
    await cache.delete('shared-video')
    const name = decodeURIComponent(r.headers.get('x-name') ?? 'video.mp4')
    return new File([await r.blob()], name, { type: r.headers.get('content-type') ?? 'video/mp4' })
  } catch {
    return null
  }
}

/** Running as the installed app (from the home screen). */
export const installed = () => matchMedia('(display-mode: standalone)').matches || (navigator as any).standalone === true
