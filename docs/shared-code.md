# Shared code plan: app and web

Goal: write each feature once. Share definitions and test vectors between `app/` and `web/`, generate plain native code from them, and never make either app slower.

Measured on `origin/develop` 4e974ce (2026-11-27). `A` = `app/src/main/java/com/elad/faceblur`, `W` = `web/src`. LOC = non-blank, non-comment lines.

## 1. Today

### 1.1 Numbers

| Area | App | Web |
|---|---|---|
| Detection, tracking, segmentation | 2,928 | 2,313 |
| Subtitles, SRT, speakers, speech | 1,056 | 1,251 |
| Cuts, silence, audio DSP, sfx | 1,247 | 839 |
| Templates, projects, batch, prefs | 1,699 | 1,468 |
| Styles, effects, rendering, picture | 2,240 | 1,451 |
| Export pipeline, errors | 770 | 411 |
| Model downloads (`A/models`) | 1,131 | 0 |
| i18n tables | 927 | 832 |
| UI, editor state, shell (incl. `styles.css`) | 14,017 | 7,597 |
| **Total** | **26,015** | **16,162** |

Tests: app 6,222 lines (31 JVM test classes, 10 instrumented files); web 7,166 lines in 42 `web/tests/*.cjs`.

Code ported by hand between the two (pairs in 1.3):

| Kind | App | Web |
|---|---|---|
| Pure logic | 2,519 | 2,190 |
| Data tables | 1,105 | 1,090 |
| Same algorithm, platform-bound | 3,248 | 3,225 |
| **Share of the codebase** | **26%** | **40%** |

Strings: the app has 912 keys and the web 805 (`i18n.ts`, `ui/templates-en.ts`, `ui/Beta.tsx`). 519 Hebrew keys are on both sides, and 34 of those have different English (8 differ only by `'` vs `’`). 393 keys are app-only and 286 web-only.

### 1.2 Already shared (files only, no code)

- `models/`: RetinaFace (6.29 MB), YuNet (233 KB), PP-HumanSeg (3.10 MB). The app downloads them through `app/src/main/assets/model-registry.json` (schema 1, 5 packs). `web/scripts/copy-assets.mjs` converts them at build time.
- 10 fonts and their licenses. The web also gets Allo instances at roundness 0/25/75/100, made with fontTools.
- 51 sound effects (Ogg, converted to AAC for the web).
- The speaker model at the same pinned commit `bb8e2af`. `copy-assets.mjs` hard-codes its URL instead of reading the registry, and checks no SHA-256.
- The test clips (`test-media`).
- The template JSON shape (`W/lib/template.ts` `templateToJson` writes the app's shape) is shared by convention only, with no test.

### 1.3 Module pairs

| App (`A/`) | Web (`W/`) | Kind |
|---|---|---|
| `analysis/DetectTuning.kt` | `lib/sensitivity.ts`, `lib/fusion.ts` | pure |
| `analysis/OfflineTracker.kt` | `lib/tracker.ts` | pure, **drifted** |
| `analysis/AnalysisStats.kt`, `batch/BatchMath.kt` | `lib/analysis-stats.ts`, `lib/lowconf.ts` | pure |
| `edit/CutMap.kt`, `edit/CutSkip.kt` | `lib/cutmap.ts` | pure, **drifted** |
| `edit/Subtitles.kt`, `subs/SubLanguages.kt`, `subs/Srt.kt` | `lib/subtitles.ts` | pure, **drifted** |
| `edit/SilenceFinder.kt`, `audio/VoiceDsp.kt`, `audio/Denoiser.kt`, `audio/Loudness.kt`, `audio/VoiceEnhancer.kt`, `subs/Fbank.kt`, `subs/Speakers.kt` | `lib/silence.ts`, `lib/voice.ts`, `lib/cleanup.ts`, `lib/speakers.ts` | pure DSP |
| `template/Template.kt`, `BuiltInTemplates.kt`, `StartRoute.kt` | `lib/template.ts` | pure + data |
| `pipeline/ExportOptions.kt`, `pipeline/ProcessingErrors.kt`, `project/ProjectGroups.kt`, `edit/Stabilizer.kt`, `edit/Reframer.kt` | `lib/exportopts.ts`, `lib/errors.ts`, `lib/projects.ts`, `lib/camera.ts` | pure |
| `subs/SubtitleStyle.kt`, `template/SafeZone.kt`, `audio/Sfx.kt`, `i18n/English.kt` | `lib/substyle.ts`, `lib/safezone.ts`, `lib/sfx-list.ts`, `i18n.ts` + `ui/templates-en.ts` | data, **drifted** |
| Constants in `FaceDetector.kt`, `RetinaFaceDetector.kt`, `Motion.kt` `CutDetector` | `lib/fusion.ts`, `lib/detector.ts`, `lib/cuts.ts` | data inside platform code |
| `FaceAnalyzer.kt`, `ManualTracker.kt`, `SubtitleRenderer.kt`, `BlurEffect.kt`, `BackgroundEffect.kt`, `Segmenter.kt`, `Watermark.kt`, `SafeZoneMock.kt`, `TemplateRunner.kt`, `BatchRunner.kt`, `Translator.kt` | `pace.ts` + `coverage.ts` + `analyze.worker.ts`, `follow.worker.ts` + `flow.ts`, `subrender.ts`, `renderer.ts`, `background.ts` + `picture.worker.ts`, `seg.ts`, `watermark.ts`, `safezonemock.ts`, `templaterun.ts`, `batch.ts`, `translate.ts` | platform |

### 1.4 Known drifts (re-verified)

| # | Drift | App | Web |
|---|---|---|---|
| 1 | CutMap units | ms in, µs inside (`Long`) | seconds (`Float64Array`) |
| 2 | Time inside a cut | `outUs` returns -1 (`CutEffect` drops the frame) | `out()` returns the cut's start |
| 3 | Overlapping cuts | not merged (relies on `EditModel.addCut`) | merged in the constructor |
| 4 | Cue almost wholly cut | `mapCues` drops it only if nothing is kept | `cutCues` drops it if under 50 ms is kept |
| 5 | Allo presets "עגול" / "אלגנטי" | `round = 100` / `0` | unset, so 50 |
| 6 | Outline choices | `[0, .08, .16, .24]` (`ui/SubtitleStylePanel.kt:305`) | `[0, .1, .18]` (`ui/SubStyleCard.tsx:106`) |
| 7 | Tracker camera-motion compensation | `OfflineTracker.cameraOffsets` | none (`analyze.worker.ts` measures no motion) |
| 8 | Which words a subtitle masks | by time (`EditModel.bleepedWords`) | by word index (`Cue.wi`, `maskCue`) |
| 9 | SRT edges | fraction cut to 3 digits (`,3456` = 345 ms), BOM stripped, empty cues skipped | `,3456` = 3.456 s, BOM kept, every cue written |
| 10 | English | 34 of 519 shared keys, e.g. voices "Low / Disguised / No sound" | "Deep / Disguise / Mute" |
| 11 | Export defaults | quality `ORIGINAL`, `saveSrt = true` | `'high'`, `srt: false` |

`docs/parity.md` marks row 8 `same` because the visible result matches.

## 2. What stays per platform

| Layer | Reason |
|---|---|
| UI (Compose vs Preact) | Different toolkits; about half of each codebase. |
| Media pipelines (Media3 vs Mediabunny/WebCodecs) | Different APIs, threading and codecs. |
| GPU shaders | The app's are GLSL ES 1.0 (`BlurEffect.kt`), the web's 3.0 (`renderer.ts`). A transpiler adds a layer and saves little. |
| Inference | LiteRT, OpenCV DNN and whisper.cpp vs ORT Web and Transformers.js. The models are shared as files already. |
| Storage, workers, services, pacing | URI permissions vs IndexedDB; a foreground service vs a tab. The web's reduced mode is an accepted exception. |

QuickJS on Android (slow hot paths, +1-2 MB) and Rust/WASM (rewrite risk) are rejected. KMP is a gated pilot only (Phase 4).

## 3. Architecture

```
shared/
  strings/en.json         # [{he, en, on: "both"|"app"|"web", enApp?, enWeb?}], in feature order
  catalog/*.json          # subtitles (fonts, effects, 28 presets, colors, outline), looks, sfx,
                          # voices, safezones, export (choices, bitrate constants), edit (joins, fillers)
  detect/detect.json      # thresholds, pool, fusion, detector sizes, tracker, cut detector, rates
  vectors/<area>/*.json   # golden vectors (Phase 2)
  perf/baseline.json      # chunk sizes, APK size, bench medians
ci/shared_gen.py          # python3, stdlib only; --target kotlin|ts|all; --check
```

`model-registry.json` stays in the app's assets: the app reads it at run time, and that is not a hot path.

**Outputs.** Generated files are committed, so the IDE, reviews and Node tests work without a build. Each starts with `// GENERATED by ci/shared_gen.py from shared/<file>. Do not edit.`

- Kotlin goes in `A/gen/` (package `com.elad.faceblur.gen`): `English.kt`, `SubCatalog.kt`, `Looks.kt`, `SfxCatalog.kt`, `Voices.kt`, `SafeZones.kt`, `ExportConst.kt`, `EditConst.kt`, `DetectConst.kt`.
- TypeScript goes in `W/gen/`, one module per area: `strings-en.ts`, `subcatalog.ts`, … `detect.ts`. Workers then never import the strings. Today the main chunk holds 31,313 Hebrew characters and every worker 313 or fewer.

**Gradle** (`app/build.gradle.kts`):

```kotlin
val genShared by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("shared")); inputs.file(rootProject.file("ci/shared_gen.py"))
    outputs.dir("src/main/java/com/elad/faceblur/gen")
    commandLine("python3", rootProject.file("ci/shared_gen.py").path, "--target", "kotlin")
}
tasks.named("preBuild") { dependsOn(genShared) }
android.sourceSets["test"].resources.srcDir(rootProject.file("shared/vectors"))
```

**Vite** (`web/vite.config.ts`): a `sharedGen()` plugin runs `python3 ../ci/shared_gen.py --target ts` in `buildStart`, and again when a file in `../shared` changes (`configureServer`).

**Generator rules:**

- It writes a file only if the content changed, so incremental builds stay incremental.
- TypeScript output uses erasable syntax only (`export const`, `as const`; no `enum`), because `web/tests/fusion.cjs` imports `.ts` under `--experimental-strip-types`.
- Kotlin enums (`SubFont`, `SubEffect`, `VoiceFx`, `Platform`) are generated whole: entries in JSON order and names unchanged, because projects and templates save `name`. Their methods move to extension functions.
- Maps over 400 entries are split into private chunk functions, to stay under the JVM's 64 KB method limit.

**CI:**

- `python3 ci/shared_gen.py --check` (regenerate, then `git diff --exit-code`) runs in the `android-build` and `web-build` jobs.
- `pr.yml` filters: add `shared/**` to `android` and to `web`, and `ci/shared_gen.py` to `web`.
- `web.yml` `push.paths`: add both.
- `build.yml` `paths-ignore` stays as it is: `shared/` changes the APK and must make a beta.

**i18n checks:**

- `ci/i18n_check.py` reads `shared/strings/en.json` (`on` = `both` or `app`) instead of `English.kt`.
- `ci/web_i18n_check.py` reads the same file (`both` or `web`) instead of the `EN` and `addEnglish` tables.
- Both keep their literal `t('...')` scans and report duplicate or empty entries from the JSON.

## 4. Performance guardrails

### 4.1 Baseline

T0.1 records the full baseline before any change. Already known:

- Web chunks (gh-pages `beta/`, develop@c310a29), raw / gzip:
  - `index`: 430,047 / 142,188
  - `analyze.worker`: 549,634 / 150,954
  - `export.worker`: 1,136,373 / 287,723
  - `detect.worker`: 130,156 / 42,147
  - ORT wasm: 28,747,738 raw
- APK: `ONIT-Beta-2.1.0-beta.31.apk` (arm64, release), 24,143,295 B.

Not measured anywhere yet: cold start, analysis ms per video second and export speed.

### 4.2 How a task proves "no slower"

| Change | Gate |
|---|---|
| Constants or tables moved | Every web chunk within ±0.5% gzip; release APK within ±4 KB; `javap -c -constants` shows the same literals |
| Pure-logic refactor | A/B bench, base and head alternating 7 times; head median ≤ 1.05 × base |
| Behaviour change (Phase 3) | A/B bench plus the end-to-end timings, on the same runner class |

The tools:

- `ci/perf_check.py web-size` (in `web-build`): reads `web/dist/assets`, strips the hashes and compares with `shared/perf/baseline.json`. It fails on more than +1 KB or +0.5% gzip in `index`, or growth of more than 32 B (hash slack) in a worker chunk, unless the PR updates the baseline with a reason.
- `ci/perf_check.py bench`: runs `web/tests/bench.cjs` (`track`, `fuse`, `widen`, 10^6 `CutMap.out`, a 2,000-cue SRT) as A/B.
- JVM `TrackerBench.kt` (only with `ONIT_BENCH=1`): runs the same input, `shared/vectors/tracker/bench.json` (3,000 frames at 30 fps, 6 faces, seeded). It prints ms per video second at sensitivity 0 and 0.5. A sub-agent runs it on base and on head, and puts both numbers in the PR body.
- For information, since they are noisy:
  - `SelfTest` report lines `analysis_ms` and `render_ms`.
  - Cold start in `ci/e2e.sh`: `am start -W`, `TotalTime`, median of 5.
  - `e2e.cjs` analysis and export ms.
  - After a merge, `build.yml` writes the APK size to the job summary and warns at more than 50 KB over the previous beta.

### 4.3 Why generated constants cost nothing

- kotlinc inlines `const val` primitives at every use. Enums and `val` objects are built once, as they are today.
- TypeScript `export const` literals are what the code already has.
- No JSON is read at run time. Importing JSON in Vite would not be the same: with `json.stringify: 'auto'`, Vite turns a JSON file over 10 kB into a `JSON.parse` call, so the generator emits literals instead.

## 5. Work plan

Each task is one PR into `develop` that follows CLAUDE.md and passes the gates in 4.2. "Behaviour: none" means the vectors and benches show identical output.

### Phase 0: baseline and plumbing

**T0.1 Baseline and perf harness.** Effort 1.5 d. Depends on nothing. Risk low. Behaviour: none.

- Outputs: `ci/perf_check.py`, `ci/make_bench_vectors.py` (seeded), `web/tests/bench.cjs`, `TrackerBench.kt`, the `SelfTest` timing lines, the cold-start median in `ci/e2e.sh`, `shared/perf/baseline.json`.
- Accept: the baseline from `develop` is committed, and `web-size` passes on `develop`.

**T0.2 Skeleton and generator.** Effort 1 d. Depends on T0.1. Risk low (Gradle needs python3; add it to CONTRIBUTING.md). Behaviour: none.

- Outputs: `ci/shared_gen.py`, the Gradle and Vite wiring, the CI check and path filters, and the first table, `catalog/safezones.json`.
- Steps: `SafeZone.of` and `zoneOf` read the generated values, and the literals they replace are deleted.
- Accept:
  - `SafeZoneTest` passes.
  - A hand edit to a generated file fails CI.
  - Chunk sizes stay within ±0.5%.

### Phase 1: one source for definitions

**T1.1 Strings.** Effort 1.5 d. Depends on T0.2. Risk low. Behaviour: none.

- Steps:
  1. Import all 1,198 keys into `en.json`, tagged from where each exists today.
  2. For the 34 conflicts, set `en` to the app's text and add `enWeb` with the web's.
  3. Generate `gen/English.kt` and `W/gen/strings-en.ts`. `I18n.kt` and `i18n.ts` import them, and `addEnglish` stays.
  4. Delete `English.kt`, the `EN` table and `templates-en.ts`, and update both checks.
- Accept:
  - Both checks pass.
  - A script diff shows the generated maps equal the old ones.
  - `index` stays within ±0.5%.
- Parity: "Wording differences" becomes "pending D1".

**T1.2 Subtitle catalogue.** Effort 1.5 d. Depends on T0.2. Risk medium (enum generation). Behaviour: none.

- Steps:
  1. Move `SubFont`, `SubEffect`, `SubPresets`, the colors, the default style and `ROUNDS` into `subtitles.json`. The web's effect ids (`wordBox`) are derived from the app's names (`WORD_BOX`).
  2. Platform overrides keep today's values: `app: {round}` on the two Allo presets, and `outline: {app, web}`.
- Accept:
  - `TemplateTest`, `TemplatePreviewTest`, `style.cjs` and `parity.cjs` pass.
  - A vector lists every saved enum name.

**T1.3 Looks, sfx, voices, export and edit constants.** Effort 1 d. Depends on T1.2. Risk low. Behaviour: none.

- Sources:
  - `BuiltInTemplates.kt` ↔ `BUILT_IN`
  - `SfxLibrary.builtins` ↔ `SFX_GROUPS`
  - `VoiceFx` ↔ `VOICES`
  - The export choices and bitrate constants (0.13, 0.6, 0.65, 1.4, 0.5, 500k-150M, 128k)
  - `CUT_JOIN_MS` 160 ↔ `CUT_JOIN`, the `SilenceFinder` step, keep and fillers, and the sentence ends
- Accept: `TemplateTest`, `AudioAndCutMathTest`, `sound.cjs` and `templates.cjs` pass.

**T1.4 Detection constants and registry.** Effort 1 d. Depends on T0.2. Risk low (privacy path, so A/B is required). Behaviour: none.

- `detect.json` holds:
  - `DEFAULT` and `MOST` thresholds; the pool 0.20 / 0.25 / 0.45; `MAX_EXTRA_CHECKS` 12; `SAME_FACE_IOU` 0.3.
  - Fusion: IoU 0.3, weight 0.8, YuNet-only 0.6 at ×0.85, NMS 0.4.
  - `RF_THRESHOLD` 0.35, `YUNET_THRESHOLD` 0.4, `CLEAR` 0.8.
  - Sides 1280 / 960 / 640 / 480 / 192; rates 15 / 8 / 5 (×0.98).
  - The 11 `OfflineTracker` constants, and `CutDetector` 25 / 0.75 / 45.
- Constants that exist only on the web (`OLD`, `BUDGET_MS`, `APP_SHARE`, the reduced thresholds) stay in `pace.ts` and `fusion.ts`.
- `copy-assets.mjs` takes the speaker model's URL and SHA-256 from `model-registry.json`, and verifies it.
- Accept:
  - `DetectModesTest`, `fusion.cjs` and `fast.cjs` pass (the constants check in `fusion.cjs` reads `W/gen/detect.ts`).
  - `javap` shows the same literals.
- Parity: the top privacy row's constants become CI-enforced.

### Phase 2: golden vectors

Format: `{"cases":[{name, in, out, tol?, knownDrift?}]}`, with times in integer ms. A case tagged `knownDrift: "D4"` asserts each platform's current output (`outApp` / `outWeb`) until that decision lands.

| Task | Content | Effort | Depends |
|---|---|---|---|
| T2.1 Harness | `app/src/test/.../vectors/Vectors.kt` (it uses `org.json`, already a test dependency) and `web/tests/vectors.cjs`, run in `ci/web-tests.sh unit` | 0.5 d | T0.2 |
| T2.2 Cuts, cues, SRT | `CutMap` (overlapping, touching and end cuts), `mapCues` / `cutCues`, `Srt.parse` / `parseSrt` and `format` / `toSrt` (BOM, 4-digit fractions, tags, empty cues, he/ar/fa), cue split and merge, sentence ends. Drifts 1-4 and 9 are tagged. | 1 d | T2.1 |
| T2.3 Templates, size, silence, low confidence | Template JSON round trip app → web → app after canonical sorting, and the other way. Size estimate: first extract a pure `ExportMath.videoBitrate` (`VideoInfo` holds an `android.net.Uri`). `SilenceFinder` on PCM described by parameters. `lowConfidenceTracks`. Risk medium. | 1.5 d | T2.1 |
| T2.4 Detection | Tracker on fixed detections with zero motion: tracks and frames exact, boxes within 1e-4 (the app computes in `Float`). `fuse`. `thresholds(s)` at s = 0..1 in steps of 0.05. `quantize`, `roundUp`, `levels`, `combine`. Monotonicity: every box at k is present at k + 0.05. App `cadence(NORMAL)` against web `appRate` at 12-120 fps. | 1.5 d | T2.1, T1.4 |

Accept for every T2 task: `testDebugUnitTest` and `vectors.cjs` pass, and each parity row backed by a vector cites its file. Behaviour: none.

### Phase 3: unify drifted semantics (behaviour changes)

Each task runs only after its owner decision (section 6) and removes its `knownDrift` tags. Every task needs the A/B bench; T3.7 also needs the end-to-end timings.

| Task | Decision | Effort | Risk |
|---|---|---|---|
| T3.1 English for the 34 keys; drop `enApp` / `enWeb` | D1 | 0.5 d | low |
| T3.2 Allo roundness and outline choices from one list | D2, D3 | 0.5 d | low (old values map to the nearest) |
| T3.3 One CutMap contract | D4 | 1 d | medium (SRT times) |
| T3.4 One SRT rule | D5 | 0.5 d | low |
| T3.5 Export defaults | D8 | 0.5 d | low |
| T3.6 Masking model | D6 | 0.5 d (accept) to 5 d (converge) | high if converged |
| T3.7 Web tracker camera-motion compensation | D7 | 3 d | high (analysis time) |

### Phase 4: optional KMP pilot (T4.1)

Put `CutMap` and the SRT parse and format in a `shared-kmp` module (JVM + Kotlin/JS IR), checked by the Phase 2 vectors. The web uses it through a thin TypeScript adapter.

Go only if every budget holds:

- `index` grows at most +20 KB gzip, and the workers not at all.
- The APK grows at most +50 KB.
- Benches at most 1.05 × today on both platforms. Kotlin `Long` is emulated on JS, so the pilot uses `Double` or `Int` ms.
- `web-build` and `ci/pages-build.sh` take at most +3 min (they now need a JDK and Gradle).
- `npm run dev` hot reload still works.

Effort 5 d. Depends on Phase 2, T3.3 and T3.4. Risk high. The result is a go/no-go note added here.

## 6. Owner decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | English for the 34 conflicting keys | app / web / per key | Per key, with the app as the default. Use "Deep / Disguise / Mute" for voices and ASCII `'`. |
| D2 | Allo presets' roundness | app 100 / 0, or web 50 | App: it is the design, and the web already ships the r0 and r100 fonts. |
| D3 | Outline widths | app's 4, or web's 3 | App's 4 on both platforms. |
| D4 | CutMap contract | inside a cut: -1, cut start, or keep both APIs; cue drop at 0 or 50 ms; overlap merging | Keep both APIs, pinned by vectors (both drop the frame). Merge overlaps in both constructors. Drop cues under 50 ms on both. Units stay per platform. |
| D5 | SRT edge rules | app / web | App's rules. |
| D6 | Masking model | converge on time, converge on index, or accept both | Accept both as an exception (`MaskPolicyTest` and `splitmask.cjs` guard each). Add vectors that check the outcome. |
| D7 | Web camera-motion compensation | add it / accept it with a zero-motion vector | Accept for now. Try it separately, with motion measured on frames already decoded, in a parallel worker; merge only at +0% analysis time on the 4 test clips. |
| D8 | Export defaults | app / web | App's quality default. The SRT default stays per platform, as an accepted exception. |
| D9 | Run the KMP pilot | yes / no | Not before Phases 1 and 2 land; then decide by the budgets. |

## 7. Rules for future work

- **In `shared/`:**
  - every user-visible string;
  - every table both platforms list;
  - every numeric constant with a counterpart on the other platform;
  - one vector file for each pure algorithm that exists on both.
- **Per platform:** UI, media, shaders, inference, storage, workers, pacing, and constants with no counterpart.
- A pure-logic change on one platform updates its vectors. The other platform passes them or carries a `knownDrift` tag plus a parity row.
- Never hand-edit `gen/`. Any growth in size or time updates `shared/perf/baseline.json` and states the reason.

Proposed CLAUDE.md lines (not applied):

- "Strings, catalogues and detection constants live in `shared/`. Edit the JSON there and run `python3 ci/shared_gen.py`. Never edit `app/.../gen/` or `web/src/gen/`."
- "A change to shared pure logic adds or updates `shared/vectors/`. `./gradlew testDebugUnitTest` and `node web/tests/vectors.cjs` must both pass."
- "No PR may make analysis or export slower, or grow the APK or a web chunk, without a reason and an update to `shared/perf/baseline.json`."
- Build section: "Both builds need python3 (stdlib only) for `ci/shared_gen.py`."
- The list of web files that deploy the web app: add `shared/` and `ci/shared_gen.py`.

Proposed for `docs/parity.md`: each `same` row backed by a vector cites the vector file, and the `parity` agent edits `shared/` instead of porting tables.

## 8. Status

Done on `feature/shared-phase0` (behaviour: none; javap and bundle evidence in the PR):

- T0.1: `ci/perf_check.py` (`web-size` in `web-build`, `bench`, `fusion-time`, `record`), `ci/make_bench_vectors.py` and `shared/vectors/tracker/bench.json`, `web/tests/bench.cjs`, `TrackerBench.kt`, the `SelfTest` lines `analysis_ms` / `render_ms`, the cold-start median in `ci/e2e.sh`, and `shared/perf/baseline.json` measured on develop 4e974ce.
- T0.2: `ci/shared_gen.py`, `shared/catalog/safezones.json`, the Vite plugin, the `--check` step (in `pr.yml`'s `changes` job, which both required checks depend on) and the path filters.
- T1.4: `shared/detect/detect.json` (50 constants) and the speaker model's URL and SHA-256 from the registry.

Differences from the plan:

- No Gradle `genShared` task. The generated Kotlin is committed and CI checks it, so an Android build needs no python3; the task could not be tested here (no Android SDK).
- `TrackerBench` runs with `ONIT_BENCH=1` instead of `-Pbench`, so `app/build.gradle.kts` is unchanged.
- TypeScript fractions are emitted as their computed values (the division stays in a comment), so the bundler sees plain literals (the constants become module-level vars: about +106 B gzip, no runtime cost; Kotlin `const val`s are inlined); exported constants are re-exported (`export { X }`), never copied into a new `const`.
- In T1.4 `OLD`, `BUDGET_MS`, `APP_SHARE`, the reduced thresholds and the app-only `0.98` and `AGREE_CLEAR` stay where they are.
