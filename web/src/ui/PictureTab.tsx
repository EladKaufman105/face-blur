import { useRef } from 'preact/hooks'
import { t } from '../i18n'
import { BG_COLORS, type Background, type BgFill, type BgKey } from '../lib/background'
import type { Aspect } from '../lib/camera'

/** A long job over the whole video (finding the people, measuring the camera): how far, and to stop it. */
export type PictureBusy = { what: 'people' | 'motion'; progress: number } | null

function Progress({ label, progress, onCancel }: { label: string; progress: number; onCancel: () => void }) {
  return (
    <div class="busy">
      <div class="row">
        <span class="small grow">{label}</span>
        <button class="btn ghost small-btn" onClick={onCancel}>{t('ביטול')}</button>
      </div>
      <div class="progress"><div style={{ width: `${Math.round(progress * 100)}%` }} /></div>
    </div>
  )
}

/**
 * Background removal, as in the app: keep the people (found on the device) or everything but a
 * green or blue screen, and put a blur, a color or a picture behind. Seen in the player.
 */
export function BackgroundCard(p: { bg: Background; onChange: (b: Background) => void; masksReady: boolean; busy: PictureBusy; onFindPeople: () => void; onCancel: () => void; /** In a template: the people are found when it's applied to a video. */ template?: boolean }) {
  const b = p.bg
  const set = (patch: Partial<Background>) => p.onChange({ ...b, ...patch })
  const input = useRef<HTMLInputElement>(null)
  const pick = () => input.current?.click()
  const people = p.busy?.what === 'people' ? p.busy : null
  return (
    <section class="card picture">
      <h3 class="section-title">{t('רקע\u200E')}</h3>
      <h4 class="caption">{t('מה נשאר בתמונה')}</h4>
      <div class="seg bg-key" role="radiogroup">
        {([['person', 'אנשים'], ['green', 'מסך ירוק'], ['blue', 'מסך כחול']] as [BgKey, string][]).map(([k, label]) => (
          <button class={b.key === k ? 'on' : ''} role="radio" aria-checked={b.key === k} onClick={() => set({ key: k })}>{t(label)}</button>
        ))}
      </div>
      {b.key === 'person' ? (
        p.template ? (
          <p class="small muted" style="margin:0">{t('הרקע מוחלף אחרי שהאנשים בסרטון מזוהים (שלב נוסף בעיבוד).')}</p>
        ) : people ? (
          <Progress label={t('מזהה אנשים… %d%', Math.round(people.progress * 100))} progress={people.progress} onCancel={p.onCancel} />
        ) : !p.masksReady ? (
          <>
            <p class="small muted" style="margin:0">{t('קודם מזהים את האנשים בכל פריים: מעבר אחד על הסרטון, על המכשיר.')}</p>
            <button class="btn tonal find-people" disabled={!!p.busy} onClick={p.onFindPeople}>{t('זהה אנשים')}</button>
          </>
        ) : (
          <div class="row">
            <span class="small grow people-ready" style="color:var(--primary)">{t('האנשים זוהו בכל הסרטון')}</span>
            <button class="btn ghost small-btn" disabled={!!p.busy} onClick={p.onFindPeople}>{t('זהה שוב')}</button>
          </div>
        )
      ) : (
        <div class="slider-row">
          <label>{t('רגישות')}</label>
          <input type="range" min={0} max={1} step={0.01} value={b.tolerance} onInput={(e) => set({ tolerance: Number((e.currentTarget as HTMLInputElement).value) })} />
        </div>
      )}
      <h4 class="caption">{t('מה מאחור')}</h4>
      <div class="seg seg4 bg-fill" role="radiogroup">
        {([['none', 'ללא'], ['blur', 'טשטוש'], ['color', 'צבע'], ['image', 'תמונה']] as [BgFill, string][]).map(([f, label]) => (
          <button class={b.fill === f ? 'on' : ''} role="radio" aria-checked={b.fill === f} onClick={() => (f === 'image' && !b.image ? pick() : set({ fill: f }))}>{t(label)}</button>
        ))}
      </div>
      {b.fill === 'color' && (
        <div class="swatches">
          {BG_COLORS.map((c) => (
            <button class={'swatch' + (b.color === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ color: c })} />
          ))}
          <label class="swatch custom" title={t('צבע אחר')}>
            <input type="color" value={b.color} onInput={(e) => set({ color: (e.currentTarget as HTMLInputElement).value })} />
          </label>
        </div>
      )}
      {b.fill === 'image' && b.image && (
        <div class="row">
          <img class="bg-thumb" src={b.image} alt="" />
          <button class="btn ghost small-btn" onClick={pick}>{t('החלף תמונה')}</button>
        </div>
      )}
      {b.key === 'person' && b.fill !== 'none' && !p.masksReady && !people && !p.template && (
        <p class="small" style="margin:0;color:#e5484d">{t('הרקע יוחלף אחרי שהאנשים יזוהו.')}</p>
      )}
      <input
        ref={input}
        type="file"
        accept="image/*"
        class="bg-image-in"
        hidden
        onChange={async (e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          ;(e.currentTarget as HTMLInputElement).value = ''
          if (!f) return
          const url = await shrink(f)
          if (url) p.onChange({ ...b, fill: 'image', image: url })
        }}
      />
    </section>
  )
}

/** A picked picture, at most 1920 px, as a JPEG data URL. */
async function shrink(f: File): Promise<string | null> {
  try {
    const img = await createImageBitmap(f)
    const s = Math.min(1, 1920 / Math.max(img.width, img.height))
    const c = document.createElement('canvas')
    c.width = Math.max(1, Math.round(img.width * s))
    c.height = Math.max(1, Math.round(img.height * s))
    c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height)
    img.close()
    return c.toDataURL('image/jpeg', 0.9)
  } catch {
    return null
  }
}

/** The exported frame: as shot, or 9:16, 1:1 or 4:5 following the faces like a camera operator. */
export function FrameCard({ aspect, onChange, hasFaces }: { aspect: Aspect; onChange: (a: Aspect) => void; hasFaces: boolean }) {
  return (
    <section class="card picture">
      <h3 class="section-title">{t('פורמט')}</h3>
      <div class="seg seg4 frame-aspect" role="radiogroup">
        {([['original', t('מקור')], ['9:16', '9:16'], ['1:1', '1:1'], ['4:5', '4:5']] as [Aspect, string][]).map(([a, label]) => (
          <button class={aspect === a ? 'on' : ''} role="radio" aria-checked={aspect === a} onClick={() => onChange(a)}><bdi dir="ltr">{label}</bdi></button>
        ))}
      </div>
      <p class="small muted" style="margin:0">
        {aspect === 'original' ? t('הסרטון יישמר בצורה המקורית שלו.') : hasFaces ? t('המסגרת עוקבת אחרי הפנים, כמו צלם. רואים אותה על הסרטון.') : t('בלי פנים שזוהו המסגרת נשארת באמצע. אפשר לזהות פנים בקבוצת הפרטיות.')}
      </p>
    </section>
  )
}

/** Steadying a shaky video (light, medium, strong), as in the app. */
export function StabilizeCard({ level, onLevel, busy, zoom, onCancel }: { level: number; onLevel: (l: number) => void; busy: PictureBusy; zoom: number | null; onCancel: () => void }) {
  const motion = busy?.what === 'motion' ? busy : null
  return (
    <section class="card picture">
      <h3 class="section-title">{t('ייצוב')}</h3>
      <div class="seg seg4 stabilize" role="radiogroup">
        {[t('כבוי'), t('קל'), t('בינוני'), t('חזק')].map((label, k) => (
          <button class={level === k ? 'on' : ''} role="radio" aria-checked={level === k} disabled={!!busy && !motion} onClick={() => onLevel(k)}>{label}</button>
        ))}
      </div>
      {motion ? (
        <Progress label={t('מודד את תנועת המצלמה… %d%', Math.round(motion.progress * 100))} progress={motion.progress} onCancel={onCancel} />
      ) : level > 0 && zoom !== null ? (
        <p class="small stabilize-info" style="margin:0">{t('התמונה מוגדלת ב-%d% כדי להסתיר את השוליים. נראה מיד בנגן.', zoom)}</p>
      ) : level > 0 ? (
        <p class="small muted" style="margin:0">{t('המצלמה כמעט לא זזה: אין מה לייצב.')}</p>
      ) : (
        <p class="small muted" style="margin:0">{t('קל: רעידות קטנות, כמעט בלי חיתוך. חזק: הליכה וצילום ביד, עם יותר חיתוך של השוליים.')}</p>
      )}
    </section>
  )
}
