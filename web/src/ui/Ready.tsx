import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { duration, t } from '../i18n'
import { loadLook, saveLook, type BlurMode, type Look } from '../lib/types'
import { loadExportOptions, saveExportOptions, type ExportOptions } from '../lib/exportopts'
import type { FollowMessage, FollowRequest } from '../workers/follow.worker'
import { ExportSheet } from './ExportSheet'
import { BackIcon, FaceIcon } from './icons'

type Props = {
  file: File
  sensitive: boolean
  onSensitive: (v: boolean) => void
  /** Find the faces, then open the editor to check them. */
  onBlur: (look: Look) => void
  /** Find the faces and save straight away, with these export choices. */
  onBlurAndSave: (look: Look, options: ExportOptions) => void
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
  const [src, setSrc] = useState<{ fps: number; codec: string } | null>(null)
  const [look, setLook] = useState<Look>(loadLook)
  const [open, setOpen] = useState(false)
  const [sheet, setSheet] = useState(false)
  const [options, setOptions] = useState<ExportOptions>(() => ({ ...loadExportOptions(), aspect: 'original' }))
  const replace = useRef<HTMLInputElement>(null)
  useEffect(() => saveLook(look), [look])

  // The frame rate and codec (from the packets, no decoding), for the details and the export's choices.
  useEffect(() => {
    setSrc(null)
    const w = new Worker(new URL('../workers/follow.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<FollowMessage>) => {
      w.terminate()
      if (e.data.type === 'times') setSrc({ fps: e.data.fps, codec: e.data.codec })
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
        <section class="ready-actions">
          <button class="btn primary blur-faces" onClick={() => onBlur(look)}><FaceIcon /> {t('טשטש פנים')}</button>
          <p class="small muted">{t('הפנים יזוהו ויטושטשו, ואז ייפתח העורך לבדיקה ולתיקון לפני השמירה.')}</p>
          <button class="btn ghost blur-save" disabled={!meta} onClick={() => setSheet(true)}>{t('או: טשטש ושמור ישר, בלי עורך')}</button>
        </section>

        <section class="card fold">
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
        </section>
      </div>

      {sheet && meta && (
        <ExportSheet
          width={meta.w}
          height={meta.h}
          fps={src?.fps ?? null}
          sourceHevc={src?.codec === 'hevc'}
          hasCues={false}
          options={options}
          onChange={setOptions}
          onClose={() => setSheet(false)}
          action={t('טשטש ושמור')}
          onGo={() => {
            setSheet(false)
            saveExportOptions(options)
            onBlurAndSave(look, options)
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
