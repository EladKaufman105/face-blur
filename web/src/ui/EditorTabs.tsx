import { useEffect, useRef, useState } from 'preact/hooks'
import { storageKey } from '../lib/channel'
import { t, duration } from '../i18n'
import { decodeSound, forgetSfx, MAX_IMPORT_SEC, previewSfx, sfxName, SFX_GROUPS, USER_SFX, type SfxClip } from '../lib/sfx'
import { NO_WATERMARK, normalizeWatermark, POSITIONS, type Watermark, type WatermarkLook } from '../lib/watermark'
import { fontFamily, SUB_COLORS, SUB_FONTS } from '../lib/substyle'
import { FontAxes } from './FontAxes'
import { AddIcon, CloseIcon, CutIcon, DeleteIcon, MergeIcon, PlayIcon } from './icons'
import { deleteSound, listSounds, saveSound } from '../lib/projects'
import { LANGUAGES, newCueId, parseSrt, splitCue, toSrt, wordsToCues, type Cue, type SubShow, type SubStyle, type Word } from '../lib/subtitles'
import { SubStyleCard } from './SubStyleCard'
import { canTranslate, guessLang, LANG_NAMES, translateAll } from '../lib/translate'
import { MAX_SPEAKERS } from '../lib/speakers'
import { SpeakerDot, SpeakersCard } from './Speakers'
import { VOICES, type VoiceFx } from '../lib/voice'
import type { SubsMessage, SubsRequest } from '../workers/subs.worker'
import type { Cleanup } from '../lib/cleanup'

/** A row with a title, a line under it and a switch. */
export function SwitchRow({ title, text, on, disabled, onChange, cls }: { title: string; text: string; on: boolean; disabled?: boolean; onChange: (on: boolean) => void; cls?: string }) {
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
  // The sounds imported from the device ("mine", the last group), kept in the browser.
  const MINE = SFX_GROUPS.length
  const [mine, setMine] = useState<string[] | null>(null)
  const [importing, setImporting] = useState(false)
  const [importNote, setImportNote] = useState<string | null>(null)
  const importer = useRef<HTMLInputElement>(null)
  useEffect(() => { listSounds().then(setMine) }, [])
  const importSound = async (f: File) => {
    setImporting(true)
    setImportNote(null)
    try {
      const bytes = await f.arrayBuffer()
      await decodeSound(bytes.slice(0))
      const base = f.name.replace(/\.[^.]+$/, '') || t('צליל')
      const taken = new Set(mine ?? [])
      let name = base
      for (let k = 2; taken.has(name); k++) name = `${base} (${k})`
      await saveSound(name, bytes)
      setMine([...(mine ?? []), name].sort())
    } catch {
      setImportNote(t('לא הצלחתי לקרוא את הקובץ'))
    }
    setImporting(false)
  }
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
          <button class={'chip mine-chip' + (group === MINE ? ' on' : '')} onClick={() => setGroup(MINE)}>{t('שלי')}</button>
        </div>
        <p class="small muted" style="margin:10px 0">
          {group === MINE && mine?.length === 0 ? t('כאן יופיעו צלילים שתייבא מהמכשיר (עד %d שניות כל אחד).', MAX_IMPORT_SEC) : t('הקש על צליל כדי להוסיף אותו במיקום הנוכחי. ▶ כדי לשמוע.')}
        </p>
        <div class="sounds">
          {group < MINE && SFX_GROUPS[group].sounds.map((s) => (
            <div class="sound">
              <button class="play" aria-label={t('השמע')} onClick={() => previewSfx(s.id)}><PlayIcon size={18} /></button>
              <button class="name" onClick={() => add(s.id)}>{t(s.name)}</button>
            </div>
          ))}
          {group === MINE && (mine ?? []).map((name) => (
            <div class="sound mine">
              <button class="play" aria-label={t('השמע')} onClick={() => previewSfx(USER_SFX + name)}><PlayIcon size={18} /></button>
              <button class="name" onClick={() => add(USER_SFX + name)}>{name}</button>
              <button class="icon-btn small-icon" aria-label={t('מחק')} onClick={async () => { await deleteSound(name); forgetSfx(USER_SFX + name); setMine((mine ?? []).filter((x) => x !== name)) }}><CloseIcon size={14} /></button>
            </div>
          ))}
          {group === MINE && (
            <button class="sound import-sound" disabled={importing} onClick={() => importer.current?.click()}>
              <AddIcon size={18} /> {importing ? t('מייבא…') : t('ייבוא צליל')}
            </button>
          )}
        </div>
        {importNote && <p class="small" style="margin:8px 0 0;color:var(--primary)">{importNote}</p>}
        <input ref={importer} type="file" accept="audio/*" hidden onChange={(e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          ;(e.currentTarget as HTMLInputElement).value = ''
          if (f) importSound(f)
        }} />
      </section>
      {clips.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('בסרטון')}</h3>
          <div class="clips">
            {clips.map((c) => (
              <div class="clip">
                <div class="row">
                  <button class="time-btn" onClick={() => onSeek(c.at)} dir="ltr">{duration(c.at)}.{Math.floor((c.at % 1) * 10)}</button>
                  <strong class="grow">{sfxName(c.sound)}</strong>
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
    const w = JSON.parse(localStorage.getItem(storageKey('watermark')) ?? 'null')
    if (w && typeof w.text === 'string') return { ...normalizeWatermark(w), image: null }
  } catch {}
  return NO_WATERMARK
}

const LOOKS: [WatermarkLook, string][] = [['plain', 'רגיל'], ['shadow', 'צל'], ['outline', 'קו מתאר'], ['box', 'רקע']]
const ARROWS: Record<string, string> = { tl: '↖', tc: '↑', tr: '↗', cl: '←', c: '•', cr: '→', bl: '↙', bc: '↓', br: '↘' }

/** A line of text or a logo, where and how it shows. */
export function WatermarkTab({ mark, onChange, template }: { mark: Watermark; onChange: (w: Watermark) => void; /** In a template: not kept as the last used one, and a logo kept whole (a data URL). */ template?: boolean }) {
  const file = useRef<HTMLInputElement>(null)
  const set = (patch: Partial<Watermark>) => {
    const w = { ...mark, ...patch }
    onChange(w)
    if (!template) try { localStorage.setItem(storageKey('watermark'), JSON.stringify({ ...w, image: null })) } catch {}
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
          if (f && template) {
            const r = new FileReader()
            r.onload = () => set({ image: String(r.result) })
            r.readAsDataURL(f)
          } else if (f) set({ image: URL.createObjectURL(f) })
          ;(e.currentTarget as HTMLInputElement).value = ''
        }}
      />
      {!mark.image && (
        <>
          <input class="text-in" type="text" dir="auto" placeholder={t('למשל @שם_הערוץ')} value={mark.text} onInput={(e) => set({ text: (e.currentTarget as HTMLInputElement).value })} />
          <h4 class="caption">{t('פונט')}</h4>
          <div class="chips mark-fonts">
            {SUB_FONTS.map((f) => (
              <button class={'chip' + (mark.font === f.id ? ' on' : '')} style={{ fontFamily: fontFamily({ font: f.id, weight: 0 }) }} onClick={() => set({ font: f.id, weight: 0, round: undefined })}>
                {f.id === 'system' ? t('של המכשיר') : f.label}
              </button>
            ))}
          </div>
          <FontAxes choice={mark} onChange={(weight, round) => set({ weight, round })} />
        </>
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
                {SUB_COLORS.map((c) => (
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
  // How the subtitles are cut (as the app's): by spoken words or by characters, up to whole sentences.
  const [byChars, setByChars] = useState(false)
  const [split, setSplit] = useState<{ words: number; chars: number }>({ words: 0, chars: 0 })
  const regroup = (rule: { words: number; chars: number }) => {
    setSplit(rule)
    onBleeped(new Set())
    onCues(wordsToCues(words, rule.words, rule.chars), true)
  }
  const [lang, setLang] = useState<string | null>(() => { try { return localStorage.getItem(storageKey('subs.lang')) } catch { return null } })
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
                <button class={'chip' + (lang === code ? ' on' : '')} onClick={() => { setLang(code); try { if (code) localStorage.setItem(storageKey('subs.lang'), code); else localStorage.removeItem(storageKey('subs.lang')) } catch {} }}>{t(label)}</button>
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
          <h3 class="section-title">{t('צפצוף מילים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{bleeped.size === 1 ? t('מילה אחת') : bleeped.size > 1 ? t('%d מילים', bleeped.size) : ''}</span></h3>
          <p class="small muted" style="margin:0 0 10px">{t('בחר מילים בטקסט, והן יוחלפו בצפצוף או בשקט.')}</p>
          <div class="actions">
            <button class={'btn ' + (bleepMode ? 'primary' : 'tonal')} onClick={() => setBleepMode(!bleepMode)}>{bleepMode ? t('סיום') : t('בחר מילים')}</button>
            {bleeped.size > 0 && <button class="btn ghost" onClick={() => onBleeped(new Set())}>{t('נקה')}</button>}
          </div>
          <h4 class="caption">{t('במקום המילה')}</h4>
          <div class="seg" style="grid-template-columns:1fr 1fr">
            <button class={tone ? 'on' : ''} onClick={() => onTone(true)}>{t('צפצוף')}</button>
            <button class={!tone ? 'on' : ''} onClick={() => onTone(false)}>{t('שקט')}</button>
          </div>
          <SwitchRow cls="mask-subs" title={t('הסתר אותן גם בכתוביות')} text="****" on={style.maskBleeped !== false} onChange={(on) => onStyle({ ...style, maskBleeped: on })} />
        </section>
      )}
      {cues.length > 0 && <TranslateCard cues={cues} onCues={onCues} show={style.show ?? 'src'} onShow={(v) => set({ show: v })} />}
      {cues.length > 0 && <SpeakersCard file={file} cues={cues} onCues={onCues} color={style.color} chosen={style.speakerColors} />}
      {cues.length > 0 && <SubStyleCard style={style} onStyle={onStyle} />}
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
            <div class="split-cues">
              <div class="row">
                <span class="small grow">{t('חלוקת הכתוביות')}</span>
                <div class="seg seg2 split-by" role="radiogroup">
                  <button class={!byChars ? 'on' : ''} role="radio" aria-checked={!byChars} onClick={() => setByChars(false)}>{t('מילים')}</button>
                  <button class={byChars ? 'on' : ''} role="radio" aria-checked={byChars} onClick={() => setByChars(true)}>{t('תווים')}</button>
                </div>
              </div>
              <div class="chips">
                {(byChars ? [10, 15, 20, 25, 32, 42] : [1, 2, 3, 4, 5, 6, 8]).map((n) => {
                  const rule = byChars ? { words: 0, chars: n } : { words: n, chars: 0 }
                  return <button class={'chip' + (split.words === rule.words && split.chars === rule.chars ? ' on' : '')} onClick={() => regroup(rule)}>{byChars ? t('עד %d', n) : <bdi dir="ltr">{n}</bdi>}</button>
                })}
                <button class={'chip' + (!split.words && !split.chars ? ' on' : '')} onClick={() => regroup({ words: 0, chars: 0 })}>{t('משפט שלם')}</button>
              </div>
            </div>
          )}
          {listNote && <p class="small" style="margin:0;color:var(--primary)">{listNote}</p>}
          <div class="clips">
            {sorted.map((c) => (
              <div class={'clip' + (time >= c.start && time < c.end ? ' now' : '')}>
                <div class="row">
                  <div class="cue-meta">
                    <button class="time-btn" onClick={() => onSeek(c.start + 0.01)} dir="ltr">{duration(c.start)}</button>
                    {speakers > 1 && <SpeakerDot speaker={c.speaker} color={style.color} chosen={style.speakerColors} max={Math.min(MAX_SPEAKERS, speakers + 1)} onChange={(sp) => onCues(cues.map((x) => (x.id === c.id ? { ...x, speaker: sp } : x)), true)} />}
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
