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
 * segment shows 30 px and takes touches over 48 px of height. It is the video's own overlay, so it is dark
 * glass with light icons whatever the theme; it is as wide as the video's frame, at most 122 px. It is shown
 * while the safe zone is on (the zone itself stays on without it, e.g. while a panel is open on a phone).
 */
export function SafeZoneBar({ open, platform, frameWidth, onPick }: { open: boolean; platform: Platform; frameWidth: number; onPick: (p: Platform) => void }) {
  const at = PLATFORMS.indexOf(platform)
  return (
    <div class={'safe-float' + (open ? ' open' : '')} aria-hidden={!open} {...(open ? {} : ({ inert: true } as any))} onPointerDown={(e) => e.stopPropagation()}>
      <div class="safe-pill" role="radiogroup" aria-label={t('אזור בטוח')} style={{ '--at': at, '--w': `${Math.round(frameWidth)}px` } as any}>
        <span class="safe-hi" />
        {PLATFORMS.map((p) => (
          <button class={'safe-seg' + (p === platform ? ' on' : '')} data-platform={p} role="radio" aria-checked={p === platform} aria-label={NAME[p]} title={NAME[p]} onClick={() => onPick(p)}>
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><path d={PLATFORM_ICON[p]} /></svg>
          </button>
        ))}
      </div>
    </div>
  )
}
