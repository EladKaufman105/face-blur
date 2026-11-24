import { useEffect, useRef, useState } from 'preact/hooks'
import { lang, setLang, t, duration } from './i18n'
import { palette, PALETTES, setPalette, setTheme, theme, type Theme } from './theme'
import type { Analysis, Look } from './lib/types'
import { Editor, type EditorTab } from './ui/Editor'
import { Analyzing } from './ui/Analyzing'
import { Exporting, type ExportJob } from './ui/Exporting'
import { LockIcon, AddIcon, MergeIcon, SubtitlesIcon, SoundIcon, MarkIcon, MusicIcon, SettingsIcon, CloseIcon, CheckIcon } from './ui/icons'
import { DEFAULT_LOOK } from './lib/types'
import { NO_WATERMARK } from './lib/watermark'
import { DEFAULT_SUB_STYLE } from './lib/subtitles'
import { DEFAULT_EXPORT, type ExportOptions } from './lib/exportopts'
import { ASPECT_RATIO, cropFraction, reframePath } from './lib/camera'
import { loadWatermark } from './ui/EditorTabs'
import { NoFaces, Ready } from './ui/Ready'
import { loadProject, type ProjectPicture, type ProjectState } from './lib/projects'
import { ProjectsSection } from './ui/Projects'
import { installed, takeShared } from './lib/share'
import { BETA } from './lib/channel'
import { BetaLogo, BetaName, BetaNote } from './ui/Beta'
import { BatchQueue, BatchSetup } from './ui/Batch'
import { TemplatesManager } from './ui/TemplateEditor'
import { type TemplateSettings } from './lib/template'
import { loadTemplates } from './lib/templatestore'
import { current as batchNow, running as batchRunning, startBatch, version as batchVersion, doneCount } from './lib/batch'
import { videoSize as sizeOf } from './lib/templaterun'
import './ui/templates-en'
import { SpeechAsk } from './ui/SpeechUi'

/** A tool from the home screen: a group of the editor, or straight to an MP3. */
type Tool = EditorTab | 'mp3'

type Screen =
  | { name: 'home'; error?: string }
  | { name: 'ready'; file: File }
  | { name: 'analyzing'; file: File; save?: { look: Look; options: ExportOptions }; template?: TemplateSettings | null }
  | { name: 'noFaces'; file: File; analysis: Analysis; save?: { look: Look; options: ExportOptions }; template?: TemplateSettings | null }
  | { name: 'editor'; file: File; analysis: Analysis; thumbs: (string | null)[]; info: string; tab?: EditorTab; project?: { id: string; state: ProjectState | null; picture: ProjectPicture | null }; template?: TemplateSettings | null }
  | { name: 'batchSetup' }
  | { name: 'batch' }

declare const __BUILD__: string

export const RELEASES = 'https://github.com/EladKaufman105/face-blur/releases/latest'

export function App() {
  const [screen, setScreen] = useState<Screen>({ name: 'home' })
  // Exporting keeps the editor (and everything in it) underneath, for "back to editing".
  const [job, setJob] = useState<ExportJob | null>(null)
  const [sensitive, setSensitive] = useState(false)
  // What was found in a video saved straight away (to open the editor on it afterwards).
  const saved = useRef<{ file: File; analysis: Analysis } | null>(null)
  // Re-render on a change of language, theme or palette.
  useEffect(() => { loadTemplates() }, [])
  // A new screen starts at its top (the home screen is long on a phone: a tap on a tool low down must not open the editor scrolled).
  useEffect(() => { window.scrollTo(0, 0) }, [screen.name])
  void batchVersion.value
  void lang.value
  void theme.value
  void palette.value

  const supported = () => {
    // A batch is working in the workers: another video waits for it (as in the app).
    if (batchRunning()) {
      setScreen({ name: 'home', error: t('אצווה רצה כרגע. אפשר להמשיך כשהיא תסתיים.') })
      return false
    }
    if ('VideoDecoder' in window && 'VideoEncoder' in window) return true
    setScreen({ name: 'home', error: t('הדפדפן הזה לא תומך בעיבוד וידאו (WebCodecs). נסה Chrome, Edge או Safari מעודכנים.') })
    return false
  }
  // A project opened again: the video and what was done to it, from the browser's storage.
  const openProject = async (id: string) => {
    if (!supported()) return
    const p = await loadProject(id).catch(() => null)
    if (!p) { setScreen({ name: 'home', error: t('לא ניתן לפתוח את הפרויקט') }); return }
    const analysis = p.state?.analysis ?? { times: new Float64Array(0), width: 0, height: 0, fps: 30, tracks: [] }
    if (!analysis.width) {
      const size = await videoSize(p.file)
      if (!size) { setScreen({ name: 'home', error: t('לא ניתן לפתוח את הפרויקט') }); return }
      analysis.width = size[0]
      analysis.height = size[1]
    }
    setScreen({ name: 'editor', file: p.file, analysis, thumbs: [], info: p.state?.detectInfo ?? '', project: { id, state: p.state, picture: p.picture } })
  }
  const pick = (file: File) => {
    if (supported()) setScreen({ name: 'ready', file })
  }
  // A video shared to the installed app (from the gallery), or opened with it (a file handler).
  useEffect(() => {
    if (new URLSearchParams(location.search).has('shared')) {
      history.replaceState(null, '', location.pathname)
      takeShared().then((f) => { if (f) pick(f) })
    }
    ;(window as any).launchQueue?.setConsumer?.(async (p: { files?: FileSystemFileHandle[] }) => {
      const h = p.files?.[0]
      if (h) pick(await h.getFile())
    })
  }, [])
  // A tool from the home screen: straight to the editor (faces can be found from there).
  const pickTool = async (file: File, tab: Tool) => {
    if (!supported()) return
    if (tab === 'mp3') {
      setJob({ file, times: new Float64Array(0), tracks: [], look: DEFAULT_LOOK, width: 0, height: 0, sfx: [], watermark: NO_WATERMARK, cues: [], subStyle: DEFAULT_SUB_STYLE, voice: null, anonymize: false, cuts: [], options: { ...DEFAULT_EXPORT, format: 'mp3' }, standalone: true })
      return
    }
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
      <SpeechAsk />
      {screen.name === 'home' && !job && <Home onPick={pick} onTool={pickTool} onProject={openProject} onBatch={() => { if (supported()) setScreen({ name: 'batchSetup' }) }} onQueue={() => setScreen({ name: 'batch' })} error={screen.error} />}
      {screen.name === 'batchSetup' && !job && <BatchSetup onBack={() => setScreen({ name: 'home' })} onStarted={() => setScreen({ name: 'batch' })} />}
      {screen.name === 'batch' && !job && <BatchQueue onHome={() => setScreen({ name: 'home' })} onCheck={(id) => { void openProject(id) }} />}
      {screen.name === 'ready' && !job && (
        <Ready
          key={screen.file.name + screen.file.size + screen.file.lastModified}
          file={screen.file}
          sensitive={sensitive}
          onSensitive={setSensitive}
          onBlur={async (_look, template) => {
            // With no face blur in the template: the editor opens straight away (as a tool does), and the template applies there.
            if (template?.blurOff) {
              const size = await sizeOf(screen.file)
              if (!size) { setScreen({ name: 'home', error: t('לא ניתן לפתוח את הסרטון') }); return }
              setScreen({ name: 'editor', file: screen.file, analysis: { times: new Float64Array(0), width: size[0], height: size[1], fps: 30, tracks: [] }, thumbs: [], info: '', template })
              return
            }
            setScreen({ name: 'analyzing', file: screen.file, template })
          }}
          onBlurAndSave={async (look, options, template, templateName) => {
            // Any template (its processing, watermark and export settings): processed as a batch of one, with no editor on the way.
            // An audio export (MP3) has no video to apply them to: it goes the plain way, unless the
            // template changes the sound (voice, cleanup, loudness, cuts): then the editor opens with
            // it applied, so the sound is never saved without them (a voice change is for anonymity).
            if (template && options.format === 'mp3' && (template.voice !== 'none' || template.denoise > 0 || template.enhance || template.loud || template.cutSilences || template.cutFillers)) {
              // With no face blur the editor opens straight away (as onBlur does), with no face detection.
              if (template.blurOff) {
                const size = await sizeOf(screen.file)
                if (!size) { setScreen({ name: 'home', error: t('לא ניתן לפתוח את הסרטון') }); return }
                setScreen({ name: 'editor', file: screen.file, analysis: { times: new Float64Array(0), width: size[0], height: size[1], fps: 30, tracks: [] }, thumbs: [], info: '', template })
                return
              }
              setScreen({ name: 'analyzing', file: screen.file, template })
              return
            }
            if (template && options.format !== 'mp3') {
              startBatch([screen.file], template, templateName, { folder: false, autoDownload: true })
              setScreen({ name: 'batch' })
              return
            }
            setScreen({ name: 'analyzing', file: screen.file, save: { look, options }, template: template ?? undefined })
          }}
          onReplace={pick}
          onBack={() => setScreen({ name: 'home' })}
        />
      )}
      {screen.name === 'analyzing' && !job && (
        <Analyzing
          file={screen.file}
          sensitive={sensitive}
          twoSteps={!!screen.save}
          onCancel={() => setScreen({ name: 'ready', file: screen.file })}
          onError={(error) => setScreen({ name: 'home', error })}
          onDone={(analysis, thumbs, ms, backend) => {
            const save = screen.save
            const template = screen.template
            // No faces found: say so, with what can be done (as the app).
            if (analysis.tracks.length === 0) {
              setScreen({ name: 'noFaces', file: screen.file, analysis, save, template })
              return
            }
            if (!save) {
              setScreen({ name: 'editor', file: screen.file, analysis, thumbs, template, info: t('זמן עיבוד: %s', duration(ms / 1000)) + ' · ' + t('הזיהוי רץ על %s', backend === 'webgpu' ? t('המעבד הגרפי') : t('המעבד')) })
              return
            }
            // Saved straight away, as found: every face blurred, the watermark last used, and for
            // a frame of another shape, a frame that follows the faces (as the editor would).
            const { width: w, height: h, times, tracks, fps } = analysis
            const [cw, ch] = cropFraction(w, h, ASPECT_RATIO[save.options.aspect])
            const path = save.options.aspect !== 'original' && times.length ? reframePath(times.length, tracks, analysis.cuts ?? [], fps, cw, ch) : null
            setScreen({ name: 'ready', file: screen.file })
            saved.current = { file: screen.file, analysis }
            setJob({ file: screen.file, times, tracks, look: save.look, width: w, height: h, sfx: [], watermark: template?.watermark ?? loadWatermark(), cues: [], subStyle: DEFAULT_SUB_STYLE, voice: null, anonymize: false, cuts: [], options: save.options, standalone: true, twoSteps: true, picture: { background: null, masks: null, steady: null, path } })
          }}
        />
      )}
      {screen.name === 'noFaces' && (
        <NoFaces
          file={screen.file}
          canEdit={!screen.save}
          sensitive={sensitive}
          onEdit={() => setScreen({ name: 'editor', file: screen.file, analysis: screen.analysis, thumbs: [], info: '', tab: 'faces', template: screen.template })}
          onRetry={() => { setSensitive(true); setScreen({ name: 'analyzing', file: screen.file, save: screen.save, template: screen.template }) }}
          onBack={() => setScreen({ name: 'ready', file: screen.file })}
        />
      )}
      {screen.name === 'editor' && (
        <Editor
          key={screen.project?.id ?? screen.file.name + screen.file.size + screen.file.lastModified}
          file={screen.file}
          analysis={screen.analysis}
          thumbs={screen.thumbs}
          info={screen.info}
          initialTab={screen.tab}
          hidden={job !== null}
          onExport={setJob}
          onClose={() => setScreen({ name: 'home' })}
          project={screen.project}
          template={screen.template}
        />
      )}
      {job && (
        <Exporting
          job={job}
          onBack={() => setJob(null)}
          onNew={() => { setJob(null); setScreen({ name: 'home' }) }}
          onEdit={job.twoSteps && saved.current ? () => { const s = saved.current!; setJob(null); setScreen({ name: 'editor', file: s.file, analysis: s.analysis, thumbs: [], info: '' }) } : undefined}
        />
      )}
      <footer class="footer">
        {t('גרסת ווב')} <bdi dir="ltr" class="num">2.0 ({__BUILD__})</bdi> · <a href={RELEASES} target="_blank" rel="noopener">{t('האפליקציה המלאה לאנדרואיד')}</a> · <a href="home/">{t('על ONIT')}</a>
      </footer>
    </div>
  )
}

function Header() {
  const [open, setOpen] = useState(false)
  return (
    <header class="top">
      <div class="brand">
        {BETA ? <BetaLogo /> : (
          <>
            <img class="logo light" src="icons/logo-light.png" alt="" />
            <img class="logo dark" src="icons/logo-dark.png" alt="" />
          </>
        )}
        <div class="grow">
          <h1>ONIT{BETA && <BetaName />}</h1>
          <p class="tag"><LockIcon size={14} /> {t('הכל מעובד על המכשיר. שום דבר לא עולה לרשת.')}</p>
          {BETA && <BetaNote />}
        </div>
      </div>
      <button class="icon-btn settings-btn" onClick={() => setOpen(true)} aria-label={t('הגדרות')} title={t('הגדרות')}><SettingsIcon /></button>
      {open && <Settings onClose={() => setOpen(false)} />}
    </header>
  )
}

/** Language, light / dark mode and the color palette, as the app's settings. */
function Settings({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [])
  const themes: [Theme, string][] = [['dark', t('כהה')], ['light', t('בהיר')], ['system', t('לפי המכשיר')]]
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet settings-sheet" role="dialog" aria-modal="true" aria-label={t('הגדרות')}>
        <div class="row">
          <h2 class="grow sheet-title">{t('הגדרות')}</h2>
          <button class="icon-btn" aria-label={t('סגור')} onClick={onClose}><CloseIcon /></button>
        </div>
        <div class="sheet-body">
          <h4 class="caption">{t('שפה')}</h4>
          <div class="seg seg2 lang-seg" role="radiogroup">
            {([['he', 'עברית'], ['en', 'English']] as const).map(([l, label]) => (
              <button class={lang.value === l ? 'on' : ''} role="radio" aria-checked={lang.value === l} lang={l} onClick={() => setLang(l)}>{label}</button>
            ))}
          </div>
          <h4 class="caption">{t('מצב תצוגה')}</h4>
          <div class="seg theme-seg" role="radiogroup">
            {themes.map(([v, label]) => (
              <button class={theme.value === v ? 'on' : ''} role="radio" aria-checked={theme.value === v} onClick={() => setTheme(v)}>{label}</button>
            ))}
          </div>
          <h4 class="caption">{t('ערכת צבעים')}</h4>
          <div class="palettes" role="radiogroup">
            {PALETTES.map(([p, label, fill]) => (
              <button class={'palette' + (palette.value === p ? ' on' : '')} role="radio" aria-checked={palette.value === p} data-p={p} onClick={() => setPalette(p)}>
                <span class="palette-dot" style={{ background: fill }}>{palette.value === p && <CheckIcon size={18} />}</span>
                <span>{p === 'onit' ? label : t(label)}</span>
              </button>
            ))}
          </div>
          <TemplatesManager />
        </div>
      </div>
    </div>
  )
}

function Home({ onPick, onTool, onProject, onBatch, onQueue, error }: { onPick: (f: File) => void; onTool: (f: File, tab: Tool) => void; onProject: (id: string) => void; onBatch: () => void; onQueue: () => void; error?: string }) {
  const input = useRef<HTMLInputElement>(null)
  const toolInput = useRef<HTMLInputElement>(null)
  const toolTab = useRef<Tool>('subs')
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
        {installed() ? (
          <div class="drop-hint share-hint">{t('אפשר גם לשתף סרטון מהגלריה ישירות לאפליקציה')}</div>
        ) : matchMedia('(pointer: coarse)').matches ? (
          <div class="drop-hint share-hint">{t('אחרי הוספת האתר למסך הבית אפשר לשתף אליו סרטונים ישירות מהגלריה.')}</div>
        ) : null}
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
        {batchNow.value && (
          <section class="card batch-banner">
            <div class="row">
              <div class="grow">
                <strong>{batchNow.value.finished ? t('תוצאות העיבוד האחרון') : t('עיבוד סרטונים')}</strong>
                <div class="small muted">{batchNow.value.finished ? t('נשמרו %d מתוך %d', batchNow.value.items.filter((i) => !!i.output).length, batchNow.value.items.length) : t('מעבד סרטונים · %d מתוך %d', Math.min(batchNow.value.items.length, doneCount(batchNow.value) + 1), batchNow.value.items.length)}</div>
              </div>
              <button class="btn tonal small-btn batch-open" onClick={onQueue}>{batchNow.value.finished ? t('הצג') : t('הצג את התור')}</button>
            </div>
          </section>
        )}
        <section class="card batch-entry">
          <div class="row">
            <span class="tool-icon" style={{ color: 'var(--primary)', background: 'color-mix(in srgb, var(--primary) 16%, transparent)' }}><MergeIcon /></span>
            <div class="grow">
              <h3 class="section-title" style="margin:0">{t('כמה סרטונים עם תבנית אחת')}</h3>
              <p class="small muted" style="margin:2px 0 0">{t('בוחרים כמה סרטונים ותבנית, והם מעובדים אחד אחרי השני, בלי לבדוק כל אחד בנפרד.')}</p>
            </div>
          </div>
          <button class="btn tonal several" onClick={onBatch}>{t('בחירת סרטונים ותבנית')}</button>
        </section>
        <ProjectsSection onOpen={onProject} />
        <section>
          <h3 class="section-title" style="margin:4px 0 4px">{t('כלים נוספים')}</h3>
          <p class="small muted" style="margin:0 0 10px">{t('פותח את העורך ישר, בלי זיהוי פנים')}</p>
          <div class="tools">
            {([['subs', 'כתוביות', SubtitlesIcon, 'var(--amber)'], ['sound', 'קול ואפקטים', SoundIcon, 'var(--cyan)'], ['mark', 'סימן מים', MarkIcon, 'var(--violet)'], ['mp3', 'המרה ל-MP3', MusicIcon, 'var(--cyan)']] as const).map(([tab, label, Icon, color]) => (
              <button class="tool" onClick={() => { toolTab.current = tab; if (toolInput.current) toolInput.current.accept = tab === 'mp3' ? 'video/*,audio/*' : 'video/*'; toolInput.current?.click() }}>
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
