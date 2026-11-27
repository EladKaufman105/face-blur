import { useEffect, useRef, useState } from 'preact/hooks'
import { technical } from '../lib/errors'
import { t, duration } from '../i18n'
import type { Analysis } from '../lib/types'
import type { AnalyzeMessage, AnalyzeRequest, Tune } from '../workers/analyze.worker'
import { FaceIcon } from './icons'
import { AnalysisStatsPanel, type VideoFacts } from './AnalysisStatsPanel'
import type { AnalysisStats } from '../lib/analysis-stats'
import { releaseSpeechForExport } from '../lib/speech'
import { canKeepAwake, useWakeLock } from '../lib/wakelock'

type Props = {
  file: File
  sensitive: boolean
  /** Fast detection ([sensitive] wins) and the sensitivity (lib/sensitivity.ts). */
  fast?: boolean
  /** Selfie detection ([sensitive] and [fast] win). */
  selfie?: boolean
  sensitivity?: number
  /** The first of two steps (found, then saved straight away). */
  twoSteps?: boolean
  onDone: (a: Analysis, thumbs: (string | null)[], ms: number, backend: string) => void
  /** What went wrong, and its technical details (to copy). */
  onError: (message: string, details?: string) => void
  onCancel: () => void
}

/** The main pointer is touch (a phone or tablet, also with the browser's desktop site, whose user agent says nothing of it). */
function touchPrimary(): boolean {
  try {
    return matchMedia('(pointer: coarse)').matches || (navigator.maxTouchPoints > 1 && !matchMedia('(any-pointer: fine)').matches)
  } catch {
    return false
  }
}

/** For tests, on localhost only: ?detect=yunet:0,side:640,rate:5 pins detection (no YuNet, a 640 px fine pass, 5 detections a second), with no pacing; reduced:1 starts it in the reduced mode. */
function tuneFromUrl(): Tune | undefined {
  // Only on this computer (the tests' server): on the site it would weaken detection with a link.
  if (location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') return undefined
  const q = new URLSearchParams(location.search).get('detect')
  if (!q) return undefined
  const kv = Object.fromEntries(q.split(',').map((p) => p.split(':')))
  return { yunet: kv.yunet !== '0', ...(Number(kv.side) ? { side: Number(kv.side) } : {}), ...(Number(kv.rate) ? { rate: Number(kv.rate) } : {}), ...(kv.reduced === '1' ? { reduced: true } : {}) }
}

export function Analyzing({ file, sensitive, fast, selfie, sensitivity, twoSteps, onDone, onError, onCancel }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [progress, setProgress] = useState({ done: 0, total: 1, faces: 0 })
  const [ready, setReady] = useState(false)
  const [backend, setBackend] = useState('')
  const [stats, setStats] = useState<AnalysisStats | null>(null)
  const [video, setVideo] = useState<VideoFacts | null>(null)
  // Tap the percentage to open the stats panel (where the time goes) (?debug shows it from the start).
  const [debug, setDebug] = useState(() => new URLSearchParams(location.search).has('debug'))
  const [aspect, setAspect] = useState(16 / 9)
  const started = useRef(performance.now())
  const workerRef = useRef<Worker | null>(null)
  // The device report's placement probe (where the model's nodes run) starts when the stats panel is opened, not in every analysis.
  useEffect(() => { if (debug) workerRef.current?.postMessage({ type: 'probe' }) }, [debug, ready])

  useEffect(() => {
    releaseSpeechForExport() // a phone gives the speech model's memory back first
    const worker = new Worker(new URL('../workers/analyze.worker.ts', import.meta.url), { type: 'module' })
    started.current = performance.now()
    worker.onmessage = (e: MessageEvent<AnalyzeMessage>) => {
      const m = e.data
      if (m.type === 'log') {
        console.log('ONIT analyze:', m.message)
      } else if (m.type === 'ready') {
        setReady(true)
        setBackend(m.backend)
        setAspect(m.width / m.height)
        setVideo({ width: m.width, height: m.height, fps: m.fps, duration: m.duration })
        started.current = performance.now()
      } else if (m.type === 'progress') {
        setProgress({ done: m.done, total: m.total, faces: m.faces })
        setBackend(m.backend)
        setStats(m.stats)
        const c = canvas.current
        if (m.preview && c) {
          c.width = m.preview.width
          c.height = m.preview.height
          const ctx = c.getContext('2d')!
          ctx.drawImage(m.preview, 0, 0)
          ctx.lineWidth = Math.max(2, c.width / 200)
          ctx.strokeStyle = '#ff4d6d'
          for (const d of m.dets ?? []) {
            if (d.score < 0.5) continue
            const w = d.w * c.width, h = d.h * c.height
            ctx.beginPath()
            ctx.roundRect(d.cx * c.width - w / 2, d.cy * c.height - h / 2, w, h, Math.min(w, h) * 0.25)
            ctx.stroke()
          }
          m.preview.close()
        }
      } else if (m.type === 'done') {
        const thumbs = m.thumbs.map((b) => {
          if (!b) return null
          const c = document.createElement('canvas')
          c.width = b.width
          c.height = b.height
          c.getContext('2d')!.drawImage(b, 0, 0)
          b.close()
          return c.toDataURL('image/jpeg', 0.85)
        })
        onDone({ times: m.times, width: m.width, height: m.height, fps: m.fps, tracks: m.tracks, cuts: m.cuts, lowConfidence: m.lowConfidence, reduced: m.reduced, fast: m.fast, selfie: m.selfie, sensitive: m.sensitive, sensitivity: m.sensitivity }, thumbs, m.ms, m.backend)
        worker.terminate()
      } else if (m.type === 'error') {
        worker.terminate()
        onError(m.code === 'codec' ? t('הדפדפן לא מצליח לפענח את הסרטון הזה (%s). נסה Chrome או Safari מעודכנים.', m.message.replace("can't decode ", '')) : t('לא ניתן לפתוח את הסרטון') + ` (${m.message})`, technical('face detection', m.message, m.code))
      }
    }
    worker.onerror = (e) => {
      worker.terminate()
      onError(t('משהו השתבש בעיבוד. נסה שוב; אם זה חוזר, העתק את הפרטים הטכניים ושלח אותם.'), technical('face detection worker', e.message || 'error'))
    }
    const forced = new URLSearchParams(location.search).get('backend')
    const req: AnalyzeRequest = { file, sensitive, fast, selfie, sensitivity, base: new URL('.', document.baseURI).href, touch: touchPrimary(), backend: forced === 'wasm' || forced === 'webgpu' ? forced : undefined, workers: Number(new URLSearchParams(location.search).get('workers')) || undefined, tune: tuneFromUrl() }
    worker.postMessage(req)
    workerRef.current = worker
    return () => { workerRef.current = null; worker.terminate() }
  }, [file, sensitive, fast, selfie, sensitivity])

  useWakeLock(true)
  const pct = Math.min(99, Math.floor((progress.done / progress.total) * 100))
  const elapsed = (performance.now() - started.current) / 1000
  const rate = progress.done / Math.max(elapsed, 0.001)
  const left = progress.done > 10 && rate > 0 ? (progress.total - progress.done) / rate : null

  return (
    <main class="analyze">
      <div class="stage" style={{ aspectRatio: String(aspect) }}>
        <canvas ref={canvas} />
        <div class="pill"><span class="dot" />{t('מזהה פנים')}{progress.faces > 0 ? ` · ${progress.faces}` : ''}</div>
      </div>
      <div class="card">
        <div class="row">
          <div class="face-icon"><FaceIcon /></div>
          <div class="grow">
            <strong>{ready ? (twoSteps ? t('שלב 1 מתוך 2 · מזהה פנים') : t('מזהה פנים')) : t('מכין את המודל…')}</strong>
            <div class="small muted">{[ready ? t('עברו %s', duration(elapsed)) : '', left !== null ? t('נותרו כ-%s', duration(left)) : '', backend ? (backend === 'webgpu' ? t('על המעבד הגרפי') : t('על המעבד')) : ''].filter(Boolean).join(' · ') || '\u00a0'}</div>
          </div>
          <button type="button" class="big-num stats-toggle" aria-expanded={debug} onClick={() => setDebug((d) => !d)}>{ready ? pct : 0}%</button>
        </div>
        <div class="progress" style="margin:14px 0 12px"><div style={{ width: `${ready ? pct : 2}%` }} /></div>
        {backend === 'wasm' && <p class="small" style="margin:0 0 10px;color:var(--primary)">{t('הדפדפן הזה לא נותן גישה למעבד הגרפי (WebGPU), אז הזיהוי איטי. ב-Chrome או Safari מעודכנים זה מהיר הרבה יותר.')}</p>}
        <AnalysisStatsPanel open={debug && !!stats} stats={stats} video={video} percent={pct} elapsed={() => (performance.now() - started.current) / 1000} />
        <div class="row">
          <div class="small muted grow">{canKeepAwake() ? t('השאר את הלשונית פתוחה עד הסוף. המסך יישאר דלוק.') : t('בטלפון: השאר את המסך פתוח עד הסיום.')}</div>
          <button class="btn tonal" onClick={onCancel}>{t('ביטול')}</button>
        </div>
      </div>
    </main>
  )
}
