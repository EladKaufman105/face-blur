import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { canKeepAwake, useWakeLock } from '../lib/wakelock'
import type { Look, Track } from '../lib/types'
import type { ExportMessage, ExportPicture, ExportRequest } from '../workers/export.worker'
import { sfxSamples, type SfxClip } from '../lib/sfx'
import { watermarkImage, type Watermark } from '../lib/watermark'
import type { Cue, SubStyle, Word } from '../lib/subtitles'
import { getFont } from '../lib/projects'
import { BackIcon, CheckIcon, SaveIcon, ShareIcon, SubtitlesIcon } from './icons'
import { CutMap, type Range } from '../lib/cutmap'
import { DEFAULT_EXPORT, outputSize, type ExportOptions } from '../lib/exportopts'
import { cutCues, toSrt } from '../lib/subtitles'

export type ExportJob = { file: File; times: Float64Array; tracks: Track[]; look: Look; width: number; height: number; sfx: SfxClip[]; watermark: Watermark; cues: Cue[]; subStyle: SubStyle; words?: Word[]; voice: { rate: number; data: Float32Array } | null; cuts: Range[]; options?: ExportOptions; standalone?: boolean; picture?: Omit<ExportPicture, 'image'>; /** The second of two steps (found and saved straight away). */ twoSteps?: boolean }

export function Exporting({ job, onBack, onNew, onEdit }: { job: ExportJob; onBack: () => void; onNew: () => void; /** Open the editor on what was saved straight away. */ onEdit?: () => void }) {
  const [progress, setProgress] = useState(0)
  const [result, setResult] = useState<{ blob: Blob; ms: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const started = useMemo(() => performance.now(), [job])
  // The export as it's made: a small picture now and then.
  const live = useRef<HTMLCanvasElement>(null)
  const [seen, setSeen] = useState(false)
  useWakeLock(!result && !error)

  useEffect(() => {
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
      else { setError(m.message); worker.terminate() }
    }
    worker.onerror = (e) => { setError(e.message || 'error'); worker.terminate() }
    let cancelled = false
    ;(async () => {
      // The sounds' samples and the watermark's picture are made here (the page has the decoder and
      // the font); track boxes are copied, not transferred: the editor keeps its own.
      const sfx = await sfxSamples(job.sfx)
      // The watermark at the result's size (its frame and resolution).
      const o = job.options ?? DEFAULT_EXPORT
      const [ow, oh] = outputSize(job.width, job.height, o.shortSide, o.aspect)
      const overlay = await watermarkImage(job.watermark, ow, oh)
      const bg = job.picture?.background
      const image = bg?.fill === 'image' && bg.image ? await createImageBitmap(await (await fetch(bg.image)).blob()) : null
      const customFont = job.subStyle.customFont ? await getFont(job.subStyle.customFont) : null
      if (cancelled) return
      const picture: ExportPicture | undefined = job.picture ? { ...job.picture, image } : undefined
      const req: ExportRequest = { file: job.file, times: job.times, tracks: job.tracks, look: job.look, sfx, overlay, cues: job.options?.burn === false ? [] : job.cues, subStyle: job.subStyle, voice: job.voice, cuts: job.cuts, options: job.options, picture, base: new URL('.', document.baseURI).href, words: job.words ?? [], customFont }
      const transfer: Transferable[] = []
      if (overlay) transfer.push(overlay)
      if (image) transfer.push(image)
      worker.postMessage(req, transfer)
    })().catch((e) => setError(String(e?.message ?? e)))
    return () => { cancelled = true; worker.terminate() }
  }, [job])

  const opts = job.options ?? DEFAULT_EXPORT
  const mp3 = opts.format === 'mp3'
  const base = job.file.name.replace(/\.[^.]+$/, '') + '-onit'
  const name = base + (mp3 ? '.mp3' : '.mp4')
  const url = useMemo(() => (result ? URL.createObjectURL(result.blob) : null), [result])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  const file = useMemo(() => (result ? new File([result.blob], name, { type: mp3 ? 'audio/mpeg' : 'video/mp4' }) : null), [result])
  // The subtitles as SRT, at their times after the cuts.
  const srt = useMemo(() => {
    if (!opts.srt || mp3 || job.cues.length === 0) return null
    return URL.createObjectURL(new Blob([toSrt(cutCues(job.cues, new CutMap(job.cuts)))], { type: 'application/x-subrip' }))
  }, [job])
  useEffect(() => () => { if (srt) URL.revokeObjectURL(srt) }, [srt])
  const canShare = !!file && typeof navigator.canShare === 'function' && navigator.canShare({ files: [file] })

  if (error) {
    return (
      <main class="side narrow">
        <div class="card error">
          <strong>{t('משהו השתבש')}</strong>
          <p class="small" style="margin:6px 0 12px">{error}</p>
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
      {srt && <a class="btn tonal srt-link" href={srt} download={base + '.srt'} style="text-decoration:none"><SubtitlesIcon /> {t('שמור כתוביות SRT')}</a>}
      <div class="actions">
        {!job.standalone && <button class="btn ghost" onClick={onBack}><span class="flip"><BackIcon /></span> {t('חזרה לעריכה')}</button>}
        {onEdit && <button class="btn ghost edit-saved" onClick={onEdit}>{t('ערוך')}</button>}
        <button class="btn ghost" onClick={onNew}>{t('סרטון חדש')}</button>
      </div>
      </div>
    </main>
  )
}
