import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { canKeepAwake, useWakeLock } from '../lib/wakelock'
import type { ExportMessage } from '../workers/export.worker'
import { exportRequest, type ExportJob } from '../lib/exportjob'
import { BackIcon, CheckIcon, SaveIcon, ShareIcon, SubtitlesIcon } from './icons'
import { CutMap } from '../lib/cutmap'
import { DEFAULT_EXPORT } from '../lib/exportopts'
import { cutCues, toSrt } from '../lib/subtitles'
import { releaseSpeechForExport } from '../lib/speech'
import { friendlyExport, technical } from '../lib/errors'
import { ErrorDetails } from './ErrorReport'

export type { ExportJob }

export function Exporting({ job, onBack, onNew, onEdit }: { job: ExportJob; onBack: () => void; onNew: () => void; /** Open the editor on what was saved straight away. */ onEdit?: () => void }) {
  const [progress, setProgress] = useState(0)
  const [result, setResult] = useState<{ blob: Blob; ms: number } | null>(null)
  const [error, setError] = useState<{ message: string; details: string } | null>(null)
  const started = useMemo(() => performance.now(), [job])
  // The export as it's made: a small picture now and then.
  const live = useRef<HTMLCanvasElement>(null)
  const [seen, setSeen] = useState(false)
  useWakeLock(!result && !error)

  useEffect(() => {
    releaseSpeechForExport()
    const worker = new Worker(new URL('../workers/export.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<ExportMessage>) => {
      const m = e.data
      if (m.type === 'progress') setProgress(m.progress)
      else if (m.type === 'frame') {
        const c = live.current
        if (c) {
          if (c.width !== m.bitmap.width || c.height !== m.bitmap.height) { c.width = m.bitmap.width; c.height = m.bitmap.height }
          c.getContext('2d')!.drawImage(m.bitmap, 0, 0)
          setSeen(true)
        }
        m.bitmap.close()
      } else if (m.type === 'done') { setResult({ blob: m.blob, ms: m.ms }); worker.terminate() }
      else { setError({ message: friendlyExport(m.code, m.message), details: technical('export', m.message, m.code) }); worker.terminate() }
    }
    worker.onerror = (e) => { setError({ message: friendlyExport('failed', e.message || ''), details: technical('export worker', e.message || 'error') }); worker.terminate() }
    let cancelled = false
    ;(async () => {
      const { req, transfer } = await exportRequest(job)
      if (cancelled) return
      worker.postMessage(req, transfer)
    })().catch((e) => setError({ message: friendlyExport('failed', String(e?.message ?? e)), details: technical('export request', e) }))
    return () => { cancelled = true; worker.terminate() }
  }, [job])

  const opts = job.options ?? DEFAULT_EXPORT
  const mp3 = opts.format === 'mp3'
  const base = job.file.name.replace(/\.[^.]+$/, '') + '-onit'
  const name = base + (mp3 ? '.mp3' : '.mp4')
  const url = useMemo(() => (result ? URL.createObjectURL(result.blob) : null), [result])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  const file = useMemo(() => (result ? new File([result.blob], name, { type: mp3 ? 'audio/mpeg' : 'video/mp4' }) : null), [result])
  // The subtitles as SRT, at their times after the cuts, as the export shows them (the bleeped words hidden): always offered when there are subtitles.
  const srt = useMemo(() => {
    if (mp3 || job.cues.length === 0) return null
    return URL.createObjectURL(new Blob([toSrt(cutCues(job.cues, new CutMap(job.cuts), job.words))], { type: 'application/x-subrip' }))
  }, [job])
  useEffect(() => () => { if (srt) URL.revokeObjectURL(srt) }, [srt])
  const [srtAsk, setSrtAsk] = useState(false)
  const canShare = !!file && typeof navigator.canShare === 'function' && navigator.canShare({ files: [file] })

  if (error) {
    return (
      <main class="side narrow">
        <div class="card error">
          <strong>{t('משהו השתבש')}</strong>
          <p class="small export-error" style="margin:6px 0 12px">{error.message}</p>
          <ErrorDetails details={error.details} />
          <button class="btn tonal" onClick={onBack}>{job.standalone ? t('חזרה') : t('חזרה לעריכה')}</button>
        </div>
      </main>
    )
  }

  if (!result || !url) {
    const pct = Math.floor(progress * 100)
    const elapsed = (performance.now() - started) / 1000
    const left = progress > 0.05 ? (elapsed / progress) * (1 - progress) : null
    return (
      <main class="side narrow">
        {!mp3 && (
          <div class={'live' + (seen ? '' : ' empty')}>
            <canvas ref={live} />
            {seen && <div class="pill"><span class="dot" />{t('תצוגה חיה של הייצוא')}</div>}
          </div>
        )}
        <div class="card">
          <div class="row">
            <div class="face-icon"><SaveIcon /></div>
            <div class="grow">
              <strong>{job.twoSteps ? t('שלב 2 מתוך 2 · מטשטש ושומר') : t('מייצא')}</strong>
              <div class="small muted">{[t('עברו %s', duration(elapsed)), left !== null ? t('נותרו כ-%s', duration(left)) : ''].filter(Boolean).join(' · ')}</div>
            </div>
            <div class="big-num">{pct}%</div>
          </div>
          <div class="progress" style="margin:14px 0 12px"><div style={{ width: `${Math.max(2, pct)}%` }} /></div>
          <div class="row">
            <div class="small muted grow">{canKeepAwake() ? t('השאר את הלשונית פתוחה עד הסוף. המסך יישאר דלוק.') : t('אל תסגור את הלשונית עד הסיום.')}</div>
            <button class="btn tonal" onClick={onBack}>{t('ביטול')}</button>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main class={'result' + (mp3 ? ' audio' : '')}>
      {mp3 ? <audio src={url} controls /> : <video src={url} controls playsInline />}
      <div class="side">
      <div class="row" style="gap:10px">
        <div class="face-icon"><CheckIcon /></div>
        <div class="grow">
          <strong style="font-size:20px">{mp3 ? t('קובץ ה-MP3 מוכן') : t('הסרטון מוכן')}</strong>
          <div class="small muted">{name} · {(result.blob.size / 1e6).toFixed(1)} MB</div>
        </div>
      </div>
      {!mp3 && (job.tracks.length > 0 || job.twoSteps) && <p class="small muted review-note">{t('כדאי לעבור על הסרטון לפני פרסום: זיהוי אוטומטי עלול לפספס פנים מסובבות, מוסתרות או קטנות מאוד.')}</p>}
      {!mp3 && job.tracks.length === 0 && !job.twoSteps && (job.words?.length ?? 0) > 0 && <p class="small muted review-note">{t('כדאי לעבור על הכתוביות לפני פרסום: זיהוי דיבור ותרגום אוטומטיים עלולים לטעות.')}</p>}
      <div class="actions">
        <a class="btn primary" href={url} download={name} style="text-decoration:none"><SaveIcon /> {t('שמור')}</a>
        {canShare && (
          <button class="btn tonal" onClick={() => navigator.share({ files: [file!], title: name }).catch(() => {})}><ShareIcon /> {t('שתף')}</button>
        )}
      </div>
      {srt && !job.showsBleeps && <a class="btn tonal srt-link" href={srt} download={base + '.srt'} style="text-decoration:none"><SubtitlesIcon /> {t('שמור כתוביות (SRT)')}</a>}
      {/* Hiding the bleeped words is off: the file would show them, so it asks first (as the subtitles panel). */}
      {srt && job.showsBleeps && (
        <>
          <button class="btn tonal srt-link srt-shows" onClick={() => setSrtAsk(true)} title={t('ההסתרה כבויה: הקובץ יכלול את המילים המצופצפות כפי שהן')}><SubtitlesIcon /> {t('שמור כתוביות (SRT, בלי הסתרה)')}</button>
          {srtAsk && (
            <div class="confirm srt-ask" role="alertdialog" aria-label={t('לשמור SRT בלי הסתרה?')}>
              <span class="small">{t('קובץ ה-SRT יכלול את המילים המצופצפות כפי שהן. אל תפרסם אותו.')}</span>
              <div class="row">
                <a class="btn small-btn danger-btn srt-ask-yes" href={srt} download={base + '.srt'} style="text-decoration:none" onClick={() => setSrtAsk(false)}>{t('שמור')}</a>
                <button class="btn ghost small-btn srt-ask-no" onClick={() => setSrtAsk(false)}>{t('ביטול')}</button>
              </div>
            </div>
          )}
        </>
      )}
      <div class="actions">
        {!job.standalone && <button class="btn ghost" onClick={onBack}><span class="flip"><BackIcon /></span> {t('חזרה לעריכה')}</button>}
        {onEdit && <button class="btn ghost edit-saved" onClick={onEdit}>{t('ערוך')}</button>}
        <button class="btn ghost" onClick={onNew}>{t('סרטון חדש')}</button>
      </div>
      </div>
    </main>
  )
}
