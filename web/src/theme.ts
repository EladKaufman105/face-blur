import { signal } from '@preact/signals'
import { storageKey } from './lib/channel'

// Dark first, like a video editor (and like the app); light, or the system's choice, on request.
export type Theme = 'dark' | 'light' | 'system'
/** The accent colors, as the app's palettes (the wallpaper's colors have no web equivalent). */
export type Palette = 'onit' | 'blue' | 'purple' | 'green' | 'orange' | 'rose'

/** Each palette's swatch (its light-mode primary), as the app's palette picker. */
export const PALETTES: [Palette, string, string][] = [
  ['onit', 'ONIT', 'conic-gradient(#ff4d6d, #ffc53d, #3ddcff, #8b6cff, #ff4d6d)'],
  ['blue', 'כחול', '#3355e0'],
  ['purple', 'סגול', '#7246d8'],
  ['green', 'ירוק', '#1f7a4d'],
  ['orange', 'כתום', '#c2410c'],
  ['rose', 'ורוד', '#c2185b'],
]

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const saved = localStorage.getItem(key)
    if (saved && (allowed as readonly string[]).includes(saved)) return saved as T
  } catch {}
  return fallback
}

export const theme = signal<Theme>(read(storageKey('theme'), ['dark', 'light', 'system'] as const, 'dark'))
export const palette = signal<Palette>(read(storageKey('palette'), PALETTES.map(([p]) => p), 'onit'))

export function applyTheme(v: Theme) {
  document.documentElement.dataset.theme = v
  const dark = v === 'dark' || (v === 'system' && !matchMedia('(prefers-color-scheme: light)').matches)
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', dark ? '#0B0D1C' : '#F7F6FB')
}

export function applyPalette(p: Palette) {
  if (p === 'onit') delete document.documentElement.dataset.palette
  else document.documentElement.dataset.palette = p
}

export function setTheme(v: Theme) {
  theme.value = v
  try { localStorage.setItem(storageKey('theme'), v) } catch {}
  applyTheme(v)
}

export function setPalette(p: Palette) {
  palette.value = p
  try { localStorage.setItem(storageKey('palette'), p) } catch {}
  applyPalette(p)
}
