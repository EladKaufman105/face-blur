import { useEffect, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { deleteProject, listProjects, renameProject, type ProjectMeta } from '../lib/projects'
import { BETA } from '../lib/channel'
import { ImportFromRelease } from './Beta'

/** "Today 14:05", "Yesterday 09:12" or a date. */
function when(ms: number): string {
  const d = new Date(ms)
  const now = new Date()
  const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  const day = (x: Date) => x.toDateString()
  if (day(d) === day(now)) return t('היום') + ' ' + time
  const y = new Date(now)
  y.setDate(now.getDate() - 1)
  if (day(d) === day(y)) return t('אתמול') + ' ' + time
  return d.toLocaleDateString()
}

function content(p: ProjectMeta): string {
  const parts: string[] = []
  if (!p.detected) parts.push(t('בלי זיהוי פנים'))
  else parts.push(p.blurs === 0 ? t('בלי טשטושים') : p.blurs === 1 ? t('טשטוש אחד') : t('%d טשטושים', p.blurs))
  if (p.cues > 0) parts.push(p.cues === 1 ? t('כתובית אחת') : t('%d כתוביות', p.cues))
  return parts.join(' · ')
}

const size = (bytes: number) => (bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1e6))} MB`)

/**
 * The projects, as in the app: every video opened is kept in the browser as it's edited, to be
 * opened again where it was left. Renamed or deleted from here.
 */
export function ProjectsSection({ onOpen }: { onOpen: (id: string) => void }) {
  const [list, setList] = useState<ProjectMeta[] | null>(null)
  const [all, setAll] = useState(false)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const reload = () => listProjects().then(setList)
  useEffect(() => { reload() }, [])
  if (!list) return null
  const shown = all ? list : list.slice(0, 4)
  return (
    <section class="projects">
      <div class="row">
        <h3 class="section-title grow" style="margin:4px 0">{t('הפרויקטים שלי')}</h3>
        {list.length > 4 && <button class="btn ghost small-btn" onClick={() => setAll(!all)}>{all ? t('הצג פחות') : t('הצג הכל (%d)', list.length)}</button>}
        {BETA && <ImportFromRelease onDone={reload} />}
      </div>
      {list.length === 0 ? (
        <p class="small muted" style="margin:0">{t('עדיין אין פרויקטים. כל סרטון שתפתח נשמר כאן תוך כדי עריכה, ואפשר לחזור אליו ולהמשיך מאיפה שעצרת.')}</p>
      ) : (
        <>
          <p class="small muted" style="margin:0 0 10px">{t('נשמרים תוך כדי עריכה, בדפדפן הזה בלבד')}</p>
          <div class="project-list">
            {shown.map((p) => (
              <div class="project card">
                <button class="project-open" onClick={() => onOpen(p.id)} aria-label={p.name}>
                  {p.thumb ? <img src={p.thumb} alt="" /> : <div class="ph" />}
                </button>
                <div class="grow project-info">
                  {renaming?.id === p.id ? (
                    <div class="row">
                      <input class="text-in rename-in" style="margin:0;padding:8px 10px" dir="auto" value={renaming.name} aria-label={t('שם הפרויקט')} onInput={(e) => setRenaming({ id: p.id, name: (e.currentTarget as HTMLInputElement).value })} />
                      <button class="btn tonal small-btn" disabled={!renaming.name.trim()} onClick={async () => { await renameProject(p.id, renaming.name.trim()); setRenaming(null); reload() }}>{t('שמור')}</button>
                    </div>
                  ) : (
                    <button class="project-name" onClick={() => onOpen(p.id)}><bdi>{p.name}</bdi></button>
                  )}
                  <div class="small muted"><bdi>{when(p.updated)}</bdi> · <bdi dir="ltr">{duration(p.duration)}</bdi></div>
                  <div class="small muted">{content(p)}</div>
                  {deleting === p.id && (
                    <div class="confirm">
                      <span class="small">{t('למחוק את הפרויקט? יפונו %s. הסרטון המקורי לא יימחק.', size(p.bytes))}</span>
                      <div class="row">
                        <button class="btn small-btn danger-btn project-delete-yes" onClick={async () => { await deleteProject(p.id); setDeleting(null); reload() }}>{t('מחק')}</button>
                        <button class="btn ghost small-btn" onClick={() => setDeleting(null)}>{t('ביטול')}</button>
                      </div>
                    </div>
                  )}
                </div>
                <div class="project-acts">
                  <button class="btn ghost small-btn" onClick={() => setRenaming({ id: p.id, name: p.name })}>{t('שנה שם')}</button>
                  <button class="btn ghost small-btn project-delete" style="color:#e5484d" onClick={() => setDeleting(p.id)}>{t('מחק')}</button>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
