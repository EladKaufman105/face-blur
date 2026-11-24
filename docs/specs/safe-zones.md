# Platform safe zones (Android)

The safe zone is **a view aid in the editor only**: a switch (with a small chooser of three segments, each a neutral glyph and the short name TikTok / Reels / Shorts, never a brand logo) draws a generic mock of a short-video app's own screens over the preview, laid out by the platform's covered areas. It shows only when the frame being edited is vertical 9:16 (a vertical export, or a vertical video as shot) and is hidden for landscape and square frames. It is never burned into the export, and it is not part of templates, template previews, batch or export placement: subtitles and the watermark are not moved by platform.

Values are fractions of a 1080x1920 frame: the part the platform's own screens cover at the top, bottom, left and right. `SafeZone.of(platform)` in `template/SafeZone.kt` holds them.

| Platform | Top | Bottom | Left | Right |
|---|---|---|---|---|
| TikTok | 240 px (12.5%) | 660 px (34.4%) | 120 px (11.1%) | 180 px (16.7%, the button column) |
| Instagram Reels | 14% | 35% | 6% | 6% |
| YouTube Shorts | 288 px (15%) | 672 px (35%) | 48 px (4.4%) | 192 px (17.8%) |

TikTok and Reels are separate choices in the chooser because their zones differ at the sides (11% / 17% against 6%) and at the top.

## Sources and how sure they are

None of the three platforms publishes one official table of numbers for organic videos. The values come from their official ad templates and guides, as measured or quoted by the pages below (checked November 2026). They are a safe margin, not an exact map: the real screens change between app versions and phones.

- TikTok: TikTok for Business, "TikTok Auction In-Feed Ads" (https://ads.tiktok.com/help/article/video-ads-specifications?lang=en) says the safe zone depends on the caption length and offers downloadable safe-zone templates (in-feed standard, LTR). The numbers above are measured from those templates (720x1280, scaled by 1.5) in https://www.manypixels.co/blog/social-media-design/tiktok-ad-specs . A caption of several lines covers more at the bottom (up to about 1014 px); we use the standard one.
- Instagram Reels: Meta's Reels ads guide (https://www.facebook.com/business/ads-guide/update/video/facebook-facebook-reels) and its safe-zone guardrail: 14% top, 35% bottom, 6% at the sides, as quoted in https://billo.app/blog/meta-ads-safe-zones and https://www.hopperhq.com/blog/instagram-reel-size/ . Meta's page itself could not be fetched here (robots).
- YouTube Shorts: Google's "Universal video ad safe zones" (https://services.google.com/fh/files/misc/universalsafezones-youtube.pdf), whose vertical template gives the safe rectangle; the pixel values are read from it in https://adkit.so/tools/safe-zones/youtube and https://www.tella.com/help/editing/social-safe-zones.md . See also Google Ads, "YouTube Shorts ad specs" (https://support.google.com/google-ads/answer/13547298).

## How the overlay works

- Overlay (`drawSafeZone` in `ui/SafeZoneMock.kt`): a generic, made-up app interface in semi-transparent white with a soft shadow, over a light shade at the top and bottom, plus a thin dashed outline around the clear middle. It copies no platform's logo, name or artwork. Elements: a tab strip at the top ("Following | For you"; TikTok adds a search icon, Shorts search and menu), a right column of buttons placed in the right margin (TikTok: avatar with plus, heart, comment, bookmark, share, sound disc; Reels: heart, comment, paper plane, menu, sound thumbnail; Shorts: like, dislike, comment, share, menu, sound thumbnail), at the bottom the account line (Reels and Shorts with an avatar and a Follow / Subscribe pill), two caption bars, a sound line, and a thin progress bar. All positions come from the platform's margins. Drawn in the editor's canvas, over the video's window only: it takes no touches.
- `SafeZone.appliesTo(exportAspect, sourceWidth, sourceHeight)` decides whether the aid is offered.
