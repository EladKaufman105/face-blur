# Edit templates and batch processing (Android)

Status: agreed with the owner 2026-11-24; revised after his first beta test (same day): no platforms in templates, safe zone only in the editor, batch folders and progress. Android only; the web app comes later. Arabic/Farsi subtitles are out of scope here.

## 1. Templates

A template is a named set of **technical and design choices** for subtitles and editing. It has no notion of a platform (TikTok, Reels, Shorts, WhatsApp): those are not templates. It holds:

- **Subtitle design:** font, colors, effect, position, size, speaker colors, line split.
- **Blur look:** none / blur / pixelate / cover, strength, area.
- **Watermark** placement and style.
- **Processing:** create subtitles (model, language), translate, voice change, denoise / enhance, even loudness, cut silences and filler words, background, stabilize.
- **Plain export settings:** frame shape (original / 9:16 / 1:1 / 4:5), resolution, fps, quality, codec, SRT file.

The user's own templates hold any of these under a name.

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

### Built-in templates (looks)

A few design presets ship with the app, each with a preview. They are subtitle designs with a blur look, and **applying one turns subtitle creation on** (Hebrew-tuned model, burned in) unless the user turns it off with the switch under the picker. They do not set the frame shape, size or encoding: those stay as they are.

- **Clean:** white subtle text.
- **Bold:** the current word pops, highlighted in yellow.
- **News:** a dark bar behind the text.
- **Minimal:** one small line.

Earlier builds also had destinations (TikTok, Reels, Shorts, WhatsApp, As the source) and a platform in the export part. They are gone. A saved user template that still has a platform loads as before: its export settings are kept and the platform is dropped (when saved again).

### Where templates are used

- **Ready screen** (after picking one video): one row of template cards with previews ("No template" first, then the user's own, then the built-in looks). The settings fill in from the chosen one.
- **Editor:** "Save as template" from the current video's settings.
- **Settings:** list of templates: rename, duplicate, edit, delete.

Built-in templates can't be deleted; they can be duplicated and edited as the user's own.

Templates are stored as JSON in app storage, reusing the existing `toJson`/`fromJson` of `SubtitleStyle`, `AudioEdit`, `Background`, `Watermark` and the export options.

## 2. Batch processing

1. On the home screen: **"Several videos"**. Pick up to **10** videos from the gallery, then a template. Show a total time estimate (from the learned analysis/render/subtitle speeds).
2. **Before starting:**
   - If the template creates subtitles and the speech model isn't downloaded (~570 MB), download it first.
   - Check free storage for the estimated outputs.
3. **Start.** Processing goes on with the screen off and the app in the background, in a foreground service with a progress notification. No pausing for heat or battery.
4. **Queue screen.** Each video's status: waiting, analyzing, subtitles, exporting, saved, failed (with the reason), no faces. Overall percent and time left (from the pace so far, and the learned speeds for videos not yet started) on the screen and in the notification; percent for the running video. Actions: skip a video, cancel all.
5. **One failure doesn't stop the rest.** Every video is saved to `Movies/ONIT` and also as a project (reusing `ProjectStore`), so it can be reopened in the editor.
6. **At the end:** a list with a thumbnail per video. "Check" on each opens it in the editor, to fix and export again. Flagged for checking:
   - no faces found;
   - faces with low detection confidence;
   - failed;
   - a template step that did not work out (for example no speech found, or the subtitles failed): the reason is shown on the video, it is never skipped silently.

Each batch's projects carry a batch id and are shown in the Projects list as **one folder** (named by the batch's date and time, renamable). Opening it lists its videos; each opens in the editor. Deleting the folder asks first and deletes its projects, not the videos saved in the gallery.

The single-video automatic flow (`MainViewModel.start()` with review off) is the base: analyze, then apply the template's processing (subtitles, voice, cuts, background, stabilize) without UI, then render.

## Out of scope (v1)

- The web app.
- Arabic/Farsi subtitles.
- Pausing for heat or battery.
