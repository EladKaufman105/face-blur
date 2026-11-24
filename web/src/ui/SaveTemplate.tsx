import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'
import { CATEGORIES, templateOf, type Template, type TemplateCategory, type TemplateSettings } from '../lib/template'
import { newTemplateId, saveTemplate } from '../lib/templatestore'
import { CloseIcon } from './icons'
import { SwitchRow } from './EditorTabs'
import './templates-en'

const TITLES: Record<TemplateCategory, string> = {
  blur: 'טשטוש פנים', subs: 'כתוביות', voice: 'קול', cuts: 'חיתוכים', background: 'רקע', stabilize: 'ייצוב', export: 'ייצוא וסימן מים',
}

/** A picture's object URL as a data URL (a template keeps its logo whole). */
async function dataUrl(src: string | null): Promise<string | null> {
  if (!src || src.startsWith('data:')) return src
  try {
    const blob = await (await fetch(src)).blob()
    return await new Promise((resolve) => { const r = new FileReader(); r.onload = () => resolve(String(r.result)); r.onerror = () => resolve(null); r.readAsDataURL(blob) })
  } catch {
    return null
  }
}

/**
 * "Save as template", from the editor: this video's choices, by the categories chosen. What
 * isn't in a template isn't changed when it's applied.
 * [settings]: the video's settings now; [initial]: the categories on at first.
 */
export function SaveTemplate({ settings, initial, onClose, onSaved }: { settings: TemplateSettings; initial: TemplateCategory[]; onClose: () => void; onSaved: (t: Template) => void }) {
  const [name, setName] = useState('')
  const [keep, setKeep] = useState<Set<TemplateCategory>>(new Set(initial))
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [])
  const save = async () => {
    setBusy(true)
    const logo = await dataUrl(settings.watermark.image)
    const tpl = templateOf({ ...settings, watermark: { ...settings.watermark, image: logo } }, newTemplateId(), name.trim(), keep)
    await saveTemplate(tpl)
    onSaved(tpl)
  }
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet save-template" role="dialog" aria-modal="true" aria-label={t('שמירה כתבנית')}>
        <div class="row">
          <h3 class="section-title grow" style="margin:0">{t('שמירה כתבנית')}</h3>
          <button class="icon-btn" aria-label={t('סגור')} onClick={onClose}><CloseIcon size={20} /></button>
        </div>
        <div class="sheet-body">
          <input class="text-in tpl-name-in" dir="auto" autofocus maxLength={60} placeholder={t('שם התבנית')} aria-label={t('שם התבנית')} value={name} onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)} />
          <p class="small muted" style="margin:0">{t('מה ייכנס לתבנית: הבחירות של הסרטון הזה, לפי הקטגוריות שנבחרו. מה שלא נכנס לא ישתנה כשמחילים אותה.')}</p>
          {CATEGORIES.map((c) => (
            <SwitchRow cls={'tpl-keep tpl-keep-' + c} title={t(TITLES[c])} text="" on={keep.has(c)} onChange={(on) => setKeep((k) => { const n = new Set(k); if (on) n.add(c); else n.delete(c); return n })} />
          ))}
        </div>
        <button class="btn primary save-template-go" disabled={busy || !name.trim() || keep.size === 0} onClick={save}>{t('שמור כתבנית')}</button>
      </div>
    </div>
  )
}

