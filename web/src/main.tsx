import { render } from 'preact'
import { App } from './app'
import { lang, setLang } from './i18n'
import { applyPalette, applyTheme, palette, theme } from './theme'
import './styles.css'
import { BETA } from './lib/channel'

// The app's font (Allo Hebrew, with weight and roundness axes), from the same file as Android.
const font = new FontFace('Allo Hebrew', `url(${new URL('fonts/allo_hebrew.ttf', document.baseURI)})`, { weight: '100 900', display: 'swap' })
font.load().then((f) => document.fonts.add(f)).catch(() => {})

setLang(lang.value)
applyTheme(theme.value)
applyPalette(palette.value)
render(<App />, document.getElementById('app')!)

// Only for videos shared to the installed app (see public/sw.js): nothing is cached. The beta's
// own worker (under beta/, scoped to it) keeps what it receives apart from the release's.
if ('serviceWorker' in navigator) navigator.serviceWorker.register(BETA ? 'sw.js?channel=beta' : 'sw.js').catch(() => {})
