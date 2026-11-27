import { t } from '../i18n'
import { PLATFORMS, PLATFORM_ICON, type Platform } from '../lib/safezone'

/** What each platform is called (for screen readers: the buttons show the logos alone). */
const NAME: Record<Platform, string> = { tiktok: 'TikTok', reels: 'Instagram Reels', shorts: 'YouTube Shorts' }

/** From the video's top edge to the pill's bottom edge (CSS px): the mock's own top row (its tabs) starts below it. */
export const SAFE_PILL_RESERVED = 40

/**
 * The safe zone's platform chooser: a small pill floating over the top of the video (the video keeps its
 * whole size), three equal segments with the platforms' logos (no text), the chosen one on a high-contrast
 * stadium that slides from the old choice to the new one while the icons' colors cross-fade with it. Each
 * segment shows 30 px and takes touches over the same 30 px (inside the pill). Beside it a small round button mirrors the zone for an app in a right-to-left language. It is the video's own overlay, so it is dark
 * glass with light icons whatever the theme; it is as wide as the video's frame, at most 122 px. It is shown
 * while the safe zone is on (the zone itself stays on without it, e.g. while a panel is open on a phone).
 */
export function SafeZoneBar({ open, platform, frameWidth, mirrored, onPick, onMirror }: { open: boolean; platform: Platform; frameWidth: number; mirrored: boolean; onPick: (p: Platform) => void; onMirror: (on: boolean) => void }) {
  const at = PLATFORMS.indexOf(platform)
  return (
    <div class={'safe-float' + (open ? ' open' : '')} aria-hidden={!open} {...(open ? {} : ({ inert: true } as any))} onPointerDown={(e) => e.stopPropagation()}>
      <div class="safe-wrap">
      <div class="safe-pill" role="radiogroup" aria-label={t('אזור בטוח')} style={{ '--at': at, '--w': `${Math.round(frameWidth)}px` } as any}>
        <span class="safe-hi" />
        {PLATFORMS.map((p) => (
          <button class={'safe-seg' + (p === platform ? ' on' : '')} data-platform={p} role="radio" aria-checked={p === platform} aria-label={NAME[p]} title={NAME[p]} onClick={() => onPick(p)}>
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><path d={PLATFORM_ICON[p]} /></svg>
          </button>
        ))}
      </div>
      <button class={'safe-mirror' + (mirrored ? ' on' : '')} aria-pressed={mirrored} aria-label={t('מראה: אפליקציה מימין לשמאל')} title={t('מראה: אפליקציה מימין לשמאל')} onClick={() => onMirror(!mirrored)}>
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M15 21h2v-2h-2v2zm4-12h2V7h-2v2zM3 5v14c0 1.1.9 2 2 2h4v-2H5V5h4V3H5c-1.1 0-2 .9-2 2zm16-2v2h2c0-1.1-.9-2-2-2zm-8 20h2V1h-2v22zm8-6h2v-2h-2v2zM15 5h2V3h-2v2zm4 8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2z" /></svg>
      </button>
      </div>
    </div>
  )
}
