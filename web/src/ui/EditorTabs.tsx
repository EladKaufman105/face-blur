import { useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { previewSfx, SFX_GROUPS, SFX_NAMES, type SfxClip } from '../lib/sfx'
import { NO_WATERMARK, POSITIONS, type Watermark, type WatermarkLook } from '../lib/watermark'
import { CloseIcon, CutIcon, DeleteIcon, MergeIcon, PlayIcon } from './icons'
import { LANGUAGES, newCueId, parseSrt, splitCue, toSrt, wordsToCues, type Cue, type SubLook, type SubPosition, type SubShow, type SubStyle, type Word } from '../lib/subtitles'
import { canTranslate, guessLang, LANG_NAMES, translateAll } from '../lib/translate'
import { MAX_SPEAKERS } from '../lib/speakers'
import { SpeakerDot, SpeakersCard } from './Speakers'
import { VOICES, type VoiceFx } from '../lib/voice'
import type { SubsMessage, SubsRequest } from '../workers/subs.worker'
import type { Cleanup } from '../lib/cleanup'

/** A row with a title, a line under it and a switch. */
function SwitchRow({ title, text, on, disabled, onChange, cls }: { title: string; text: string; on: boolean; disabled?: boolean; onChange: (on: boolean) => void; cls?: string }) {
  return (
    <label class={'row switch-row ' + (cls ?? '')} style={disabled ? 'opacity:0.6' : 'cursor:pointer'}>
      <div class="grow">
        <strong class="small-title">{title}</strong>
        <div class="small muted">{text}</div>
      </div>
      <span class="switch">
        <input type="checkbox" checked={on} disabled={disabled} onChange={(e) => onChange((e.currentTarget as HTMLInputElement).checked)} />
        <span />
      </span>
    </label>
  )
}

/**
 * Cleaning up the sound, as in the app: background noise lowered (light, medium, strong), the
 * voice made clearer, the loudness that social networks expect.
 */
export function CleanupCard({ clean, onClean, loud, loudBusy, onLoud }: { clean: Cleanup; onClean: (c: Cleanup) => void; loud: { on: boolean; gainDb: number | null }; loudBusy: boolean; onLoud: (on: boolean) => void }) {
  const gain = loud.gainDb ?? 0
  return (
    <section class="card cleanup">
      <h3 class="section-title">{t('ניקוי קול')}</h3>
      <strong class="small-title">{t('הסרת רעשי רקע')}</strong>
      <div class="small muted" style="margin-bottom:8px">{t('מזגן, רוח, תנועה, זמזום. נשמע בנגן אחרי עיבוד קצר.')}</div>
      <div class="seg seg4 denoise" role="radiogroup">
        {[t('כבוי'), t('קל'), t('בינוני'), t('חזק')].map((label, k) => (
          <button class={clean.denoise === k ? 'on' : ''} role="radio" aria-checked={clean.denoise === k} onClick={() => onClean({ ...clean, denoise: k })}>{label}</button>
        ))}
      </div>
      <SwitchRow cls="enhance" title={t('שיפור קול')} text={t('קול ברור ואחיד: בלי רעמים נמוכים, מילים חדות יותר, עוצמה מאוזנת')} on={clean.enhance} onChange={(on) => onClean({ ...clean, enhance: on })} />
      <SwitchRow
        cls="loud"
        title={t('עוצמה לרשתות')}
        text={loudBusy ? t('מודד את העוצמה…') : loud.on ? t('מיושר לעוצמה של הרשתות (%s), בלי עיוות', `\u2066${gain >= 0 ? '+' : ''}${gain.toFixed(1)} dB\u2069`) : t('כמו שטיקטוק, אינסטגרם ויוטיוב מצפים')}
        on={loud.on}
        disabled={loudBusy}
        onChange={onLoud}
      />
    </section>
  )
}

let nextClip = 1

/** New sounds get ids after [clips]' (a project opened again). */
export function reserveClipIds(clips: SfxClip[]) {
  for (const c of clips) nextClip = Math.max(nextClip, c.id + 1)
}

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

/**
 * Translating the subtitles on the device (the browser's own translator), and what the video
 * shows: the text, the translation or both.
 */
function TranslateCard({ cues, onCues, show, onShow }: { cues: Cue[]; onCues: (c: Cue[], step?: boolean) => void; show: SubShow; onShow: (s: SubShow) => void }) {
  const from = guessLang(cues.map((c) => c.text).join(' '))
  const [to, setTo] = useState(from === 'en' ? 'he' : 'en')
  const [run, setRun] = useState<{ stage: 'download' | 'translate'; p: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const translated = cues.some((c) => c.tr !== undefined)
  const go = async () => {
    setError(null)
    setRun({ stage: 'translate', p: 0 })
    try {
      const ordered = [...cues].sort((a, b) => a.start - b.start)
      const out = await translateAll(ordered.map((c) => c.text), from, to, (stage, p) => setRun({ stage, p }))
      const byId = new Map(ordered.map((c, k) => [c.id, out[k]]))
      onCues(cues.map((c) => ({ ...c, tr: byId.get(c.id) ?? c.tr })), true)
      if (show === 'src') onShow('both')
    } catch (e) {
      setError(String((e as Error)?.message) === 'unavailable' ? t('הדפדפן לא מתרגם בין השפות האלה.') : t('התרגום נכשל: %s', String((e as Error)?.message ?? e)))
    } finally {
      setRun(null)
    }
  }
  return (
    <section class="card translate">
      <h3 class="section-title">{t('תרגום')}</h3>
      {!canTranslate() ? (
        <p class="small muted translate-unsupported" style="margin:0">{t('תרגום על המכשיר זמין בכרום ובאדג׳ במחשב. בטלפון ובטאבלט אפשר לתרגם באפליקציה לאנדרואיד, או לכתוב את התרגום כאן ביד.')}</p>
      ) : (
        <>
          <div class="row wrap">
            <span class="small">{t('לשפה')}</span>
            {LANG_NAMES.filter(([code]) => code !== from).map(([code, label]) => (
              <button class={'chip' + (to === code ? ' on' : '')} onClick={() => setTo(code)}>{t(label)}</button>
            ))}
          </div>
          {run ? (
            <div class="busy">
              <span class="small">{run.stage === 'download' ? t('מוריד את חבילת השפה… %d%', Math.round(run.p * 100)) : t('מתרגם… %d%', Math.round(run.p * 100))}</span>
              <div class="progress"><div style={{ width: `${Math.max(2, run.p * 100)}%` }} /></div>
            </div>
          ) : (
            <button class="btn tonal translate-go" onClick={go}>{translated ? t('תרגם שוב') : t('תרגם')}</button>
          )}
          {error && <p class="small" style="margin:0;color:var(--primary)">{error}</p>}
        </>
      )}
      <div class="row wrap">
        <button class="btn ghost small-btn translate-manual" onClick={() => onCues(cues.map((c) => ({ ...c, tr: c.tr ?? '' })), true)} hidden={translated}>{t('הוסף תרגום ביד')}</button>
      </div>
      {translated && (
        <>
          <h4 class="caption">{t('בסרטון')}</h4>
          <div class="seg sub-show" role="radiogroup">
            {([['src', 'המקור'], ['tr', 'התרגום'], ['both', 'שניהם']] as [SubShow, string][]).map(([v, label]) => (
              <button class={show === v ? 'on' : ''} role="radio" aria-checked={show === v} onClick={() => onShow(v)}>{t(label)}</button>
            ))}
          </div>
          <button class="btn ghost small-btn" onClick={() => { onCues(cues.map(({ tr, ...c }) => c), true); onShow('src') }}>{t('מחק את התרגום')}</button>
        </>
      )}
    </section>
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
  /** [step]: a change of its own for undo (not merged with the typing around it). */
  file: File; cues: Cue[]; onCues: (c: Cue[], step?: boolean) => void; words: Word[]; onWords: (w: Word[]) => void
  bleeped: Set<number>; onBleeped: (b: Set<number>) => void; tone: boolean; onTone: (t: boolean) => void
  style: SubStyle; onStyle: (s: SubStyle) => void; time: number; onSeek: (t: number) => void
}) {
  const [bleepMode, setBleepMode] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  const [find, setFind] = useState('')
  const [repl, setRepl] = useState('')
  const [findResult, setFindResult] = useState<string | null>(null)
  const [listNote, setListNote] = useState<string | null>(null)
  const [group, setGroup] = useState(0)
  // Where the cursor is in each subtitle's text (for splitting there).
  const caret = useRef(new Map<number, number>())
  const sorted = [...cues].sort((a, b) => a.start - b.start)
  const speakers = cues.reduce((m, c) => Math.max(m, c.speaker ?? 0), 0)
  const replaceAll = () => {
    let n = 0
    const esc = find.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const re = new RegExp(esc, 'g')
    const next = cues.map((c) => {
      const m = c.text.match(re)
      if (!m) return c
      n += m.length
      return { ...c, text: c.text.replace(re, repl) }
    })
    if (n > 0) onCues(next, true)
    setFindResult(n === 0 ? t('לא נמצא') : t('הוחלפו %d', n))
  }
  const addHere = () => {
    if (cues.some((c) => time >= c.start && time < c.end)) { setListNote(t('אין כאן מקום לכתובית חדשה. אפשר לפצל כתובית קיימת.')); return }
    const next = sorted.find((c) => c.start > time)
    const end = Math.min(time + 2, next ? next.start : time + 2)
    if (end - time < 0.3) { setListNote(t('אין כאן מקום לכתובית חדשה. אפשר לפצל כתובית קיימת.')); return }
    setListNote(null)
    onCues([...cues, { id: newCueId(), start: time, end, text: '' }].sort((a, b) => a.start - b.start), true)
  }
  const splitAt = (c: Cue) => {
    const parts = splitCue(c, caret.current.get(c.id) ?? Math.floor(c.text.length / 2), words)
    if (!parts) { setListNote(t('אפשר לפצל רק כתובית עם יותר ממילה אחת')); return }
    setListNote(null)
    onCues(cues.flatMap((x) => (x.id === c.id ? parts : [x])), true)
  }
  const mergeNext = (c: Cue) => {
    const k = sorted.findIndex((x) => x.id === c.id)
    const n = sorted[k + 1]
    if (!n) return
    const tr = c.tr || n.tr ? [c.tr ?? '', n.tr ?? ''].join(' ').trim() : undefined
    onCues(cues.filter((x) => x.id !== n.id).map((x) => (x.id === c.id ? { ...x, end: n.end, text: (x.text + ' ' + n.text).trim(), tr } : x)), true)
  }
  const regroup = (maxWords: number) => {
    setGroup(maxWords)
    onBleeped(new Set())
    onCues(wordsToCues(words, maxWords), true)
  }
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
            <label class="btn ghost srt-import" style="width:100%;margin-top:6px;cursor:pointer">
              {t('או ייבא קובץ SRT')}
              <input type="file" accept=".srt,text/plain,application/x-subrip" hidden onChange={async (e) => {
                const f = (e.currentTarget as HTMLInputElement).files?.[0]
                ;(e.currentTarget as HTMLInputElement).value = ''
                if (!f) return
                const list = parseSrt(await f.text())
                if (list.length) { onWords([]); onBleeped(new Set()); onCues(list, true) }
                else setRun({ stage: 'error', message: t('לא נמצאו כתוביות בקובץ') })
              }} />
            </label>
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
      {cues.length > 0 && <TranslateCard cues={cues} onCues={onCues} show={style.show ?? 'src'} onShow={(v) => set({ show: v })} />}
      {cues.length > 0 && <SpeakersCard file={file} cues={cues} onCues={onCues} color={style.color} />}
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
        <section class="card cue-list">
          <h3 class="section-title">{t('הטקסט')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{cues.length}</span></h3>
          <div class="row wrap">
            <button class="btn tonal small-btn cue-add" onClick={addHere}>{t('הוסף כתובית כאן')}</button>
            <button class={'btn small-btn ' + (findOpen ? 'primary' : 'ghost')} onClick={() => { setFindOpen(!findOpen); setFindResult(null) }}>{t('חפש והחלף')}</button>
          </div>
          {findOpen && (
            <div class="find-row">
              <input class="text-in find-in" dir="auto" placeholder={t('חפש')} value={find} onInput={(e) => { setFind((e.currentTarget as HTMLInputElement).value); setFindResult(null) }} />
              <input class="text-in repl-in" dir="auto" placeholder={t('החלף ב…')} value={repl} onInput={(e) => { setRepl((e.currentTarget as HTMLInputElement).value); setFindResult(null) }} />
              <div class="row">
                <button class="btn tonal small-btn replace-all" disabled={!find} onClick={replaceAll}>{t('החלף הכל')}</button>
                {findResult && <span class="small muted find-result">{findResult}</span>}
              </div>
            </div>
          )}
          {words.length > 0 && (
            <div class="row wrap">
              <span class="small">{t('חלוקת הכתוביות')}</span>
              {[[3, t('עד 3 מילים')], [6, t('עד 6 מילים')], [0, t('משפט שלם')]].map(([n, label]) => (
                <button class={'chip' + (group === n ? ' on' : '')} onClick={() => regroup(n as number)}>{label}</button>
              ))}
            </div>
          )}
          {listNote && <p class="small" style="margin:0;color:var(--primary)">{listNote}</p>}
          <div class="clips">
            {sorted.map((c) => (
              <div class={'clip' + (time >= c.start && time < c.end ? ' now' : '')}>
                <div class="row">
                  <div class="cue-meta">
                    <button class="time-btn" onClick={() => onSeek(c.start + 0.01)} dir="ltr">{duration(c.start)}</button>
                    {speakers > 1 && <SpeakerDot speaker={c.speaker} color={style.color} max={Math.min(MAX_SPEAKERS, speakers + 1)} onChange={(sp) => onCues(cues.map((x) => (x.id === c.id ? { ...x, speaker: sp } : x)), true)} />}
                  </div>
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
                    <div class="cue-fields">
                      <textarea
                        class="cue-text"
                        dir="auto"
                        rows={2}
                        value={c.text}
                        onInput={(e) => { const el = e.currentTarget as HTMLTextAreaElement; caret.current.set(c.id, el.selectionStart); edit(c.id, { text: el.value }) }}
                        onSelect={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                        onClick={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                        onKeyUp={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                      />
                      {c.tr !== undefined && (
                        <textarea class="cue-text cue-tr" dir="auto" rows={2} aria-label={t('תרגום')} placeholder={t('תרגום')} value={c.tr} onInput={(e) => edit(c.id, { tr: (e.currentTarget as HTMLTextAreaElement).value })} />
                      )}
                    </div>
                  )}
                  <div class="cue-acts">
                    <button class="icon-btn cue-split" aria-label={t('פצל במקום הסמן')} title={t('פצל במקום הסמן')} onClick={() => splitAt(c)}><CutIcon size={18} /></button>
                    <button class="icon-btn cue-merge" aria-label={t('חבר עם הבאה')} title={t('חבר עם הבאה')} onClick={() => mergeNext(c)}><MergeIcon size={18} /></button>
                    <button class="icon-btn cue-delete" aria-label={t('מחק')} title={t('מחק')} onClick={() => onCues(cues.filter((x) => x.id !== c.id), true)}><DeleteIcon size={18} /></button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}
