// The page's service worker (public/sw.js) and cross-origin isolation. GitHub Pages can't send the
// COOP / COEP headers that WebAssembly threads need (SharedArrayBuffer), so the service worker
// adds them. The first visit has no worker yet: the page registers it, waits until it controls
// the page and reloads ONCE (never twice: if the page is still not isolated after that, the
// browser can't do it and the app goes on with one thread). From the second visit on the headers
// are there from the first byte.
import { BETA } from './channel'

const GUARD = 'onit.isolation.reload'

/** ?coi=0 keeps the old behavior (the worker only serves the share target; no isolation, no reload): for the tests of everything else. */
const wanted = () => new URLSearchParams(location.search).get('coi') !== '0'

/** The COEP the browser knows: "credentialless" (Chrome, Firefox: cross-origin downloads need nothing from the host), else "require-corp". */
function coep(): string {
  if (!wanted()) return 'off'
  return 'credentialless' in window || (typeof HTMLIFrameElement !== 'undefined' && 'credentialless' in HTMLIFrameElement.prototype) ? 'credentialless' : 'require-corp'
}

const later = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * Registers the service worker. Resolves true when the page is being reloaded (draw nothing then).
 * When the page is already isolated (every visit after the first) it resolves at once.
 */
export async function startServiceWorker(): Promise<boolean> {
  if (!('serviceWorker' in navigator)) return false
  const registering = navigator.serviceWorker.register(`sw.js?${BETA ? 'channel=beta&' : ''}coep=${coep()}`)
  if (window.crossOriginIsolated) {
    registering.catch(() => {})
    try { sessionStorage.removeItem(GUARD) } catch {}
    return false
  }
  if (!wanted() || !window.isSecureContext) {
    registering.catch(() => {})
    return false
  }
  try {
    const reg = await registering
    // An older worker in charge (a visit before this one) must be replaced first: ask now, don't wait for the browser's own check.
    if (!reg.installing && !reg.waiting) await Promise.race([reg.update().catch(() => {}), later(4000)])
    // A new worker (the first visit, or a newer version of the worker) is on its way: wait until it
    // is active and has taken over this page; only that page is isolated.
    const sw = reg.installing || reg.waiting
    if (sw && sw.state !== 'activated') {
      await Promise.race([
        new Promise<void>((r) => sw.addEventListener('statechange', () => { if (sw.state === 'activated') r() })),
        later(6000),
      ])
    }
    if (reg.active && navigator.serviceWorker.controller === null) {
      // Active but not in charge of this page yet (claiming takes a moment), or a forced reload.
      await Promise.race([new Promise<void>((r) => navigator.serviceWorker.addEventListener('controllerchange', () => r(), { once: true })), later(1500)])
    } else if (sw) {
      await later(150)
    }
    if (!reg.active) return false
    try {
      // sessionStorage can be unavailable (then no reload: better one thread than a loop).
      if (sessionStorage.getItem(GUARD)) return false
      sessionStorage.setItem(GUARD, String(Date.now()))
    } catch { return false }
    location.reload()
    return true
  } catch {
    return false
  }
}
