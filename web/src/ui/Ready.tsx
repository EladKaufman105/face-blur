import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { duration, t } from '../i18n'
import { loadLook, saveLook, type BlurMode, type Look } from '../lib/types'
import { loadExportOptions, saveExportOptions, type ExportOptions } from '../lib/exportopts'
import type { FollowMessage, FollowRequest } from '../workers/follow.worker'
import { ExportSheet } from './ExportSheet'
import { BackIcon, FaceIcon } from './icons'
import { TemplatePicker, templateLabel } from './TemplatePicker'
import { useFrame } from './TemplatePreview'
import { loadWatermark } from './EditorTabs'
import { lang } from '../i18n'
import { applyTemplate, currentSettings, exportOptionsOf, withExportOptions, type TemplateSettings } from '../lib/template'
import { findTemplate, loadTemplates } from '../lib/templatestore'

type Props = {
  file: File
  sensitive: boolean
  onSensitive: (v: boolean) => void
  /** Find the faces, then open the editor to check them ([template]: the settings of the template chosen, to apply there). */
  onBlur: (look: Look, template: TemplateSettings | null) => void
  /** Find the faces and save straight away, with these export choices ([template]: the template chosen). */
  onBlurAndSave: (look: Look, options: ExportOptions, template: TemplateSettings | null, templateName: string) => void
  onReplace: (f: File) => void
  onBack: () => void
}

/**
 * A video was picked, as the app's ready screen: the video, one big button, and saving straight
 * away as the other way. The blur's look and wider detection are folded away (the look can
 * change in the editor too); wider detection is the one choice to make before detection runs.
 */
export function Ready({ file, sensitive, onSensitive, onBlur, onBlurAndSave, onReplace, onBack }: Props) {
  const url = useMemo(() => URL.createObjectURL(file), [file])
  useEffect(() => () => URL.revokeObjectURL(url), [url])
  const [meta, setMeta] = useState<{ w: number; h: number; dur: number } | null>(null)
  const [src, setSrc] = useState<{ fps: number; codec: string; bitrate: number; duration: number } | null>(null)
  const [look, setLook] = useState<Look>(loadLook)
  const [open, setOpen] = useState(false)
  const [sheet, setSheet] = useState(false)
  const [options, setOptions] = useState<ExportOptions>(() => ({ ...loadExportOptions(), aspect: 'original' }))
  const replace = useRef<HTMLInputElement>(null)
  useEffect(() => saveLook(look), [look])

  // Templates: one chosen fills in the settings (and brings its processing: subtitles, voice, ...).
  const frame = useFrame(file)
  useEffect(() => { loadTemplates() }, [])
  const baseline = useMemo(() => currentSettings({ sensitive, hebrewUi: lang.value === 'he', watermark: loadWatermark(), options }), [])
  const [tplId, setTplId] = useState<string | null>(null)
  const [tpl, setTpl] = useState<TemplateSettings | null>(null)
  /** The look, wider detection and export choices as they were before a template was chosen. */
  const before = useRef<{ look: Look; sensitive: boolean; options: ExportOptions } | null>(null)
  const pick = (id: string | null) => {
    setTplId(id)
    const t0 = findTemplate(id)
    if (!t0) {
      // No template: the settings go back to what they were.
      if (before.current) { setLook(before.current.look); onSensitive(before.current.sensitive); setOptions(before.current.options) }
      before.current = null
      setTpl(null)
      return
    }
    before.current ??= { look, sensitive, options }
    const b = before.current
    const s = applyTemplate(t0, withExportOptions({ ...baseline, look: b.look, sensitive: b.sensitive }, b.options))
    setLook(s.look)
    onSensitive(s.sensitive)
    setOptions(exportOptionsOf(s, options))
    setTpl(s)
  }
  /** The template's settings with the changes made here since. */
  const current = (): TemplateSettings | null => (tpl ? withExportOptions({ ...tpl, look, sensitive }, options) : null)

  // The frame rate and codec (from the packets, no decoding), for the details and the export's choices.
  useEffect(() => {
    setSrc(null)
    const w = new Worker(new URL('../workers/follow.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<FollowMessage>) => {
      w.terminate()
      if (e.data.type === 'times') setSrc({ fps: e.data.fps, codec: e.data.codec, bitrate: e.data.bitrate, duration: e.data.duration })
    }
    w.onerror = () => w.terminate()
    w.postMessage({ type: 'times', file } satisfies FollowRequest)
    return () => w.terminate()
  }, [file])

  const modeLabel = (m: BlurMode) => (m === 'blur' ? t('טשטוש') : m === 'pixelate' ? t('פיקסלים') : t('כיסוי'))
  const summary = modeLabel(look.mode) + ' · ' + (sensitive ? t('זיהוי מורחב') : t('זיהוי רגיל'))

  return (
    <main class="ready">
      <div class="ready-top">
        <button class="btn ghost ready-back" onClick={onBack}><span class="flip"><BackIcon /></span> {t('חזרה')}</button>
      </div>
      <section class="card video-card">
        <div class="ready-frame">
          <video
            src={url + '#t=0.1'}
            muted
            playsInline
            preload="metadata"
            style={meta ? { aspectRatio: `${meta.w} / ${meta.h}` } : undefined}
            onLoadedMetadata={(e) => {
              const v = e.currentTarget as HTMLVideoElement
              setMeta({ w: v.videoWidth, h: v.videoHeight, dur: v.duration })
            }}
          />
        </div>
        <div class="row">
          <div class="grow">
            <strong class="ready-name">{file.name}</strong>
            <div class="chips info-chips">
              {meta && <span class="info-chip" dir="ltr">{meta.w}×{meta.h}</span>}
              {meta && Number.isFinite(meta.dur) && <span class="info-chip" dir="ltr">{duration(meta.dur)}</span>}
              {src && <span class="info-chip" dir="ltr">{Math.round(src.fps)} fps</span>}
            </div>
          </div>
          <button class="btn ghost replace" onClick={() => replace.current?.click()}>{t('החלף')}</button>
          <input ref={replace} type="file" accept="video/*" hidden onChange={(e) => {
            const f = (e.currentTarget as HTMLInputElement).files?.[0]
            ;(e.currentTarget as HTMLInputElement).value = ''
            if (f) onReplace(f)
          }} />
        </div>
      </section>

      <div class="ready-side">
        <TemplatePicker
          frame={frame}
          base={baseline}
          current={tplId}
          onPick={pick}
          settings={tpl ?? baseline}
          onSettings={(s) => setTpl(s)}
          onEdited={pick}
        />
        <section class="ready-actions">
          <button class="btn primary blur-faces" onClick={() => onBlur(look, current())}><FaceIcon /> {tpl?.blurOff ? t('פתח בעורך') : t('טשטש פנים')}</button>
          <p class="small muted">{tpl?.blurOff ? t('בלי טשטוש פנים: ייפתח העורך, והתבנית תחול על הסרטון.') : t('הפנים יזוהו ויטושטשו, ואז ייפתח העורך לבדיקה ולתיקון לפני השמירה.')}</p>
          <button class="btn ghost blur-save" disabled={!meta} onClick={() => setSheet(true)}>{tpl?.blurOff ? t('או: שמור ישר, בלי עורך') : t('או: טשטש ושמור ישר, בלי עורך')}</button>
          {tpl?.blurOff && <p class="small batch-noblur ready-noblur" role="alert" style="margin:0;color:var(--danger-text);font-weight:700">{t('הפנים לא יטושטשו בסרטון הזה: בתבנית הזו אין טשטוש פנים.')}</p>}
        </section>

        {!tpl?.blurOff && <section class="card fold">
          <button class="fold-head blur-settings" aria-expanded={open} onClick={() => setOpen(!open)}>
            <span class="grow">
              <span class="section-title">{t('הגדרות טשטוש')}</span>
              <span class="small muted fold-summary">{summary}</span>
            </span>
            <span class={'chevron' + (open ? ' up' : '')} aria-hidden="true">⌄</span>
          </button>
          {open && (
            <div class="fold-body">
              <p class="small muted" style="margin:0">{t('אפשר לשנות את המראה גם אחר כך, בעורך.')}</p>
              <h4 class="caption">{t('סגנון')}</h4>
              <div class="seg look-seg" role="radiogroup">
                {(['blur', 'pixelate', 'solid'] as BlurMode[]).map((m) => (
                  <button class={look.mode === m ? 'on' : ''} role="radio" aria-checked={look.mode === m} onClick={() => setLook({ ...look, mode: m })}>{modeLabel(m)}</button>
                ))}
              </div>
              {look.mode !== 'solid' && (
                <div class="slider-row">
                  <label>{t('עוצמה')}</label>
                  <input type="range" class="strength" min={0} max={1} step={0.01} value={look.strength} onInput={(e) => setLook({ ...look, strength: Number((e.currentTarget as HTMLInputElement).value) })} />
                  <div class="range-ends small muted"><span>{t('עדין')}</span><span>{t('חזק')}</span></div>
                </div>
              )}
              <div class="slider-row">
                <label>{t('גודל האזור סביב הפנים')}</label>
                <input type="range" class="padding" min={1.2} max={2} step={0.01} value={look.padding} onInput={(e) => setLook({ ...look, padding: Number((e.currentTarget as HTMLInputElement).value) })} />
                <div class="range-ends small muted"><span>{t('צמוד')}</span><span>{t('רחב')}</span></div>
              </div>
              <label class="row switch-row sensitive">
                <div class="grow">
                  <strong>{t('זיהוי מורחב')}</strong>
                  <div class="small muted" style="margin-top:4px">{t('בודק כל פריים ומטשטש גם פנים שהמערכת פחות בטוחה בהן. מפספס פחות, אבל עלול לטשטש גם דברים מיותרים, ואיטי יותר.')}</div>
                </div>
                <span class="switch">
                  <input type="checkbox" checked={sensitive} onChange={(e) => onSensitive((e.currentTarget as HTMLInputElement).checked)} />
                  <span />
                </span>
              </label>
            </div>
          )}
        </section>}
      </div>

      {sheet && meta && (
        <ExportSheet
          width={meta.w}
          height={meta.h}
          fps={src?.fps ?? null}
          sourceHevc={src?.codec === 'hevc'}
          srcBitrate={src?.bitrate ?? null}
          duration={Number.isFinite(meta.dur) && meta.dur > 0 ? meta.dur : src?.duration ?? 0}
          hasCues={false}
          options={options}
          onChange={setOptions}
          onClose={() => setSheet(false)}
          action={tpl?.blurOff ? t('שמור ישר') : t('טשטש ושמור')}
          onGo={() => {
            setSheet(false)
            saveExportOptions(options)
            onBlurAndSave(look, options, current(), tplId ? templateLabel(findTemplate(tplId)!) : '')
          }}
        />
      )}
    </main>
  )
}

/**
 * No faces were found, as the app's: small, distant or partly hidden faces may need wider
 * detection, or a blur marked by hand (when there's an editor to open).
 */
export function NoFaces({ file, canEdit, sensitive, onEdit, onRetry, onBack }: { file: File; canEdit: boolean; sensitive: boolean; onEdit: () => void; onRetry: () => void; onBack: () => void }) {
  return (
    <main class="ready no-faces">
      <section class="card video-card">
        <div class="ready-frame"><video src={URL.createObjectURL(file) + '#t=0.1'} muted playsInline preload="metadata" /></div>
        <strong class="ready-name">{file.name}</strong>
      </section>
      <section class="card message-card">
        <div class="row">
          <div class="face-icon"><FaceIcon /></div>
          <strong class="grow">{t('לא זוהו פנים בסרטון')}</strong>
        </div>
        <p class="small muted" style="margin:0">{t('אם יש בסרטון פנים קטנות, רחוקות או מוסתרות חלקית, נסה שוב עם זיהוי מורחב, או סמן אותן ידנית.')}</p>
        <div class="actions">
          {canEdit && <button class="btn primary mark-by-hand" onClick={onEdit}>{t('סמן טשטוש ידנית')}</button>}
          {!sensitive && <button class={'btn retry-wide ' + (canEdit ? 'tonal' : 'primary')} onClick={onRetry}>{t('נסה עם זיהוי מורחב')}</button>}
          <button class="btn ghost" onClick={onBack}>{t('חזרה')}</button>
        </div>
      </section>
    </main>
  )
}
