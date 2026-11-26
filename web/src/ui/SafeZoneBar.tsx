import { t } from '../i18n'
import { PLATFORMS, PLATFORM_ICON, type Platform } from '../lib/safezone'

/** What each platform is called (for screen readers: the buttons show the logos alone). */
const NAME: Record<Platform, string> = { tiktok: 'TikTok', reels: 'Instagram Reels', shorts: 'YouTube Shorts' }

/**
 * The safe zone's platform chooser, as the app's: a pill of three equal segments with the platforms'
 * logos (no text) in the theme's colors, the chosen one on a high-contrast stadium that slides from
 * the old choice to the new one while the icons' colors cross-fade with it. Each segment shows 30 px
 * and takes touches over 48 px of height. It sits in a strip of its own directly above the video
 * (not over it: the whole frame stays in view), as wide as the video's frame (at least 240 px).
 */
export function SafeZoneBar({ open, platform, frameWidth, onPick }: { open: boolean; platform: Platform; frameWidth: number; onPick: (p: Platform) => void }) {
  const at = PLATFORMS.indexOf(platform)
  return (
    <div class={'safe-strip' + (open ? ' open' : '')} aria-hidden={!open} {...(open ? {} : ({ inert: true } as any))}>
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
