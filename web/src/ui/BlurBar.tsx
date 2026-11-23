import { t } from '../i18n'
import { MAX_SCALE, MIN_SCALE } from '../lib/tracks'
import type { Track } from '../lib/types'
import { CloseIcon, CutIcon, DeleteIcon, EndHereIcon, FaceIcon, ForwardIcon, RewindIcon, StartHereIcon, VisibleIcon } from './icons'

type Props = {
  track: Track
  label: string
  off: boolean
  /** Following it, how far (0..1); null when not. */
  following: number | null
  onToggle: () => void
  onSplit: () => void
  onStart: () => void
  onEnd: () => void
  onFollow: (dir: number) => void
  onScale: (s: number) => void
  onDelete: () => void
  onCancelFollow: () => void
  onClose: () => void
}

/**
 * The selected blur's actions, as the app's bar for a selected clip: hide or show it, split it
 * at the playhead, start or end it here, follow it from here, its size, delete.
 */
export function BlurBar(p: Props) {
  if (p.following !== null) {
    return (
      <div class="ctx-bar" role="toolbar">
        <span class="ctx-color" />
        <strong class="small">{t('עוקב אחרי %s…', p.label)}</strong>
        <div class="progress grow"><div style={{ width: `${Math.round(p.following * 100)}%` }} /></div>
        <span class="small muted num">{Math.round(p.following * 100)}%</span>
        <button class="btn ghost small-btn" onClick={p.onCancelFollow}>{t('ביטול')}</button>
      </div>
    )
  }
  const act = (label: string, Icon: (q: { size?: number }) => any, onClick: () => void, cls = '') => (
    <button class={'ctx-act ' + cls} onClick={onClick} title={label}>
      <Icon size={20} />
      <span>{label}</span>
    </button>
  )
  return (
    <div class="ctx-bar" role="toolbar" aria-label={p.label}>
      <span class="ctx-color" />
      <strong class="small ctx-name">{p.label}</strong>
      <div class="ctx-acts">
        {act(p.off ? t('טשטש') : t('הצג'), p.off ? FaceIcon : VisibleIcon, p.onToggle, 'blur-toggle')}
        {act(t('פצל'), CutIcon, p.onSplit, 'blur-split')}
        {act(t('התחלה כאן'), StartHereIcon, p.onStart, 'blur-start')}
        {act(t('סוף כאן'), EndHereIcon, p.onEnd, 'blur-end')}
        {act(t('עקוב אחורה'), RewindIcon, () => p.onFollow(-1), 'blur-back')}
        {act(t('עקוב קדימה'), ForwardIcon, () => p.onFollow(1), 'blur-fwd')}
        <label class="ctx-size">
          <span>{t('גודל')}</span>
          <input type="range" min={MIN_SCALE} max={MAX_SCALE} step={0.05} value={p.track.scale ?? 1} aria-label={t('גודל')} onInput={(e) => p.onScale(Number((e.currentTarget as HTMLInputElement).value))} />
        </label>
        {act(t('מחק'), DeleteIcon, p.onDelete, 'danger blur-delete')}
      </div>
      <button class="icon-btn" aria-label={t('סגור')} title={t('סגור') + ' (Esc)'} onClick={p.onClose}><CloseIcon size={18} /></button>
    </div>
  )
}
