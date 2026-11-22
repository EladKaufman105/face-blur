import { useEffect, useRef, useState } from 'preact/hooks'
import { lang, setLang, t, duration } from './i18n'
import type { Analysis } from './lib/types'
import { Editor } from './ui/Editor'
import { Analyzing } from './ui/Analyzing'
import { Exporting, type ExportJob } from './ui/Exporting'
import { LockIcon, AddIcon } from './ui/icons'

type Screen =
  | { name: 'home'; error?: string }
  | { name: 'analyzing'; file: File }
  | { name: 'editor'; file: File; analysis: Analysis; thumbs: (string | null)[]; ms: number; backend: string }
  | { name: 'export'; job: ExportJob; back: Extract<Screen, { name: 'editor' }> }

declare const __BUILD__: string

export const RELEASES = 'https://github.com/EladKaufman105/face-blur/releases/latest'

export function App() {
  const [screen, setScreen] = useState<Screen>({ name: 'home' })
  const [sensitive, setSensitive] = useState(false)
  // Re-render on language change.
  void lang.value

  const pick = (file: File) => {
    if (!('VideoDecoder' in window) || !('VideoEncoder' in window)) {
      setScreen({ name: 'home', error: t('הדפדפן הזה לא תומך בעיבוד וידאו (WebCodecs). נסה Chrome, Edge או Safari מעודכנים.') })
      return
    }
    setScreen({ name: 'analyzing', file })
  }

  return (
    <div class="app">
      <Header />
      {screen.name === 'home' && <Home onPick={pick} sensitive={sensitive} setSensitive={setSensitive} error={screen.error} />}
      {screen.name === 'analyzing' && (
        <Analyzing
          file={screen.file}
          sensitive={sensitive}
          onCancel={() => setScreen({ name: 'home' })}
          onError={(error) => setScreen({ name: 'home', error })}
          onDone={(analysis, thumbs, ms, backend) => setScreen({ name: 'editor', file: screen.file, analysis, thumbs, ms, backend })}
        />
      )}
      {screen.name === 'editor' && (
        <Editor
          key={screen.file.name + screen.file.size}
          file={screen.file}
          analysis={screen.analysis}
          thumbs={screen.thumbs}
          info={t('זמן עיבוד: %s', duration(screen.ms / 1000)) + ' · ' + t('הזיהוי רץ על %s', screen.backend === 'webgpu' ? t('המעבד הגרפי') : t('המעבד'))}
          onExport={(job) => setScreen({ name: 'export', job, back: screen })}
          onClose={() => setScreen({ name: 'home' })}
        />
      )}
      {screen.name === 'export' && (
        <Exporting job={screen.job} onBack={() => setScreen(screen.back)} onNew={() => setScreen({ name: 'home' })} />
      )}
      <footer class="footer">
        {t('גרסת ווב')} {__BUILD__} · <a href={RELEASES} target="_blank" rel="noopener">{t('האפליקציה המלאה לאנדרואיד')}</a>
      </footer>
    </div>
  )
}

function Header() {
  return (
    <header class="top">
      <div class="brand">
        <img class="logo light" src="icons/logo-light.png" alt="" />
        <img class="logo dark" src="icons/logo-dark.png" alt="" />
        <div class="grow">
          <h1>ONIT</h1>
          <p class="tag"><LockIcon size={14} /> {t('הכל מעובד על המכשיר. שום דבר לא עולה לרשת.')}</p>
        </div>
      </div>
      <button class="chip-btn" onClick={() => setLang(lang.value === 'he' ? 'en' : 'he')} aria-label="Language">
        {lang.value === 'he' ? 'EN' : 'עב'}
      </button>
    </header>
  )
}

function Home({ onPick, sensitive, setSensitive, error }: { onPick: (f: File) => void; sensitive: boolean; setSensitive: (v: boolean) => void; error?: string }) {
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  useEffect(() => {
    // A video dropped anywhere on the page.
    const over = (e: DragEvent) => { e.preventDefault(); setDragging(true) }
    const leave = () => setDragging(false)
    const drop = (e: DragEvent) => {
      e.preventDefault()
      setDragging(false)
      const f = e.dataTransfer?.files?.[0]
      if (f) onPick(f)
    }
    window.addEventListener('dragover', over)
    window.addEventListener('dragleave', leave)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragover', over)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('drop', drop)
    }
  }, [onPick])
  return (
    <main class="home-grid">
      <section class={'hero' + (dragging ? ' dragging' : '')}>
        <div class="strips" aria-hidden="true">
          <div style="width:72%;background:#ff4d6d" />
          <div style="width:48%;background:#ff7a59;margin-inline-start:14%" />
          <div style="width:60%;background:#b3365f;opacity:.8" />
        </div>
        <h2>{t('טשטוש פנים בסרטון')}</h2>
        <p>{t('הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה.')}</p>
        <button class="btn primary" onClick={() => input.current?.click()}>
          <AddIcon /> {t('בחר סרטון')}
        </button>
        <div class="drop-hint">{t('או גרור סרטון לכאן')}</div>
        <input
          ref={input}
          type="file"
          accept="video/*"
          hidden
          onChange={(e) => {
            const f = (e.currentTarget as HTMLInputElement).files?.[0]
            if (f) onPick(f)
            ;(e.currentTarget as HTMLInputElement).value = ''
          }}
        />
      </section>
      <div class="side">
        {error && (
          <div class="card error">
            <strong>{t('משהו השתבש')}</strong>
            <p class="small" style="margin:6px 0 0">{error}</p>
          </div>
        )}
        <label class="card row" style="cursor:pointer">
          <div class="grow">
            <strong>{t('זיהוי מורחב')}</strong>
            <div class="small muted" style="margin-top:4px">{t('מוצא יותר פנים קטנות ורחוקות, עם יותר טשטושים מיותרים.')}</div>
          </div>
          <span class="switch">
            <input type="checkbox" checked={sensitive} onChange={(e) => setSensitive((e.currentTarget as HTMLInputElement).checked)} />
            <span />
          </span>
        </label>
      </div>
    </main>
  )
}
