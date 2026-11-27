import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'
import { estimatedBytes, fpsChoices, outputFps, outputSize, resolutionChoices, sizeText, type ExportCodec, type ExportOptions, type ExportQuality } from '../lib/exportopts'
import { ASPECT_RATIO, cropFraction, type Aspect } from '../lib/camera'
import { CloseIcon, SaveIcon } from './icons'

type Props = {
  width: number
  height: number
  /** The source's frame rate (null until it's known). */
  fps: number | null
  /** The source's codec ('hevc' or another). */
  sourceHevc: boolean
  /** The source's bitrate (bits per second; 0 when unknown, null until it's known) and its length (seconds): the size estimate. */
  srcBitrate?: number | null
  duration?: number
  hasCues: boolean
  /** Bleeped words the subtitles no longer have (they would be heard): said before exporting. */
  lostBleeps?: number
  /** Subtitles that may show a bleeped word (its text was edited): exporting asks first. */
  maskWarn?: number
  options: ExportOptions
  onChange: (o: ExportOptions) => void
  onGo: () => void
  onClose: () => void
  /** What the button says (when it isn't "export"). */
  action?: string
}

/**
 * Before exporting, as the app's export sheet: a video or only the sound (MP3), the video's
 * resolution, frame rate, quality and codec. (The subtitles as an SRT file are not a choice here: the done screen and the subtitles panel offer them whenever there are subtitles.)
 */
export function ExportSheet(p: Props) {
  const o = p.options
  const set = (patch: Partial<ExportOptions>) => p.onChange({ ...o, ...patch })
  // The sizes of the frame's shape (a 9:16 frame of a wide video is narrower).
  const [cw, ch] = cropFraction(p.width, p.height, ASPECT_RATIO[o.aspect])
  const short = Math.round(Math.min(p.width * cw, p.height * ch))
  const sizes = resolutionChoices(p.width * cw, p.height * ch)
  const rates = p.fps ? fpsChoices(p.fps) : []
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') p.onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  // A subtitle that may show a bleeped word blocks the video's export until it is fixed or the person says to go on.
  const [maskOk, setMaskOk] = useState(false)
  // (The video carries the subtitles when they are burned in; the SRT file the done screen offers carries them always.)
  const carries = o.format === 'mp4'
  const blocked = (p.maskWarn ?? 0) > 0 && carries && !maskOk
  const chip = (on: boolean, label: string, onClick: () => void) => <button class={'chip' + (on ? ' on' : '')} onClick={onClick}>{label}</button>
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) p.onClose() }}>
      <div class="sheet export-sheet" role="dialog" aria-modal="true" aria-label={t('ייצוא')}>
        <div class="row">
          <h3 class="section-title grow" style="margin:0">{t('ייצוא')}</h3>
          <button class="icon-btn" aria-label={t('סגור')} onClick={p.onClose}><CloseIcon size={20} /></button>
        </div>
        <div class="sheet-body">
          {(p.lostBleeps ?? 0) > 0 && <p class="small lost-note" role="note" style="margin:0;color:var(--primary)">{p.lostBleeps === 1 ? t('מילה מצופצפת אחת לא נמצאה בכתוביות החדשות ולא תצופצף') : t('%d מילים מצופצפות לא נמצאו בכתוביות החדשות ולא יצופצפו', p.lostBleeps ?? 0)}</p>}
          {(p.maskWarn ?? 0) > 0 && carries && (
            <label class="small mask-warn-note confirm" role="alert" style="margin:0;display:flex;gap:8px;align-items:flex-start">
              <input type="checkbox" class="mask-warn-ok" checked={maskOk} onChange={(e) => setMaskOk((e.currentTarget as HTMLInputElement).checked)} />
              <span>{p.maskWarn === 1 ? t('בכתובית אחת מופיעה מילה שאולי היא המילה המצופצפת, ולכן היא עלולה להיראות. בדוק אותה, וסמן כדי לייצא בכל זאת.') : t('ב-%d כתוביות מופיעה מילה שאולי היא המילה המצופצפת, ולכן היא עלולה להיראות. בדוק אותן, וסמן כדי לייצא בכל זאת.', p.maskWarn ?? 0)}</span>
            </label>
          )}
          <h4 class="caption">{t('מה לשמור')}</h4>
          <div class="seg seg2 export-format" role="radiogroup">
            <button class={o.format === 'mp4' ? 'on' : ''} role="radio" aria-checked={o.format === 'mp4'} onClick={() => set({ format: 'mp4' })}>{t('סרטון')}</button>
            <button class={o.format === 'mp3' ? 'on' : ''} role="radio" aria-checked={o.format === 'mp3'} onClick={() => set({ format: 'mp3' })}>{t('סאונד בלבד (MP3)')}</button>
          </div>
          {o.format === 'mp3' ? (
            <p class="small muted" style="margin:0">{t('רק הסאונד, עם כל מה שעשית בו: חיתוכים, ניקוי, שינוי קול ואפקטים.')}</p>
          ) : (
            <>
              <h4 class="caption">{t('פורמט')}</h4>
              <div class="seg seg4 export-aspect" role="radiogroup">
                {([['original', t('מקור')], ['9:16', '9:16'], ['1:1', '1:1'], ['4:5', '4:5']] as [Aspect, string][]).map(([a, label]) => (
                  <button class={o.aspect === a ? 'on' : ''} role="radio" aria-checked={o.aspect === a} onClick={() => set({ aspect: a, shortSide: 0 })}><bdi dir="ltr">{label}</bdi></button>
                ))}
              </div>
              <h4 class="caption">{t('רזולוציה')}</h4>
              <div class="chips export-size">
                {chip(!sizes.includes(o.shortSide), t('מקור (%s)', `${short}p`), () => set({ shortSide: 0 }))}
                {sizes.map((s) => chip(o.shortSide === s, `${s}p`, () => set({ shortSide: s })))}
              </div>
              <h4 class="caption">{t('קצב פריימים')}</h4>
              {p.fps ? (
                <div class="chips export-fps">
                  {chip(!rates.includes(o.fps), t('מקור (%s)', `\u2066${Math.round(p.fps)} fps\u2069`), () => set({ fps: 0 }))}
                  {rates.map((f) => chip(o.fps === f, `\u2066${f} fps\u2069`, () => set({ fps: f })))}
                </div>
              ) : (
                <p class="small muted" style="margin:0">{t('בודק את הסרטון…')}</p>
              )}
              <h4 class="caption">{t('איכות וגודל קובץ')}</h4>
              <div class="seg" role="radiogroup">
                {([['original', 'כמו המקור'], ['high', 'גבוהה'], ['saver', 'חיסכון']] as [ExportQuality, string][]).map(([q, label]) => (
                  <button class={o.quality === q ? 'on' : ''} role="radio" aria-checked={o.quality === q} onClick={() => set({ quality: q })}>{t(label)}</button>
                ))}
              </div>
              <p class="small muted" style="margin:0">
                {o.quality === 'original' ? t('בלי כיווץ: אותה איכות כמו הסרטון המקורי.') : o.quality === 'high' ? t('איכות גבוהה בקובץ קטן יותר.') : t('קובץ קטן לשליחה, באיכות טובה.')}
              </p>
              <h4 class="caption">{t('קידוד')}</h4>
              <div class="seg" role="radiogroup">
                {([['same', t('כמו המקור')], ['avc', 'H.264'], ['hevc', 'HEVC']] as [ExportCodec, string][]).map(([c, label]) => (
                  <button class={o.codec === c ? 'on' : ''} role="radio" aria-checked={o.codec === c} onClick={() => set({ codec: c })}>{label}</button>
                ))}
              </div>
              <p class="small muted" style="margin:0">
                {o.codec === 'same' ? t('אותו פורמט כמו הסרטון המקורי (%s).', p.sourceHevc ? 'HEVC' : 'H.264') : o.codec === 'avc' ? t('נפתח בכל מכשיר ואתר.') : t('קובץ קטן יותר באותה איכות. נתמך ברוב המכשירים החדשים.')}
              </p>
            </>
          )}
          {p.hasCues && o.format !== 'mp3' && (
            <>
              <h4 class="caption">{t('כתוביות בסרטון המיוצא')}</h4>
              <div class="seg seg2 burn-seg" role="radiogroup">
                <button class={o.burn !== false ? 'on' : ''} role="radio" aria-checked={o.burn !== false} onClick={() => set({ burn: true })}>{t('לצרוב בסרטון')}</button>
                <button class={o.burn === false ? 'on' : ''} role="radio" aria-checked={o.burn === false} onClick={() => set({ burn: false })}>{t('לא לצרוב')}</button>
              </div>
              <p class="small muted" style="margin:0">{o.burn === false ? t('הכתוביות לא ייצרבו בסרטון.') : t('הכתוביות ייצרבו בתמונה ויופיעו בכל נגן.')}</p>
            </>
          )}
          {o.format === 'mp4' && p.fps && p.srcBitrate != null && (p.duration ?? 0) > 0 && (() => {
            // What comes out, as the app's sheet says: its size, frame rate and about how big the file is.
            const [w, h] = outputSize(p.width, p.height, o.shortSide, o.aspect)
            const hevc = o.codec === 'hevc' || (o.codec === 'same' && p.sourceHevc)
            const bytes = estimatedBytes(o, p.width, p.height, p.fps, p.srcBitrate ?? 0, hevc, p.duration ?? 0)
            return <p class="export-estimate" style="margin:0;font-weight:600">{t('יוצא: %s · %s · גודל משוער %s', `\u2066${w}×${h}\u2069`, `\u2066${Math.round(outputFps(p.fps, o.fps))} fps\u2069`, `\u2066${sizeText(bytes)}\u2069`)}</p>
          })()}
        </div>
        <button class="btn primary export-go" disabled={blocked} onClick={p.onGo}><SaveIcon /> {o.format === 'mp3' ? t('שמור MP3') : p.action ?? t('ייצא')}</button>
      </div>
    </div>
  )
}
