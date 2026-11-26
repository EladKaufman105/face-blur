import { useEffect, useRef } from 'preact/hooks'
import { t } from '../i18n'
import { MAX_SPEAKERS } from '../lib/speakers'
import type { Cue } from '../lib/subtitles'
import { CloseIcon, CutIcon, DeleteIcon, EditIcon, EndHereIcon, MergeIcon, StartHereIcon } from './icons'
import { SpeakerDot } from './Speakers'

type Props = {
  cue: Cue
  /** Whether there is one after it (to merge with) or before it (the last one merges with the one before). */
  hasNext: boolean
  hasPrev: boolean
  /** Some subtitle has a translation: the translation field shows for each (as the app's), empty where there is none. */
  hasTranslation?: boolean
  /** The properties (text, translation, speaker, times) are shown under the bar; on a phone they open from "Edit text". */
  open: boolean
  onOpen: (open: boolean) => void
  /** A phone shows the properties as a sheet over the editor; a wider screen under the bar. */
  sheet: boolean
  /** The subtitles have speakers found (more than one): the speaker's dot shows. */
  speakers: number
  /** A bleeped word of it is in none of its words' places in the text: it may show. */
  warn?: boolean
  /** Its translation is hidden in the video (it could give a bleeped word away). */
  trHidden?: boolean
  color: string
  chosen?: string[]
  onStart: () => void
  onEnd: () => void
  onSplit: () => void
  onMerge: () => void
  /** Merge with the one before it, or with the one after it (the properties offer both, as the app's subtitle editor). */
  onMergePrev: () => void
  onMergeNext: () => void
  onDelete: () => void
  onText: (text: string) => void
  onTranslation: (tr: string) => void
  onSpeaker: (s: number) => void
  onClose: () => void
}

/** A time as m:ss.s (the decimals tell a retimed edge from the one next to it). */
export function stamp(sec: number): string {
  const s = Math.max(0, sec)
  const m = Math.floor(s / 60)
  return `${m}:${(s - m * 60).toFixed(1).padStart(4, '0')}`
}

type FieldsProps = Pick<Props, 'cue' | 'hasTranslation' | 'speakers' | 'warn' | 'trHidden' | 'color' | 'chosen' | 'onText' | 'onTranslation' | 'onSpeaker' | 'hasPrev' | 'hasNext' | 'onMergePrev' | 'onMergeNext'>

/** The subtitle's properties, as the app's: its text, translation, speaker and times, and merging with the one before or after it. */
function CueFields(p: FieldsProps & { cls: string; children?: any }) {
  const c = p.cue
  return (
    <div class={'cue-props ' + p.cls}>
      <label class="cue-field">
        <span class="small muted">{t('טקסט')}</span>
        <textarea class="cue-text cue-prop-text" dir="auto" rows={2} value={c.text} onInput={(e) => p.onText((e.currentTarget as HTMLTextAreaElement).value)} />
      </label>
      {(c.tr !== undefined || p.hasTranslation) && (
        <label class="cue-field">
          <span class="small muted">{t('תרגום')}</span>
          <textarea class="cue-text cue-prop-tr" dir="auto" rows={2} value={c.tr ?? ''} onInput={(e) => p.onTranslation((e.currentTarget as HTMLTextAreaElement).value)} />
        </label>
      )}
      {p.warn && <p class="small cue-mask-warn" role="alert" style="margin:0;color:var(--primary)">{t('מילה מצופצפת לא נמצאת בטקסט, ולכן הכתובית עלולה להציג אותה. תקן את הטקסט כדי שיופיע בו, או צפצף את המילה שוב.')}</p>}
      {p.trHidden && <p class="small muted cue-tr-hidden" style="margin:0">{t('התרגום לא מוצג בסרטון: הוא עלול להציג מילה מצופצפת. תרגם שוב כדי לקבל תרגום מהטקסט המוסתר.')}</p>}
      <div class="row cue-prop-row">
        {p.speakers > 0 && (
          <>
            <span class="small">{t('דובר')}</span>
            <SpeakerDot speaker={c.speaker} color={p.color} chosen={p.chosen} max={Math.min(MAX_SPEAKERS, p.speakers + 1)} onChange={p.onSpeaker} />
          </>
        )}
        <div class="grow" />
        <strong class="cue-times num" dir="ltr">{stamp(c.start)} – {stamp(c.end)}</strong>
        <span class="small muted num" dir="ltr">{t('%s שנ׳', (c.end - c.start).toFixed(1))}</span>
      </div>
      {p.children}
      <div class="row cue-merge-row">
        <button class="btn ghost small-btn cue-merge-prev" disabled={!p.hasPrev} onClick={p.onMergePrev}>{t('מזג עם הקודמת')}</button>
        <button class="btn ghost small-btn cue-merge-next" disabled={!p.hasNext} onClick={p.onMergeNext}>{t('מזג עם הבאה')}</button>
      </div>
    </div>
  )
}

/**
 * A tablet's side panel with a subtitle selected (as the app's properties panel there): its text, translation, speaker
 * and times, start or end it at the playhead, split it there, merge it with the one before or after, delete it.
 */
export function CueSidePanel(p: Props) {
  return (
    <section class="card cue-side" aria-label={t('כתובית')}>
      <div class="row">
        <h3 class="section-title grow" style="margin:0">{t('כתובית')}</h3>
        <button class="icon-btn" aria-label={t('סגור')} title={t('סגור') + ' (Esc)'} onClick={p.onClose}><CloseIcon size={18} /></button>
      </div>
      <CueFields {...p} cls="in-side">
        <div class="row cue-side-acts">
          <button class="btn tonal small-btn cue-side-start" onClick={p.onStart}>{t('התחלה כאן')}</button>
          <button class="btn tonal small-btn cue-side-end" onClick={p.onEnd}>{t('סוף כאן')}</button>
          <button class="btn tonal small-btn cue-side-split" onClick={p.onSplit}>{t('פצל כאן')}</button>
          <button class="btn ghost small-btn danger cue-side-delete" onClick={p.onDelete}>{t('מחק')}</button>
        </div>
      </CueFields>
    </section>
  )
}

/**
 * The selected subtitle's actions, as the app's bar for a selected subtitle (and its properties): start or end it at the
 * playhead, split it there, merge it with the next one (the last one with the one before), delete it, edit its text;
 * its properties are the text, the translation, the speaker and its times.
 */
export function CueBar(p: Props) {
  const c = p.cue
  const act = (label: string, Icon: (q: { size?: number }) => any, onClick: () => void, cls = '') => (
    <button class={'ctx-act ' + cls} onClick={onClick} title={label}>
      <Icon size={20} />
      <span>{label}</span>
    </button>
  )
  const merge = p.hasNext ? t('מזג עם הבאה') : t('מזג עם הקודמת')
  const props = <CueFields {...p} cls={p.sheet ? 'in-sheet' : ''} />
  // A phone's sheet: closed with Esc as well, and the text field is where the focus goes.
  const field = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!(p.sheet && p.open)) return
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); p.onOpen(false) } }
    addEventListener('keydown', key, true)
    field.current?.querySelector('textarea')?.focus()
    return () => removeEventListener('keydown', key, true)
  }, [p.sheet, p.open])
  return (
    <>
      <div class="ctx-bar cue-bar" role="toolbar" aria-label={t('כתובית')}>
        <span class="ctx-color" style="background:var(--amber)" />
        <div class="ctx-name cue-name">
          <strong class="small">{t('כתובית')}</strong>
          <span class="small muted num cue-bar-times" dir="ltr">{stamp(c.start)} – {stamp(c.end)}</span>
        </div>
        <div class="ctx-acts">
          {act(t('התחלה כאן'), StartHereIcon, p.onStart, 'cue-start')}
          {act(t('סוף כאן'), EndHereIcon, p.onEnd, 'cue-end')}
          {act(t('פצל'), CutIcon, p.onSplit, 'cue-split')}
          {(p.hasNext || p.hasPrev) && act(merge, MergeIcon, p.onMerge, 'cue-merge')}
          {act(t('ערוך טקסט'), EditIcon, () => p.onOpen(!p.open), 'cue-edit')}
          {act(t('מחק'), DeleteIcon, p.onDelete, 'danger cue-delete')}
        </div>
        <button class="icon-btn" aria-label={t('סגור')} title={t('סגור') + ' (Esc)'} onClick={p.onClose}><CloseIcon size={18} /></button>
      </div>
      {p.open && !p.sheet && props}
      {p.open && p.sheet && (
        <div class="sheet-back cue-sheet-back" onClick={(e) => { if (e.target === e.currentTarget) p.onOpen(false) }}>
          <div class="sheet cue-sheet" role="dialog" aria-modal="true" aria-label={t('עריכת כתובית')} ref={field}>
            <div class="row">
              <h3 class="section-title grow" style="margin:0">{t('עריכת כתובית')}</h3>
              <button class="icon-btn" aria-label={t('סגור')} onClick={() => p.onOpen(false)}><CloseIcon size={20} /></button>
            </div>
            <div class="sheet-body">
              {props}
              <div class="row cue-sheet-acts">
                <button class="btn tonal small-btn" onClick={p.onStart}>{t('התחלה כאן')}</button>
                <button class="btn tonal small-btn" onClick={p.onEnd}>{t('סוף כאן')}</button>
              </div>
            </div>
            <div class="row"><span class="grow" /><button class="btn primary" onClick={() => p.onOpen(false)}>{t('סיום')}</button></div>
          </div>
        </div>
      )}
    </>
  )
}
