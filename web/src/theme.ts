import { signal } from '@preact/signals'

// Dark first, like a video editor (and like the app); light, or the system's choice, on request.
export type Theme = 'dark' | 'light' | 'system'

function initial(): Theme {
  try {
    const saved = localStorage.getItem('onit.theme')
    if (saved === 'light' || saved === 'system') return saved
  } catch {}
  return 'dark'
}

export const theme = signal<Theme>(initial())

export function applyTheme(v: Theme) {
  document.documentElement.dataset.theme = v
  const dark = v === 'dark' || (v === 'system' && !matchMedia('(prefers-color-scheme: light)').matches)
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', dark ? '#0B0D1C' : '#F7F6FB')
}

/** Dark → light → the system's → dark. */
export function nextTheme() {
  const v: Theme = theme.value === 'dark' ? 'light' : theme.value === 'light' ? 'system' : 'dark'
  theme.value = v
  try { localStorage.setItem('onit.theme', v) } catch {}
  applyTheme(v)
}
