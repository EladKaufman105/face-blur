import { useEffect, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import type { Analysis } from '../lib/types'
import type { AnalyzeMessage, AnalyzeRequest } from '../workers/analyze.worker'
import { FaceIcon } from './icons'

type Props = {
  file: File
  sensitive: boolean
  onDone: (a: Analysis, thumbs: (string | null)[], ms: number, backend: string) => void
  onError: (message: string) => void
  onCancel: () => void
}

export function Analyzing({ file, sensitive, onDone, onError, onCancel }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [progress, setProgress] = useState({ done: 0, total: 1, faces: 0 })
  const [ready, setReady] = useState(false)
  const [backend, setBackend] = useState('')
  const [stats, setStats] = useState('')
  const debug = new URLSearchParams(location.search).has('debug')
  const [aspect, setAspect] = useState(16 / 9)
  const started = useRef(performance.now())

  useEffect(() => {
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
        onDone({ times: m.times, width: m.width, height: m.height, fps: m.fps, tracks: m.tracks, cuts: m.cuts }, thumbs, m.ms, m.backend)
        worker.terminate()
      } else if (m.type === 'error') {
        worker.terminate()
        onError(m.code === 'codec' ? t('הדפדפן לא מצליח לפענח את הסרטון הזה (%s). נסה Chrome או Safari מעודכנים.', m.message.replace("can't decode ", '')) : t('לא ניתן לפתוח את הסרטון') + ` (${m.message})`)
      }
    }
    worker.onerror = (e) => {
      worker.terminate()
      onError(e.message || t('משהו השתבש'))
    }
    const forced = new URLSearchParams(location.search).get('backend')
    const req: AnalyzeRequest = { file, sensitive, base: new URL('.', document.baseURI).href, backend: forced === 'wasm' || forced === 'webgpu' ? forced : undefined }
    worker.postMessage(req)
    return () => worker.terminate()
  }, [file, sensitive])

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
            <strong>{ready ? t('מזהה פנים') : t('מכין את המודל…')}</strong>
            <div class="small muted">{[left !== null ? t('נותרו כ-%s', duration(left)) : '', backend ? (backend === 'webgpu' ? t('על המעבד הגרפי') : t('על המעבד')) : ''].filter(Boolean).join(' · ') || '\u00a0'}</div>
          </div>
          <div class="big-num">{ready ? pct : 0}%</div>
        </div>
        <div class="progress" style="margin:14px 0 12px"><div style={{ width: `${ready ? pct : 2}%` }} /></div>
        {backend === 'wasm' && <p class="small" style="margin:0 0 10px;color:var(--primary)">{t('הדפדפן הזה לא נותן גישה למעבד הגרפי (WebGPU), אז הזיהוי איטי. ב-Chrome או Safari מעודכנים זה מהיר הרבה יותר.')}</p>}
        {debug && stats && <p class="small muted" dir="ltr" style="margin:0 0 10px;font-family:monospace">{stats}</p>}
        <div class="row">
          <div class="small muted grow">{t('בטלפון: השאר את המסך פתוח עד הסיום.')}</div>
          <button class="btn tonal" onClick={onCancel}>{t('ביטול')}</button>
        </div>
      </div>
    </main>
  )
}
