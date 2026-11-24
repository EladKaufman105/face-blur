import { useEffect } from 'preact/hooks'

/** The screen can be kept on (the Screen Wake Lock API). */
export const canKeepAwake = () => typeof navigator !== 'undefined' && 'wakeLock' in navigator

/**
 * Keeps the screen on while [on] (as the app does during long work): a phone that dims and locks
 * would stop the page. Asked again when the page comes back into view (the lock ends when hidden).
 */
export function useWakeLock(on: boolean) {
  useEffect(() => {
    if (!on || !canKeepAwake()) return
    let lock: { release: () => Promise<void> } | null = null
    let live = true
    const ask = () => {
      if (document.visibilityState !== 'visible') return
      ;(navigator as any).wakeLock.request('screen').then((l: any) => { if (live) lock = l; else l.release().catch(() => {}) }).catch(() => {})
    }
    ask()
    document.addEventListener('visibilitychange', ask)
    return () => {
      live = false
      document.removeEventListener('visibilitychange', ask)
      lock?.release().catch(() => {})
    }
  }, [on])
}

/**
 * Keeps the screen on until the returned function is called (for work that goes on outside any
 * one screen, as a batch does): asked again when the page comes back into view.
 */
export function keepAwake(): () => void {
  if (!canKeepAwake()) return () => {}
  let lock: { release: () => Promise<void> } | null = null
  let live = true
  const ask = () => {
    if (document.visibilityState !== 'visible') return
    ;(navigator as any).wakeLock.request('screen').then((l: any) => { if (live) lock = l; else l.release().catch(() => {}) }).catch(() => {})
  }
  ask()
  document.addEventListener('visibilitychange', ask)
  return () => {
    live = false
    document.removeEventListener('visibilitychange', ask)
    lock?.release().catch(() => {})
  }
}
