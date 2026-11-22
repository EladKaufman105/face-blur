import { useEffect, useRef, useState } from 'preact/hooks'
import { lang, setLang, t, duration } from './i18n'
import { nextTheme, theme } from './theme'
import type { Analysis } from './lib/types'
import { Editor, type EditorTab } from './ui/Editor'
import { Analyzing } from './ui/Analyzing'
import { Exporting, type ExportJob } from './ui/Exporting'
import { LockIcon, AddIcon, SubtitlesIcon, SoundIcon, MarkIcon } from './ui/icons'

type Screen =
  | { name: 'home'; error?: string }
  | { name: 'analyzing'; file: File }
  | { name: 'editor'; file: File; analysis: Analysis; thumbs: (string | null)[]; info: string; tab?: EditorTab }

declare const __BUILD__: string

export const RELEASES = 'https://github.com/EladKaufman105/face-blur/releases/latest'

export function App() {
  const [screen, setScreen] = useState<Screen>({ name: 'home' })
  // Exporting keeps the editor (and everything in it) underneath, for "back to editing".
  const [job, setJob] = useState<ExportJob | null>(null)
  const [sensitive, setSensitive] = useState(false)
  // Re-render on a change of language or theme.
  void lang.value
  void theme.value

  const supported = () => {
    if ('VideoDecoder' in window && 'VideoEncoder' in window) return true
    setScreen({ name: 'home', error: t('הדפדפן הזה לא תומך בעיבוד וידאו (WebCodecs). נסה Chrome, Edge או Safari מעודכנים.') })
    return false
  }
  const pick = (file: File) => {
    if (supported()) setScreen({ name: 'analyzing', file })
  }
  // A tool from the home screen: straight to the editor (faces can be found from there).
  const pickTool = async (file: File, tab: EditorTab) => {
    if (!supported()) return
    const size = await videoSize(file)
    if (!size) {
      setScreen({ name: 'home', error: t('לא ניתן לפתוח את הסרטון') })
      return
    }
    setScreen({ name: 'editor', file, analysis: { times: new Float64Array(0), width: size[0], height: size[1], fps: 30, tracks: [] }, thumbs: [], info: '', tab })
  }

  return (
    <div class="app">
      <Header />
      {screen.name === 'home' && <Home onPick={pick} onTool={pickTool} sensitive={sensitive} setSensitive={setSensitive} error={screen.error} />}
      {screen.name === 'analyzing' && (
        <Analyzing
          file={screen.file}
          sensitive={sensitive}
          onCancel={() => setScreen({ name: 'home' })}
          onError={(error) => setScreen({ name: 'home', error })}
          onDone={(analysis, thumbs, ms, backend) => setScreen({ name: 'editor', file: screen.file, analysis, thumbs, info: t('זמן עיבוד: %s', duration(ms / 1000)) + ' · ' + t('הזיהוי רץ על %s', backend === 'webgpu' ? t('המעבד הגרפי') : t('המעבד')) })}
        />
      )}
      {screen.name === 'editor' && (
        <Editor
          key={screen.file.name + screen.file.size}
          file={screen.file}
          analysis={screen.analysis}
          thumbs={screen.thumbs}
          info={screen.info}
          initialTab={screen.tab}
          hidden={job !== null}
          onExport={setJob}
          onClose={() => setScreen({ name: 'home' })}
        />
      )}
      {job && (
        <Exporting job={job} onBack={() => setJob(null)} onNew={() => { setJob(null); setScreen({ name: 'home' }) }} />
      )}
      <footer class="footer">
        {t('גרסת ווב')} <bdi dir="ltr" class="num">2.0 ({__BUILD__})</bdi> · <a href={RELEASES} target="_blank" rel="noopener">{t('האפליקציה המלאה לאנדרואיד')}</a>
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
      <button class="chip-btn" onClick={nextTheme} aria-label={t('מצב תצוגה')} title={t('מצב תצוגה')}>
        {theme.value === 'dark' ? t('כהה') : theme.value === 'light' ? t('בהיר') : t('לפי המכשיר')}
      </button>
      <button class="chip-btn" onClick={() => setLang(lang.value === 'he' ? 'en' : 'he')} aria-label="Language">
        {lang.value === 'he' ? 'EN' : 'עב'}
      </button>
    </header>
  )
}

function Home({ onPick, onTool, sensitive, setSensitive, error }: { onPick: (f: File) => void; onTool: (f: File, tab: EditorTab) => void; sensitive: boolean; setSensitive: (v: boolean) => void; error?: string }) {
  const input = useRef<HTMLInputElement>(null)
  const toolInput = useRef<HTMLInputElement>(null)
  const toolTab = useRef<EditorTab>('subs')
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
        <section>
          <h3 class="section-title" style="margin:4px 0 4px">{t('כלים נוספים')}</h3>
          <p class="small muted" style="margin:0 0 10px">{t('פותח את העורך ישר, בלי זיהוי פנים')}</p>
          <div class="tools">
            {([['subs', 'כתוביות', SubtitlesIcon, 'var(--amber)'], ['sound', 'קול ואפקטים', SoundIcon, 'var(--cyan)'], ['mark', 'סימן מים', MarkIcon, 'var(--violet)']] as const).map(([tab, label, Icon, color]) => (
              <button class="tool" onClick={() => { toolTab.current = tab; toolInput.current?.click() }}>
                <span class="tool-icon" style={{ color, background: `color-mix(in srgb, ${color} 18%, transparent)` }}><Icon /></span>
                <span>{t(label)}</span>
              </button>
            ))}
          </div>
          <input
            ref={toolInput}
            type="file"
            accept="video/*"
            hidden
            onChange={(e) => {
              const f = (e.currentTarget as HTMLInputElement).files?.[0]
              if (f) onTool(f, toolTab.current)
              ;(e.currentTarget as HTMLInputElement).value = ''
            }}
          />
        </section>
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

/** The video's displayed size, from the browser's own player (no decoding needed). */
function videoSize(file: File): Promise<[number, number] | null> {
  return new Promise((resolve) => {
    const v = document.createElement('video')
    const url = URL.createObjectURL(file)
    const done = (r: [number, number] | null) => { URL.revokeObjectURL(url); resolve(r) }
    v.preload = 'metadata'
    v.muted = true
    v.onloadedmetadata = () => done(v.videoWidth > 0 ? [v.videoWidth, v.videoHeight] : null)
    v.onerror = () => done(null)
    v.src = url
  })
}
