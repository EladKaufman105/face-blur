import { t } from '../i18n'

/**
 * Face detection ran lighter than the app's on this device (a smaller frame, fewer detections
 * per second): faces may be missed. Shown for as long as the video is open, in the editor and
 * where no faces were found. [overlay]: a pill over the top of the video (where the layout has no
 * room for a line, a phone or tablet), not taking any room itself.
 */
export function DetectionNotice({ overlay }: { overlay?: boolean }) {
  const text = t('הזיהוי הופחת במכשיר הזה; כדאי לבדוק את הפנים.')
  if (overlay) {
    return (
      <div class="detect-reduced detect-reduced-pill" role="status" style="position:absolute;top:6px;left:6px;right:6px;z-index:5;pointer-events:none;text-align:center;padding:3px 8px;border-radius:10px;background:rgba(0,0,0,.66);color:#fff;font-size:11px;font-weight:700;line-height:1.3">
        {text}
      </div>
    )
  }
  return <p class="small detect-reduced" role="status" style="margin:0;padding:6px 12px;color:var(--primary);font-weight:700">{text}</p>
}
