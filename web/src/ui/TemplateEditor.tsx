import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { lang, t } from '../i18n'
import { type BlurMode } from '../lib/types'
import { LANGUAGES } from '../lib/subtitles'
import { VOICES, type VoiceFx } from '../lib/voice'
import { NO_WATERMARK } from '../lib/watermark'
import { DEFAULT_EXPORT, type ExportCodec, type ExportQuality } from '../lib/exportopts'
import { DEFAULT_SUB_STYLE } from '../lib/substyle'
import {
  applyTemplate, blankTemplate, builtIn, categoriesOf, CATEGORIES, copyOf, currentSettings, languageCode, only, templateToJson, type Template, type TemplateCategory,
} from '../lib/template'
import { deleteTemplate, duplicateTemplate, isEdited, newTemplateId, restoreTemplate, saveTemplate, storeFailed, userTemplates, lookTemplates } from '../lib/templatestore'
import { CloseIcon, DeleteIcon, EditIcon } from './icons'
import { SubStyleCard } from './SubStyleCard'
import { BackgroundCard, FrameCard, StabilizeCard } from './PictureTab'
import { CleanupCard, SwitchRow, WatermarkTab } from './EditorTabs'
import { TemplatePreview, lastFrame } from './TemplatePreview'
import { templateLabel } from './TemplatePicker'
import './templates-en'

const TITLES: Record<TemplateCategory, string> = {
  blur: 'טשטוש פנים', subs: 'כתוביות', voice: 'קול', cuts: 'חיתוכים', background: 'רקע', stabilize: 'ייצוב', export: 'ייצוא וסימן מים',
}
const SUBTITLES: Record<TemplateCategory, string> = {
  blur: 'סגנון, עוצמה וגודל האזור', subs: 'עיצוב, שפה וחלוקה', voice: 'שינוי קול וניקוי סאונד', cuts: 'שתיקות ומילות מילוי', background: 'מה מאחורי האנשים', stabilize: 'סרטון פחות רועד', export: 'צורה, איכות וסימן מים',
}

const SIZES = [0, 2160, 1440, 1080, 720, 480]
const RATES = [0, 60, 50, 30, 25, 24]
const PAUSES = [500, 800, 1200, 2000]

/** The categories at their neutral values (what a switched-on category starts as). */
const defaults = (): Template => blankTemplate('blank', '')

function sameJson(a: Template, b: Template) {
  return JSON.stringify(templateToJson(only({ ...a, name: '', id: '' }, new Set(categoriesOf(a))))) === JSON.stringify(templateToJson(only({ ...b, name: '', id: '' }, new Set(categoriesOf(b)))))
}

type Props = { template: Template; onClose: () => void; /** Saved (or restored to the original): the screen using the template takes it again. */ onSaved?: (id: string) => void }

/**
 * A template's editor: its name, a live preview, and each category's properties with its switch
 * ("include in template") - with the web app's own controls. A built-in look is edited here too
 * (and can be restored); the other templates can be renamed.
 */
export function TemplateEditor({ template, onClose, onSaved }: Props) {
  const [draft, setDraft] = useState<Template>(template)
  const [name, setName] = useState(template.name)
  const [busy, setBusy] = useState(false)
  const edited = template.builtIn && isEdited(template.id)
  // What a category held when it was switched off, to bring it back as it was.
  const stash = useRef<Partial<Template>>({})
  const base = useMemo(() => currentSettings({ sensitive: false, hebrewUi: lang.value === 'he', watermark: NO_WATERMARK, options: { ...DEFAULT_EXPORT } }), [])
  // Leaving with changes that were not saved asks first (as the app's "Save changes?"): Esc, the backdrop and the X.
  const [askLeave, setAskLeave] = useState(false)
  const dirty = name !== template.name || !sameJson(draft, template)
  const leave = useRef<() => void>(() => {})
  leave.current = () => { if (askLeave) setAskLeave(false); else if (dirty) setAskLeave(true); else onClose() }
  useEffect(() => {
    // In the capture phase: this sheet closes alone (the settings under it stay).
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); leave.current() } }
    addEventListener('keydown', key, true)
    return () => removeEventListener('keydown', key, true)
  }, [])

  const toggle = (c: TemplateCategory, on: boolean) => {
    setDraft((d) => {
      const n: Template = { ...d }
      if (on) (n as any)[c] = (stash.current as any)[c] ?? (defaults() as any)[c]
      else { (stash.current as any)[c] = (d as any)[c]; delete n[c] }
      return n
    })
  }
  const patch = <C extends 'blur' | 'subs' | 'cuts' | 'export'>(c: C, p: Partial<NonNullable<Template[C]>>) => setDraft((d) => ({ ...d, [c]: { ...(d[c] as object), ...p } }))
  const s = applyTemplate(draft, base)
  const subsShown = !!draft.subs

  const save = async () => {
    setBusy(true)
    const out: Template = { ...draft, name: template.builtIn ? template.name : name.trim() || template.name }
    if (template.builtIn && sameJson(out, builtIn(template.id)!)) await restoreTemplate(template.id)
    else await saveTemplate(out)
    onSaved?.(template.id)
    onClose()
  }

  const blur = draft.blur
  const subs = draft.subs
  const voice = draft.voice
  const cuts = draft.cuts
  const exp = draft.export

  const head = (c: TemplateCategory) => (
    <label class="row tpl-cat-head">
      <div class="grow">
        <strong class="small-title">{t(TITLES[c])}</strong>
        <div class="small muted">{t(SUBTITLES[c])}</div>
      </div>
      <span class="switch"><input type="checkbox" class={'tpl-inc tpl-inc-' + c} checked={draft[c] !== undefined} onChange={(e) => toggle(c, (e.currentTarget as HTMLInputElement).checked)} /><span /></span>
    </label>
  )
  const cat = (c: TemplateCategory, body: any) => (
    <section class={'card tpl-cat' + (draft[c] !== undefined ? ' on' : '')} data-cat={c}>
      {head(c)}
      {draft[c] !== undefined && <div class="tpl-cat-body">{body}</div>}
    </section>
  )
  const modeLabel = (m: BlurMode) => (m === 'blur' ? t('טשטוש') : m === 'pixelate' ? t('פיקסלים') : t('כיסוי'))
  const chip = (on: boolean, label: string, onClick: () => void) => <button class={'chip' + (on ? ' on' : '')} onClick={onClick}>{label}</button>

  return (
    <div class="sheet-back tpl-back" onClick={(e) => { if (e.target === e.currentTarget) leave.current() }}>
      <div class="sheet tpl-sheet" role="dialog" aria-modal="true" aria-label={t('עריכת תבנית')}>
        <div class="row">
          <h3 class="section-title grow" style="margin:0">{t('עריכת תבנית')}</h3>
          <button class="icon-btn tpl-x" aria-label={t('סגור')} onClick={() => leave.current()}><CloseIcon size={20} /></button>
        </div>
        <div class="sheet-body tpl-body">
          <div class="tpl-side">
            <div class="tpl-live">
              <TemplatePreview s={s} frame={lastFrame.canvas} subs={subsShown} height={260} animate />
            </div>
            {template.builtIn ? (
              <strong class="tpl-title"><bdi>{templateLabel(template)}</bdi></strong>
            ) : (
              <input class="text-in tpl-name-in" dir="auto" value={name} maxLength={60} aria-label={t('שם התבנית')} placeholder={t('שם התבנית')} onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)} />
            )}
            <p class="small muted" style="margin:0">{t('קטגוריה שכבויה לא משנה כלום: ההגדרות הנוכחיות נשארות.')}</p>
          </div>
          <div class="tpl-cats">
            {cat('blur', blur && (
              <>
                <SwitchRow cls="tpl-blur-none" title={t('בלי טשטוש פנים')} text={t('הסרטון נשאר כמו שהוא; אפשר להוסיף כתוביות, קול ועוד')} on={!!blur.none} onChange={(on) => patch('blur', { none: on })} />
                {!blur.none && (
                  <>
                    <h4 class="caption">{t('סגנון')}</h4>
                    <div class="seg tpl-mode" role="radiogroup">
                      {(['blur', 'pixelate', 'solid'] as BlurMode[]).map((m) => (
                        <button class={blur.mode === m ? 'on' : ''} role="radio" aria-checked={blur.mode === m} onClick={() => patch('blur', { mode: m })}>{modeLabel(m)}</button>
                      ))}
                    </div>
                    {blur.mode !== 'solid' && (
                      <div class="slider-row">
                        <label>{t('עוצמה')}</label>
                        <input type="range" class="tpl-strength" min={0} max={1} step={0.01} value={blur.strength ?? 0.6} onInput={(e) => patch('blur', { strength: Number((e.currentTarget as HTMLInputElement).value) })} />
                      </div>
                    )}
                    <div class="slider-row">
                      <label>{t('גודל האזור סביב הפנים')}</label>
                      <input type="range" min={1.2} max={2} step={0.01} value={blur.padding ?? 1.6} onInput={(e) => patch('blur', { padding: Number((e.currentTarget as HTMLInputElement).value) })} />
                    </div>
                    <SwitchRow title={t('זיהוי מורחב')} text={t('בודק כל פריים ומטשטש גם פנים שהמערכת פחות בטוחה בהן.')} on={!!blur.sensitive} onChange={(on) => patch('blur', { sensitive: on })} />
                  </>
                )}
              </>
            ))}
            {cat('subs', subs && (
              <>
                <SwitchRow cls="tpl-create" title={t('ליצור כתוביות אוטומטית')} text={t('זיהוי הדיבור במכשיר, לכל סרטון')} on={!!subs.create} onChange={(on) => patch('subs', { create: on })} />
                <h4 class="caption">{t('שפת הדיבור')}</h4>
                <div class="chips">
                  {chip(subs.language === undefined, t('השפה שבחרתי'), () => setDraft((d) => ({ ...d, subs: { ...d.subs, language: undefined } })))}
                  {LANGUAGES.map(([code, label]) => chip(subs.language === languageCode(code), t(label), () => patch('subs', { language: languageCode(code) })))}
                </div>
                <div class="tpl-subs-live"><TemplatePreview class="tpl-subs-preview" s={s} frame={lastFrame.canvas} subs height={150} animate /></div>
                <SubStyleCard style={subs.style ?? DEFAULT_SUB_STYLE} onStyle={(st) => patch('subs', { style: st })} />
                <div class="row">
                  <span class="small grow">{t('חלוקת הכתוביות')}</span>
                </div>
                <div class="chips tpl-split">
                  {chip(!subs.splitWords && !subs.splitChars, t('משפט שלם'), () => patch('subs', { splitWords: 0, splitChars: 0 }))}
                  {[1, 2, 3, 4, 6].map((n) => chip(subs.splitWords === n, t('עד %d מילים', n), () => patch('subs', { splitWords: n, splitChars: 0 })))}
                  {[20, 32, 42].map((n) => chip(subs.splitChars === n && !subs.splitWords, t('עד %d תווים', n), () => patch('subs', { splitWords: 0, splitChars: n })))}
                </div>
                <h4 class="caption">{t('כתוביות בסרטון המיוצא')}</h4>
                <div class="seg seg2 tpl-burn" role="radiogroup">
                  <button class={subs.burn !== 'NONE' ? 'on' : ''} role="radio" aria-checked={subs.burn !== 'NONE'} onClick={() => patch('subs', { burn: 'ORIGINAL' })}>{t('לצרוב בסרטון')}</button>
                  <button class={subs.burn === 'NONE' ? 'on' : ''} role="radio" aria-checked={subs.burn === 'NONE'} onClick={() => patch('subs', { burn: 'NONE' })}>{t('לא לצרוב')}</button>
                </div>
                <SwitchRow title={t('שמור גם קובץ כתוביות SRT')} text={t('לפלטפורמות שמציגות כתוביות בעצמן.')} on={!!subs.saveSrt} onChange={(on) => patch('subs', { saveSrt: on })} />
              </>
            ))}
            {cat('voice', voice && (
              <>
                <h4 class="caption">{t('שינוי קול')}</h4>
                <div class="chips tpl-voice">
                  {VOICES.map((v) => chip(voice.voice === v.id, t(v.label), () => setDraft((d) => ({ ...d, voice: { ...d.voice!, voice: v.id as VoiceFx } }))))}
                </div>
                <CleanupCard
                  clean={{ denoise: voice.denoise, enhance: voice.enhance, loud: voice.loud }}
                  onClean={(c) => setDraft((d) => ({ ...d, voice: { ...d.voice!, denoise: c.denoise, enhance: c.enhance } }))}
                  loud={{ on: voice.loud, gainDb: null }}
                  loudBusy={false}
                  onLoud={(on) => setDraft((d) => ({ ...d, voice: { ...d.voice!, loud: on } }))}
                />
              </>
            ))}
            {cat('cuts', cuts && (
              <>
                <SwitchRow cls="tpl-silences" title={t('חיתוך שתיקות')} text={t('חותך הפסקות ארוכות בדיבור')} on={cuts.silences} onChange={(on) => patch('cuts', { silences: on })} />
                {cuts.silences && (
                  <div class="chips">
                    {PAUSES.map((ms) => chip(cuts.minPauseMs === ms, t('הפסקה מעל %s שנ׳', `⁦${ms / 1000}⁩`), () => patch('cuts', { minPauseMs: ms })))}
                  </div>
                )}
                <SwitchRow cls="tpl-fillers" title={t('חיתוך מילות מילוי')} text={t('דורש כתוביות עם זמני מילים')} on={cuts.fillers} onChange={(on) => patch('cuts', { fillers: on })} />
              </>
            ))}
            {cat('background', draft.background && (
              <BackgroundCard bg={draft.background} onChange={(b) => setDraft((d) => ({ ...d, background: b }))} masksReady={false} busy={null} onFindPeople={() => {}} onCancel={() => {}} template />
            ))}
            {cat('stabilize', draft.stabilize !== undefined && (
              <StabilizeCard level={draft.stabilize} onLevel={(l) => setDraft((d) => ({ ...d, stabilize: l }))} busy={null} zoom={null} onCancel={() => {}} />
            ))}
            {cat('export', exp && (
              <>
                <FrameCard aspect={exp.aspect ?? 'original'} onChange={(a) => patch('export', { aspect: a })} hasFaces />
                <h4 class="caption">{t('רזולוציה')}</h4>
                <div class="chips tpl-size">
                  {SIZES.map((v) => chip((exp.shortSide ?? 0) === v, v ? `${v}p` : t('מקור'), () => patch('export', { shortSide: v })))}
                </div>
                <h4 class="caption">{t('קצב פריימים')}</h4>
                <div class="chips tpl-fps">
                  {RATES.map((v) => chip((exp.fps ?? 0) === v, v ? `⁦${v} fps⁩` : t('מקור'), () => patch('export', { fps: v })))}
                </div>
                <h4 class="caption">{t('איכות וגודל קובץ')}</h4>
                <div class="seg" role="radiogroup">
                  {([['original', 'כמו המקור'], ['high', 'גבוהה'], ['saver', 'חיסכון']] as [ExportQuality, string][]).map(([q, label]) => (
                    <button class={(exp.quality ?? 'original') === q ? 'on' : ''} role="radio" aria-checked={(exp.quality ?? 'original') === q} onClick={() => patch('export', { quality: q })}>{t(label)}</button>
                  ))}
                </div>
                <h4 class="caption">{t('קידוד')}</h4>
                <div class="seg" role="radiogroup">
                  {([['same', t('כמו המקור')], ['avc', 'H.264'], ['hevc', 'HEVC']] as [ExportCodec, string][]).map(([c, label]) => (
                    <button class={(exp.codec ?? 'same') === c ? 'on' : ''} role="radio" aria-checked={(exp.codec ?? 'same') === c} onClick={() => patch('export', { codec: c })}>{label}</button>
                  ))}
                </div>
                <WatermarkTab mark={exp.watermark ?? NO_WATERMARK} onChange={(w) => patch('export', { watermark: w })} template />
              </>
            ))}
          </div>
        </div>
        {storeFailed.value && <p class="small tpl-store-error" role="alert" style="margin:0;color:#e5484d">{t('לא ניתן לשמור בדפדפן (אין מקום פנוי?). השינוי יישמר עד שהדף ייסגר.')}</p>}
        <div class="tpl-foot">
          {edited && <button class="btn tonal tpl-restore" onClick={async () => { await restoreTemplate(template.id); onSaved?.(template.id); onClose() }}>{t('שחזר מקור')}</button>}
          <span class="grow" />
          <button class="btn ghost" onClick={() => leave.current()}>{t('ביטול')}</button>
          <button class="btn primary tpl-save" disabled={busy || (!template.builtIn && !name.trim())} onClick={save}>{t('שמור')}</button>
        </div>
        {askLeave && (
          <div class="confirm tpl-leave" role="alertdialog" aria-label={t('לשמור את השינויים?')}>
            <strong class="small">{t('לשמור את השינויים?')}</strong>
            <span class="small">{t('עשית שינויים בתבנית שלא נשמרו.')}</span>
            <div class="row" style="flex-wrap:wrap">
              <button class="btn small-btn primary tpl-leave-save" disabled={busy || (!template.builtIn && !name.trim())} onClick={save}>{t('שמור')}</button>
              <button class="btn ghost small-btn tpl-leave-keep" onClick={() => setAskLeave(false)}>{t('המשך לערוך')}</button>
              <button class="btn small-btn danger-btn tpl-leave-discard" onClick={onClose}>{t('בטל שינויים')}</button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/** The templates, as a list in the settings: edit, duplicate, rename and delete (a built-in look: edit, duplicate, restore). */
export function TemplatesManager() {
  const [editing, setEditing] = useState<Template | null>(null)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const users = userTemplates.value
  const looks = lookTemplates.value
  const base = useMemo(() => currentSettings({ sensitive: false, hebrewUi: lang.value === 'he', watermark: NO_WATERMARK, options: { ...DEFAULT_EXPORT } }), [])
  const row = (tp: Template) => {
    const s = applyTemplate(tp, base)
    const edited = tp.builtIn && isEdited(tp.id)
    return (
      <div class="tpl-item" data-tpl={tp.id}>
        <div class="row">
          <TemplatePreview s={s} frame={lastFrame.canvas} subs={!!tp.subs} height={64} />
          <div class="grow tpl-item-info">
            {renaming?.id === tp.id ? (
              <div class="row">
                <input class="text-in tpl-rename-in" style="margin:0;padding:8px 10px" dir="auto" value={renaming.name} aria-label={t('שם התבנית')} onInput={(e) => setRenaming({ id: tp.id, name: (e.currentTarget as HTMLInputElement).value })} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setRenaming(null) } }} />
                <button class="btn tonal small-btn" disabled={!renaming.name.trim()} onClick={async () => { const x = userTemplates.value.find((u) => u.id === tp.id); if (x) await saveTemplate({ ...x, name: renaming.name.trim().slice(0, 60) }); setRenaming(null) }}>{t('שמור')}</button>
              </div>
            ) : (
              <strong class="tpl-item-name"><bdi>{templateLabel(tp)}</bdi></strong>
            )}
            <div class="small muted">{tp.builtIn ? (edited ? t('מובנית · נערכה') : t('מובנית')) : categoriesOf(tp).map((c) => t(TITLES[c])).join(' · ')}</div>
          </div>
          <div class="tpl-acts">
            <button class="icon-btn tpl-edit" aria-label={`${t('עריכה')}: ${templateLabel(tp)}`} title={t('עריכה')} onClick={() => setEditing(tp)}><EditIcon size={20} /></button>
            <button class="btn ghost small-btn tpl-dup" onClick={async () => { await duplicateTemplate(tp.id, `${templateLabel(tp)} ${t('(עותק)')}`) }}>{t('שכפל')}</button>
            {!tp.builtIn && <button class="btn ghost small-btn tpl-rename" onClick={() => setRenaming({ id: tp.id, name: tp.name })}>{t('שנה שם')}</button>}
            {edited && <button class="btn ghost small-btn tpl-restore-row" onClick={() => restoreTemplate(tp.id)}>{t('שחזר מקור')}</button>}
            {!tp.builtIn && <button class="icon-btn danger tpl-del" aria-label={`${t('מחק')}: ${tp.name}`} title={t('מחק')} onClick={() => setDeleting(deleting === tp.id ? null : tp.id)}><DeleteIcon size={20} /></button>}
          </div>
        </div>
        {deleting === tp.id && (
          <div class="confirm">
            <span class="small">{t('התבנית "%s" תימחק. סרטונים שכבר עובדו לא ישתנו.', tp.name)}</span>
            <div class="row">
              <button class="btn small-btn danger-btn tpl-del-yes" onClick={async () => { await deleteTemplate(tp.id); setDeleting(null) }}>{t('מחק')}</button>
              <button class="btn ghost small-btn" onClick={() => setDeleting(null)}>{t('ביטול')}</button>
            </div>
          </div>
        )}
      </div>
    )
  }
  return (
    <>
      <div class="row" style="margin-top:8px">
        <h4 class="caption grow" style="margin:0">{t('תבניות')}</h4>
        <button class="btn tonal small-btn tpl-new" onClick={() => setEditing({ ...copyOf(blankTemplateWithout(), newTemplateId(), t('תבנית חדשה')) })}>{t('תבנית חדשה')}</button>
      </div>
      <p class="small muted" style="margin:0">{t('מראה ועיבוד לסרטונים: כתוביות, טשטוש, קול, חיתוכים וייצוא. אפשר לבחור תבנית במסך הסרטון ובעיבוד כמה סרטונים.')}</p>
      {storeFailed.value && <p class="small tpl-store-error" role="alert" style="margin:0;color:#e5484d">{t('לא ניתן לשמור בדפדפן (אין מקום פנוי?). השינוי יישמר עד שהדף ייסגר.')}</p>}
      <h4 class="caption">{t('התבניות שלי')}</h4>
      {users.length === 0 ? <p class="small muted" style="margin:0">{t('עדיין אין תבניות. בעורך: ייצוא, ואז "שמור כתבנית".')}</p> : <div class="tpl-list">{users.map(row)}</div>}
      <h4 class="caption">{t('מובנות')}</h4>
      <div class="tpl-list">{looks.map(row)}</div>
      {editing && <TemplateEditor key={editing.id} template={editing} onClose={() => setEditing(null)} />}
    </>
  )
}

/** A new template: the blur look and the subtitle design, as a start (the others can be switched on). */
function blankTemplateWithout(): Template {
  const d = defaults()
  return only(d, new Set<TemplateCategory>(['blur', 'subs']))
}

export { CATEGORIES }
