import { useEffect, useRef, useState } from 'preact/hooks'
import { storageKey } from '../lib/channel'
import { lastLogo, readLogo, saveLogo } from '../lib/logostore'
import { t, duration } from '../i18n'
import { decodeSound, forgetSfx, MAX_IMPORT_SEC, previewSfx, sfxName, SFX_GROUPS, USER_SFX, type SfxClip } from '../lib/sfx'
import { NO_WATERMARK, normalizeWatermark, POSITIONS, type Watermark, type WatermarkLook } from '../lib/watermark'
import { fontFamily, SUB_COLORS, SUB_FONTS } from '../lib/substyle'
import { FontAxes } from './FontAxes'
import { AddIcon, CloseIcon, CutIcon, DeleteIcon, MergeIcon, PlayIcon } from './icons'
import { deleteSound, listSounds, saveSound } from '../lib/projects'
import { LANGUAGES, cueWi, isNoise, maskCue, maskWarning, mergeCues, newCueId, orphansOf, parseSrt, unownedIn, splitCue, toSrt, trVisible, wordsToCues, type Cue, type SubShow, type SubStyle, type Word } from '../lib/subtitles'
import { SubStyleCard } from './SubStyleCard'
import { canTranslate, guessLang, LANG_NAMES, translateAll } from '../lib/translate'
import { MAX_SPEAKERS } from '../lib/speakers'
import { SpeakerDot, SpeakersCard } from './Speakers'
import { VOICES, type VoiceFx } from '../lib/voice'
import { currentPlan, grantSpeech, langChoice, mbOf, probeDevice, setLangChoice, SpeechAborted, transcribeFile, type SpeechStatus } from '../lib/speech'
import { QualityChoice, SpeechProgress, useSpeechPlan } from './SpeechUi'
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
export function SoundTab({ clips, time, onChange, onSeek, voice, onVoice, voiceBusy, part }: { part?: 'voice' | 'sfx'; clips: SfxClip[]; time: number; onChange: (c: SfxClip[]) => void; onSeek: (at: number) => void; voice: VoiceFx; onVoice: (v: VoiceFx) => void; voiceBusy: boolean }) {
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
      {(!part || part === 'voice') && <section class="card">
        <h3 class="section-title">{t('שינוי קול')}</h3>
        <div class="chips wrap">
          {VOICES.map((v) => (
            <button class={'chip' + (voice === v.id ? ' on' : '')} onClick={() => onVoice(v.id)}>{t(v.label)}</button>
          ))}
        </div>
        <p class="small muted" style="margin:10px 0 0">{voiceBusy ? t('מעבד את הקול…') : t('שינוי קול מקשה לזהות את הדובר, אבל לא מבטיח אנונימיות מלאה.')}</p>
      </section>}
      {(!part || part === 'sfx') && <section class="card">
        <h3 class="section-title">{t('אפקטים קוליים')}</h3>
        <div class="chips wrap">
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
      </section>}
      {(!part || part === 'sfx') && clips.length > 0 && (
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
function TranslateCard({ cues, words, bleeped, maskOn, onCues, show, onShow, onBusy, onAttention }: { cues: Cue[]; words: Word[]; bleeped: Set<number>; maskOn: boolean; onCues: (c: Cue[], step?: boolean) => void; show: SubShow; onShow: (s: SubShow) => void; onBusy?: (b: boolean) => void; onAttention?: () => void }) {
  const latest = useRef(cues)
  latest.current = cues
  const from = guessLang(cues.map((c) => c.text).join(' '))
  const [to, setTo] = useState(from === 'en' ? 'he' : 'en')
  const [run, setRun] = useState<{ stage: 'download' | 'translate'; p: number } | null>(null)
  useEffect(() => { onBusy?.(run !== null) }, [run !== null])
  // The card going away (the subtitles were deleted, or an undo took them): no busy mark left behind, and the answer is not applied.
  const alive = useRef(true)
  useEffect(() => () => { alive.current = false; onBusy?.(false) }, [])
  const shown = useRef(show)
  shown.current = show
  const [error, setError] = useState<string | null>(null)
  const translated = cues.some((c) => c.tr !== undefined)
  const go = async () => {
    setError(null)
    setRun({ stage: 'translate', p: 0 })
    try {
      const ordered = [...cues].sort((a, b) => a.start - b.start)
      // What is translated is what the video shows (the bleeped words masked), so the translation can't give one away.
      const orph = orphansOf(latest.current, bleeped)
      const sent = ordered.map((c) => (maskOn ? maskCue(c, words, bleeped, orph) : c.text))
      const out = await translateAll(sent, from, to, (stage, p) => setRun({ stage, p }))
      if (!alive.current) return
      const byId = new Map(ordered.map((c, k) => [c.id, [out[k], sent[k]] as [string, string]]))
      onCues(latest.current.map((c) => { const r = byId.get(c.id); return r ? { ...c, tr: r[0], trm: r[1] } : c }), true)
      if (shown.current === 'src') onShow('both')
    } catch (e) {
      onAttention?.()
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
          <button class="btn ghost small-btn" onClick={() => { onCues(cues.map(({ tr, trm, ...c }) => c), true); onShow('src') }}>{t('מחק את התרגום')}</button>
        </>
      )}
    </section>
  )
}

export function loadWatermark(): Watermark {
  try {
    const w = JSON.parse(localStorage.getItem(storageKey('watermark')) ?? 'null')
    // The logo's picture is kept apart (lib/logostore.ts): the choices say whether it is the one in use.
    if (w && typeof w.text === 'string') return { ...normalizeWatermark(w), image: w.logo === true ? lastLogo() : null }
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
    if (!template) {
      // The picture goes to the browser's storage (IndexedDB), the rest of the choices to localStorage.
      if (w.image && w.image !== lastLogo()) void saveLogo(w.image)
      try { localStorage.setItem(storageKey('watermark'), JSON.stringify({ ...w, image: null, logo: !!w.image })) } catch {}
    }
  }
  return (
    <section class="card">
      <h3 class="section-title">{t('סימן מים')}</h3>
      <div class="seg" style="grid-template-columns:1fr 1fr">
        <button class={!mark.image ? 'on' : ''} onClick={() => set({ image: null })}>{t('טקסט')}</button>
        <button class={mark.image ? 'on' : ''} onClick={() => { const last = lastLogo(); if (last && !mark.image) set({ image: last }); else file.current?.click() }}>{t('לוגו')}</button>
      </div>
      <input
        ref={file}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          // A data URL, not an object URL: it is kept with the project and the template, and comes back when the page is opened again.
          if (f) readLogo(f).then((image) => set({ image })).catch(() => {})
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


const FIELD_SIZING = typeof CSS !== 'undefined' && CSS.supports?.('field-sizing', 'content')
/** A subtitle's field shows all its text (CSS does it where it can; elsewhere its height follows the text). */
function fitText(el: HTMLTextAreaElement | null) {
  if (!el || FIELD_SIZING || el.dataset.fit === el.value) return
  el.dataset.fit = el.value
  el.style.height = 'auto'
  el.style.height = el.scrollHeight + 2 + 'px'
}

type SubsRun = SpeechStatus | { stage: 'error'; message: string } | null

/** What the model costs: its size the first time (before anything downloads), or that it is already here. */
function SpeechNote() {
  const { plan, cached } = useSpeechPlan()
  return (
    <p class="small muted speech-note" style="margin:10px 0 0">
      {t('הזיהוי רץ על המכשיר עם Whisper, והקול לא עוזב אותו.')}{' '}
      {plan.model === 'ivrit' && <>{t('מודל העברית של ivrit.ai, כמו באפליקציה.')}{' '}</>}
      {cached ? t('המודל כבר במכשיר.') : t('בפעם הראשונה יורד מודל של כ-%d MB (נשמר בדפדפן לפעמים הבאות).', mbOf(plan))}
    </p>
  )
}

/** Subtitles: made on the device from the speech, then edited, styled and saved as SRT too. */
/** Bleeped words that the new subtitles had no word for, with the words and bleeps they were found for (a later change makes them stale). */
export type LostBleeps = { n: number; words: Word[]; bleeped: Set<number> }

export function SubtitlesTab({ srtCues, onLost, onAttention, onBusy, hidden, part, stylePart, onPart, file, cues, onCues, words, onReplace, bleeped, onBleeped, tone, onTone, style, onStyle, time, onSeek }: {
  /** On a phone: the sub-tool shown ('make', 'list' or 'style'); on a wide screen all of them. */
  part?: 'make' | 'list' | 'bleep' | 'style'; stylePart?: string; onPart?: (p: 'list') => void
  /** [step]: a change of its own for undo (not merged with the typing around it). */
  file: File; cues: Cue[]; onCues: (c: Cue[], step?: boolean) => void; words: Word[]
  /** The subtitles, their words and the bleeped words replaced together, as one undo step. */
  onReplace: (c: Cue[], w: Word[], b: Set<number>) => void
  bleeped: Set<number>; onBleeped: (b: Set<number>) => void; tone: boolean; onTone: (t: boolean) => void
  style: SubStyle; onStyle: (s: SubStyle) => void; time: number; onSeek: (t: number) => void
  /** The subtitles as the export shows them (bleeped words hidden, times after the cuts): what "Save subtitles (SRT)" writes. */
  srtCues: () => Cue[]
  /** Something here is running (speech, speakers, translation): the editor keeps this tab mounted while it does, and marks its group. */
  onBusy?: (b: boolean) => void
  /** Another group is open: the tab stays mounted for its jobs, and does not move the video. */
  hidden?: boolean
  /** Bleeped words the new subtitles did not have (the editor shows them in every group). */
  onLost: (l: LostBleeps | null) => void
  /** An error arrived while the tab is hidden: the editor marks the group and brings the make tool up. */
  onAttention?: () => void
}) {
  const attention = () => { if (isHidden.current) onAttention?.() }
  const isHidden = useRef(!!hidden)
  isHidden.current = !!hidden
  const styleNow = useRef(style)
  styleNow.current = style
  const [kids, setKids] = useState({ spk: false, tr: false })
  const setKid = (k: 'spk' | 'tr', b: boolean) => setKids((o) => (o[k] === b ? o : { ...o, [k]: b }))
  const show = (k: 'make' | 'list' | 'bleep' | 'style') => !part || part === k
  const [bleepMode, setBleepMode] = useState(false)
  // On a phone the word picking belongs to the bleep tool: the list shows plain text again when it is left.
  useEffect(() => { if (part && part !== 'bleep') setBleepMode(false) }, [part])
  const bleeping = bleepMode && (!part || part === 'bleep')
  const [findOpen, setFindOpen] = useState(false)
  const [find, setFind] = useState('')
  const [repl, setRepl] = useState('')
  const [findResult, setFindResult] = useState<string | null>(null)
  const [listNote, setListNote] = useState<string | null>(null)
  // Where the cursor is in each subtitle's text (for splitting there).
  const caret = useRef(new Map<number, number>())
  const sorted = [...cues].sort((a, b) => a.start - b.start)
  // Bleeped words that no subtitle has any more (its subtitle was deleted): they stay bleeped in the sound until removed.
  const orphans = [...bleeped].filter((k) => !cues.some((c) => cueWi(c).includes(k)))
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
    onCues([...cues, { id: newCueId(), start: time, end, text: '', wi: unownedIn(cues, words, time, end) }].sort((a, b) => a.start - b.start), true)
  }
  const splitAt = (c: Cue) => {
    const parts = splitCue(c, caret.current.get(c.id) ?? Math.floor(c.text.length / 2), words, bleeped, orphans)
    if (!parts) { setListNote(t('אי אפשר לפצל כאן: הכתובית קצרה מדי, יש בה מילה אחת, או שמילה מוסתרת לא תישאר מוסתרת')); return }
    setListNote(null)
    onCues(cues.flatMap((x) => (x.id === c.id ? parts : [x])), true)
  }
  const mergeNext = (c: Cue) => {
    const k = sorted.findIndex((x) => x.id === c.id)
    const n = sorted[k + 1]
    if (!n) return
    onCues(cues.filter((x) => x.id !== n.id).map((x) => (x.id === c.id ? mergeCues(x, n) : x)), true)
  }
  // How the subtitles are cut (as the app's): by spoken words or by characters, up to whole sentences.
  const [byChars, setByChars] = useState(false)
  const [split, setSplit] = useState<{ words: number; chars: number }>({ words: 0, chars: 0 })
  const regroup = (rule: { words: number; chars: number }) => {
    setSplit(rule)
    // The words stay the same, so the bleeped words stay bleeped.
    onReplace(wordsToCues(words, rule.words, rule.chars), words, bleeped)
  }
  const lang = langChoice.value
  const setLang = setLangChoice
  const [run, setRun] = useState<SubsRun>(null)
  const ctl = useRef<AbortController | null>(null)
  // The words and bleeps as they are now (undo or a tap may change them while the speech model runs).
  const live = useRef({ words, bleeped })
  live.current = { words, bleeped }
  // Bleeped words that the new subtitles had no word for are reported to the editor (onLost), which shows them in an alert
  // in every group and in the export sheet, while those subtitles and bleeps are the current ones, until dismissed.
  // The speech worker goes when the tab is left (it must not go on heating the device unseen).
  useEffect(() => () => ctl.current?.abort(), [])
  const start = async () => {
    ctl.current?.abort()
    setImportErr(null)
    setPendingImport(null)
    onLost(null)
    const c = new AbortController()
    ctl.current = c
    setRun({ stage: 'audio', progress: 0 })
    try {
      const d = await probeDevice()
      const plan = currentPlan(d, lang)
      // Pressing "create subtitles" is the go-ahead: the size was shown above the button.
      grantSpeech(plan)
      const r = await transcribeFile(file, lang, { plan, onStatus: (s) => { if (ctl.current === c) setRun(s) }, signal: c.signal })
      if (ctl.current !== c) return
      const now = live.current
      const old = [...now.bleeped].map((i) => now.words[i]).filter((w): w is Word => !!w)
      const made = wordsToCues(r.words)
      // Nothing recognized (or only noise): the old words and their bleeps stay as they are.
      if (!made.length) { setRun(null); return }
      // Bleeped words carry over to the new words at the same time: they must share at least half of the
      // shorter word (words touching at an edge do not count). A word with no length (start = end)
      // counts when it sits inside the other word, give or take a little.
      const eps = 0.05
      const shared = (a: Word, b: Word) => Math.max(0, Math.min(a.end, b.end) - Math.max(a.start, b.start))
      const hits = (o: Word, w: Word) => {
        const lo = Math.min(o.end - o.start, w.end - w.start)
        if (lo <= 0.001) return w.start <= o.end + eps && w.end >= o.start - eps
        return shared(o, w) >= lo / 2
      }
      const kept = new Set<number>()
      r.words.forEach((w, i) => { if (!isNoise(w.text) && old.some((o) => hits(o, w))) kept.add(i) })
      // An old bleep is lost when the new bleeped words cover less than half of its time (it would be
      // heard). Undo brings the old ones back, and the notice says so.
      const covered = (o: Word) => {
        const len = o.end - o.start
        const words = [...kept].map((i) => r.words[i])
        if (len <= 0.001) return words.some((w) => hits(o, w))
        return words.reduce((sum, w) => sum + shared(o, w), 0) >= len / 2
      }
      const n = old.filter((o) => !covered(o)).length
      onReplace(made, r.words, kept)
      onLost(n ? { n, words: r.words, bleeped: kept } : null)
      if (made.length && !isHidden.current) onSeek(Math.min(...made.map((c) => c.start)) + 0.01)
      setRun(null)
    } catch (e) {
      if (e instanceof SpeechAborted || ctl.current !== c) return
      attention()
      setRun({ stage: 'error', message: (e as any)?.code === 'no-audio' ? t('לסרטון אין פס קול.') : String((e as Error)?.message ?? e) })
    }
  }
  /** Cancel ends the worker on the spot. */
  const cancel = () => { ctl.current?.abort(); ctl.current = null; setRun(null) }
  const set = (patch: Partial<SubStyle>) => onStyle({ ...styleNow.current, ...patch })
  const edit = (id: number, patch: Partial<Cue>) => onCues(cues.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  // The file has every word as typed: with bleeped words it asks first.
  const [srtAsk, setSrtAsk] = useState<false | 'edit' | 'shown'>(false)
  const saveSrt = (list: Cue[]) => {
    setSrtAsk(false)
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([toSrt(list)], { type: 'application/x-subrip' }))
    a.download = file.name.replace(/\.[^.]+$/, '') + '.srt'
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 5000)
  }
  /** The main action: the file as the export has it, the bleeped words hidden. */
  /** Hiding the bleeped words is off: the file shows them, so it asks first (as the app's srtShowsBleeps). */
  const srtShowsBleeps = style.maskBleeped === false && bleeped.size > 0
  const srtMasked = () => (srtShowsBleeps ? setSrtAsk('shown') : saveSrt(srtCues()))
  /** For editing: every word as typed (asks first when words are bleeped). */
  const srt = () => saveSrt(cues)
  const busy = run !== null && run.stage !== 'error'
  const anyBusy = busy || kids.spk || kids.tr
  useEffect(() => { onBusy?.(anyBusy) }, [anyBusy])
  // The subtitle being edited (its actions show; the video shows it too).
  const [openId, setOpenId] = useState<number | null>(null)
  // An SRT replaces the subtitles: with some there (or bleeped words, which go with them) it asks first.
  const [pendingImport, setPendingImport] = useState<Cue[] | null>(null)
  const [importErr, setImportErr] = useState<string | null>(null)
  const applyImport = (list: Cue[]) => {
    setPendingImport(null)
    setOpenId(null)
    setBleepMode(false)
    onReplace(list, [], new Set())
    onSeek(Math.min(...list.map((c) => c.start)) + 0.01)
    onPart?.('list')
  }
  const importSrt = busy ? null : (
    <>
      <label class="btn tonal srt-import">
        {t('ייבוא קובץ SRT')}
        <input type="file" accept=".srt,text/plain,application/x-subrip" hidden onChange={async (e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          ;(e.currentTarget as HTMLInputElement).value = ''
          if (!f) return
          setImportErr(null)
          setPendingImport(null)
          const list = parseSrt(await f.text())
          if (!list.length) { setImportErr(t('לא נמצאו כתוביות בקובץ')); return }
          if (cues.length > 0 || bleeped.size > 0) setPendingImport(list)
          else applyImport(list)
        }} />
      </label>
      {importErr && <p class="small srt-error" role="alert" style="margin:0;color:var(--primary)">{importErr}</p>}
      {pendingImport && (
        <div class="confirm srt-confirm" role="alertdialog" aria-label={t('להחליף את הכתוביות?')}>
          <strong class="small">{t('להחליף את הכתוביות?')}</strong>
          <span class="small">
            {bleeped.size > 0
              ? t('הכתוביות הקיימות יוחלפו בקובץ, והצפצופים (%d) יוסרו. אפשר לבטל את הפעולה.', bleeped.size)
              : t('הכתוביות הקיימות יוחלפו בקובץ. אפשר לבטל את הפעולה.')}
          </span>
          <div class="row">
            <button class="btn small-btn danger-btn srt-confirm-yes" onClick={() => applyImport(pendingImport)}>{t('החלף')}</button>
            <button class="btn ghost small-btn srt-confirm-no" onClick={() => setPendingImport(null)}>{t('השאר את הקיימות')}</button>
          </div>
        </div>
      )}
    </>
  )
  // Saving the subtitles as an SRT file: always in view with the list, not folded away. The main action is the file as exported (bleeped words hidden).
  const srtBlock = (
    <div class="srt-tools" style="display:grid;gap:8px">
      <div class="row wrap">
        <button class="btn tonal small-btn srt-save" onClick={srtMasked} title={srtShowsBleeps ? t('ההסתרה כבויה: הקובץ יכלול את המילים המצופצפות כפי שהן') : t('הקובץ כמו בייצוא: המילים המצופצפות מוסתרות')}>{srtShowsBleeps ? t('שמור כתוביות (SRT, בלי הסתרה)') : t('שמור כתוביות (SRT)')}</button>
        {bleeped.size > 0 && !srtShowsBleeps && <button class="btn ghost small-btn srt-download" onClick={() => setSrtAsk('edit')} title={t('הקובץ כולל את כל המילים, בלי הסתרה: לעריכה')}>{t('הורד SRT')} <span class="small muted">{t('(בלי הסתרה, לעריכה)')}</span></button>}
      </div>
    {srtAsk && (
      <div class="confirm srt-ask" role="alertdialog" aria-label={t('לשמור SRT בלי הסתרה?')}>
        <span class="small">{t('קובץ ה-SRT יכלול את המילים המצופצפות כפי שהן. אל תפרסם אותו.')}</span>
        <div class="row">
          <button class="btn small-btn danger-btn srt-ask-yes" onClick={srtAsk === 'shown' ? () => saveSrt(srtCues()) : srt}>{t('שמור')}</button>
          <button class="btn ghost small-btn srt-ask-no" onClick={() => setSrtAsk(false)}>{t('ביטול')}</button>
        </div>
      </div>
    )}
    </div>
  )
  // What used to be folded away as "more options". On a phone each piece sits in the sub-tool it
  // belongs to (making and translating, the list's tools); on a wide screen they share one fold,
  // which shows only when it has something in it.
  const findBlock = cues.length > 0 && (
    <>
      <div class="row wrap">
        <button class={'btn small-btn ' + (findOpen ? 'primary' : 'tonal')} onClick={() => { setFindOpen(!findOpen); setFindResult(null) }}>{t('חפש והחלף')}</button>
        {!part && importSrt}
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
          <div class="chips scroll-row">
            {(byChars ? [10, 15, 20, 25, 32, 42] : [1, 2, 3, 4, 5, 6, 8]).map((n) => {
              const rule = byChars ? { words: 0, chars: n } : { words: n, chars: 0 }
              return <button class={'chip' + (split.words === rule.words && split.chars === rule.chars ? ' on' : '')} onClick={() => regroup(rule)}>{byChars ? t('עד %d', n) : <bdi dir="ltr">{n}</bdi>}</button>
            })}
            <button class={'chip' + (!split.words && !split.chars ? ' on' : '')} onClick={() => regroup({ words: 0, chars: 0 })}>{t('משפט שלם')}</button>
          </div>
        </div>
      )}
    </>
  )
  const makeBlock = cues.length === 0 ? (!busy && (
    <>
      <p class="small muted" style="margin:0">{t('איכות הזיהוי')}</p>
      <QualityChoice />
      {importSrt}
    </>
  )) : (
    <>
      {part && importSrt}
      <TranslateCard cues={cues} words={words} bleeped={bleeped} maskOn={style.maskBleeped !== false} onCues={onCues} show={style.show ?? 'src'} onShow={(v) => set({ show: v })} onBusy={(b) => setKid('tr', b)} onAttention={attention} />
      <SpeakersCard file={file} cues={cues} onCues={onCues} color={style.color} chosen={style.speakerColors} onBusy={(b) => setKid('spk', b)} onAttention={attention} />
      <button class="btn ghost" onClick={() => { onReplace([], words, bleeped); setOpenId(null); setBleepMode(false); setPendingImport(null) }}>{t('מחק כתוביות')}</button>
    </>
  )
  const more = part ? (
    <>
      {part === 'make' && cues.length === 0 && makeBlock && <section class="card subs-make-more">{makeBlock}</section>}
      {/* Kept (hidden) while another tool is open: a running translation or speaker search goes on. */}
      {cues.length > 0 && <div class="subs-make-more" hidden={part !== 'make'}>{makeBlock}</div>}
      {part === 'list' && cues.length > 0 && <section class="card subs-list-tools">{findBlock}</section>}
    </>
  ) : (cues.length > 0 || makeBlock) ? (
    <details class="card subs-more">
      <summary>{t('עוד אפשרויות')}</summary>
      <div class="subs-more-body">
        {cues.length === 0 ? makeBlock : <>{findBlock}{makeBlock}</>}
      </div>
    </details>
  ) : null
  return (
    <>
      {show('make') && cues.length === 0 && (
        <section class="card subs-start">
          <h3 class="section-title">{t('כתוביות')}</h3>
          {!busy && (
            <>
              <p class="small muted" style="margin:0 0 8px">{t('שפת הדיבור')}</p>
              <div class="chips scroll-row lang-chips">
                {LANGUAGES.map(([code, label]) => (
                  <button class={'chip' + (lang === code ? ' on' : '')} onClick={() => setLang(code)}>{t(label)}</button>
                ))}
              </div>
              {bleeped.size > 0 && (
                <p class="small" style="margin:12px 0 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                  {bleeped.size === 1 ? t('מילה אחת מצופצפת. בכתוביות חדשות יצופצפו המילים שבאותו זמן.') : t('%d מילים מצופצפות. בכתוביות חדשות יצופצפו המילים שבאותו זמן.', bleeped.size)}
                  <button class="btn ghost" onClick={() => onBleeped(new Set())}>{t('נקה')}</button>
                </p>
              )}
              <button class="btn primary subs-create" style="width:100%;margin-top:14px" onClick={start}>{t('צור כתוביות')}</button>
              <SpeechNote />
            </>
          )}
          {busy && run && <SpeechProgress status={run} onCancel={cancel} />}
          {run?.stage === 'error' && (
            <div style="margin-top:10px">
              <p class="small" style="color:var(--primary);margin:0 0 8px">{t('משהו השתבש')}: {run.message}</p>
              <button class="btn tonal" onClick={start}>{t('נסה שוב')}</button>
            </div>
          )}
        </section>
      )}
      {show('make') && cues.length > 0 && busy && run && <section class="card"><SpeechProgress status={run} onCancel={cancel} /></section>}
      {/* The looks, with a sample of each, show at once (also before the subtitles are made). */}
      {show('style') && <SubStyleCard file={file} style={style} onStyle={onStyle} part={part ? stylePart ?? 'looks' : undefined} />}
      {show('bleep') && cues.length > 0 && words.length > 0 && (
                <section class="card bleep">
                  <h3 class="section-title keep">{t('צפצוף מילים')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{bleeped.size === 1 ? t('מילה אחת') : bleeped.size > 1 ? t('%d מילים', bleeped.size) : ''}</span></h3>
                  <p class="small muted" style="margin:0 0 10px">{t('בחר מילים בטקסט, והן יוחלפו בצפצוף או בשקט.')}</p>
                  <div class="actions">
                    <button class={'btn ' + (bleepMode ? 'primary' : 'tonal')} onClick={() => setBleepMode(!bleepMode)}>{bleepMode ? t('סיום') : t('בחר מילים')}</button>
                    {bleeped.size > 0 && <button class="btn ghost" onClick={() => onBleeped(new Set())}>{t('נקה')}</button>}
                  </div>
                  {orphans.length > 0 && (
                    <p class="small orphan-bleeps" style="margin:8px 0 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                      {orphans.length === 1 ? t('מילה מצופצפת אחת היא בלי כתובית, והיא נשארת מצופצפת בסאונד.') : t('%d מילים מצופצפות הן בלי כתובית, והן נשארות מצופצפות בסאונד.', orphans.length)}
                      <button class="btn ghost small-btn orphan-clear" onClick={() => onBleeped(new Set([...bleeped].filter((k) => !orphans.includes(k))))}>{t('הסר את הצפצופים האלה')}</button>
                    </p>
                  )}
                  <h4 class="caption">{t('במקום המילה')}</h4>
                  <div class="seg" style="grid-template-columns:1fr 1fr">
                    <button class={tone ? 'on' : ''} onClick={() => onTone(true)}>{t('צפצוף')}</button>
                    <button class={!tone ? 'on' : ''} onClick={() => onTone(false)}>{t('שקט')}</button>
                  </div>
                  <SwitchRow cls="mask-subs" title={t('הסתר אותן גם בכתוביות')} text="****" on={style.maskBleeped !== false} onChange={(on) => onStyle({ ...style, maskBleeped: on })} />
                </section>
              )}
      {(show('list') || part === 'bleep') && cues.length > 0 && (
        <section class="card cue-list">
          <h3 class="section-title keep">{t('הטקסט')} <span class="small muted" style="font-weight:500;margin-inline-start:auto">{cues.length}</span></h3>
          <p class="small muted" style="margin:0">{t('הקש על כתובית כדי לערוך אותה.')}</p>
          {srtBlock}
          {listNote && <p class="small" style="margin:0;color:var(--primary)">{listNote}</p>}
          <div class="clips">
            {sorted.map((c) => {
              const isOpen = openId === c.id
              const maskOn = style.maskBleeped !== false
              const warn = maskOn && maskWarning(c, words, bleeped, orphans)
              const trHidden = maskOn && !!c.tr && !trVisible(c, maskCue(c, words, bleeped, orphans))
              return (
              <div class={'clip' + (time >= c.start && time < c.end ? ' now' : '') + (isOpen ? ' open' : '')}>
                <div class="row">
                  <div class="cue-meta">
                    <button class="time-btn" onClick={() => onSeek(c.start + 0.01)} dir="ltr">{duration(c.start)}</button>
                    {speakers > 1 && <SpeakerDot speaker={c.speaker} color={style.color} chosen={style.speakerColors} max={Math.min(MAX_SPEAKERS, speakers + 1)} onChange={(sp) => onCues(cues.map((x) => (x.id === c.id ? { ...x, speaker: sp } : x)), true)} />}
                  </div>
                  {bleeping && words.length > 0 ? (
                    <div class="word-chips" dir="auto">
                      {cueWi(c).map((k) => (words[k] ? (
                        <button class={'word' + (bleeped.has(k) ? ' on' : '')} onClick={() => {
                          const n = new Set(bleeped)
                          if (n.has(k)) n.delete(k)
                          else n.add(k)
                          onBleeped(n)
                        }}>{words[k].text.trim()}</button>
                      ) : null))}
                    </div>
                  ) : (
                    <div class="cue-fields">
                      <textarea
                        class="cue-text"
                        dir="auto"
                        rows={isOpen ? 2 : 1}
                        value={c.text}
                        onFocus={(e) => {
                          const el = e.currentTarget as HTMLTextAreaElement
                          if (openId !== c.id) { setOpenId(c.id); if (!(time >= c.start && time < c.end)) onSeek(c.start + 0.01) }
                          // Room above the export bar and the keyboard: the subtitle in the middle of what shows.
                          setTimeout(() => el.scrollIntoView({ block: 'center', behavior: 'smooth' }), 300)
                        }}
                        ref={fitText}
                        onInput={(e) => { const el = e.currentTarget as HTMLTextAreaElement; caret.current.set(c.id, el.selectionStart); fitText(el); edit(c.id, { text: el.value }) }}
                        onSelect={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                        onClick={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                        onKeyUp={(e) => caret.current.set(c.id, (e.currentTarget as HTMLTextAreaElement).selectionStart)}
                      />
                      {c.tr !== undefined && (
                        <textarea class="cue-text cue-tr" dir="auto" rows={isOpen ? 2 : 1} aria-label={t('תרגום')} placeholder={t('תרגום')} value={c.tr} ref={fitText} onFocus={() => setOpenId(c.id)} onInput={(e) => { fitText(e.currentTarget as HTMLTextAreaElement); edit(c.id, { tr: (e.currentTarget as HTMLTextAreaElement).value }) }} />
                      )}
                    </div>
                  )}
                </div>
                {warn && <p class="small cue-mask-warn" role="alert" style="margin:4px 0 0;color:var(--primary)">{t('מילה מצופצפת לא נמצאת בטקסט, ולכן הכתובית עלולה להציג אותה. תקן את הטקסט כדי שיופיע בו, או צפצף את המילה שוב.')}</p>}
                {trHidden && <p class="small muted cue-tr-hidden" style="margin:4px 0 0">{t('התרגום לא מוצג בסרטון: הוא עלול להציג מילה מצופצפת. תרגם שוב כדי לקבל תרגום מהטקסט המוסתר.')}</p>}
                {isOpen && !bleeping && (
                  <div class="cue-acts">
                    <button class="btn tonal small-btn cue-split" onClick={() => splitAt(c)}><CutIcon size={18} /> {t('פצל כאן')}</button>
                    <button class="btn tonal small-btn cue-merge" onClick={() => mergeNext(c)}><MergeIcon size={18} /> {t('חבר עם הבאה')}</button>
                    <button class="btn ghost small-btn cue-delete" onClick={() => { setOpenId(null); onCues(cues.filter((x) => x.id !== c.id), true) }}><DeleteIcon size={18} /> {t('מחק')}</button>
                  </div>
                )}
              </div>
              )
            })}
          </div>
          <button class="btn tonal small-btn cue-add" onClick={addHere}>{t('הוסף כתובית כאן')}</button>
        </section>
      )}
      {more}
    </>
  )
}
