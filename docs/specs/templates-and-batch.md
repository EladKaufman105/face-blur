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

A few design presets ship with the app, each with a preview. If the Hebrew speech model isn't on the phone but the multilingual one is, a look uses that one instead of waiting for a download (and the other way round only when the spoken language is Hebrew; otherwise the model is offered for download). With the Hebrew model the spoken-language choice is Hebrew only. They are subtitle designs with a blur look, and **applying one turns subtitle creation on** (Hebrew-tuned model, burned in) unless the user turns it off with the switch under the picker. They do not set the frame shape, size or encoding: those stay as they are.

- **Clean:** white subtle text.
- **Bold:** the current word pops, highlighted in yellow.
- **News:** a dark bar behind the text.
- **Minimal:** one small line.

Earlier builds also had destinations (TikTok, Reels, Shorts, WhatsApp, As the source) and a platform in the export part. They are gone. A saved user template that still has a platform loads as before: its export settings are kept and the platform is dropped (when saved again).

### Where templates are used

- **Ready screen** (after picking one video): one row of template cards with previews ("No template" first, then the user's own, then the built-in looks). The settings fill in from the chosen one.
- **Editor:** "Save as template" from the current video's settings.
- **Settings:** list of templates: edit, rename, duplicate, delete (built-in: edit, duplicate, restore the original).

Built-in templates can't be deleted; they can be duplicated, and edited (see below).

### The template editor

One full-screen page edits any template, built-in or the user's, on every property it stores. It opens from the template manager in Settings (tap a row, or "Edit"), and from the Ready screen and the batch setup (long press on a card, or "Edit" beside the chosen template).

- The name, and at the top a **live preview** (the same renderer as the cards) that follows each change: frame shape, blur look, subtitle design, watermark.
- One block per category with its include switch; inside, every property the category keeps, in the app's existing controls: the subtitle style panel (styles, fonts including imported ones, weight and roundness, colors, outline / shadow / box, effect, position, size, speaker colors), the line split, the burned-in language and SRT, the watermark controls, the blur look controls, and the plain choices for model / spoken language / translate, voice, cuts, background (what stays, what's behind, tolerance, picture), stabilize level and export (frame shape, resolution, fps, quality, codec). A category switched off and on again in the same session gets its values back.
- **Save** or **discard**; leaving with unsaved changes asks first.
- **Built-in looks are editable.** Saving one stores the user's version under the same id (`templates/builtin/<id>.json`), which replaces the shipped look everywhere (cards, manager, the Ready screen, batches). "Restore the original" (in the editor's menu and in the manager) deletes it and brings the shipped look back. Built-ins still can't be deleted. A user's version is a full copy, so when a later app version changes a shipped look, the user's version wins until it is restored. Its file format is the template JSON as before (older files, with or without `v` / `group`, unknown fields and the old platform field all still load).
- User templates: rename, duplicate and delete stay in the manager.

A template's watermark logo is its own file (`<id>.wm.png`, in `builtin/` for a look the user changed), picked into a staging file in the editor and copied in only on Save (Discard leaves the saved logo alone; Restore original removes it). The app's own logo is never changed from the template editor. A video or batch item that uses a template with a logo gets its own copy in its project.

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

Each batch's projects carry a batch id and are shown in the Projects list as **one folder** (named by the batch's date and time, renamable). Opening it lists its videos; each opens in the editor. Deleting the folder asks first and deletes its projects, not the videos saved in the gallery. **While the batch is running its folder can't be renamed or deleted** (the menu says so, and a project of it can't be renamed or deleted either).

Time left: the scale between the estimate and the real time is learned from the videos that were **saved** only (a failed or skipped video stopped early). The video running counts the stage it is in: the analysis follows its own pace, the template's processing (which has no progress of its own) counts down its estimate and never climbs, the export follows its pace.

Notes about a video are of two kinds. A **failure** (a step of the template didn't work out: no speech found, the models missing, the people not found) flags the video for a look. A **warning** (only worth knowing, for example "the subtitles were created but won't be burned in") is shown on the video and doesn't flag it.

A video with **no faces** where the processing changed nothing (no subtitles to burn, no voice, cut, background or steadying change, and an export that is as the source) is not saved as an unchanged copy, in the batch and in the single automatic flow. The project is there to open.

The single-video automatic flow (`MainViewModel.start()` with review off) is the base: analyze, then apply the template's processing (subtitles, voice, cuts, background, stabilize) without UI, then render.

## Out of scope (v1)

- The web app in v1 of the Android feature; it follows in the section below.
- Arabic/Farsi subtitles.
- Pausing for heat or battery.

## Web app

The web app (`web/`) has the same model, with these differences.

- **Storage:** templates are rows of Android-shaped JSON in their own IndexedDB database (`onit-templates` / `onit-beta-templates`, stores `user` and `builtin`; the built-in store holds only edited looks, restore deletes the row). Web-only extra keys (`imageData` for a logo or picture, `maskBleeped`) are ignored by Android.
- **Built-in looks:** Clean, Bold, News, Minimal. They carry no speech language, so the language the user chose is used. The web has one multilingual Whisper model.
- **Ready screen:** one row of cards drawn by the web's own subtitle, blur and watermark renderer on the video's frame. "No template" is first and restores the settings as they were.
- **Editor and manager:** in Settings. Edit, duplicate, rename, delete, restore original. "Save as template" in the editor.
- **Batch:** up to 10 videos, one template, run one after the other while the tab is open (notice, Wake Lock, `beforeunload` guard). Each result is a Blob in memory with a download link, a download-all list at the end, and a project in a batch folder in Projects (rename, delete with confirmation). Failed or skipped steps are reported on the video.
- **Not in the web version:** translation of subtitles in a template or batch run, saving to a gallery folder, background running, flagging low-confidence faces.
- **Tests:** `web/tests/templates.cjs` (in `ci/web-tests.sh` shard 3). The speech model is blocked; the test checks that the subtitle step was requested and reported.
