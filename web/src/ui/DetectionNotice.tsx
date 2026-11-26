import { t } from '../i18n'

/**
 * Face detection ran lighter than the app's on this device (a smaller frame, fewer detections
 * per second): faces may be missed. Shown for as long as the video is open, in the editor and
 * where no faces were found.
 */
export function DetectionNotice() {
  return (
    <p class="small detect-reduced" role="status" style="margin:0;padding:6px 12px;color:var(--primary);font-weight:700">
      {t('הזיהוי הופחת במכשיר הזה; כדאי לבדוק את הפנים.')}
    </p>
  )
}
