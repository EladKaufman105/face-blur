import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { duration, lang, t } from '../i18n'
import {
  cancelAll, checkCount, current, dismiss, doneCount, downloadAll, estimateText, failedCount, isDone, loadSpeeds, MAX_VIDEOS, needsCheck, progressOf, remainingMs, remainingText,
  savedCount, skip, startBatch, storageCheck, totalMs, version, videoDuration, type BatchItem,
} from '../lib/batch'
import { applyTemplate, currentSettings, type TemplateSettings } from '../lib/template'
import { findTemplate, loadTemplates } from '../lib/templatestore'
import { loadExportOptions } from '../lib/exportopts'
import { canKeepAwake } from '../lib/wakelock'
import { AddIcon, BackIcon, CheckIcon, CloseIcon, SaveIcon } from './icons'
import { TemplatePicker, templateLabel } from './TemplatePicker'
import { useFrame } from './TemplatePreview'
import { loadWatermark } from './EditorTabs'
import { SwitchRow } from './EditorTabs'
import { QualityChoice, useSpeechPlan } from './SpeechUi'
import { grantSpeech, mbOf } from '../lib/speech'
import './templates-en'

const thumbs = new WeakMap<File, Promise<string | null>>()
let thumbQueue: Promise<unknown> = Promise.resolve()

/** A small picture of a video's first frame, taken with one player at a time that's let go of after (not a player per video on the page). */
function frameOf(file: File): Promise<string | null> {
  let p = thumbs.get(file)
  if (!p) {
    const grab = () => new Promise<string | null>((resolve) => {
      const v = document.createElement('video')
      const url = URL.createObjectURL(file)
      let over = false
      const end = (r: string | null) => {
        if (over) return
        over = true
        clearTimeout(timer)
        v.removeAttribute('src')
        v.load()
        URL.revokeObjectURL(url)
        resolve(r)
      }
      const timer = setTimeout(() => end(null), 8000)
      v.muted = true
      v.preload = 'auto'
      v.onerror = () => end(null)
      v.onloadedmetadata = () => { v.currentTime = Math.min(0.1, (v.duration || 0.1) / 2) }
      v.onseeked = () => {
        try {
          const c = document.createElement('canvas')
          c.width = 144
          c.height = Math.max(1, Math.round((144 * v.videoHeight) / Math.max(1, v.videoWidth)))
          c.getContext('2d')!.drawImage(v, 0, 0, c.width, c.height)
          end(c.toDataURL('image/jpeg', 0.7))
        } catch {
          end(null)
        }
      }
      v.src = url
    })
    p = thumbQueue.then(grab, grab)
    thumbQueue = p
    thumbs.set(file, p)
  }
  return p
}

/** A video's first frame, as a picture. */
function Thumb({ file }: { file: File }) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    frameOf(file).then((s) => { if (live) setSrc(s) })
    return () => { live = false }
  }, [file])
  return src ? <img class="batch-thumb" src={src} alt="" /> : <div class="batch-thumb" />
}

const mb = (bytes: number) => (bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1e6))} MB`)

/**
 * Several videos, as the app's: up to 10 picked, one template for all, the time it will take,
 * and a notice that the tab has to stay open.
 */
export function BatchSetup({ onBack, onStarted, initial }: { onBack: () => void; onStarted: () => void; initial?: File[] }) {
  const [files, setFiles] = useState<File[]>(initial ?? [])
  const [lengths, setLengths] = useState<Map<File, number>>(new Map())
  const input = useRef<HTMLInputElement>(null)
  const frame = useFrame(files[0] ?? null)
  useEffect(() => { loadTemplates() }, [])
  const baseline = useMemo(() => currentSettings({ sensitive: false, hebrewUi: lang.value === 'he', watermark: loadWatermark(), options: { ...loadExportOptions(), aspect: 'original' } }), [])
  const [tplId, setTplId] = useState<string | null>(null)
  const [settings, setSettings] = useState<TemplateSettings>(baseline)
  const [auto, setAuto] = useState(true)
  const [space, setSpace] = useState<{ need: number; free: number | null } | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const { plan: speechPlan, cached } = useSpeechPlan(settings.language)
  useEffect(() => { storageCheck(files).then(setSpace) }, [files])
  useEffect(() => {
    let live = true
    for (const f of files) if (!lengths.has(f)) videoDuration(f).then((d) => { if (live) setLengths((m) => new Map(m).set(f, d)) })
    return () => { live = false }
  }, [files])

  const add = (list: FileList | null) => {
    if (!list) return
    const room = MAX_VIDEOS - files.length
    const next = [...list].filter((f) => f.type.startsWith('video/') || !f.type)
    setNote(next.length > room ? t('עד %d סרטונים בבת אחת.', MAX_VIDEOS) : null)
    setFiles([...files, ...next.slice(0, room)])
  }
  const pick = (id: string | null) => {
    setTplId(id)
    const tp = findTemplate(id)
    setSettings(tp ? applyTemplate(tp, baseline) : baseline)
  }
  const durations = files.map((f) => lengths.get(f) ?? 0)
  const estimate = totalMs(durations, settings, loadSpeeds())
  const tight = space && space.free !== null && space.free < space.need
  const start = () => {
    // Start is the go-ahead for the download whose size was shown above: no dialog at the first video.
    if (settings.createSubs) grantSpeech(speechPlan)
    startBatch(files, settings, tplId ? templateLabel(findTemplate(tplId)!) : t('בלי תבנית'), { folder: true, autoDownload: auto })
    onStarted()
  }
  return (
    <main class="batch-setup">
      <div class="ready-top">
        <button class="btn ghost ready-back" onClick={onBack}><span class="flip"><BackIcon /></span> {t('חזרה')}</button>
      </div>
      <section class="card">
        <h3 class="section-title">{t('כמה סרטונים')}</h3>
        <p class="small muted" style="margin:0 0 10px">{t('עד %d סרטונים בבת אחת, עם תבנית אחת. העיבוד רץ בלשונית הזו.', MAX_VIDEOS)}</p>
        <div class="batch-files">
          {files.map((f, k) => (
            <div class="batch-file">
              <Thumb file={f} />
              <div class="grow">
                <strong class="ready-name"><bdi>{f.name}</bdi></strong>
                <div class="small muted" dir="ltr">{lengths.get(f) ? duration(lengths.get(f)!) + ' · ' : ''}{mb(f.size)}</div>
              </div>
              <button class="icon-btn batch-remove" aria-label={`${t('הסר')}: ${f.name}`} title={t('הסר')} onClick={() => setFiles(files.filter((_, i) => i !== k))}><CloseIcon size={18} /></button>
            </div>
          ))}
        </div>
        {note && <p class="small" style="margin:0;color:var(--primary)">{note}</p>}
        <button class="btn tonal batch-add" disabled={files.length >= MAX_VIDEOS} onClick={() => input.current?.click()}><AddIcon /> {files.length ? t('הוסף סרטונים') : t('בחר סרטונים')}</button>
        <input ref={input} class="batch-input" type="file" accept="video/*" multiple hidden onChange={(e) => { add((e.currentTarget as HTMLInputElement).files); (e.currentTarget as HTMLInputElement).value = '' }} />
      </section>

      <TemplatePicker frame={frame} base={baseline} current={tplId} onPick={pick} settings={settings} onSettings={setSettings} onEdited={pick} />

      <section class="card batch-before">
        <h3 class="section-title">{t('לפני שמתחילים')}</h3>
        {files.length > 0 && (
          <div class="row"><span class="grow">{t('זמן משוער')}</span><strong class="batch-estimate">{estimateText(estimate)}</strong></div>
        )}
        {settings.blurOff && <p class="small batch-noblur" role="alert" style="margin:0;color:var(--danger-text);font-weight:700">{t('הפנים לא יטושטשו בסרטונים האלה: בתבנית הזו אין טשטוש פנים.')}</p>}
        {files.length > 0 && <p class="small muted" style="margin:0">{t('ההערכה משתפרת אחרי שהמכשיר הזה מעבד כמה סרטונים.')}</p>}
        {settings.createSubs && cached === false && (
          <>
            <p class="small batch-model" style="margin:0;color:var(--primary)">{t('כתוביות צריכות מודל זיהוי דיבור, שמורידים פעם אחת (%d MB).', mbOf(speechPlan))}</p>
            <QualityChoice language={settings.language} />
          </>
        )}
        {tight && <p class="small batch-space" style="margin:0;color:#e5484d">{t('אין מספיק מקום בדפדפן לשמירת הפרויקטים (%s פנוי, צריך כ-%s). אפשר להסיר סרטונים או למחוק פרויקטים.', mb(space!.free!), mb(space!.need))}</p>}
        <p class="small muted" style="margin:0">{canKeepAwake() ? t('השאר את הלשונית פתוחה עד הסוף: העיבוד נעצר אם היא נסגרת. המסך יישאר דלוק.') : t('השאר את הלשונית ואת המסך פתוחים עד הסוף: העיבוד נעצר אם הם נסגרים.')}</p>
        <SwitchRow cls="batch-auto" title={t('שמור כל סרטון כשהוא מוכן')} text={t('אם הדפדפן חוסם הורדות אוטומטיות, אפשר לשמור מהרשימה בסוף.')} on={auto} onChange={setAuto} />
        <button class="btn primary batch-start" disabled={files.length === 0 || !!tight} onClick={start}><SaveIcon /> {t('התחל עיבוד')}</button>
      </section>
    </main>
  )
}

const PHASES: Record<string, string> = { read: 'קורא את הסרטון', faces: 'מזהה פנים', subtitles: 'יוצר כתוביות', download: 'מוריד את מודל הדיבור…', sound: 'מעבד סאונד', picture: 'מעבד תמונה', export: 'מייצא' }

function statusText(i: BatchItem): string {
  switch (i.status) {
    case 'waiting': return t('ממתין')
    case 'faces': return t('מזהה פנים')
    case 'processing': return t(PHASES[i.phase ?? 'subtitles'] ?? 'מעבד') + (i.detail ? ` ${i.detail}` : '')
    case 'exporting': return t('מייצא')
    case 'saved': return t('נשמר')
    case 'failed': return t('נכשל')
    case 'nofaces': return i.output ? t('נשמר · לא נמצאו פנים') : t('לא נמצאו פנים')
    case 'skipped': return t('דולג')
  }
}

/** The queue: each video's status, the overall percent and time left, skip and cancel; at the end, what to save and check. */
export function BatchQueue({ onHome, onCheck }: { onHome: () => void; onCheck: (projectId: string) => void }) {
  void version.value
  const b = current.value
  const [now, setNow] = useState(performance.now())
  const [confirm, setConfirm] = useState(false)
  useEffect(() => {
    const id = setInterval(() => setNow(performance.now()), 1000)
    return () => clearInterval(id)
  }, [])
  if (!b) return (
    <main class="batch">
      <section class="card"><p style="margin:0">{t('אין עיבוד כרגע.')}</p><button class="btn tonal" style="margin-top:10px" onClick={onHome}>{t('חזרה')}</button></section>
    </main>
  )
  const pct = Math.min(100, Math.floor(progressOf(b) * 100))
  const left = !b.finished && b.items.some((i) => i.duration > 0) ? remainingMs(b, loadSpeeds(), now) : null
  const running = b.items.find((i) => !isDone(i) && i.status !== 'waiting')
  const saved = savedCount(b), check = checkCount(b)
  return (
    <main class="batch">
      <section class="card batch-head">
        <div class="row">
          <div class="grow">
            <strong class="batch-title">{b.finished ? (b.cancelled ? t('העיבוד הופסק') : t('העיבוד הסתיים')) : t('מעבד סרטונים · %d מתוך %d', Math.min(b.items.length, doneCount(b) + 1), b.items.length)}</strong>
            <div class="small muted">{t('תבנית')}: <bdi>{b.templateName}</bdi></div>
          </div>
          <div class="big-num batch-pct">{b.finished ? 100 : pct}%</div>
        </div>
        <div class="progress" style="margin:12px 0 8px"><div style={{ width: `${Math.max(2, b.finished ? 100 : pct)}%` }} /></div>
        {!b.finished && <div class="small muted batch-line">{[left !== null ? remainingText(left) : '', running ? t('הסרטון הנוכחי: %d%', Math.floor(running.progress * 100)) : ''].filter(Boolean).join(' · ') || ' '}</div>}
        {b.finished && (
          <div class="small batch-summary">
            {t('נשמרו %d מתוך %d', saved, b.items.length)}{failedCount(b) > 0 ? ' · ' + t('%d נכשלו', failedCount(b)) : ''}{check > 0 ? ' · ' + t('%d לבדיקה', check) : ''}
          </div>
        )}
        {!b.finished && (
          <p class="small batch-stay" style="margin:8px 0 0">{canKeepAwake() ? t('השאר את הלשונית פתוחה עד הסוף: העיבוד נעצר אם היא נסגרת. המסך יישאר דלוק.') : t('השאר את הלשונית ואת המסך פתוחים עד הסוף: העיבוד נעצר אם הם נסגרים.')}</p>
        )}
        {!b.finished && !confirm && (
          <div class="actions" style="margin-top:10px">
            <button class="btn tonal batch-cancel" onClick={() => setConfirm(true)}>{t('בטל הכל')}</button>
            <button class="btn ghost" onClick={onHome}>{t('חזרה')}</button>
          </div>
        )}
        {!b.finished && confirm && (
          <div class="confirm" style="margin-top:10px">
            <strong class="small">{t('לבטל את העיבוד?')}</strong>
            <span class="small">{t('הסרטון שבעיבוד ומה שממתין ייעצרו. סרטונים שכבר נשמרו נשארים.')}</span>
            <div class="row">
              <button class="btn small-btn danger-btn batch-cancel-yes" onClick={() => { cancelAll(b); setConfirm(false) }}>{t('בטל הכל')}</button>
              <button class="btn ghost small-btn" onClick={() => setConfirm(false)}>{t('המשך')}</button>
            </div>
          </div>
        )}
        {b.finished && (
          <>
            <p class="small muted" style="margin:8px 0 0">{b.folder ? t('נשמרו גם כפרויקטים, בתיקייה אחת ברשימת הפרויקטים. "בדיקה" פותחת את הסרטון בעורך, לתיקון ולייצוא מחדש. כדאי לעבור על מה שמסומן.') : t('נשמר גם כפרויקט. "בדיקה" פותחת את הסרטון בעורך, לתיקון ולייצוא מחדש.')}</p>
            <div class="actions" style="margin-top:10px">
              {saved > 1 && <button class="btn primary batch-save-all" onClick={() => downloadAll(b)}><SaveIcon /> {t('שמור הכל')}</button>}
              <button class="btn tonal batch-home" onClick={() => { dismiss(); onHome() }}>{t('חזרה לדף הבית')}</button>
            </div>
            {b.autoDownload && saved > 0 && <p class="small muted" style="margin:6px 0 0">{t('אם הדפדפן חסם את ההורדות האוטומטיות, אפשר לשמור כל סרטון מהרשימה.')}</p>}
          </>
        )}
      </section>

      <div class="batch-items">
        {b.items.map((i) => (
          <div class={'batch-item card ' + i.status} data-status={i.status}>
            <div class="row">
              <Thumb file={i.file} />
              <div class="grow">
                <strong class="ready-name"><bdi>{i.name}</bdi></strong>
                <div class="small batch-status">
                  {i.status === 'saved' ? <span class="ok"><CheckIcon size={14} /> </span> : null}
                  {statusText(i)}
                  {!isDone(i) && i.status !== 'waiting' ? ` · ${Math.floor(i.progress * 100)}%` : ''}
                </div>
              </div>
              {!isDone(i) && !b.finished && <button class="btn ghost small-btn batch-skip" onClick={() => skip(i)}>{t('דלג')}</button>}
            </div>
            {!isDone(i) && i.status !== 'waiting' && <div class="progress" style="margin-top:8px"><div style={{ width: `${Math.max(3, Math.floor(i.progress * 100))}%` }} /></div>}
            {i.status === 'failed' && i.error && <p class="small batch-error" style="margin:6px 0 0;color:#e5484d">{i.error}</p>}
            {i.lowConfidence && i.status !== 'skipped' && i.status !== 'failed' && <p class="small batch-lowconf" style="margin:6px 0 0;color:var(--primary)">{t('זיהוי לא בטוח')}: {t('כדאי לבדוק את הפנים בעורך.')}</p>}
            {i.notes.map((n) => <p class="small batch-note" style="margin:6px 0 0;color:var(--primary)">{t('שלב בתבנית לא הושלם')}: {n}</p>)}
            {isDone(i) && i.status !== 'skipped' && (
              <div class="actions batch-item-acts" style="margin-top:8px">
                {i.output && <a class="btn tonal small-btn batch-download" href={i.output.url} download={i.output.name} style="text-decoration:none"><SaveIcon size={16} /> {t('שמור')}</a>}
                {i.output?.srt && <a class="btn ghost small-btn" href={i.output.srt.url} download={i.output.srt.name} style="text-decoration:none">SRT</a>}
                {i.projectId && b.finished && <button class={'btn small-btn batch-check ' + (needsCheck(i) ? 'primary' : 'ghost')} onClick={() => onCheck(i.projectId!)}>{t('בדיקה')}</button>}
              </div>
            )}
          </div>
        ))}
      </div>
    </main>
  )
}

