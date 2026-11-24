# Platform safe zones (Android)

A destination with a platform (TikTok, Reels, Shorts) puts the burned-in subtitles and the watermark inside that platform's safe zone, and the editor and the template previews can draw the zone over the picture (preview only, never in the export).

Values are fractions of a 1080x1920 frame: the part the platform's own screens cover at the top, bottom, left and right. `SafeZone.of(platform)` in `template/SafeZone.kt` holds them.

| Platform | Top | Bottom | Left | Right |
|---|---|---|---|---|
| TikTok | 240 px (12.5%) | 660 px (34.4%) | 120 px (11.1%) | 180 px (16.7%, the button column) |
| Instagram Reels | 14% | 35% | 6% | 6% |
| YouTube Shorts | 288 px (15%) | 672 px (35%) | 48 px (4.4%) | 192 px (17.8%) |

TikTok and Reels are separate destinations because their zones differ at the sides (11% / 17% against 6%) and at the top.

## Sources and how sure they are

None of the three platforms publishes one official table of numbers for organic videos. The values come from their official ad templates and guides, as measured or quoted by the pages below (checked November 2026). They are a safe margin, not an exact map: the real screens change between app versions and phones.

- TikTok: TikTok for Business, "TikTok Auction In-Feed Ads" (https://ads.tiktok.com/help/article/video-ads-specifications?lang=en) says the safe zone depends on the caption length and offers downloadable safe-zone templates (in-feed standard, LTR). The numbers above are measured from those templates (720x1280, scaled by 1.5) in https://www.manypixels.co/blog/social-media-design/tiktok-ad-specs . A caption of several lines covers more at the bottom (up to about 1014 px); we use the standard one.
- Instagram Reels: Meta's Reels ads guide (https://www.facebook.com/business/ads-guide/update/video/facebook-facebook-reels) and its safe-zone guardrail: 14% top, 35% bottom, 6% at the sides, as quoted in https://billo.app/blog/meta-ads-safe-zones and https://www.hopperhq.com/blog/instagram-reel-size/ . Meta's page itself could not be fetched here (robots).
- YouTube Shorts: Google's "Universal video ad safe zones" (https://services.google.com/fh/files/misc/universalsafezones-youtube.pdf), whose vertical template gives the safe rectangle; the pixel values are read from it in https://adkit.so/tools/safe-zones/youtube and https://www.tella.com/help/editing/social-safe-zones.md . See also Google Ads, "YouTube Shorts ad specs" (https://support.google.com/google-ads/answer/13547298).

## How the placement works

- Subtitles: when a destination with a platform is applied (Ready screen, batch, template), the subtitle position is moved so that a two-line block stays inside the safe area (`SafeZone.fitSubtitlePosition`). It is done once, when the template is applied: a position the user sets by hand afterwards stays.
- Watermark: its margins are at least the safe area's (`SafeZone.watermarkMargins`), on the preview and in the export (`ExportOptions.platform`).
- Overlay: shaded where the platform covers, dashed outline around what stays clear.
