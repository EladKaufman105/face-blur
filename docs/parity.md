# Parity tracker: Android app and web app

The app (`app/`) and the web app (`web/`) offer the same features. This file tracks where they differ. The `parity` agent (`.claude/agents/parity.md`) keeps it current.

Source: parity audit of `origin/develop` at bf160f9 (2026-11-26), refreshed at f60e7ba (2026-11-26) for the app's PR #34 (no-blur warning, MP3 with edits, find faces, face list, SRT import) and the web's parity gaps (sentence ends, merge with the previous subtitle, size estimate, error details and report, blur view, subtitle properties on a tablet, web i18n check). Paths: `A` = `app/src/main/java/com/elad/faceblur`, `W` = `web/src`. "unverified" means it could not be confirmed from code alone.

Status values:

- `same`: the same on both platforms.
- `gap-web`: the app has it, the web app does not.
- `gap-app`: the web app has it, the app does not.
- `differs`: both have it, but behaviour or look differs.
- `exception`: differs on purpose, listed under "Accepted exceptions".

Rows marked **privacy** must be closed first.

## Home screen

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Tools row | Subtitles, Remove background, Sound and effects, MP3 (`A/ui/HomeScreen.kt:204`) | Subtitles, Sound and effects, Watermark, MP3 (`W/app.tsx:392`) | differs | Add Remove background to web, Watermark to app, or list as exception |
| Batch entry while a batch runs | Hidden, banner shown (`HomeScreen.kt:185`) | Entry visible plus banner; second video refused (`app.tsx:64`) | differs | Web: hide entry while running |
| Share target | `ACTION_SEND` video, single (`AndroidManifest.xml:34`) | PWA share_target, single file, installed PWA only | differs | App could accept `SEND_MULTIPLE` into batch setup |
| Drag and drop, file handler | none | Drop anywhere, `launchQueue` (`app.tsx:90`) | exception | Platform: desktop browser |
| Projects list | Pills, rename, delete, batch folders | Same (`W/ui/Projects.tsx`) | same | |
| Default language | Always Hebrew (`A/prefs/AppPrefs.kt:25`) | Hebrew if browser language is he, else English (`W/i18n.ts:12`) | differs | Pick one rule (the web rule is better) |
| Footer / about | none | "Web version", link to the app, About (`app.tsx:229`) | exception | Web only |

## Pick, analyze, Ready

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Face detection strength (**privacy**) | RetinaFace fused with YuNet (noisy-or where both agree, YuNet-only faces from 0.6 at 0.85), 1280 px GPU / 960 px CPU, every n-th frame (n = fps / 15 rounded, at least 1: 20 fps every frame, 24 / 30 fps every second, 31-35 fps every second = 15.5-17.5/s, 50 fps every third = 16.7/s), every frame in sensitive mode; on a slow device 640 px, no YuNet, down to every (fps / 8, rounded down)-th frame (8/s or more; every frame under 16 fps) (`A/analysis/FaceDetector.kt`, `FaceAnalyzer.kt`) | The same models and fusion (`W/lib/fusion.ts`, `W/lib/detector.ts`; `yunet.onnx` is the app's file, copied at build), 1280 px GPU / 960 px CPU, the full rate: 15/s or the app's cadence, whichever is more, never under 15/s whatever the nominal frame rate reads (`fullRate`: 20 fps 20/s, 23.976-30 fps 15/s, 31 fps 15.5/s, 50 fps 16.7/s; under 15 fps every frame), every frame in sensitive mode (at the frame rate measured as the frames come, not the nominal one read from the first packets; the pace can lower it). The frames on a fixed schedule (`DetectGate`, `W/lib/pace.ts`): min(rate, fps) a second on steady timestamps (the old gate rounded gaps up to whole frames: 12/s on 24-25 fps, 10/s on 20 fps), with the old gate running beside it (every frame it would take at that rate is taken too: on variable frame rates and holes never a longer gap or a sparser 2 s; about 3% more detections on a mixed set, up to a half more at 31-50 fps), every cut's frame. On a slow device (`W/lib/pace.ts`): the rate follows the whole detection's time and the frame size RetinaFace's own (fine + coarse passes), so YuNet's fixed cost never shrinks the frame. On the CPU 960 -> 640 px first, then down to 8/s (the app's floor; it was 4/s; under 16 fps the app keeps every frame, the web goes down to 8/s); on the GPU 1280 -> 960 px only when RetinaFace takes over 80 ms (the app's GPU threshold) and 960 -> 640 px only over 200 ms (the old web rule), else the rate; back up when timings recover; paced from the first windows also on a video with no faces (the GPU's check against the CPU, which runs until faces are seen, is taken out of the timings); reduced when the rate covers under 80% of the full rate's frames, or when the frames really detected fall short: under min(80% of the full rate, 97% of the app's cadence), at the frame rate the frames really come at, in a 2 s window of a shot (or a gap over 2 s) (`W/lib/coverage.ts`; D, G, S in the stats panel and the result's log line). Never weaker than the web before YuNet (RetinaFace alone, 960 px GPU / 640 px CPU): the frame never below its size, the rate never below the rate it would have had on the device (`web/tests/fusion.cjs` checks this on simulated devices); YuNet's cost can only make the analysis longer. With one detector (the GPU's) YuNet runs in a worker of its own, beside RetinaFace. YuNet stays on. The score thresholds drop (the web's own: RetinaFace 0.35 -> 0.25, YuNet score 0.4 -> 0.3; the app's 0.3 is YuNet's NMS IoU, the same on both), so two weak detections that agree can add up to a face the tracker keeps (from 0.4). The video is marked `reduced` (see "Detection reduced on slow device"); "blur and save straight" stops at a check screen (editor, or save anyway). See `docs/web-detection.md` | same | Same strength on a capable device, on at least the app's frames at every frame rate (`web/tests/fusion.cjs`, `web/tests/detect.cjs`, `web/tests/gate.cjs`). The web also detects every cut's frame (the app only on its cadence) and measures what it detected. The reduced mode is the web's own (see Accepted exceptions) and is never silent. YuNet runs on WebAssembly in a fixed 640 x 640 input (the frame in the top left corner), as its ONNX file is fixed-size; the app's OpenCV resizes the net per frame |
| Fast detection (**privacy**) | A third mode beside normal and extended ("מהיר" / "Fast" on the ready screen and in the template editor's segmented control; `fast` in template JSON, also in project.json). The same fused detector (RetinaFace + YuNet, crop checks on, standard tracker) at 5 a second: every floor(fps / 4.9)-th frame (29.97 fps every 6th, 24 fps every 4th = 6/s), never fewer; on a trusted GPU the fine pass at the frame's size, on the CPU at 640 px from the start; a slow device only gets lighter (GPU -> CPU, then lite), never under 5/s (`A/analysis/DetectTuning.kt` `cadence`, `A/analysis/FaceAnalyzer.kt`). Its warning under the choice and on the result screen; a lasting pill in the editor (`A/ui/SettingsPanels.kt` `DetectionControls`, `FastDetectionWarning`, `A/ui/EditorShell.kt`); with the settings folded the warning stays in view; "no faces" shows it; "save straight" opens the editor instead of saving; a batch flags the video | The same choices (`W/ui/DetectControls.tsx`); 5 a second on DetectGate's schedule (no old gate), the pace's floor is the rate, the frame not shrunk to chase 15 (`sizeRate`), the CPU's fine pass at 640 px on frames decoded at 960; coverage judged against 5/s (`needRate(..., fast)`: 4/s), so no false "reduced"; the stats panel shows the mode; its warning on the ready and check screens and in the editor (a pill on a phone) for as long as the video is open (`W/ui/DetectionNotice.tsx`); flagged like low confidence, so "blur and save straight" stops at the check screen (`W/lib/lowconf.ts`). Tests: `web/tests/fusion.cjs`, `web/tests/fast.cjs` | same | No MediaPipe in fast mode (measured: it misses small and distant faces); selfie mode (next row) is the one place BlazeFace runs. Older versions read only `sensitive`: a fast template is normal detection there (never weaker) |
| Selfie detection (**privacy**) | A fourth mode for a talking head ("סלפי" / "Selfie" on the ready screen and in the template editor; `selfie` in template JSON, also in project.json): MediaPipe's BlazeFace full range (Apache-2.0) on every frame, no other detector, score 0.5, MediaPipe's anchors, decoding and weighted NMS, then the same tracker (the detector's score is also the crop re-check's) and blur. Its own optional model pack ("selfie": `blaze_face_full_range.tflite`, 1.1 MB, Google's versioned URL pinned by SHA-256 in `model-registry.json`; "download to use" until it is installed); the LiteRT interpreter on the CPU (`A/analysis/BlazeFace.kt`, `BlazeFaceDetector.kt`, `FaceAnalyzer.kt`). Its warning under the choice and on the result screen, a lasting pill in the editor, in view with the settings folded, shown by "no faces"; "save straight" opens the editor instead of saving; a batch flags the video | The same choices (`W/ui/DetectControls.tsx`); the model converted to ONNX (`models/blazeface_full_range.onnx`, `scripts/blazeface_to_onnx.py`), ONNX Runtime Web on WebAssembly in the analysis worker, frames decoded at 640 px (`W/lib/blazeface.ts`, `blazeface-detector.ts`, `W/workers/analyze.worker.ts`); no download step (the file is served with the app); the same warning, pill and check screen (`W/ui/DetectionNotice.tsx`, `W/lib/lowconf.ts`). Tests: `A` `BlazeFaceTest`, `TemplateSelfieTest` (JVM), `web/tests/blazeface.cjs`, `web/tests/selfie.cjs` | same | Honest limit: it misses about 60% of faces under 48 px, so it is offered as a mode with a warning, never the default. Sensitivity lowers its threshold 0.5 -> 0.3 (0 is the default exactly). Older versions read `sensitive` and `fast` only: a selfie template is normal detection there (never weaker). The app runs it on the CPU (no GPU delegate: untested on a device) |
| Detection sensitivity (**privacy**) | A slider, 0 to 100% in 5% steps, under the mode ("רגישות"; `sensitivity` in template JSON and project.json). 0 is today's detection exactly. Above 0 the tracker's thresholds go down linearly (cut 0.40 -> 0.25, birth 0.45 -> 0.30, strong 0.60 -> 0.45, crop re-check 0.50 -> 0.30, short tracklet re-check 0.90 -> 0.70); candidates from a fixed lower pool (RetinaFace 0.20, YuNet 0.25, YuNet-only 0.45) that aren't a default face are added and re-checked; the default tracks are kept whole and each 5% step's tracks only add to them (`A/analysis/DetectTuning.kt`, `FaceDetector.kt`, `RetinaFaceDetector.kt`, `OfflineTracker.kt`). YuNet runs once (from 0.25; the default faces those over 0.4). The extra work's time is kept out of the frames' wait the pace follows (`blockedWithoutExtras`) and its crops don't judge the GPU or fp16, so the default detection is the same as at 0 and the extra work is never dropped | The same mapping and steps (`W/lib/sensitivity.ts`, `W/lib/detector.ts`, `W/lib/tracker.ts`, `W/workers/analyze.worker.ts`); the extra work's time is kept out of the pace's timings (`extraMs`) | same | Only ever more blur (a privacy floor): never above the defaults. Tests: `A` `DetectModesTest` (JVM), `web/tests/fusion.cjs` (0 is the default exactly; detections nested; a higher sensitivity never covers less on simulated scenes), `web/tests/fast.cjs` |
| Low-confidence faces flag | Batch flags uncertain videos (`A/batch/BatchRunner.kt:101`), and a fast or selfie run | Batch flags a video with low-confidence tracks (`W/lib/lowconf.ts`, `W/lib/batch.ts`), one whose detection ran reduced, or a fast or selfie run | same | The web also flags a reduced run (stricter) |
| "No faces" path | "Mark by hand" / "Try extended detection" | Same (`W/ui/Ready.tsx:222`) | same | |
| Template with face blur OFF, Ready screen (**privacy**) | "Open in editor", "no face blur: the editor opens" and a red alert "faces will NOT be blurred" (`A/ui/ReadyScreen.kt:69`, `NoBlurWarning` `:131`) | "Open in editor" and red alert "faces will NOT be blurred" (`W/ui/Ready.tsx:137`) | same | Same Hebrew strings on both |
| Blur-off template, "Blur and save straight" (**privacy**) | "Or: save straight, no editor", the sheet's button "Save straight"; no detection, the template's processing applied (`A/ui/ReadyScreen.kt:85`, `:124`, `A/template/StartRoute.kt`) | Label "Save straight", batch-of-one path runs (`W/ui/Ready.tsx:139`, `:199`, `W/app.tsx`) | same | |
| Template picker on Ready | Cards, "Edit" next to chosen template (`A/ui/TemplatesUi.kt:255`) | Cards, inline rename, "Edit" next to chosen template; saved edits apply (`W/ui/TemplatePicker.tsx:76`) | same | Tested in `W/tests/parity2.cjs` |
| Speech model on Ready | Row with download button | Dialog with size (`W/ui/SpeechUi.tsx`) | differs | |
| Blur and save straight | Export sheet, then auto flow | Same (`Ready.tsx:196`) | same | |
| Analysis stats panel | Tap percentage | Tap or `?debug` | same | |
| Export stats panel | Tap the percentage while exporting: encode fps and frames (charts), blur ms per frame, likely encoder, time, details (audio steps, size, bitrate), (i), Copy (`A/ui/ExportStatsPanel.kt`, `A/pipeline/ExportStats.kt`, `ExportStatsTest.kt`) | Tap the percentage or `?debug`: the same tiles (`W/ui/ExportStatsPanel.tsx`, `W/lib/export-stats.ts`, `web/tests/exportstats.cjs`) | same | The encoder is "hardware (likely)": the system lists the encoder but does not say which one it used. Blur ms per frame is the time in the blur step on the GL thread (app) or the worker thread (web); the GPU may finish later. The sound steps differ a little (the app has a separate pitch step) |
| Cancel analysis | Yes | Yes | same | |
| Keep screen on | `FLAG_KEEP_SCREEN_ON` | Wake Lock if available, else warning | differs | |

## Editor: top bar, transport, timeline

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Top bar | Back, title, Properties (tablet, panel closed), blur view toggle, safe zone, undo, redo, Export | Back, Properties (tablet, panel closed), safe zone, undo, redo, blur view, save template, settings, Export (`W/ui/Editor.tsx` `editTools`) | differs | Order and the web's save-template and settings icons. On a wide web screen (desktop, ChromeOS) the site header is hidden in the editor: its logo, the settings button and an (i) with the privacy line and the beta note sit in this one compact row, so the video gets 40 px or more of height (`W/ui/EditorBrand.tsx`, `web/tests/panels.cjs`) |
| Blur view toggle (blur / outlines) | Eye icon: the real blur in the player, or outlines only (`A/ui/EditorScreen.kt:333`, `A/edit/EditorController.kt` `togglePreview`) | Eye icon `.blur-view` (shown once there are blurs): the blur, or each blur as a light shape with its number; editor only, never the export (`W/ui/Editor.tsx` `blurView`, `web/tests/parity3.cjs`) | same | |
| Transport | Time, prev, play, next, zoom -/+ | Prev, play, next, time; zoom by pinch and keys | differs | Web: add zoom buttons (unverified) |
| Mini scrubber while a panel is open (phone) | `MiniScrubber` (`A/ui/EditorShell.kt:518`) | Thin range input at the top of the open phone panel (`W/ui/Editor.tsx`, `web/tests/parity2.cjs`) | same | Hidden under 600 px of height |
| Keyboard shortcuts | none | Space, J/K/L, arrows, Ctrl+Z, Delete, Esc (`Editor.tsx:1385`) | exception | Platform: keyboard |
| Timeline lanes | Filmstrip, subtitles, sounds, blur, cuts, bleep marks | Filmstrip, subtitles, sounds, faces, cuts; bleep marks not seen (unverified) | differs | |
| Subtitle clips on timeline | Tap selects, drag edges to retime (`EditorScreen.kt:1353`) | Tap selects, handles retime, one undo step (`W/ui/Timeline.tsx`, `web/tests/cues.cjs`) | same | Web clamps to the neighbours; the app lets them overlap |
| Group bar | 6 groups with "on" dot | 6 groups | same | |
| Per-face list | Tiles with a picture of each face, tap to turn its blur off or on, All / None, one undo step each (`A/ui/FacesList.kt`, in the privacy panel `A/ui/EditorScreen.kt:611`) | Thumbnails, tap to turn on/off, All / None (`W/ui/Editor.tsx`, Faces tab) | same | |
| Find faces after entering via a tool | "Find faces" in the privacy panel of a session opened without detection; the edits stay (`A/ui/FacesList.kt:58`, `A/edit/EditorController.kt:1091`) | "Find faces" in Faces tab (`W/ui/Editor.tsx`) | same | |

## Editor: tools by group

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Privacy: blur look (style, strength, area) | `A/ui/SettingsPanels.kt:419` | `Editor.tsx:1140` | same | |
| Privacy: manual blur, auto-follow | Yes | Yes | same | |
| Privacy: resize blur on video | Pinch plus slider | Pinch (two pointers) plus slider (`web/tests/pinch.cjs`) | same | |
| Edit: cut silences, filler words, manual, by text | `A/ui/CutPanel.kt` | `W/ui/CutTab.tsx` | same | |
| Edit: stabilize | Off/light/medium/strong | Same | same | "Nothing to stabilize" wording differs |
| Picture: background (people / green / blue, none/blur/color/picture) | `A/ui/BackgroundPanel.kt` | `W/ui/PictureTab.tsx:33` | same | |
| Picture: frame 9:16 / 1:1 / 4:5 | Yes | Yes | same | |
| Subtitles: tabs | Create & translate, List, Style | Create & translate, List, Bleep words, Style (`W/ui/panels.tsx:46`) | differs | |
| Subtitles: create (**privacy**-adjacent) | Hebrew model (ivrit.ai whisper-large-v3-turbo, ggml q5_0) or multilingual; with the multilingual one a language chip row: automatic, Hebrew, Arabic, English, Persian. A picked language is forced (no detection), as on the web (`A/ui/SubtitlesSheet.kt`, `A/subs/SubLanguages.kt`, `A/subs/ModelManager.kt:19`) | Hebrew: the same ivrit.ai checkpoint as a 4-bit ONNX (community export with word timings, pinned to one commit in every request, Apache-2.0; 763 MB, size shown first): "better quality" in Hebrew on a computer (the default where the page has threads or a big GPU); on a phone or other weak device a separate named choice with a memory warning. Arabic, Persian, English: multilingual Whisper (`W/lib/speech.ts`, tests `subsplan.cjs`, `subshe.cjs`) | differs | Hebrew model and word timings now match in kind (q4 against q5_0, so timings can differ a little). Left: a phone keeps the small multilingual model unless ivrit.ai is chosen by name (760 MB needs a lot of page memory; the app runs it natively). Risk: the web model is in one uploader's Hugging Face account (`instush`), not ivrit.ai's. The pin means its files can't change, but the repo can be deleted or closed: then the download fails and the subtitles screen says the Hebrew model can't be downloaded and suggests "Fast" (multilingual); a model already in the browser keeps working. Follow-up: a fallback (mirror the files, or switch to the multilingual model by itself). Arabic and Persian: same on both. Not verified on a device: the glyphs come from the system fallback font (below) |
| Subtitles: translate | ML Kit on the device between he, en, ar and fa, every pair (two non-English languages go through English; one pack per language, downloaded once); "Translate to" chips: automatic, he, en, ar, fa (`A/subs/Translator.kt`, `A/subs/SubtitleEngine.kt`) | Translator API in Chrome/Edge only; he/en/ar/fa (`W/lib/translate.ts:5`) | differs | Differs only in engine and availability. The app keeps the "Translate to" choice per project; a subtitle holds the spoken text and one translation |
| Subtitles: Arabic and Persian text on screen | Right to left by the text, joined letters, line breaks by the system text layout; the bundled fonts have no Arabic letters, so the system font draws them (same as the web: `system-ui`) in the preview and the burned-in video | Same: canvas, bundled font then `system-ui` (`W/lib/substyle.ts` `fontFamily`) | same | Unverified on a device: the weight of the fallback glyphs follows the system font. Neither platform bundles an Arabic font; add one to both if the result looks thin |
| Subtitles: bleep words in Arabic and Persian | A bleeped word is hidden with its diacritics (marks count as part of the word) and the punctuation around it kept, like the web (`A/edit/Subtitles.kt` `mask`) | `maskCue` and `normWord` (`W/lib/subtitles.ts`) | same | The app masks by place (time), so it needs no text matching and has no `normWord`; the web needs it to find a bleeped word in edited text |
| Subtitles: sentence ends | `. ? ! …` and the Arabic question mark and full stop (`؟ ۔`) (`A/subs/SubLanguages.kt:96`) | Same (`W/lib/subtitles.ts` `wordsToCues`, `web/tests/splitmask.cjs`) | same | |
| Subtitles: SRT file name and import by language | Import: Arabic and Persian text is spoken text in that language; saved files are named by the language most of the text is in (no duplicate file when both slots have the same text) (`.ar.srt`, `.fa.srt`, `.he.srt`, `.en.srt`) | Web SRT import and download: unverified for Arabic and Persian | differs | Check the web names and the import of Arabic text |
| Subtitles: speakers | Find speakers, colors, per-cue dot | `W/ui/Speakers.tsx` | same | |
| Subtitles: import SRT | "Import SRT file" in the subtitles sheet and the list; replacing asks first and says how many bleeps go; one undo step (`A/ui/SrtImport.kt:39`) | Import with replace confirmation (`W/ui/EditorTabs.tsx:560`) | same | |
| Subtitles: download SRT now | "Save subtitles (SRT)" in the list's header and the Subtitles panel, and on the done screen (`A/ui/SrtSave.kt`, `A/ui/MainScreen.kt` `DoneCard`): saves the export's .srt files at once (one per language, named by the video, to Download/ONIT) and says what it saved. No export switch: an export from the editor saves no .srt by itself (a template's run and a batch keep the template's own choice) | "Save subtitles (SRT)" with the list (always in view, not folded) and on the done screen (`W/ui/EditorTabs.tsx` `srtBlock`, `W/ui/Exporting.tsx`); no export switch (the template editor keeps its own) | same | Both write the masked file (the export's: hidden words, cut times) |
| Subtitles: find and replace, split rule | Yes | Yes | same | |
| Subtitles: bleep words placement | Under Sound > Voice and bleeps, and list header | Under Subtitles > Bleep words | differs | Align placement or accept |
| **Privacy**: which words a subtitle masks | By place: the text's words (`Subtitles.mask(s, hide)`, hidden = words under a bleep) | By place too: a subtitle owns its word indices (`Cue.wi`); split divides them, merge joins them, retime keeps them (`W/lib/subtitles.ts`, `web/tests/splitmask.cjs`) | same | Once the text is edited the web also masks every token that says what a bleeped word said, and warns if one is in no token |
| **Privacy**: subtitle edited so a bleeped word may show | Not applicable: positions are always masked | A persistent warning on the subtitle; export (burn-in or SRT) is blocked until fixed or confirmed (`W/ui/ExportSheet.tsx`) | differs | Web only: its text is edited freely, as in the app, but its words are tracked by index |
| **Privacy**: translation of a subtitle with bleeped words | Translates the masked text of the whole sentence and keeps it as the translation's source (`Cue.trm`, saved with the project); a translation not made from the masked text shown now (old, typed by hand, text edited or more words bleeped since) is hidden in the preview, the burned-in video (any mode) and the .srt files, with a notice under the subtitle; kept, not deleted. Old projects: hidden over bleeped words (`A/edit/EditModel.kt` `translationShown`, `translationJobs`, `exportCues`; `A/test/.../TranslationMaskTest.kt`) | Translates the masked text; a translation not made from the masked text is hidden in the video, with a notice (`trVisible`) | same | "Complete translation" counts the sentences to translate again separately, and one undo step brings back hand-typed translations it replaces. A sentence with a cut word does not export its translation (the app cannot cut it to match; unverified on the web). The app decides per sentence (a split sentence shares one source); the web per subtitle. "Translate again" re-translates the hidden ones from the masked text |
| **Privacy**: subtitle made by hand over a bleeped word | Masks by time (`EditModel.kt` `bleepedWords`) | The cue is given the free words in its time; one with none masks the bleeped words that start inside it by text, and warns if one is in no token | same | Web: an edit that respells and moves a bleeped word warns (a pure in-place respelling does not) |
| Deleting a subtitle with bleeped words | Bleeps are time ranges and stay | The words stay bleeped; the Subtitles tab lists them as bleeps with no subtitle and removes them on request | same | |
| Download SRT in the list | Masked, the export's files (hidden words, hidden translations, cut times); bleeped words show only when hiding them in subtitles is switched off, and then it asks first (`A/ui/SrtSave.kt`) | The main action is the masked file (as the export's); a second, quieter "Download SRT (unmasked, for editing)" shows only when words are bleeped, and asks first | differs | The app has no unmasked copy for editing |
| Bleep loudness | The beep's peak follows the speech it replaces: RMS over the bleep and 0.1 s either side, times 1.0 (a sine 3 dB under the speech), kept between 0.02 and 0.32; 0.16 until the sound is decoded in the editor (`A/audio/BleepLevel.kt`, `VoiceDsp`; `FaceBlurProcessor.bleepLevels` for the export) | The same formula and constants (`W/lib/voice.ts` `bleepLevel`, `speechRms`); the constants are `shared/audio/bleep.json` | differs | Tests: `A` `BleepLevelTest`, `web/tests/bleep.cjs`, both on `shared/vectors/bleep/level.json`. No user-facing bleep volume on either platform. Measured at slightly different points: the web from the sound it processes (its own sample rate, before the voice change), the app from the 16 kHz mono decode (times the gain); results differ by a little, both inside the same clamp. Not worth a shared decode path |
| Subtitles job progress | The app's engine reports its own phases | From the first second: the sound is read beside the model's download and load, with its own percent; "downloading X / Y MB", "loading the model", "recognizing N%" and the 30-second part (`W/workers/subs.worker.ts`, `W/lib/speech.ts`, `web/tests/subsstart.cjs`) | unverified | The app was not measured here |
| Subtitle style: looks (27), fonts (10 + import), effects (18), colors, outline, shadow, glow, box, size, position | `A/ui/SubtitleStylePanel.kt`: grids of small cards, 3 columns at least, more on a wide panel (`gridColumns`, about 104 dp a card) | `W/ui/SubStyleCard.tsx`: grids of small cards (`auto-fill`, about 92 px), no scrollbar of their own | same | |
| Subtitle style: outline widths | 4 (none/thin/medium/thick) | 3 (none/thin/thick) | differs | Web: add medium. Web maps an outline of 0.13 or more to "thick" (`SubStyleCard.tsx:38`), so an app style saved as medium shows as thick on web |
| Sound: cleanup (noise, enhance, loudness) | `VoicePanel.kt:188` | `EditorTabs.tsx:40` | same | |
| Sound: voice change | none, deep, high, robot, disguise, mute | Same functions; labels differ ("Low" vs "Deep", "No sound" vs "Mute") | differs | Unify labels |
| Sound: effects library (51), My sounds (20 s) | Yes | Yes | same | |
| Branding: watermark | `A/ui/WatermarkPanel.kt` | `EditorTabs.tsx:265` | same | |
| Watermark persistence | Logo file remembered | Logo kept in IndexedDB, shrunk to 512 px (`W/lib/logostore.ts`) | same | |

## Editor: tablet layout

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Layout rule | `isWide()` (`A/ui/Adaptive.kt:49`) | Media queries for 601-1100 px; desktop layout above | exception | Desktop layout is web only |
| Side rail, panel beside video, panel hide | Yes | Yes (`web/tests/parity.cjs`) | same | |
| Properties panel for selected clip | The side panel shows the selected blur's, subtitle's or sound's properties; "Properties" in the top bar when the panel is closed (`A/ui/EditorScreen.kt:1733` `PropertiesPanel`) | Subtitle: the same, in the side panel (text, translation, speaker, times, start / end here, split, merge with the previous or next, delete; `W/ui/CueBar.tsx` `CueSidePanel`, `web/tests/parity3.cjs`). Blur and sound: their bars (`W/ui/BlurBar.tsx`, the transport row) | differs | Web: blur and sound properties in the side panel too. The web shows a speaker only once speakers were found |

## Selection and context bars

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Selected blur | Bar: Properties, Split, Follow, Delete | Bar: Show/Blur toggle, Split, Start/End here, Follow, Size, Delete (`W/ui/BlurBar.tsx:48`) | differs | See per-face toggle |
| Selected subtitle | Bar and properties: text, translation, speaker, times, start/end here, split, merge, delete | Same (`W/ui/CueBar.tsx`; on a tablet the properties are in the side panel) | same | |
| Selected sound | Play, Move here, Delete; volume, start | Inline name, volume, Move here, Delete; no Play | differs | Web: add Play |
| Merge subtitle | The bar's Merge: with the next, the last with the previous; the subtitle's editor: "merge with previous" and "merge with next" (`A/ui/EditorShell.kt:285`, `A/ui/SubtitlesSheet.kt:523`) | Same: the bar's Merge, and both in the properties (`W/ui/CueBar.tsx` `.cue-merge-prev`, `.cue-merge-next`; `web/tests/parity3.cjs`) | same | |

## Safe zone

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Safe-zone overlay (TikTok / Reels / Shorts) | Switch, a small floating platform pill over the top of the video (no space of its own: the video keeps its size), mock overlay, 9:16 only; on stays on while editing and is saved per project (`safeZone` in project.json) (`A/ui/SafeZoneMock.kt`, `A/ui/EditorScreen.kt` `PlatformChooser`, `docs/specs/safe-zones.md`) | Same, drawn in the editor only (`W/lib/safezonemock.ts`, `W/ui/SafeZoneBar.tsx`); saved per project (`safe` in `ProjectState`); the export worker never imports it (`web/tests/parity2.cjs`) | same | Icons are SVG paths. A small round switch beside the pill mirrors the zone and the mock for a right-to-left app, saved per project (`safeMirrored` / `safe.mirrored`; the margins are the left-to-right ones swapped, not verified from a source). The app also remembers the last platform for new projects, as the web does. Margins, 1080x1920 (top / bottom / left / right): TikTok 240 / 660 / 120 / 300 px, Reels 14% / 35% / 6% / 6%, Shorts 288 / 672 / 48 / 192 px, one source `shared/catalog/safezones.json`; sources in `docs/specs/safe-zones.md` (TikTok: ads.tiktok.com video-ads-specifications; Meta: facebook.com/business/ads-guide Reels; Google: services.google.com/fh/files/misc/universalsafezones-youtube.pdf). TikTok right was 180 px (without its 120 px margin), now 300 |

## Export

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Resolution, format, fps, quality, codec | `A/ui/SettingsPanels.kt:110` | `W/ui/ExportSheet.tsx:60` | same | |
| Estimated output size | "Output: WxH · fps · about X MB" (`A/ui/SettingsPanels.kt:280`, `A/pipeline/ExportOptions.kt` `estimatedBytes`) | Same text and math: the video bitrate asked for plus 128 kbps of sound, over the video's length (`W/lib/exportopts.ts` `estimatedBytes`, `W/ui/ExportSheet.tsx`, `web/tests/parity3.cjs`) | same | Neither takes the cuts off the length |
| Subtitles in export | 4 choices plus burn | Burn toggle; language set in translation card | differs | |
| SRT file | Saved to Download/ONIT by "Save subtitles (SRT)" (list, panel, done screen) | Download link: the done screen's "Save subtitles (SRT)" and the panel's | differs | Platform: Download/ONIT vs a browser download |
| Watermark row in sheet | Edit button | None (own tab) | differs | |
| MP3 with edits (**privacy**) | The export sheet's "sound only (MP3)" applies voice change, cuts, bleeps, effects and cleanup; a failure fails the export, the original sound is never saved instead; all muted: nothing to save (`A/audio/Mp3Plan.kt`, `A/MainViewModel.kt:976` `exportMp3`) | MP3 in export sheet applies cuts, cleanup, voice change, effects | same | The home screen's MP3 tool converts an unedited video on both |
| Sound failure gate (**privacy**) | Audio processors run in the export pipeline, failure fails export (`A/pipeline/FaceBlurProcessor.kt:303`); unverified that every path is closed | Explicit: voice-change failure blocks export (`Editor.tsx:675`, `Exporting.tsx:39`) | differs | App: verify fail-closed, add a test |
| Lost-bleeps warning | None; bleeps stored as time ranges | Warning when new subtitles drop bleeped words | exception | App design avoids the loss |
| Location and phone metadata scrubbed (**privacy**) | `Mp4Privacy.scrubOrFail`: a failed scrub fails the export, nothing is saved (`A/pipeline/FaceBlurProcessor.kt:402`, `A/pipeline/Mp4Privacy.kt:17`) | `allowTransformationMetadata:false`, `web/tests/privacy.cjs` | same | MP3 path unverified on both |
| Result screen | Share, Open, Save subtitles (SRT), back to editing, face count, review note | Save, Share, Save subtitles (SRT), back to editing, review note | differs | "Open" has no web equivalent |
| Output location | `Movies/ONIT`, `Download/ONIT` | Browser download | exception | Platform |
| Error handling | Friendly mapping, technical details folded under it, sent with the share sheet (`A/pipeline/ProcessingErrors.kt`, `A/ui/MainScreen.kt` `MessageCard`) | Friendly mapping of the export's failures (the app's texts), technical details folded under it, copied to the clipboard (`W/lib/errors.ts`, `W/ui/ErrorReport.tsx`, `W/ui/Exporting.tsx`, `web/tests/parity3.cjs`) | same | Web copies instead of sharing: the page sends nothing itself |

## Templates

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Built-in looks | Clean, Bold, News, Minimal | Same | same | |
| Editor surface | Full page, live preview, 7 categories | Sheet, live preview, 7 categories (`W/ui/TemplateEditor.tsx:42`) | differs | Look only |
| Leave with unsaved changes | Asks "Save changes?" | Esc, backdrop, X and Cancel ask "Save changes?" (save, keep editing, discard) (`W/ui/TemplateEditor.tsx:262`) | same | Tested in `W/tests/parity2.cjs` |
| Edit entry points | Settings, Ready, batch setup | Settings, Ready, batch setup (`W/ui/TemplatePicker.tsx:76`, `W/ui/Batch.tsx:148`) | same | |
| New template from scratch | "New template" in Settings > Templates: the editor on the default face blur and subtitles (other categories off), saved as a new template; leaving it unchanged keeps nothing (`A/ui/TemplatesUi.kt`, `A/template/Template.kt` `blankTemplate`) | "New template" in Settings | same | Web starts from the browser's saved blur look and subtitle language; the app from the defaults |
| Save from editor | Export sheet, category checkboxes | Button in editor, same categories | same | |
| Spoken language / model | Model and language choice | "The language I chose" or fixed; model auto | differs | |
| Translate in template | Yes; automatic: to English, English speech to Hebrew | Same: switch in the editor (`W/ui/TemplateEditor.tsx:161`), shown in the picker (`W/ui/TemplatePicker.tsx:106`), run in automatic processing and in the editor (`W/lib/templaterun.ts:263`, `W/ui/Editor.tsx:539`) | same | Browser's own translator (Chrome and Edge on a computer); elsewhere a note and the subtitles stay untranslated. Tested in `W/tests/tpltranslate.cjs` (stubbed translator) |
| Per-template logo | `<id>.wm.png` | `imageData` data URL | differs | |
| Rename | Settings list | Settings and inline on Ready | differs | |

## Batch

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Limit | 10 | 10 | same | |
| Runs in background | Foreground service, survives screen off | Only while the tab is open; Wake Lock | exception | See Accepted exceptions |
| Queue, ETA, percent, skip, cancel all | Yes | Yes | same | |
| Results location | `Movies/ONIT` plus projects folder | Blobs in memory, per-file download, "Save all" | exception | Memory pressure for 10 long videos (unverified) |
| Flags | Failed, no faces, low confidence, step failed | Failed, no faces, low confidence, step note (`W/lib/batch.ts` `needsCheck`) | same | |
| Free-storage check | Device storage | Browser quota | differs | |
| Speech model | Download button | Notice plus dialog | differs | |
| Blur-off warning (**privacy**) | Red alert "faces will not be blurred in these videos" (`A/ui/BatchScreens.kt:138`) | Same (`W/ui/Batch.tsx:155`) | same | |
| Folders in Projects | Rename/delete, locked while running | Rename/delete with confirm | same | |

## Settings

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Language, theme, palette | `A/ui/SettingsPanels.kt:331` | `app.tsx:267` | same | |
| Palette from wallpaper (Android 12+) | Yes | No | exception | Platform |
| Templates manager | Opens from settings | Inline in settings | differs | |
| Import projects from release | n/a | Web beta only (`W/ui/Beta.tsx`) | exception | Web beta only |

## Projects

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Save as you edit, reopen | Yes | Yes | same | |
| Video storage | Not copied; URI permission, relink flow | Full copy in IndexedDB | exception | See Accepted exceptions |
| Delete confirm with freed size | Yes | Yes | same | |
| Rename, folders, pills | Yes | Yes | same | |
| Show more | Collapses | Collapses after 4 | same | |

## Messages and alerts

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| No-face-blur warning (**privacy**) | Ready and batch (`A/ui/ReadyScreen.kt:87`, `A/ui/BatchScreens.kt:138`) | Present | same | Same rows as above |
| Voice change is not an anonymity guarantee | Yes | Yes | same | |
| Review-before-posting note | Yes | Yes | same | |
| Detection reduced on slow device | Silent: a lighter pass (640 px, no YuNet, fewer detections) shows only in the stats panel | "Detection reduced on this device; check faces." (he + en) for as long as the video is open, in the editor and on the "no faces" screen (`W/ui/DetectionNotice.tsx`); the video is flagged low confidence (batch); "blur and save straight" stops at a screen with "Open in editor" and "Save anyway" (also for low-confidence faces). Also a message when WebGPU is missing | exception | Web only. App: consider the same notice (privacy: a light pass may miss faces) |
| Unsupported browser | n/a | WebCodecs check | exception | Web only |
| Crash / report dialog | Yes (`A/CrashLog.kt`, `CrashDialog` in `A/ui/MainScreen.kt`): the last crash on the next launch, Send | An error nothing caught opens "Something went wrong" with its details, Copy and Close (`W/lib/errors.ts` `installCrashReport`, `W/ui/ErrorReport.tsx` `CrashDialog`) | same | Web shows it at once (the page keeps running) and copies instead of sending |
| Empty states | Same | Same | same | |
| Progress stage chips | Same | Same | same | |

## i18n

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Coverage check | CI `ci/i18n_check.py` | CI `ci/web_i18n_check.py` in the web build job (`.github/workflows/web-tests.yml`, part of `web-build`): every `t('<Hebrew>')` in `web/src` has English in `W/i18n.ts` or a table given to `addEnglish` (templates, beta) | same | Only literal keys are checked on both |
| Fallback | Missing English shows Hebrew | Same | same | |
| Wording differences | Voice labels "Low, Disguised, No sound"; stabilize "already steady" | "Deep, Disguise, Mute"; "barely moves" | differs | Unify |
| RTL / LTR | Timeline forced LTR | Same (`dir=ltr`) | same | |

## Accepted exceptions

Differences that are allowed. Add a row here, with a reason, before marking anything `exception`. A privacy gate is never an exception in the weaker direction.

| Exception | Reason |
|---|---|
| Web batches run only while the tab is open | A browser tab cannot run a foreground service. The web app keeps a Wake Lock and warns. |
| Web stores each project's video in IndexedDB; the app keeps a URI permission | A browser has no persistent file access. Quota and storage-full warnings cover the cost. |
| Web batch results are kept as blobs, with per-file download and "Save all" | No shared storage folder in a browser. |
| Drag and drop, file handler, keyboard shortcuts, desktop layout above 1100 px | Web platform only. |
| Palette from wallpaper | Android 12+ only. |
| Output location, "Open" on result screen, share-target type | Platform file handling. |
| Footer, About, unsupported-browser notice, slow-device notice, beta project import | Web only. |
| Web's reduced detection on a slow device (smaller frame first, then fewer detections a second, down to 8 as the app; lower score thresholds) | A browser tab has no big-core pinning or foreground service, and WebAssembly is much slower than the app's native pass; analysis must still end. It is never silent (a persistent notice and a low-confidence flag), never weaker than the web's detection before YuNet, and keeps YuNet on. |
| No lost-bleeps warning in the app | The app stores bleeps as time ranges, so no bleep is lost when subtitles are replaced. |

## How to keep parity

- A PR that changes user-facing behaviour on one platform either includes the same change on the other, or links a follow-up parity PR or issue and sets the row here to `gap-web` or `gap-app`.
- Use the `parity` agent (`.claude/agents/parity.md`) to port. Its PRs start with `Parity:` and link the source PR.
- Privacy behaviour (blur, bleep, voice change, export gates, data staying on the device) must be identical or stricter on both sides. Never weaken a gate to match the other platform.
- Keep UI structure and names the same; use each platform's idioms.
- A platform-specific difference is allowed only when it is listed under "Accepted exceptions" with a reason.
- Every user-visible string has Hebrew and English on both platforms. `ci/i18n_check.py` checks the app and `ci/web_i18n_check.py` the web app (literal `t('...')` keys); strings passed through a variable are checked by hand.
- Release PRs (develop to main) list the open `gap-*` rows in the release notes.
- Run the `parity` agent with `audit` from time to time, and before a release, to refresh this file.
