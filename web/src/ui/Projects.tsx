import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { deleteBatch, deleteProject, listProjects, renameBatch, renameProject, type ProjectMeta } from '../lib/projects'
import { BETA } from '../lib/channel'
import { current as batchNow, version as batchVersion } from '../lib/batch'
import { ImportFromRelease } from './Beta'
import { CutIcon, DeleteIcon, EditIcon, FaceIcon, MicIcon, MusicIcon, PictureIcon, SubtitlesIcon, WaveformIcon } from './icons'
import './templates-en'

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

type Pill = { icon: ((p: { size?: number }) => any) | null; text: string; color: string; kind: string }

/**
 * What a project has, as in the app: blurs, subtitles (or "subtitles only"), bleeped words, a
 * changed voice, sound effects, a background, cuts, each in its tool's color; "no edits yet" if none.
 */
function pills(p: ProjectMeta): Pill[] {
  const out: Pill[] = []
  const count = (n: number, one: string, many: string) => (n === 1 ? t(one) : t(many, n))
  // Without face detection (a tool from the home screen) and no blur added by hand: "subtitles
  // only" when it has subtitles, else just what else it has.
  if (!p.detected && p.blurs === 0) {
    if (p.cues > 0) out.push({ kind: 'subs', icon: SubtitlesIcon, text: t('כתוביות בלבד · %d', p.cues), color: 'var(--amber-tint)' })
  } else {
    if (p.blurs > 0) out.push({ kind: 'blurs', icon: FaceIcon, text: count(p.blurs, 'טשטוש אחד', '%d טשטושים'), color: 'var(--coral-tint)' })
    if (p.cues > 0) out.push({ kind: 'subs', icon: SubtitlesIcon, text: count(p.cues, 'כתובית אחת', '%d כתוביות'), color: 'var(--amber-tint)' })
  }
  if (p.bleeped) out.push({ kind: 'bleeped', icon: WaveformIcon, text: count(p.bleeped, 'מילה מצופצפת', '%d מצופצפות'), color: 'var(--amber-tint)' })
  // '\u200F' (invisible) keeps these apart from 'שינוי קול' and 'רקע' elsewhere, which read differently in English.
  if (p.voice) out.push({ kind: 'voice', icon: MicIcon, text: t('שינוי קול\u200F'), color: 'var(--cyan-tint)' })
  if (p.sounds) out.push({ kind: 'sounds', icon: MusicIcon, text: count(p.sounds, 'צליל אחד', '%d צלילים'), color: 'var(--cyan-tint)' })
  if (p.background) out.push({ kind: 'background', icon: PictureIcon, text: t('רקע\u200F'), color: 'var(--violet-tint)' })
  if (p.cuts) out.push({ kind: 'cuts', icon: CutIcon, text: count(p.cuts, 'חיתוך אחד', '%d חיתוכים'), color: 'var(--muted)' })
  if (!out.length) out.push({ kind: 'none', icon: null, text: t('עדיין בלי עריכות'), color: 'var(--muted)' })
  return out
}

/** Element ids of a project's rename and delete controls (for focus and aria-controls). */
const ids = (id: string) => ({ pencil: `project-rename-btn-${id}`, input: `project-rename-in-${id}`, trash: `project-delete-btn-${id}`, confirm: `project-confirm-${id}`, yes: `project-confirm-yes-${id}` })

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
  // A batch's folder: which are open, its rename and its delete confirmation.
  const [openFolders, setOpenFolders] = useState<Set<string>>(new Set())
  const [folderRename, setFolderRename] = useState<{ id: string; name: string } | null>(null)
  const [folderDelete, setFolderDelete] = useState<string | null>(null)
  const reload = () => listProjects().then(setList)
  // The folder of the batch working now can't be renamed or deleted from under it.
  void batchVersion.value
  const working = batchNow.value && !batchNow.value.finished ? batchNow.value.id : null
  useEffect(() => { reload() }, [])
  // Renaming and the delete confirm, one open at a time; focus goes into what opens and back to
  // its button when it closes (a second tap or Escape).
  const focusNext = useRef<string | null>(null)
  useLayoutEffect(() => {
    const id = focusNext.current
    if (!id) return
    focusNext.current = null
    document.getElementById(id)?.focus()
  })
  const toggleRename = (p: ProjectMeta) => {
    const open = renaming?.id !== p.id
    setDeleting(null)
    setRenaming(open ? { id: p.id, name: p.name } : null)
    focusNext.current = open ? ids(p.id).input : ids(p.id).pencil
  }
  const toggleDelete = (p: ProjectMeta) => {
    const open = deleting !== p.id
    setRenaming(null)
    setDeleting(open ? p.id : null)
    focusNext.current = open ? ids(p.id).yes : ids(p.id).trash
  }
  if (!list) return null
  // The projects of a batch are one folder (by the batch's id), among the projects, by when it was last changed.
  type Folder = { kind: 'folder'; id: string; at: number; name?: string; items: ProjectMeta[]; updated: number }
  const folders = new Map<string, Folder>()
  const entries: ({ kind: 'project'; p: ProjectMeta; updated: number } | Folder)[] = []
  for (const p of list) {
    if (!p.batch) { entries.push({ kind: 'project', p, updated: p.updated }); continue }
    let f = folders.get(p.batch.id)
    if (!f) { f = { kind: 'folder', id: p.batch.id, at: p.batch.at, name: p.batch.name, items: [], updated: 0 }; folders.set(f.id, f); entries.push(f) }
    f.items.push(p)
    f.updated = Math.max(f.updated, p.updated)
  }
  entries.sort((a, b) => b.updated - a.updated)
  const shown = all ? entries : entries.slice(0, 4)
  const folderName = (f: Folder) => f.name ?? new Date(f.at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
  const folder = (f: Folder) => {
    const isOpen = openFolders.has(f.id)
    const bytes = f.items.reduce((n, p) => n + p.bytes, 0)
    return (
      <div class="project card batch-folder" data-batch={f.id}>
        <div class="project-top">
          <button class="folder-open" aria-expanded={isOpen} aria-label={folderName(f)} onClick={() => setOpenFolders((s) => { const n = new Set(s); if (n.has(f.id)) n.delete(f.id); else n.add(f.id); return n })}>
            <div class="folder-thumbs">
              {f.items.slice(0, 4).map((p) => (p.thumb ? <img src={p.thumb} alt="" /> : <div class="ph" />))}
            </div>
          </button>
          <div class="grow project-info">
            {folderRename?.id === f.id ? (
              <div class="row">
                <input class="text-in folder-rename-in" style="margin:0;padding:8px 10px" dir="auto" value={folderRename.name} aria-label={t('שם התיקייה')} onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); setFolderRename(null) } }} onInput={(e) => setFolderRename({ id: f.id, name: (e.currentTarget as HTMLInputElement).value })} />
                <button class="btn tonal small-btn folder-rename-ok" disabled={!folderRename.name.trim()} onClick={async () => { await renameBatch(f.id, folderRename.name.trim()); setFolderRename(null); reload() }}>{t('שמור')}</button>
              </div>
            ) : (
              <button class="project-name folder-name" onClick={() => setOpenFolders((s) => { const n = new Set(s); if (n.has(f.id)) n.delete(f.id); else n.add(f.id); return n })}><bdi>{folderName(f)}</bdi></button>
            )}
            <div class="small muted">{t('%d סרטונים', f.items.length)} · <bdi>{when(f.updated)}</bdi></div>
            {folderDelete === f.id && working !== f.id && (
              <div class="confirm folder-confirm">
                <strong class="small">{t('למחוק את התיקייה?')}</strong>
                <span class="small">{t('%d פרויקטים יימחקו (יפונו %s). הסרטונים ששמורים במכשיר, והסרטונים המקוריים, לא יימחקו.', f.items.length, size(bytes))}</span>
                <div class="row">
                  <button class="btn small-btn danger-btn folder-delete-yes" onClick={async () => { await deleteBatch(f.id); setFolderDelete(null); reload() }}>{t('מחק')}</button>
                  <button class="btn ghost small-btn" onClick={() => setFolderDelete(null)}>{t('ביטול')}</button>
                </div>
              </div>
            )}
          </div>
          <div class="project-acts">
            {working !== f.id && <>
            <button class="icon-btn folder-rename" aria-label={`${t('שנה שם')}: ${folderName(f)}`} title={t('שנה שם')} aria-pressed={folderRename?.id === f.id} onClick={() => { setFolderDelete(null); setFolderRename(folderRename?.id === f.id ? null : { id: f.id, name: folderName(f) }) }}><EditIcon size={20} /></button>
            <button class="icon-btn danger folder-delete" aria-label={`${t('מחק')}: ${folderName(f)}`} title={t('מחק')} aria-expanded={folderDelete === f.id} onClick={() => { setFolderRename(null); setFolderDelete(folderDelete === f.id ? null : f.id) }}><DeleteIcon size={20} /></button>
            </>}
          </div>
        </div>
        {isOpen && <div class="folder-items">{f.items.sort((a, b) => a.created - b.created).map(card)}</div>}
      </div>
    )
  }
  return (
    <section class="projects">
      <div class="row">
        <h3 class="section-title grow" style="margin:4px 0">{t('הפרויקטים שלי')}</h3>
        {entries.length > 4 && <button class="btn ghost small-btn" onClick={() => setAll(!all)}>{all ? t('הצג פחות') : t('הצג הכל (%d)', entries.length)}</button>}
        {BETA && <ImportFromRelease onDone={reload} />}
      </div>
      {list.length === 0 ? (
        <p class="small muted" style="margin:0">{t('עדיין אין פרויקטים. כל סרטון שתפתח נשמר כאן תוך כדי עריכה, ואפשר לחזור אליו ולהמשיך מאיפה שעצרת.')}</p>
      ) : (
        <>
          <p class="small muted" style="margin:0 0 10px">{t('נשמרים תוך כדי עריכה, בדפדפן הזה בלבד')}</p>
          <div class="project-list">
            {shown.map((e) => (e.kind === 'folder' ? folder(e) : card(e.p)))}
          </div>
        </>
      )}
    </section>
  )

  /** One project's card. */
  function card(p: ProjectMeta) {
    return (
              <div class="project card with-pills">
                <div class="project-top">
                  <button class="project-open" onClick={() => onOpen(p.id)} aria-label={p.name}>
                    {p.thumb ? <img src={p.thumb} alt="" /> : <div class="ph" />}
                  </button>
                  <div class="grow project-info">
                    {renaming?.id === p.id ? (
                      <div class="row">
                        <input id={ids(p.id).input} class="text-in rename-in" style="margin:0;padding:8px 10px" dir="auto" value={renaming.name} onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); toggleRename(p) } }} aria-label={t('שם הפרויקט')} onInput={(e) => setRenaming({ id: p.id, name: (e.currentTarget as HTMLInputElement).value })} />
                        <button class="btn tonal small-btn" disabled={!renaming.name.trim()} onClick={async () => { await renameProject(p.id, renaming.name.trim()); setRenaming(null); focusNext.current = ids(p.id).pencil; reload() }}>{t('שמור')}</button>
                      </div>
                    ) : (
                      <button class="project-name" onClick={() => onOpen(p.id)}><bdi>{p.name}</bdi></button>
                    )}
                    <div class="small muted"><bdi>{when(p.updated)}</bdi> · <bdi dir="ltr">{duration(p.duration)}</bdi></div>
                    {deleting === p.id && (
                      <div id={ids(p.id).confirm} class="confirm" onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); toggleDelete(p) } }}>
                        <span class="small">{t('למחוק את הפרויקט? יפונו %s. הסרטון המקורי לא יימחק.', size(p.bytes))}</span>
                        <div class="row">
                          <button id={ids(p.id).yes} class="btn small-btn danger-btn project-delete-yes" onClick={async () => { await deleteProject(p.id); setDeleting(null); reload() }}>{t('מחק')}</button>
                          <button class="btn ghost small-btn" onClick={() => toggleDelete(p)}>{t('ביטול')}</button>
                        </div>
                      </div>
                    )}
                  </div>
                  {/* Rename toward the name, delete at the card's outer edge; a second tap closes either. */}
                  <div class="project-acts">
                    <button id={ids(p.id).pencil} class="icon-btn project-rename" aria-label={`${t('שנה שם')}: ${p.name}`} title={t('שנה שם')} aria-pressed={renaming?.id === p.id} aria-controls={ids(p.id).input} onClick={() => toggleRename(p)}><EditIcon size={20} /></button>
                    <button id={ids(p.id).trash} class="icon-btn danger project-delete" aria-label={`${t('מחק')}: ${p.name}`} title={t('מחק')} aria-expanded={deleting === p.id} aria-controls={ids(p.id).confirm} onClick={() => toggleDelete(p)}><DeleteIcon size={20} /></button>
                  </div>
                </div>
                {/* What it has, on a line of its own across the card. */}
                <div class="project-pills">
                  {pills(p).map(({ icon: Icon, text, color, kind }) => (
                    <span class={Icon ? 'ppill ic' : 'ppill'} data-kind={kind} style={{ '--c': color } as any}>{Icon && <Icon size={13} />}{text}</span>
                  ))}
                </div>
              </div>
    )
  }
}
