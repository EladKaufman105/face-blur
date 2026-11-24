import { render } from 'preact'
import { App } from './app'
import { lang, setLang } from './i18n'
import { applyPalette, applyTheme, palette, theme } from './theme'
import './styles.css'

// The app's font (Allo Hebrew, with weight and roundness axes), from the same file as Android.
const font = new FontFace('Allo Hebrew', `url(${new URL('fonts/allo_hebrew.ttf', document.baseURI)})`, { weight: '100 900', display: 'swap' })
font.load().then((f) => document.fonts.add(f)).catch(() => {})

import { startServiceWorker } from './lib/isolation'

// The service worker (public/sw.js) receives videos shared to the installed app and makes the page
// cross-origin isolated (WebAssembly threads); see lib/isolation.ts. The first visit reloads once,
// before anything is drawn. The beta's own worker (under beta/, scoped to it) keeps what it
// receives apart from the release's.
startServiceWorker().catch(() => false).then((reloading) => {
  if (reloading) return
  setLang(lang.value)
  applyTheme(theme.value)
  applyPalette(palette.value)
  render(<App />, document.getElementById('app')!)
})
