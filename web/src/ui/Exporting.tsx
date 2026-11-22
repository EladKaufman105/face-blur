import { useEffect, useMemo, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import type { Look, Track } from '../lib/types'
import type { ExportMessage, ExportRequest } from '../workers/export.worker'
import { BackIcon, CheckIcon, SaveIcon, ShareIcon } from './icons'

export type ExportJob = { file: File; times: Float64Array; tracks: Track[]; look: Look; width: number; height: number }

export function Exporting({ job, onBack, onNew }: { job: ExportJob; onBack: () => void; onNew: () => void }) {
  const [progress, setProgress] = useState(0)
  const [result, setResult] = useState<{ blob: Blob; ms: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const started = useMemo(() => performance.now(), [job])

  useEffect(() => {
    const worker = new Worker(new URL('../workers/export.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<ExportMessage>) => {
      const m = e.data
      if (m.type === 'progress') setProgress(m.progress)
      else if (m.type === 'done') { setResult({ blob: m.blob, ms: m.ms }); worker.terminate() }
      else { setError(m.message); worker.terminate() }
    }
    worker.onerror = (e) => { setError(e.message || 'error'); worker.terminate() }
    // Track boxes are copied (not transferred): the editor keeps its own.
    const req: ExportRequest = { file: job.file, times: job.times, tracks: job.tracks, look: job.look }
    worker.postMessage(req)
    return () => worker.terminate()
  }, [job])

  const name = job.file.name.replace(/\.[^.]+$/, '') + '-onit.mp4'
  const url = useMemo(() => (result ? URL.createObjectURL(result.blob) : null), [result])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  const file = useMemo(() => (result ? new File([result.blob], name, { type: 'video/mp4' }) : null), [result])
  const canShare = !!file && typeof navigator.canShare === 'function' && navigator.canShare({ files: [file] })

  if (error) {
    return (
      <main class="side narrow">
        <div class="card error">
          <strong>{t('משהו השתבש')}</strong>
          <p class="small" style="margin:6px 0 12px">{error}</p>
          <button class="btn tonal" onClick={onBack}>{t('חזרה לעריכה')}</button>
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
        <div class="card">
          <div class="row">
            <div class="face-icon"><SaveIcon /></div>
            <div class="grow">
              <strong>{t('מייצא')}</strong>
              <div class="small muted">{left !== null ? t('נותרו כ-%s', duration(left)) : ' '}</div>
            </div>
            <div class="big-num">{pct}%</div>
          </div>
          <div class="progress" style="margin:14px 0 12px"><div style={{ width: `${Math.max(2, pct)}%` }} /></div>
          <div class="row">
            <div class="small muted grow">{t('אל תסגור את הלשונית עד הסיום.')}</div>
            <button class="btn tonal" onClick={onBack}>{t('ביטול')}</button>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main class="result">
      <video src={url} controls playsInline />
      <div class="side">
      <div class="row" style="gap:10px">
        <div class="face-icon"><CheckIcon /></div>
        <div class="grow">
          <strong style="font-size:20px">{t('הסרטון מוכן')}</strong>
          <div class="small muted">{name} · {(result.blob.size / 1e6).toFixed(1)} MB</div>
        </div>
      </div>
      <div class="actions">
        <a class="btn primary" href={url} download={name} style="text-decoration:none"><SaveIcon /> {t('שמור')}</a>
        {canShare && (
          <button class="btn tonal" onClick={() => navigator.share({ files: [file!], title: name }).catch(() => {})}><ShareIcon /> {t('שתף')}</button>
        )}
      </div>
      <div class="actions">
        <button class="btn ghost" onClick={onBack}><span class="flip"><BackIcon /></span> {t('חזרה לעריכה')}</button>
        <button class="btn ghost" onClick={onNew}>{t('סרטון חדש')}</button>
      </div>
      </div>
    </main>
  )
}
