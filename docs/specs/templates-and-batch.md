# Edit templates and batch processing (Android)

Status: agreed with the owner 2026-11-24. Android only; the web app comes later. Arabic/Farsi subtitles are out of scope here.

## 1. Templates

A template is a named set of editing choices applied to a video. It has three parts:

- **Look (theme):** subtitle style (font, colors, effect, position, speaker colors), blur look (blur / pixelate / cover, strength), watermark placement and style.
- **Destination:** export settings (frame shape original / 9:16 / 1:1, resolution, fps, quality, codec, SRT file).
- **Processing:** what is done to the video (below).

The user's own templates combine all three under a name.

### Categories in a template

Each category has an "include in template" switch. An excluded category changes nothing: the current settings or defaults stay.

| Category | Stored |
|---|---|
| Face blur | none / blur / pixelate / cover; strength; area size (padding); extended (sensitive) detection |
| Subtitles | create automatically (yes/no); speech model (Hebrew-tuned / multilingual) and spoken language; translate; style; line split rule (words/chars); burned-in language; save SRT |
| Voice | voice change; denoise level; voice enhance; even loudness |
| Cuts | auto-cut silences (yes/no, min pause); auto-cut filler words (yes/no; needs subtitles' word timings) |
| Background | off / blur / color / picture. A picture is copied into the template's own storage. |
| Stabilize | level |
| Export | frame shape, resolution, fps, quality, codec, watermark |

Deliberately not in templates: sound effects (SFX) and the words chosen for bleeping. They are per video.

An imported font that is no longer on the phone falls back to the default font, with a short notice.

### Built-in looks (themes)

A few looks ship with the app. Each has a preview picture and sets only the Look part:

- **Clean:** white subtle text.
- **Bold:** the current word pops, highlighted in yellow.
- **News:** a dark bar behind the text.
- **Minimal:** one small line.

### Built-in destinations

- **TikTok / Reels:** 9:16, 1080p, 30 fps.
- **Shorts:** 9:16, 1080p.
- **WhatsApp:** 720p, small file.
- **As the source:** no change.

### Where templates are used

- **Ready screen** (after picking one video): choose a template. The settings fill in from it.
- **Editor:** "Save as template" from the current video's settings.
- **Settings:** list of templates: rename, duplicate, edit, delete.

Built-in looks and destinations can't be deleted; they can be duplicated and edited as the user's own.

Templates are stored as JSON in app storage, reusing the existing `toJson`/`fromJson` of `SubtitleStyle`, `AudioEdit`, `Background`, `Watermark` and the export options.

## 2. Batch processing

1. On the home screen: **"Several videos"**. Pick up to **10** videos from the gallery, then a template. Show a total time estimate (from the learned analysis/render/subtitle speeds).
2. **Before starting:**
   - If the template creates subtitles and the speech model isn't downloaded (~570 MB), download it first.
   - Check free storage for the estimated outputs.
3. **Start.** Processing goes on with the screen off and the app in the background, in a foreground service with a progress notification. No pausing for heat or battery.
4. **Queue screen.** Each video's status: waiting, analyzing, subtitles, exporting, saved, failed (with the reason), no faces. Actions: skip a video, cancel all.
5. **One failure doesn't stop the rest.** Every video is saved to `Movies/ONIT` and also as a project (reusing `ProjectStore`), so it can be reopened in the editor.
6. **At the end:** a list with a thumbnail per video. "Check" on each opens it in the editor, to fix and export again. Flagged for checking:
   - no faces found;
   - faces with low detection confidence;
   - failed.

The single-video automatic flow (`MainViewModel.start()` with review off) is the base: analyze, then apply the template's processing (subtitles, voice, cuts, background, stabilize) without UI, then render.

## Out of scope (v1)

- The web app.
- Arabic/Farsi subtitles.
- Pausing for heat or battery.
