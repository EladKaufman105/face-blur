import { useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { previewSfx, SFX_GROUPS, SFX_NAMES, type SfxClip } from '../lib/sfx'
import { NO_WATERMARK, POSITIONS, type Watermark, type WatermarkLook } from '../lib/watermark'
import { CloseIcon, PlayIcon } from './icons'
import { LANGUAGES, toSrt, wordsToCues, type Cue, type SubLook, type SubPosition, type SubStyle, type Word } from '../lib/subtitles'
import { VOICES, type VoiceFx } from '../lib/voice'
import type { SubsMessage, SubsRequest } from '../workers/subs.worker'

let nextClip = 1

/** The sound effects: pick a group, ▶ to hear, tap to add at the playhead; the added ones below. */
export function SoundTab({ clips, time, onChange, onSeek, voice, onVoice, voiceBusy }: { clips: SfxClip[]; time: number; onChange: (c: SfxClip[]) => void; onSeek: (at: number) => void; voice: VoiceFx; onVoice: (v: VoiceFx) => void; voiceBusy: boolean }) {
  const [group, setGroup] = useState(0)
  const add = (sound: string) => onChange([...clips, { id: nextClip++, sound, at: time, gain: 1 }].sort((a, b) => a.at - b.at))
  const update = (id: number, patch: Partial<SfxClip>) => onChange(clips.map((c) => (c.id === id ? { ...c, ...patch } : c)).sort((a, b) => a.at - b.at))
  return (
    <>
      <section class="card">
        <h3 class="section-title">{t('שינוי קול')}</h3>
        <div class="chips">
          {VOICES.map((v) => (
            <button class={'chip' + (voice === v.id ? ' on' : '')} onClick={() => onVoice(v.id)}>{t(v.label)}</button>
          ))}
        </div>
        <p class="small muted" style="margin:10px 0 0">{voiceBusy ? t('מעבד את הקול…') : t('שינוי קול מקשה לזהות את הדובר, אבל לא מבטיח אנונימיות מלאה.')}</p>
      </section>
      <section class="card">
        <h3 class="section-title">{t('אפקטים קוליים')}</h3>
        <div class="chips">
          {SFX_GROUPS.map((g, k) => (
            <button class={'chip' + (k === group ? ' on' : '')} onClick={() => setGroup(k)}>{t(g.label)}</button>
          ))}
        </div>
        <p class="small muted" style="margin:10px 0">{t('הקש על צליל כדי להוסיף אותו במיקום הנוכחי. ▶ כדי לשמוע.')}</p>
        <div class="sounds">
          {SFX_GROUPS[group].sounds.map((s) => (
            <div class="sound">
              <button class="play" aria-label={t('השמע')} onClick={() => previewSfx(s.id)}><PlayIcon size={18} /></button>
              <button class="name" onClick={() => add(s.id)}>{t(s.name)}</button>
            </div>
          ))}
        </div>
      </section>
      {clips.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('בסרטון')}</h3>
          <div class="clips">
            {clips.map((c) => (
              <div class="clip">
                <div class="row">
                  <button class="time-btn" onClick={() => onSeek(c.at)} dir="ltr">{duration(c.at)}.{Math.floor((c.at % 1) * 10)}</button>
                  <strong class="grow">{t(SFX_NAMES.get(c.sound) ?? c.sound)}</strong>
                  <button class="btn ghost small-btn" onClick={() => update(c.id, { at: time })}>{t('העבר לכאן')}</button>
                  <button class="icon-btn" aria-label={t('מחק')} onClick={() => onChange(clips.filter((x) => x.id !== c.id))}><CloseIcon size={18} /></button>
                </div>
                <div class="row small">
                  <span class="muted">{t('עוצמה')}</span>
                  <input type="range" min={0} max={2} step={0.05} value={c.gain} onInput={(e) => update(c.id, { gain: Number((e.currentTarget as HTMLInputElement).value) })} />
                  <span class="muted" dir="ltr" style="min-width:40px">{Math.round(c.gain * 100)}%</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}

export function loadWatermark(): Watermark {
  try {
    const w = JSON.parse(localStorage.getItem('onit.watermark') ?? 'null')
    if (w && typeof w.text === 'string') return { ...NO_WATERMARK, ...w, image: null }
  } catch {}
  return NO_WATERMARK
}

const COLORS = ['#ffffff', '#000000', '#ff4d6d', '#ffc53d', '#3ddcff', '#8b6cff']
const LOOKS: [WatermarkLook, string][] = [['plain', 'רגיל'], ['shadow', 'צל'], ['outline', 'קו מתאר'], ['box', 'רקע']]
const ARROWS: Record<string, string> = { tl: '↖', tc: '↑', tr: '↗', cl: '←', c: '•', cr: '→', bl: '↙', bc: '↓', br: '↘' }

/** A line of text or a logo, where and how it shows. */
export function WatermarkTab({ mark, onChange }: { mark: Watermark; onChange: (w: Watermark) => void }) {
  const file = useRef<HTMLInputElement>(null)
  const set = (patch: Partial<Watermark>) => {
    const w = { ...mark, ...patch }
    onChange(w)
    try { localStorage.setItem('onit.watermark', JSON.stringify({ ...w, image: null })) } catch {}
  }
  return (
    <section class="card">
      <h3 class="section-title">{t('סימן מים')}</h3>
      <div class="seg" style="grid-template-columns:1fr 1fr">
        <button class={!mark.image ? 'on' : ''} onClick={() => set({ image: null })}>{t('טקסט')}</button>
        <button class={mark.image ? 'on' : ''} onClick={() => file.current?.click()}>{t('לוגו')}</button>
      </div>
      <input
        ref={file}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          if (f) set({ image: URL.createObjectURL(f) })
          ;(e.currentTarget as HTMLInputElement).value = ''
        }}
      />
      {!mark.image && (
        <input class="text-in" type="text" dir="auto" placeholder={t('למשל @שם_הערוץ')} value={mark.text} onInput={(e) => set({ text: (e.currentTarget as HTMLInputElement).value })} />
      )}
      {mark.image && (
        <div class="row" style="margin-top:10px">
          <img src={mark.image} alt="" style="height:44px;max-width:120px;object-fit:contain;border-radius:8px;background:var(--surface-3)" />
          <div class="grow" />
          <button class="btn ghost" onClick={() => file.current?.click()}>{t('החלף תמונה')}</button>
        </div>
      )}
      <div class="mark-grid">
        <div class="positions" dir="ltr">
          {POSITIONS.map((p) => (
            <button class={mark.position === p ? 'on' : ''} aria-label={p} onClick={() => set({ position: p })}>{ARROWS[p]}</button>
          ))}
        </div>
        <div class="grow">
          {!mark.image && (
            <>
              <div class="chips">
                {LOOKS.map(([l, label]) => (
                  <button class={'chip' + (mark.look === l ? ' on' : '')} onClick={() => set({ look: l })}>{t(label)}</button>
                ))}
              </div>
              <div class="swatches">
                {COLORS.map((c) => (
                  <button class={'swatch' + (mark.color === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ color: c })} />
                ))}
              </div>
            </>
          )}
        </div>
      </div>
      <div class="slider-row">
        <label>{t('גודל')}</label>
        <input type="range" min={0.5} max={3} step={0.05} value={mark.size} onInput={(e) => set({ size: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
      <div class="slider-row">
        <label>{t('שקיפות')}</label>
        <input type="range" min={0.2} max={1} step={0.05} value={mark.opacity} onInput={(e) => set({ opacity: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
    </section>
  )
}

const SUB_COLORS = ['#ffffff', '#ffd60a', '#000000', '#ff4d6d', '#3ddcff']

type SubsRun = { stage: 'start' | 'download' | 'transcribe'; progress: number; loaded?: number; total?: number } | { stage: 'error'; message: string } | null

/** Subtitles: made on the device from the speech, then edited, styled and saved as SRT too. */
export function SubtitlesTab({ file, cues, onCues, words, onWords, bleeped, onBleeped, tone, onTone, style, onStyle, time, onSeek }: {
  file: File; cues: Cue[]; onCues: (c: Cue[]) => void; words: Word[]; onWords: (w: Word[]) => void
  bleeped: Set<number>; onBleeped: (b: Set<number>) => void; tone: boolean; onTone: (t: boolean) => void
  style: SubStyle; onStyle: (s: SubStyle) => void; time: number; onSeek: (t: number) => void
}) {
  const [bleepMode, setBleepMode] = useState(false)
  const [lang, setLang] = useState<string | null>(() => { try { return localStorage.getItem('onit.subs.lang') } catch { return null } })
  const [run, setRun] = useState<SubsRun>(null)
  const worker = useRef<Worker | null>(null)
  const start = () => {
    worker.current?.terminate()
    const w = new Worker(new URL('../workers/subs.worker.ts', import.meta.url), { type: 'module' })
    worker.current = w
    setRun({ stage: 'start', progress: 0 })
    w.onmessage = (e: MessageEvent<SubsMessage>) => {
      const m = e.data
      if (m.type === 'download') setRun({ stage: 'download', progress: m.progress, loaded: m.loaded, total: m.total })
      else if (m.type === 'transcribe') setRun({ stage: 'transcribe', progress: m.progress })
      else if (m.type === 'done') {
        onWords(m.words)
        onBleeped(new Set())
        onCues(wordsToCues(m.words))
        setRun(null)
        w.terminate()
      } else {
        setRun({ stage: 'error', message: m.code === 'no-audio' ? t('לסרטון אין פס קול.') : m.message })
        w.terminate()
      }
    }
    w.onerror = (e) => { setRun({ stage: 'error', message: e.message || 'error' }); w.terminate() }
    const req: SubsRequest = { file, language: lang }
    w.postMessage(req)
  }
  const cancel = () => { worker.current?.terminate(); setRun(null) }
  const set = (patch: Partial<SubStyle>) => onStyle({ ...style, ...patch })
  const edit = (id: number, patch: Partial<Cue>) => onCues(cues.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  const srt = () => {
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([toSrt(cues)], { type: 'application/x-subrip' }))
    a.download = file.name.replace(/\.[^.]+$/, '') + '.srt'
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 5000)
  }
  const busy = run !== null && run.stage !== 'error'
  return (
    <>
      <section class="card">
        <h3 class="section-title">{t('כתוביות')}</h3>
        {!busy && cues.length === 0 && (
          <>
            <p class="small muted" style="margin:0 0 10px">{t('שפת הדיבור')}</p>
            <div class="chips">
              {LANGUAGES.map(([code, label]) => (
                <button class={'chip' + (lang === code ? ' on' : '')} onClick={() => { setLang(code); try { if (code) localStorage.setItem('onit.subs.lang', code); else localStorage.removeItem('onit.subs.lang') } catch {} }}>{t(label)}</button>
              ))}
            </div>
            <button class="btn primary" style="width:100%;margin-top:14px" onClick={start}>{t('צור כתוביות')}</button>
            <p class="small muted" style="margin:10px 0 0">{t('הזיהוי רץ על המכשיר עם Whisper. בפעם הראשונה יורד מודל של כ-560MB (נשמר בדפדפן לפעמים הבאות). על מחשב זה מהיר; בטלפון או טאבלט חלש זה עלול לקחת זמן.')}</p>
          </>
        )}
        {busy && run && 'progress' in run && (
          <div style="display:grid;gap:10px">
            <div class="row">
              <strong class="grow">
                {run.stage === 'download' ? t('מוריד את המודל…') : run.stage === 'transcribe' ? t('מזהה דיבור…') : t('מכין את הקול…')}
              </strong>
              <span class="muted small" dir="ltr">
                {run.stage === 'download' && run.total ? `${(run.loaded! / 1e6).toFixed(0)} / ${(run.total / 1e6).toFixed(0)} MB` : `${Math.floor(run.progress * 100)}%`}
              </span>
            </div>
            <div class="progress"><div style={{ width: `${Math.max(2, run.progress * 100)}%` }} /></div>
            <button class="btn tonal" onClick={cancel}>{t('ביטול')}</button>
          </div>
        )}
        {run?.stage === 'error' && (
          <div style="margin-top:10px">
            <p class="small" style="color:var(--primary);margin:0 0 8px">{t('משהו השתבש')}: {run.message}</p>
            <button class="btn tonal" onClick={start}>{t('נסה שוב')}</button>
          </div>
        )}
        {!busy && cues.length > 0 && (
          <div class="actions">
            <button class="btn tonal" onClick={srt}>{t('הורד SRT')}</button>
            <button class="btn ghost" onClick={() => { onCues([]); onWords([]); onBleeped(new Set()) }}>{t('מחק כתוביות')}</button>
          </div>
        )}
      </section>
      {words.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('צפצוף מילים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{bleeped.size > 0 ? t('%d מילים', bleeped.size) : ''}</span></h3>
          <p class="small muted" style="margin:0 0 10px">{t('בחר מילים בטקסט: הן יוחלפו בצפצוף (או בשקט) וב-**** בכתוביות.')}</p>
          <div class="actions">
            <button class={'btn ' + (bleepMode ? 'primary' : 'tonal')} onClick={() => setBleepMode(!bleepMode)}>{bleepMode ? t('סיום') : t('בחר מילים')}</button>
            {bleeped.size > 0 && <button class="btn ghost" onClick={() => onBleeped(new Set())}>{t('נקה')}</button>}
          </div>
          <div class="seg" style="grid-template-columns:1fr 1fr;margin-top:10px">
            <button class={tone ? 'on' : ''} onClick={() => onTone(true)}>{t('צפצוף')}</button>
            <button class={!tone ? 'on' : ''} onClick={() => onTone(false)}>{t('שקט')}</button>
          </div>
        </section>
      )}
      {cues.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('מראה')}</h3>
          <div class="seg">
            {(['bottom', 'middle', 'top'] as SubPosition[]).map((p) => (
              <button class={style.position === p ? 'on' : ''} onClick={() => set({ position: p })}>{t(p === 'bottom' ? 'למטה' : p === 'middle' ? 'באמצע' : 'למעלה')}</button>
            ))}
          </div>
          <div class="chips" style="margin-top:12px">
            {([['box', 'רקע'], ['outline', 'קו מתאר'], ['shadow', 'צל']] as [SubLook, string][]).map(([l, label]) => (
              <button class={'chip' + (style.look === l ? ' on' : '')} onClick={() => set({ look: l })}>{t(label)}</button>
            ))}
          </div>
          <div class="swatches">
            {SUB_COLORS.map((c) => (
              <button class={'swatch' + (style.color === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ color: c })} />
            ))}
          </div>
          <div class="slider-row">
            <label>{t('גודל')}</label>
            <input type="range" min={0.6} max={2} step={0.05} value={style.size} onInput={(e) => set({ size: Number((e.currentTarget as HTMLInputElement).value) })} />
          </div>
        </section>
      )}
      {cues.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('הטקסט')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{cues.length}</span></h3>
          <div class="clips">
            {cues.map((c) => (
              <div class={'clip' + (time >= c.start && time < c.end ? ' now' : '')}>
                <div class="row">
                  <button class="time-btn" onClick={() => onSeek(c.start + 0.01)} dir="ltr">{duration(c.start)}</button>
                  {bleepMode ? (
                    <div class="word-chips" dir="auto">
                      {words.map((w, k) => (w.start >= c.start - 0.01 && w.start < c.end ? (
                        <button class={'word' + (bleeped.has(k) ? ' on' : '')} onClick={() => {
                          const n = new Set(bleeped)
                          if (n.has(k)) n.delete(k)
                          else n.add(k)
                          onBleeped(n)
                        }}>{w.text.trim()}</button>
                      ) : null))}
                    </div>
                  ) : (
                    <textarea class="cue-text" dir="auto" rows={2} value={c.text} onInput={(e) => edit(c.id, { text: (e.currentTarget as HTMLTextAreaElement).value })} />
                  )}
                  <button class="icon-btn" aria-label={t('מחק')} onClick={() => onCues(cues.filter((x) => x.id !== c.id))}><CloseIcon size={18} /></button>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}
