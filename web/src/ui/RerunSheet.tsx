import { useEffect, useState } from 'preact/hooks'
import { t } from '../i18n'
import { DetectControls, type DetectChoice } from './DetectControls'
import type { RerunMode } from '../lib/rerun'

/** Closes on Escape (as the other sheets). */
function useEscape(onClose: () => void) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    addEventListener('keydown', key)
    return () => removeEventListener('keydown', key)
  }, [])
}

/** "Run detection again": the detection's mode (with its warnings) and the sensitivity, then Run. [initial] is how the video was detected. */
export function RerunSheet({ initial, onRun, onClose }: { initial: DetectChoice; onRun: (d: DetectChoice) => void; onClose: () => void }) {
  const [choice, setChoice] = useState(initial)
  useEscape(onClose)
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div class="sheet rerun-sheet" role="dialog" aria-modal="true" aria-label={t('זהה פנים שוב')}>
        <h2 class="sheet-title">{t('זהה פנים שוב')}</h2>
        <div class="sheet-body">
          <DetectControls value={choice} onChange={setChoice} />
        </div>
        <div class="actions">
          <button class="btn ghost rerun-cancel" onClick={onClose}>{t('ביטול')}</button>
          <button class="btn primary rerun-run" onClick={() => onRun(choice)}>{t('הרץ')}</button>
        </div>
      </div>
    </div>
  )
}

/**
 * What to do with the blurs that are there, asked once the detection is chosen (and not at all when a remembered choice answers).
 * "Add" is first and recommended: it never leaves fewer blurs.
 */
export function RerunAsk({ count, onChoose, onCancel }: { count: number; onChoose: (m: RerunMode, remember: boolean) => void; onCancel: () => void }) {
  const [mode, setMode] = useState<RerunMode>('add')
  const [remember, setRemember] = useState(false)
  useEscape(onCancel)
  return (
    <div class="sheet-back" onClick={(e) => { if (e.target === e.currentTarget) onCancel() }}>
      <div class="sheet rerun-ask" role="alertdialog" aria-modal="true" aria-label={t('מה לעשות בטשטושים הקיימים?')}>
        <h2 class="sheet-title">{t('מה לעשות בטשטושים הקיימים?')}</h2>
        <div class="sheet-body">
          <p class="muted" style="margin:0">{t('יש %d טשטושים בסרטון.', count)}</p>
          <div role="radiogroup" class="opts">
            <button class={'opt opt-add' + (mode === 'add' ? ' on' : '')} role="radio" aria-checked={mode === 'add'} onClick={() => setMode('add')}>
              <span class="radio" aria-hidden="true" />
              <span class="grow"><b>{t('הוסף לטשטושים הקיימים')}<span class="rec">{t('מומלץ')}</span></b><span class="small muted">{t('אף פעם לא פחות טשטושים. עריכות ידניות נשמרות.')}</span></span>
            </button>
            <button class={'opt opt-replace' + (mode === 'replace' ? ' on' : '')} role="radio" aria-checked={mode === 'replace'} onClick={() => setMode('replace')}>
              <span class="radio" aria-hidden="true" />
              <span class="grow"><b>{t('החלף את הטשטושים הקיימים')}</b><span class="small muted">{t('עריכות ידניות נשמרות.')}</span><span class="fast-warning small" role="note">{t('שים לב: טשטושים שזוהו אוטומטית ולא נמצאו שוב יוסרו.')}</span></span>
            </button>
          </div>
          <label class="check rerun-remember"><input type="checkbox" checked={remember} onChange={(e) => setRemember((e.currentTarget as HTMLInputElement).checked)} /><span>{t('זכור את הבחירה שלי')}</span></label>
        </div>
        <div class="actions">
          <button class="btn ghost rerun-ask-cancel" onClick={onCancel}>{t('ביטול')}</button>
          <button class="btn primary rerun-ask-go" onClick={() => onChoose(mode, remember)}>{t('המשך')}</button>
        </div>
      </div>
    </div>
  )
}
