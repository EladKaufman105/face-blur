# ONIT

Android video editor that works entirely on-device: blurs faces, makes styled subtitles, and anonymizes voices. Tuned for the vivo X300 Pro (arm64, Android 16), works on any arm64 Android 10+ phone.

- **Detection:** RetinaFace with a MobileNetV2 backbone (yakhyo/retinaface-pytorch, MIT, trained on WIDER FACE) fused with YuNet (OpenCV Zoo, MIT, at 640 px), about 15 times per second of video (every frame in "sensitive" mode; the tracker fills the frames between): at 1280 px on the GPU (LiteRT, checked against OpenCV on the first frames with faces) or 960 px on the CPU (OpenCV, fp16 where it agrees with fp32), plus a coarse pass for faces that fill the frame. New and uncertain detections are re-checked on an upscaled crop around them; that score is used as evidence that it really is a face (a clear face already followed for several detections isn't checked again).
- **Tracking (offline, after the analysis pass):** camera motion is measured on every frame (median optical flow over the whole frame) and hard cuts split the video into shots. Detections are linked into tracks in camera-stabilized coordinates, so a shaky handheld camera doesn't break them; a track counts as a face only with enough evidence (several confident detections, or crop-verified ones; a very short one needs two crisp crop confirmations, which stops seats of trousers and backs walking away from being blurred). Tracks of the same face split by a detector dropout are joined, gaps are interpolated (moving with the camera), ends are held a little longer, and the result is smoothed over time (position barely, size more).
- Thresholds were tuned against hand-labeled real footage (shaky camera, many people, faces at all angles, hard cuts). "Sensitive" mode detects on every frame and accepts weaker evidence: fewer missed faces, more needless blurs.
- **Editor (before export, optional), laid out like a video editor:** the player on top shows the real blur live (GPU effect in the player; or outlines only), with play/pause and frame stepping; below it a timeline with a filmstrip and every blur as a clip, packed into rows, under a fixed playhead. Drag the timeline to scrub (with fling), pinch to zoom; tap a clip to select it and drag its edges to set when it starts and ends. On the video: drag a blur to move it at the current frame (corrections are interpolated between frames you fix), pinch or use the size slider to resize. Delete, split at the playhead, undo. "Add blur" puts one where you tap and follows it automatically (median optical flow on the stored analysis frames, pulled onto overlapping face detections; ~92% of frames covered on labeled real faces) until it's lost, leaves the frame or the shot ends; any blur can be re-tracked forward or backward from the playhead.
- **Rendering:** Media3 Transformer (hardware decode/encode) with a custom GPU effect: per-face Gaussian blur in an atlas, soft ellipse mask and short fades in/out; pixelate and solid modes. Audio is copied (re-encoded only for voice edits), HDR kept when the device can encode it.
- **Export options:** resolution (original or a smaller short side), frame rate (original or lower, frames dropped evenly), quality / file size (original bitrate = no compression, high, saver) and codec (original, H.264, HEVC), with an estimated file size. HDR is kept only in HEVC.
- **Subtitles (on the device):** speech is transcribed with Whisper (whisper.cpp, CPU, quantized q5_0) using ivrit.ai's Hebrew fine-tune of large-v3-turbo (Apache-2.0; also writes English speech in English) or the stock multilingual turbo; the model (~570 MB) is downloaded once from this repo's `models-1` release. Each line is translated Hebrew <-> English with ML Kit on the device (its ~30 MB language pack is downloaded once). Subtitles show as a lane on the editor's timeline: tap one to edit its Hebrew / English text, drag its edges to retime it, add one by hand. At export they can be burned into the picture (English, Hebrew, both or none) and saved as `.he.srt` / `.en.srt` in `Download/EDITIT`.
- **App settings:** Hebrew / English UI (direction follows the language), light / dark / follow device, and a color theme (five palettes, or from the wallpaper on Android 12+).
- **Projects:** every editing session is saved as it's edited (the analysis, its frames for tracking, blurs, subtitles, speakers, bleeps, look and voice), so closing loses nothing; the home screen lists projects to reopen, rename or delete, and to export again without analyzing again. The video itself isn't copied: read access to it is kept, and if it moved, picking it again relinks the project.
- **Voice:** voice change at export and live in the player (deep, high, robot ring modulation, a disguise of both, or no sound; pitch through Sonic). Words picked from the subtitles are bleeped (a 1 kHz beep or silence, by the words' recognized times) and can be hidden in the subtitles as `****`.
- **Speakers:** each subtitle has a speaker with its own color (in the video, the timeline and the subtitle list). "Find speakers" does it on the device: an 80-bin Kaldi fbank per sentence, a WeSpeaker ResNet34-LM voice embedding (CC BY 4.0, VoxCeleb; fixed to 3 s windows and float16 weights for OpenCV DNN, ~13 MB, downloaded once from the `speaker-model` branch) and average-linkage clustering by cosine similarity (or into a number of speakers the user gives).
- **Sound effects:** 51 real recorded sounds in five groups (transitions, impacts, reactions, alerts, objects: whoosh, whip, riser, reverse cymbal, glitch, boom, braam, bass drop, sitcom laughter, cheering, booing, crickets, ba-dum-tss, wrong-answer buzzer, record scratch, ding, level-up, countdown, typing, camera, heartbeat and more; all CC0, level-matched by `scripts/build_sfx.py`, credits in `app/src/main/assets/sfx/CREDITS.txt`) plus the user's own imported sounds. Each one sits on its own timeline lane at the playhead, can be moved, made louder or quieter, and is heard in the player and mixed into the export (a silent video gets a sound track for them).
- **Background:** keep the people (PP-HumanSeg from OpenCV Zoo, Apache 2.0, one pass over the video, masks smoothed over time and kept with the project) or everything but a green / blue screen (a CbCr keyer with spill removal), and put a blur, a color or a picture behind. Seen live in the player; faces are blurred over it. CorridorKey 2.0 was considered but needs an NVIDIA GPU and ~5 GB of weights, so it can't run on a phone.
- Output is saved to `Movies/ONIT`.

CI runs an end-to-end test on an Android emulator over the clips on the `test-media` branch (results are artifacts of the workflow run), and only then publishes the APK: a beta (`vX.Y.Z-beta.N`, the "ONIT Beta" app) from `develop`, a release (`vX.Y.Z`) from `main`.

## Contributing

Work goes through pull requests: feature branches from `develop`, PRs into `develop`, releases as a PR from `develop` into `main`. See [CONTRIBUTING.md](CONTRIBUTING.md) (people) and [CLAUDE.md](CLAUDE.md) (AI agents).

## Web version

`web/` is ONIT in the browser (phone or computer), with the same promise: the video never leaves
the device. It finds faces with the same RetinaFace model (ONNX Runtime Web: WebGPU when the
browser has it, checked against the CPU on the first frames with faces, else WebAssembly),
adapts how often and at what size it detects to the device's speed, builds tracks with a port
of the app's offline tracker, previews the blur live (WebGL2) and
exports an MP4 with WebCodecs (Mediabunny), keeping the sound. Faces can be turned off and on
one by one; the look (blur, pixels, cover), strength and size match the app.

Web subtitles use Whisper through Transformers.js (the models come from Hugging Face on first use and stay in the
browser; the sound never leaves the device). Hebrew uses ivrit.ai's large-v3-turbo fine-tune (Apache-2.0), the same
checkpoint as the app, as the community ONNX export `instush/ivrit-whisper-large-v3-turbo-timestamped-onnx` (Apache-2.0,
4-bit, 763 MB, with the attention outputs word timings need), pinned to one commit in `web/src/lib/speech.ts`: the
worker puts that commit into the request path of every file, so a cached model needs no request to the Hub. On a
phone the Hebrew model is a separate, named choice (it needs a lot of memory). Arabic,
Persian and English use onnx-community's multilingual Whisper. In an incognito or private window the browser can't keep
the 763 MB Hebrew model, so it downloads again on every visit.

Build: `cd web && npm ci && npm run build` (needs python3 with onnx, numpy and
onnxconverter-common: the face model is made in float32 for the CPU and float16 for GPUs with
half precision). Add `?debug=1` to the address to see where the time goes while it analyzes. The "Web app" workflow tests it in
Chromium on a sample clip and publishes it to GitHub Pages (the `gh-pages` branch, written only
by CI): the release, from `main`, at https://eladkaufman105.github.io/face-blur/, and "ONIT Beta",
from `develop`, at https://eladkaufman105.github.io/face-blur/beta/, each updated on its own.
Both keep their data in the same site storage in the browser: clearing the site's data deletes
both versions' projects.
`ONIT_CHANNEL=beta npm run build` builds the beta: its own name, icons
(`web/scripts/make_beta_icons.py`) and installed app, and its own saved settings and projects
(`web/src/lib/channel.ts`); it can copy projects from the release, never the other way.
