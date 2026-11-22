import { render } from 'preact'
import { App } from './app'
import { lang, setLang } from './i18n'
import './styles.css'

// The app's font (Allo Hebrew, with weight and roundness axes), from the same file as Android.
const font = new FontFace('Allo Hebrew', `url(${new URL('fonts/allo_hebrew.ttf', document.baseURI)})`, { weight: '100 900', display: 'swap' })
font.load().then((f) => document.fonts.add(f)).catch(() => {})

setLang(lang.value)
render(<App />, document.getElementById('app')!)
