import { useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { MAX_SPEAKERS, speakerColor } from '../lib/speakers'
import type { Cue } from '../lib/subtitles'
import type { SpeakersMessage, SpeakersRequest } from '../workers/speakers.worker'
import { releaseSpeechForExport } from '../lib/speech'

/**
 * Who speaks, as in the app: found on the device (each subtitle's voice), each speaker in a color
 * of its own; how many, if the user knows.
 */
export function SpeakersCard({ file, cues, onCues, color, chosen }: { file: File; cues: Cue[]; onCues: (c: Cue[], step?: boolean) => void; color: string; chosen?: string[] }) {
  const [count, setCount] = useState<number | null>(null)
  const [run, setRun] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const worker = useRef<Worker | null>(null)
  const found = new Set(cues.map((c) => c.speaker ?? 0).filter((s) => s > 0))
  const start = () => {
    worker.current?.terminate()
    releaseSpeechForExport() // a phone gives the speech model's memory back first
    const w = new Worker(new URL('../workers/speakers.worker.ts', import.meta.url), { type: 'module' })
    worker.current = w
    setError(null)
    setRun(0)
    const sorted = [...cues].sort((a, b) => a.start - b.start)
    const end = () => { w.terminate(); worker.current = null; setRun(null) }
    w.onmessage = (e: MessageEvent<SpeakersMessage>) => {
      const m = e.data
      if (m.type === 'progress') { setRun(m.progress); return }
      end()
      if (m.type === 'done') {
        const by = new Map(sorted.map((c, k) => [c.id, m.speakers[k]]))
        onCues(cues.map((c) => ({ ...c, speaker: by.get(c.id) ?? c.speaker })), true)
      } else setError(m.code === 'no-audio' ? t('לסרטון אין פס קול.') : t('משהו השתבש') + ': ' + m.message)
    }
    w.onerror = (e) => { end(); setError(t('משהו השתבש') + ': ' + (e.message || '')) }
    const req: SpeakersRequest = { file, model: new URL('models/speaker.onnx', document.baseURI).href, spans: sorted.map((c) => [c.start, c.end]), count }
    w.postMessage(req)
  }
  return (
    <section class="card speakers">
      <h3 class="section-title">{t('זיהוי דוברים')}</h3>
      <p class="small muted" style="margin:0">{found.size > 1 ? t('נמצאו %d דוברים. הקש על הנקודה ליד כתובית כדי להחליף דובר.', found.size) : t('כל דובר בצבע משלו, גם ברשימת הכתוביות')}</p>
      {run !== null ? (
        <div class="busy">
          <div class="row">
            <span class="small grow">{t('מזהה דוברים… %d%', Math.round(run * 100))}</span>
            <button class="btn ghost small-btn" onClick={() => { worker.current?.terminate(); worker.current = null; setRun(null) }}>{t('ביטול')}</button>
          </div>
          <div class="progress"><div style={{ width: `${Math.max(2, run * 100)}%` }} /></div>
        </div>
      ) : (
        <>
          <h4 class="caption">{t('כמה מדברים בסרטון?')}</h4>
          <div class="chips speaker-count">
            <button class={'chip' + (count === null ? ' on' : '')} onClick={() => setCount(null)}>{t('אוטומטי')}</button>
            {Array.from({ length: MAX_SPEAKERS - 1 }, (_, k) => k + 2).map((n) => (
              <button class={'chip' + (count === n ? ' on' : '')} onClick={() => setCount(n)}><bdi dir="ltr">{n}</bdi></button>
            ))}
          </div>
          <button class="btn tonal speakers-go" onClick={start}>{found.size > 0 ? t('זהה שוב') : t('זהה דוברים')}</button>
          {found.size > 0 && (
            <div class="row wrap speaker-legend">
              {[...found].sort((a, b) => a - b).map((s) => (
                <span class="speaker-tag"><span class="speaker-dot" style={{ background: speakerColor(color, s, chosen) }}>{s}</span>{t('דובר %d', s)}</span>
              ))}
              <button class="btn ghost small-btn" onClick={() => onCues(cues.map(({ speaker, ...c }) => c), true)}>{t('נקה')}</button>
            </div>
          )}
        </>
      )}
      {error && <p class="small" style="margin:0;color:var(--primary)">{error}</p>}
    </section>
  )
}

/** Who says a subtitle: a dot in the speaker's color; a tap moves it to the next speaker. */
export function SpeakerDot({ speaker, color, chosen, max, onChange }: { speaker: number | undefined; color: string; chosen?: string[]; max: number; onChange: (s: number) => void }) {
  const s = Math.max(1, speaker ?? 1)
  return (
    <button class="speaker-dot cue-speaker" style={{ background: speakerColor(color, s, chosen) }} title={t('דובר %d', s)} aria-label={t('דובר %d', s)} onClick={() => onChange(s >= max ? 1 : s + 1)}>
      {s}
    </button>
  )
}
