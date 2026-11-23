import { useEffect } from 'preact/hooks'
import { t } from '../i18n'
import { fpsChoices, resolutionChoices, type ExportCodec, type ExportOptions, type ExportQuality } from '../lib/exportopts'
import { ASPECT_RATIO, cropFraction, type Aspect } from '../lib/camera'
import { CloseIcon, SaveIcon } from './icons'

type Props = {
  width: number
  height: number
  /** The source's frame rate (null until it's known). */
  fps: number | null
  /** The source's codec ('hevc' or another). */
  sourceHevc: boolean
  hasCues: boolean
  options: ExportOptions
  onChange: (o: ExportOptions) => void
  onGo: () => void
  onClose: () => void
  /** What the button says (when it isn't "export"). */
  action?: string
}

/**
 * Before exporting, as the app's export sheet: a video or only the sound (MP3), the video's
 * resolution, frame rate, quality and codec, and the subtitles as an SRT file too.
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
  const chip = (on: boolean, label: string, onClick: () => void) => <button class={'chip' + (on ? ' on' : '')} onClick={onClick}>{label}</button>
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) p.onClose() }}>
      <div class="sheet export-sheet" role="dialog" aria-modal="true" aria-label={t('ייצוא')}>
        <div class="row">
          <h3 class="section-title grow" style="margin:0">{t('ייצוא')}</h3>
          <button class="icon-btn" aria-label={t('סגור')} onClick={p.onClose}><CloseIcon size={20} /></button>
        </div>
        <div class="sheet-body">
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
          {p.hasCues && (
            <label class="row switch-row" style="cursor:pointer">
              <div class="grow">
                <strong class="small-title">{t('שמור גם קובץ כתוביות SRT')}</strong>
                <div class="small muted">{t('לפלטפורמות שמציגות כתוביות בעצמן. הזמנים מותאמים לחיתוכים.')}</div>
              </div>
              <span class="switch">
                <input type="checkbox" class="export-srt" checked={o.srt} onChange={(e) => set({ srt: (e.currentTarget as HTMLInputElement).checked })} />
                <span />
              </span>
            </label>
          )}
        </div>
        <button class="btn primary export-go" onClick={p.onGo}><SaveIcon /> {o.format === 'mp3' ? t('שמור MP3') : p.action ?? t('ייצא')}</button>
      </div>
    </div>
  )
}
