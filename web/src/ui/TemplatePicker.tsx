import { t } from '../i18n'
import { LANGUAGES } from '../lib/subtitles'
import { useState } from 'preact/hooks'
import { allTemplates, findTemplate, isEdited, renameTemplate, storeFailed, templatesLoaded } from '../lib/templatestore'
import { applyTemplate, type Template, type TemplateSettings } from '../lib/template'
import { storageKey } from '../lib/channel'
import { SwitchRow } from './EditorTabs'
import { TemplateEditor } from './TemplateEditor'
import { EditIcon } from './icons'
import { TemplatePreview } from './TemplatePreview'
import './templates-en'

type Props = {
  /** A frame of the video (null until it's there). */
  frame: HTMLCanvasElement | null
  /** The settings with no template: what each card is shown on. */
  base: TemplateSettings
  current: string | null
  onPick: (id: string | null) => void
  /** The settings now (the template's laid over the base, and the user's changes). */
  settings: TemplateSettings
  onSettings: (s: TemplateSettings) => void
  /** The chosen template was edited and saved here: take it again, with its new settings. */
  onEdited?: (id: string) => void
}

/** The name of [t] as shown: the built-in looks' are Hebrew and translated. */
export const templateLabel = (tp: Template) => (tp.builtIn ? t(tp.name) : tp.name)

/**
 * One row of template cards, "no template" first: each a picture of what the video would look
 * like (the web's own subtitle and blur drawing, on the video's frame). Under it, the switch for
 * creating subtitles that a look turns on, and the speech's language.
 */
export function TemplatePicker({ frame, base, current, onPick, settings, onSettings, onEdited }: Props) {
  void templatesLoaded.value
  const list = allTemplates.value
  const [renaming, setRenaming] = useState<string | null>(null)
  const [editing, setEditing] = useState<Template | null>(null)
  const card = (id: string | null, name: string, s: TemplateSettings, subs: boolean, badge?: string) => (
    <button class={'tpl-card' + (current === id ? ' on' : '')} role="radio" aria-checked={current === id} data-tpl={id ?? 'none'} onClick={() => { setRenaming(null); onPick(id) }}>
      <TemplatePreview s={s} frame={frame} subs={subs} height={120} />
      <span class="tpl-name"><bdi>{name}</bdi></span>
      {badge && <span class="tpl-badge">{badge}</span>}
    </button>
  )
  const own = current ? findTemplate(current) : null
  const ownName = own && !own.builtIn ? own : null
  const saveRename = async (id: string, old: string) => {
    const n = (renaming ?? '').trim()
    if (n && n !== old) await renameTemplate(id, n)
    if (!storeFailed.value) setRenaming(null)
  }
  const setLang = (name: string | null) => {
    onSettings({ ...settings, language: name })
    try { if (name) localStorage.setItem(storageKey('subs.lang'), name); else localStorage.removeItem(storageKey('subs.lang')) } catch {}
  }
  return (
    <section class="card templates">
      <h3 class="section-title">{t('תבנית')}</h3>
      <div class="tpl-row" role="radiogroup" aria-label={t('תבנית')}>
        {card(null, t('בלי תבנית'), base, false)}
        {list.map((tp) => {
          const s = applyTemplate(tp, base)
          return card(tp.id, templateLabel(tp), s, s.createSubs && !!tp.subs, s.blurOff ? t('בלי טשטוש פנים') : tp.builtIn && isEdited(tp.id) ? t('נערכה') : undefined)
        })}
      </div>
      {current === null ? (
        <p class="small muted" style="margin:0">{t('בלי תבנית: ההגדרות נשארות כמו שהן.')}</p>
      ) : (
        <>
          {own && (
            <div class="row tpl-chosen">
              <strong class="grow small tpl-chosen-name"><bdi>{templateLabel(own)}</bdi></strong>
              <button class="btn tonal small-btn tpl-edit-chosen" onClick={() => { setRenaming(null); setEditing(own) }}><EditIcon size={18} /> {t('עריכה')}</button>
            </div>
          )}
          {ownName && (
            renaming === null ? (
              <div class="row tpl-own">
                <strong class="grow small tpl-own-name"><bdi>{ownName.name}</bdi></strong>
                <button class="btn ghost small-btn tpl-rename" onClick={() => setRenaming(ownName.name)}>{t('שנה שם')}</button>
              </div>
            ) : (
              <div class="row tpl-own">
                <input class="text-in tpl-rename-in grow" style="margin:0;padding:8px 10px" dir="auto" autofocus maxLength={60} value={renaming} aria-label={t('שם התבנית')} onInput={(e) => setRenaming((e.currentTarget as HTMLInputElement).value)} onKeyDown={(e) => { if (e.key === 'Escape') setRenaming(null); else if (e.key === 'Enter' && renaming.trim()) { e.preventDefault(); void saveRename(ownName.id, ownName.name) } }} />
                <button class="btn tonal small-btn tpl-rename-save" disabled={!renaming.trim()} onClick={() => void saveRename(ownName.id, ownName.name)}>{t('שמור')}</button>
                <button class="btn ghost small-btn" onClick={() => setRenaming(null)}>{t('ביטול')}</button>
              </div>
            )
          )}
          {storeFailed.value && <p class="small tpl-store-error" role="alert" style="margin:0;color:#e5484d">{t('לא ניתן לשמור בדפדפן (אין מקום פנוי?). השינוי יישמר עד שהדף ייסגר.')}</p>}
          <p class="small muted" style="margin:0">{t('ההגדרות מתמלאות לפי התבנית. אפשר לשנות אותן אחר כך.')}</p>
          <SwitchRow cls="tpl-subs" title={t('ליצור כתוביות אוטומטית')} text={t('זיהוי הדיבור במכשיר, לכל סרטון')} on={settings.createSubs} onChange={(on) => onSettings({ ...settings, createSubs: on })} />
          {settings.createSubs && (
            <>
              <h4 class="caption">{t('שפת הדיבור')}</h4>
              <div class="chips tpl-lang">
                {LANGUAGES.map(([code, label]) => (
                  <button class={'chip' + (settings.language === code ? ' on' : '')} onClick={() => setLang(code)}>{t(label)}</button>
                ))}
              </div>
            </>
          )}
        </>
      )}
      {editing && <TemplateEditor key={editing.id} template={editing} onClose={() => setEditing(null)} onSaved={(id) => onEdited?.(id)} />}
    </section>
  )
}
