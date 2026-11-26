# Parity tracker: Android app and web app

The app (`app/`) and the web app (`web/`) offer the same features. This file tracks where they differ. The `parity` agent (`.claude/agents/parity.md`) keeps it current.

Source: parity audit of `origin/develop` at bf160f9 (2026-11-26). Paths: `A` = `app/src/main/java/com/elad/faceblur`, `W` = `web/src`. "unverified" means it could not be confirmed from code alone.

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
| Face detection strength (**privacy**) | RetinaFace fused with YuNet (noisy-or where both agree, YuNet-only faces from 0.6 at 0.85), 1280 px GPU / 960 px CPU, every n-th frame (n = fps / 15 rounded, at least 1: 20 fps every frame, 24 / 30 fps every second, 31-35 fps every second = 15.5-17.5/s, 50 fps every third = 16.7/s), every frame in sensitive mode; on a slow device 640 px, no YuNet, down to every (fps / 8, rounded down)-th frame (8/s or more; every frame under 16 fps) (`A/analysis/FaceDetector.kt`, `FaceAnalyzer.kt`) | The same models and fusion (`W/lib/fusion.ts`, `W/lib/detector.ts`; `yunet.onnx` is the app's file, copied at build), 1280 px GPU / 960 px CPU, the full rate: 15/s or the app's cadence, whichever is more (`fullRate`: 20 fps 20/s, 23.976-30 fps 15/s, 31 fps 15.5/s, 50 fps 16.7/s), every frame in sensitive mode. The frames on a fixed schedule (`DetectGate`, `W/lib/pace.ts`): min(rate, fps) a second on any timestamps (the old gate rounded gaps up to whole frames: 12/s on 24-25 fps, 10/s on 20 fps), every cut's frame. On a slow device (`W/lib/pace.ts`): the rate follows the whole detection's time and the frame size RetinaFace's own (fine + coarse passes), so YuNet's fixed cost never shrinks the frame. On the CPU 960 -> 640 px first, then down to 8/s (the app's floor; it was 4/s; under 16 fps the app keeps every frame, the web goes down to 8/s); on the GPU 1280 -> 960 px only when RetinaFace takes over 80 ms (the app's GPU threshold) and 960 -> 640 px only over 200 ms (the old web rule), else the rate; back up when timings recover; paced from the first windows also on a video with no faces (the GPU's check against the CPU, which runs until faces are seen, is taken out of the timings); reduced when the rate covers under 80% of the full rate's frames, or when the frames really detected fall short: under min(80% of the full rate, 97% of the app's cadence) in a 2 s window of a shot (`W/lib/coverage.ts`; D, G, S in the stats panel and the result's log line). Never weaker than the web before YuNet (RetinaFace alone, 960 px GPU / 640 px CPU): the frame never below its size, the rate never below the rate it would have had on the device (`web/tests/fusion.cjs` checks this on simulated devices); YuNet's cost can only make the analysis longer. With one detector (the GPU's) YuNet runs in a worker of its own, beside RetinaFace. YuNet stays on. The score thresholds drop (the web's own: RetinaFace 0.35 -> 0.25, YuNet score 0.4 -> 0.3; the app's 0.3 is YuNet's NMS IoU, the same on both), so two weak detections that agree can add up to a face the tracker keeps (from 0.4). The video is marked `reduced` (see "Detection reduced on slow device"); "blur and save straight" stops at a check screen (editor, or save anyway). See `docs/web-detection.md` | same | Same strength on a capable device, on at least the app's frames at every frame rate (`web/tests/fusion.cjs`, `web/tests/detect.cjs`, `web/tests/gate.cjs`). The web also detects every cut's frame (the app only on its cadence) and measures what it detected. The reduced mode is the web's own (see Accepted exceptions) and is never silent. YuNet runs on WebAssembly in a fixed 640 x 640 input (the frame in the top left corner), as its ONNX file is fixed-size; the app's OpenCV resizes the net per frame |
| Low-confidence faces flag | Batch flags uncertain videos (`A/batch/BatchRunner.kt:101`) | Batch flags a video with low-confidence tracks (`W/lib/lowconf.ts`, `W/lib/batch.ts`) or one whose detection ran reduced | same | The web also flags a reduced run (stricter) |
| "No faces" path | "Mark by hand" / "Try extended detection" | Same (`W/ui/Ready.tsx:222`) | same | |
| Template with face blur OFF, Ready screen (**privacy**) | Button still says "Blur faces"; no warning (`A/ui/ReadyScreen.kt:70`, `A/MainViewModel.kt:830`) | "Open in editor" and red alert "faces will NOT be blurred" (`Ready.tsx:136`) | gap-app | User can believe faces are blurred. Copy the web behaviour |
| Blur-off template, "Blur and save straight" (**privacy**) | Saves straight (`start()` -> `startTool(null, tpl)` -> `render(auto=true)`), but the button still says "טשטש ושמור ישר" (`ReadyScreen.kt:81`, `MainViewModel.kt:830`) | Label "Save straight", batch-of-one path runs (`Ready.tsx:138`, `app.tsx:138`) | differs | The app's label claims blur that does not happen. PR #34 changes the label |
| Template picker on Ready | Cards, "Edit" next to chosen template (`A/ui/TemplatesUi.kt:255`) | Cards, inline rename, no Edit (`W/ui/TemplatePicker.tsx:55`) | gap-web | Add Edit |
| Speech model on Ready | Row with download button | Dialog with size (`W/ui/SpeechUi.tsx`) | differs | |
| Blur and save straight | Export sheet, then auto flow | Same (`Ready.tsx:196`) | same | |
| Analysis stats panel | Tap percentage | Tap or `?debug` | same | |
| Cancel analysis | Yes | Yes | same | |
| Keep screen on | `FLAG_KEEP_SCREEN_ON` | Wake Lock if available, else warning | differs | |

## Editor: top bar, transport, timeline

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Top bar | Back, title, blur view toggle, safe zone, undo, redo, Export | Back, undo, redo, save template, settings, Export (`W/ui/Editor.tsx:1262`) | differs | |
| Blur view toggle (blur / outlines) | Yes (`A/ui/EditorScreen.kt:322`) | None | gap-web | Optional |
| Transport | Time, prev, play, next, zoom -/+ | Prev, play, next, time; zoom by pinch and keys | differs | Web: add zoom buttons (unverified) |
| Mini scrubber while a panel is open (phone) | `MiniScrubber` (`A/ui/EditorShell.kt:518`) | Thin range input at the top of the open phone panel (`W/ui/Editor.tsx`, `web/tests/parity2.cjs`) | same | Hidden under 600 px of height |
| Keyboard shortcuts | none | Space, J/K/L, arrows, Ctrl+Z, Delete, Esc (`Editor.tsx:1385`) | exception | Platform: keyboard |
| Timeline lanes | Filmstrip, subtitles, sounds, blur, cuts, bleep marks | Filmstrip, subtitles, sounds, faces, cuts; bleep marks not seen (unverified) | differs | |
| Subtitle clips on timeline | Tap selects, drag edges to retime (`EditorScreen.kt:1353`) | Tap selects, handles retime, one undo step (`W/ui/Timeline.tsx`, `web/tests/cues.cjs`) | same | Web clamps to the neighbours; the app lets them overlap |
| Group bar | 6 groups with "on" dot | 6 groups | same | |
| Per-face list | No list; each blur is a clip | Thumbnails, tap to turn on/off, All / None (`Editor.tsx:1116`) | gap-app | Add per-face toggle |
| Find faces after entering via a tool | Not available (`A/MainViewModel.kt:892`) | "Find faces" in Faces tab (`Editor.tsx:1099`) | gap-app | |

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
| Subtitles: create (**privacy**-adjacent) | Hebrew model (ivrit.ai whisper-large-v3-turbo, ggml q5_0) or multilingual; with the multilingual one a language chip row: automatic, Hebrew, Arabic, English, Persian. A picked language is forced (no detection), as on the web (`A/ui/SubtitlesSheet.kt`, `A/subs/SubLanguages.kt`, `A/subs/ModelManager.kt:19`) | Hebrew: the same ivrit.ai checkpoint as a 4-bit ONNX (community export with word timings, pinned to one commit in every request, Apache-2.0; 763 MB, size shown first): "better quality" in Hebrew on a computer (the default where the page has threads or a big GPU); on a phone or other weak device a separate named choice with a memory warning. Arabic, Persian, English: multilingual Whisper (`W/lib/speech.ts`, tests `subsplan.cjs`, `subshe.cjs`) | differs | Hebrew model and word timings now match in kind (q4 against q5_0, so timings can differ a little). Left: a phone keeps the small multilingual model unless ivrit.ai is chosen by name (760 MB needs a lot of page memory; the app runs it natively). Risk: the web model is in one uploader's Hugging Face account (`instush`), not ivrit.ai's. The pin means its files can't change, but the repo can be deleted or closed: then the download fails and the subtitles screen says the Hebrew model can't be downloaded and suggests "Faster" (multilingual); a model already in the browser keeps working. Follow-up: a fallback (mirror the files, or switch to the multilingual model by itself). Arabic and Persian: same on both. Not verified on a device: the glyphs come from the system fallback font (below) |
| Subtitles: translate | ML Kit on the device between he, en, ar and fa, every pair (two non-English languages go through English; one pack per language, downloaded once); "Translate to" chips: automatic, he, en, ar, fa (`A/subs/Translator.kt`, `A/subs/SubtitleEngine.kt`) | Translator API in Chrome/Edge only; he/en/ar/fa (`W/lib/translate.ts:5`) | differs | Differs only in engine and availability. The app keeps the "Translate to" choice per project; a subtitle holds the spoken text and one translation |
| Subtitles: Arabic and Persian text on screen | Right to left by the text, joined letters, line breaks by the system text layout; the bundled fonts have no Arabic letters, so the system font draws them (same as the web: `system-ui`) in the preview and the burned-in video | Same: canvas, bundled font then `system-ui` (`W/lib/substyle.ts` `fontFamily`) | same | Unverified on a device: the weight of the fallback glyphs follows the system font. Neither platform bundles an Arabic font; add one to both if the result looks thin |
| Subtitles: bleep words in Arabic and Persian | A bleeped word is hidden with its diacritics (marks count as part of the word) and the punctuation around it kept, like the web (`A/edit/Subtitles.kt` `mask`) | `maskCue` and `normWord` (`W/lib/subtitles.ts`) | same | The app masks by place (time), so it needs no text matching and has no `normWord`; the web needs it to find a bleeped word in edited text |
| Subtitles: sentence ends | `. ? ! …` and the Arabic question mark and full stop (`؟ ۔`) | `. ? ! …` only (`W/lib/subtitles.ts` `wordsToCues`) | gap-web | Web: add `؟` and `۔` to the sentence end test |
| Subtitles: SRT file name and import by language | Import: Arabic and Persian text is spoken text in that language; saved files are named by the language most of the text is in (no duplicate file when both slots have the same text) (`.ar.srt`, `.fa.srt`, `.he.srt`, `.en.srt`) | Web SRT import and download: unverified for Arabic and Persian | differs | Check the web names and the import of Arabic text |
| Subtitles: speakers | Find speakers, colors, per-cue dot | `W/ui/Speakers.tsx` | same | |
| Subtitles: import SRT | None | Import with replace confirmation (`W/ui/EditorTabs.tsx:549`) | gap-app | |
| Subtitles: download SRT now | Only at export | Button in list plus export option | gap-app | |
| Subtitles: find and replace, split rule | Yes | Yes | same | |
| Subtitles: bleep words placement | Under Sound > Voice and bleeps, and list header | Under Subtitles > Bleep words | differs | Align placement or accept |
| **Privacy**: which words a subtitle masks | By place: the text's words (`Subtitles.mask(s, hide)`, hidden = words under a bleep) | By place too: a subtitle owns its word indices (`Cue.wi`); split divides them, merge joins them, retime keeps them (`W/lib/subtitles.ts`, `web/tests/splitmask.cjs`) | same | Once the text is edited the web also masks every token that says what a bleeped word said, and warns if one is in no token |
| **Privacy**: subtitle edited so a bleeped word may show | Not applicable: positions are always masked | A persistent warning on the subtitle; export (burn-in or SRT) is blocked until fixed or confirmed (`W/ui/ExportSheet.tsx`) | differs | Web only: its text is edited freely, as in the app, but its words are tracked by index |
| **Privacy**: translation of a subtitle with bleeped words | Translates the masked text of the whole sentence and keeps it as the translation's source (`Cue.trm`, saved with the project); a translation not made from the masked text shown now (old, typed by hand, text edited or more words bleeped since) is hidden in the preview, the burned-in video (any mode) and the .srt files, with a notice under the subtitle; kept, not deleted. Old projects: hidden over bleeped words (`A/edit/EditModel.kt` `translationShown`, `translationJobs`, `exportCues`; `A/test/.../TranslationMaskTest.kt`) | Translates the masked text; a translation not made from the masked text is hidden in the video, with a notice (`trVisible`) | same | "Complete translation" counts the sentences to translate again separately, and one undo step brings back hand-typed translations it replaces. A sentence with a cut word does not export its translation (the app cannot cut it to match; unverified on the web). The app decides per sentence (a split sentence shares one source); the web per subtitle. "Translate again" re-translates the hidden ones from the masked text |
| **Privacy**: subtitle made by hand over a bleeped word | Masks by time (`EditModel.kt` `bleepedWords`) | The cue is given the free words in its time; one with none masks the bleeped words that start inside it by text, and warns if one is in no token | same | Web: an edit that respells and moves a bleeped word warns (a pure in-place respelling does not) |
| Deleting a subtitle with bleeped words | Bleeps are time ranges and stay | The words stay bleeped; the Subtitles tab lists them as bleeps with no subtitle and removes them on request | same | |
| Download SRT in the list | Only at export | Unmasked, for editing (labelled, and it asks first when words are bleeped); the export's SRT is masked | differs | |
| Subtitle style: looks (27), fonts (10 + import), effects (18), colors, outline, shadow, glow, box, size, position | `A/ui/SubtitleStylePanel.kt` | `W/ui/SubStyleCard.tsx` | same | |
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
| Properties panel for selected clip | Blur, subtitle, sound | Blur bar and sound bar only | gap-web | Needs subtitle selection |

## Selection and context bars

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Selected blur | Bar: Properties, Split, Follow, Delete | Bar: Show/Blur toggle, Split, Start/End here, Follow, Size, Delete (`W/ui/BlurBar.tsx:48`) | differs | See per-face toggle |
| Selected subtitle | Bar and properties: text, translation, speaker, times, start/end here, split, merge, delete | Same (`W/ui/CueBar.tsx`) | same | |
| Selected sound | Play, Move here, Delete; volume, start | Inline name, volume, Move here, Delete; no Play | differs | Web: add Play |
| Merge subtitle | With previous or next | With next only | gap-web | |

## Safe zone

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Safe-zone overlay (TikTok / Reels / Shorts) | Switch, platform pill, mock overlay, 9:16 only (`A/ui/SafeZoneMock.kt`, `docs/specs/safe-zones.md`) | Same, drawn in the editor only (`W/lib/safezonemock.ts`); the export worker never imports it | same | Icons are SVG paths |

## Export

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Resolution, format, fps, quality, codec | `A/ui/SettingsPanels.kt:110` | `W/ui/ExportSheet.tsx:60` | same | |
| Estimated output size | "Output: WxH, fps, about X MB" | None | gap-web | Bitrate math in `W/lib/exportopts.ts` |
| Subtitles in export | 4 choices plus burn | Burn toggle; language set in translation card | differs | |
| SRT file | Saved to Download/ONIT | Download link after export | differs | |
| Watermark row in sheet | Edit button | None (own tab) | differs | |
| MP3 with edits (**privacy**) | MP3 tool converts the raw sound; no voice change or cuts (`A/audio/Mp3Encoder.kt:56`) | MP3 in export sheet applies cuts, cleanup, voice change, effects | gap-app | Raw voice can leave the device. Apply edits or warn |
| Sound failure gate (**privacy**) | Audio processors run in the export pipeline, failure fails export (`A/pipeline/FaceBlurProcessor.kt:303`); unverified that every path is closed | Explicit: voice-change failure blocks export (`Editor.tsx:675`, `Exporting.tsx:39`) | differs | App: verify fail-closed, add a test |
| Lost-bleeps warning | None; bleeps stored as time ranges | Warning when new subtitles drop bleeped words | exception | App design avoids the loss |
| Location and phone metadata scrubbed (**privacy**) | `Mp4Privacy.scrub`, run as `runCatching { ... }.getOrDefault(-1)` and the file is saved anyway (`A/pipeline/FaceBlurProcessor.kt:364`) | `allowTransformationMetadata:false`, `web/tests/privacy.cjs` | differs | App scrub is fail-open (fix pending). MP3 path unverified on both |
| Result screen | Share, Open, back to editing, face count, review note | Save, Share, SRT, back to editing, review note | differs | "Open" has no web equivalent |
| Output location | `Movies/ONIT`, `Download/ONIT` | Browser download | exception | Platform |
| Error handling | Friendly mapping, technical details, send-report dialog | Plain messages, no report | gap-web | Add copy-details |

## Templates

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Built-in looks | Clean, Bold, News, Minimal | Same | same | |
| Editor surface | Full page, live preview, 7 categories | Sheet, live preview, 7 categories (`W/ui/TemplateEditor.tsx:42`) | differs | Look only |
| Leave with unsaved changes | Asks "Save changes?" | Esc or backdrop discards silently (`TemplateEditor.tsx:56`) | gap-web | Data loss; add confirm |
| Edit entry points | Settings, Ready, batch setup | Settings only | gap-web | |
| New template from scratch | Only "Save as template" | "New template" in Settings | gap-app | |
| Save from editor | Export sheet, category checkboxes | Button in editor, same categories | same | |
| Spoken language / model | Model and language choice | "The language I chose" or fixed; model auto | differs | |
| Translate in template | Yes | Stored, not run in automatic processing (`W/lib/templaterun.ts:258`) | gap-web | Surface in picker |
| Per-template logo | `<id>.wm.png` | `imageData` data URL | differs | |
| Rename | Settings list | Settings and inline on Ready | differs | |

## Batch

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Limit | 10 | 10 | same | |
| Runs in background | Foreground service, survives screen off | Only while the tab is open; Wake Lock | exception | See Accepted exceptions |
| Queue, ETA, percent, skip, cancel all | Yes | Yes | same | |
| Results location | `Movies/ONIT` plus projects folder | Blobs in memory, per-file download, "Save all" | exception | Memory pressure for 10 long videos (unverified) |
| Flags | Failed, no faces, low confidence, step failed | Failed, no faces, step note | gap-web | Low confidence, see above |
| Free-storage check | Device storage | Browser quota | differs | |
| Speech model | Download button | Notice plus dialog | differs | |
| Blur-off warning (**privacy**) | None | Red alert "faces will not be blurred in these videos" (`W/ui/Batch.tsx:155`) | gap-app | Add to app batch setup |
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
| No-face-blur warning (**privacy**) | Missing in Ready and batch | Present | gap-app | Same rows as above |
| Voice change is not an anonymity guarantee | Yes | Yes | same | |
| Review-before-posting note | Yes | Yes | same | |
| Detection reduced on slow device | Silent: a lighter pass (640 px, no YuNet, fewer detections) shows only in the stats panel | "Detection reduced on this device; check faces." (he + en) for as long as the video is open, in the editor and on the "no faces" screen (`W/ui/DetectionNotice.tsx`); the video is flagged low confidence (batch); "blur and save straight" stops at a screen with "Open in editor" and "Save anyway" (also for low-confidence faces). Also a message when WebGPU is missing | exception | Web only. App: consider the same notice (privacy: a light pass may miss faces) |
| Unsupported browser | n/a | WebCodecs check | exception | Web only |
| Crash / report dialog | Yes (`A/CrashLog.kt`) | None | gap-web | |
| Empty states | Same | Same | same | |
| Progress stage chips | Same | Same | same | |

## i18n

| Feature | App | Web | Status | Note / link |
|---|---|---|---|---|
| Coverage check | CI `ci/i18n_check.py` | No CI check for web; scan found only lazy Beta strings (`W/ui/Beta.tsx:28`) | gap-web | Add a web key check to `pr.yml` |
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
- Every user-visible string has Hebrew and English on both platforms. `ci/i18n_check.py` checks only the app; check web strings by hand against `web/src/i18n.ts` and `templates-en.ts`.
- Release PRs (develop to main) list the open `gap-*` rows in the release notes.
- Run the `parity` agent with `audit` from time to time, and before a release, to refresh this file.
