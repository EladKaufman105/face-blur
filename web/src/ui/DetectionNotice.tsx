import { t } from '../i18n'

/**
 * Face detection ran lighter than the app's on this device (a smaller frame, fewer detections
 * per second): faces may be missed. Shown for as long as the video is open, in the editor and
 * where no faces were found. [overlay]: a pill over the top of the video (where the layout has no
 * room for a line, a phone or tablet), not taking any room itself.
 */
const PILL = 'position:absolute;top:6px;left:6px;right:6px;z-index:5;pointer-events:none;display:grid;gap:3px'
const PILL_LINE = 'text-align:center;padding:3px 8px;border-radius:10px;background:rgba(0,0,0,.66);color:#fff;font-size:11px;font-weight:700;line-height:1.3'

/**
 * [fast]: fast detection ran (its own words; the full warning where there's room); [reduced] (by default, unless [fast]): detection
 * ran lighter. As an [overlay], both (when both apply) stacked in one place over the video.
 */
export function DetectionNotice({ overlay, fast, reduced = !fast }: { overlay?: boolean; fast?: boolean; reduced?: boolean }) {
  const text = t('הזיהוי הופחת במכשיר הזה; כדאי לבדוק את הפנים.')
  if (overlay) {
    return (
      <div class="detect-pills" style={PILL}>
        {reduced && <div class="detect-reduced detect-reduced-pill" role="status" style={PILL_LINE}>{text}</div>}
        {fast && <div class="detect-fast detect-fast-pill" role="status" style={PILL_LINE}>{t('זיהוי מהיר: פנים קטנות או רחוקות עלולות להתפספס לרגע. כדאי לצפות בתוצאה.')}</div>}
      </div>
    )
  }
  if (fast && !reduced) return <FastNotice />
  return (
    <>
      {reduced && <p class="small detect-reduced" role="status" style="margin:0;padding:6px 12px;color:var(--primary);font-weight:700">{text}</p>}
      {fast && <FastNotice />}
    </>
  )
}

/** What fast detection may miss: where it is chosen, after the analysis and in the editor (for as long as the video is open). */
export function FastNotice() {
  return <p class="small detect-fast" role="status" style="margin:0;padding:6px 12px;color:var(--primary);font-weight:700">{t('זיהוי מהיר בודק פחות פריימים, ולכן מהיר יותר. הוא עלול לפספס לרגע פנים קטנות, רחוקות או בקצה המסך. כדאי לצפות בתוצאה לפני שמשתפים.')}</p>
}
