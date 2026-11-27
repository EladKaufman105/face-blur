# Face detection in the web app

The web app finds faces as the Android app does: the same RetinaFace fused with the same YuNet
(`face_detection_yunet_2023mar.onnx`, the app's own file, copied to the site at build time), with
ONNX Runtime Web (RetinaFace on WebGPU when the browser has it, checked against the CPU on the
first frames with faces, else WebAssembly; YuNet on WebAssembly), at 1280 px on the GPU or 960 px
on the CPU, on at least as many frames as the app (below).

With one detector (on the GPU, or a single one on the CPU), YuNet runs in a worker of its own, at
the same time as RetinaFace (one ONNX Runtime can't run two models at once); if that worker fails
or doesn't answer within 10 s, YuNet runs in the detector's thread from then on (made only then).
In the CPU's detector workers (the cores busy already), after RetinaFace.

## Which frames

The full rate is 15 detections per second of video or the app's own cadence, whichever is more
(`fullRate` in `web/src/lib/pace.ts`). The app's `FaceAnalyzer` detects on every n-th frame,
n = fps / 15 rounded (at least 1): every frame of a 20 fps clip (20 a second), every second frame
of a 31 to 35 fps one (15.5 to 17.5), every third of a 50 fps one (16.7), every second of a 23.976
or 30 fps one (12 and 15: there the web's 15 is more). It is never under 15 a second, whatever
the nominal frame rate (read from the first packets, it can be low: a dark start, a screen
recording with a long first frame, where the frames then come faster); a clip that really has
fewer frames is detected on every frame. Wide detection: every frame, at the frame rate measured
as the frames come (`FrameRate`: the median of the last 31 intervals; the nominal one when within
3% of it): a screen recording that reads 8 fps and runs at 60 has a full rate of 60, which the
pace can lower (to 8 a second at least) on a device that can't keep it up; every frame only while
the rate covers the frames (`follow()`).

The frames are picked on a fixed schedule (`DetectGate`): each detection is due a period (1 /
rate) after the last one was due, not after the frame that met it. The old gate (the next frame at
least a period after the last detected one) rounded every gap up to whole frames: 15 a second gave
12 on a 24 or 25 fps clip, 10 on a 20 fps one, 12.5 on a 50 fps one, 10.3 on a 31 fps one, and on
WebM (times rounded to the millisecond) it could drop a frame more. The schedule detects on
min(rate, fps) frames a second on steady timestamps (exact, WebM's milliseconds, MP4's 1/90000).
It never runs ahead of the frames, nor more than a period behind them: after a hole in the frames
(a dropped stretch, a pause of a variable-rate clip) it takes the next frame, at most one more to
catch up, and goes on. A cut's frame is always detected and starts the schedule again; wide
detection at its full rate takes every frame.

The old gate runs beside the schedule, as it was (its own last frame, the same rate, at most 15 a
second, or the nominal frame rate in wide detection), and every frame it takes is detected too. On
variable frame rates, jitter or holes the schedule alone could leave out a frame the old gate took
and so leave a longer gap; with both, the frames detected are a superset of the old gate's at that
rate: no stretch of 2 s has fewer and no gap is longer, on any timestamps. A frame the old gate
takes moves the schedule to it. The cost, on a device that keeps up: on the review's mix of clips
about 3% more detections than the schedule alone; on steady clips none at 20, 29.97, 30, 59.94
and 60 fps, about 7% at 23.976 to 24 fps (16 a second), 11% at 25 (16.7), and a third to a half
where the full rate is over 15 and out of step with the old gate's (31 fps: 20.7 a second; 48 and
50 fps: 24 and 25).

## A slow device

A device too slow for that detects lighter, stepwise (`web/src/lib/pace.ts`). The rate follows the
whole detection's time; the frame's size follows RetinaFace's own passes (fine + coarse), as YuNet
runs on a fixed 640 x 640 input whatever the size. On the CPU a smaller frame first (960, then 640
px), then fewer detections a second (down to 8, the app's floor; it was 4); on the GPU a smaller
frame only when RetinaFace is what is slow (1280 -> 960 px over 80 ms, the app's threshold; 960 ->
640 px over 200 ms, the web's old rule), else fewer detections. The frame steps back up when
timings recover. The timings are taken in windows of 4 detections after the first few; the GPU's
check against the CPU (which runs until 3 frames with faces were seen, so on a video with none all
along) is taken out of a detection's time. The rate is judged by the frames it covers (min(rate,
fps)) against the full rate's: under 80% is reduced. A clip with fewer frames a second than 15
needs no more than that, and the frame isn't shrunk to reach 15. The frames covered are judged
against the frame rate measured (a nominal rate that reads high doesn't make a full detection
look reduced); the frame's size against min(15, the nominal rate), as the detection before did.

It is never weaker than the web's detection before YuNet (RetinaFace alone at 960 px on the GPU,
640 px on the CPU, 600 ms of detection per second of video): the frame never goes below that
detection's size on the device, and the rate never below the rate it would have had (estimated
from RetinaFace's fine pass, scaled to 640 px, which overestimates that rate) nor below 8 a
second; at a rate, every frame its gate took is detected. YuNet's cost can
make the analysis take longer, never lighter. `web/tests/fusion.cjs` checks both on simulated
devices (fast and slow GPUs, CPUs with one to four detectors, a cheap or costly YuNet).

## What was really detected

The analysis also measures what detection really covered, from the frames that got a detection
(`web/src/lib/coverage.ts`), per shot: D, the detections a second, over sliding windows of 2 s
(windows under 1 s, the end of a short shot, aren't judged); G, the longest gap between two
detections in a shot (frames and seconds); whether each cut's frame was detected; and S, the
smallest fine pass that ran and whether YuNet ran. A window is short when it has fewer detections
than its need, less one frame interval's (under one detection: the schedule may take its last
frame up to a frame after it was due). The need, per frame interval, is N at the frame rate the
frames really come at (1 / the interval, not the nominal rate): N = min(80% of the full rate, at
most of the frames, 97% of the app's cadence) (23.976 fps: 11.6, so the app's every second frame
isn't reduced; 30 fps: 12; 20 fps: 16; 10 fps: 8; wide: 80% of the frames). On a steady clip that
is N; a stretch of a variable-rate clip with fewer frames needs fewer, a hole in the frames almost
nothing. A gap of over 2 s within a shot (no window holds two detections; also to a shot's end,
or a shot with none) is short when it needed a detection or more. Between two detections around
an interval longer than a period at the rate aimed at (a hole in the frames), the need is at most
one: a schedule at that rate can take no more there. The stats panel ("Detected",
"Longest gap", "Strength" under more details), its copied report and the result's log line show
D, G and S.

A video is `reduced` when the pace says so (a smaller frame than the start, or a rate covering
under 80% of the full rate's frames), when a window measures short, when YuNet is missing or
failed, or with a test's weaker tune (`?detect=` on localhost). `web/tests/fusion.cjs` checks on
simulated clips (8 to 60 fps; exact, millisecond and 1/90000 times; variable frame rates, holes,
cuts, short clips) that a device that keeps up is never noticed, that one under the need is, that
any rate from 8 up keeps gaps within the tracker's limits (round(0.25 x fps) + 1 frames, 0.7 s),
and that on simulated devices the detections are never fewer, nor the gaps longer, than develop's
and PR #37's rules. `web/tests/gate.cjs` measures it in the browser on a clip at 20, 24, 25 (WebM),
30 and 31 fps.

A reduced detection is never silent: the video is marked `reduced`, the editor (and the "no faces"
screen) keep a notice, a batch flags the video, "blur and save straight" stops at a screen that
offers the editor (as it does for faces with no clear detection behind them), and the score
thresholds drop (RetinaFace 0.25, YuNet 0.3), so that two weak detections that agree can add up, in
the fusion, to a face the tracker keeps (it keeps faces from 0.4; the re-check of uncertain faces
looks at those from 0.4 up to the clear 0.8).

## Fast detection

A third mode beside normal and extended, for long videos (`fast` in the analysis request and in
templates; the app's `DetectMode.FAST`). The same detection (RetinaFace fused with YuNet, the
re-check of uncertain faces, the standard tracker) at 5 detections a second (`FAST_RATE` in
`web/src/lib/sensitivity.ts`), never fewer. No MediaPipe: measured on 8 clips it misses most faces
under 48 px. At 5 a second the tracker's hold of 0.25 s before a face's first detection covers the
0.2 s between detections; on those clips it missed only a face half out of the frame for 0.27 s
and a 21 px face for single frames (normal detection misses neither).

- Which frames: DetectGate's schedule at 5 a second, without the old gate beside it (no detection
  before it ran at 5 a second, so there is nothing to stay above); every cut's frame.
- The pace: the full rate is 5, so the rate never goes under it (a slow device takes longer); the
  frame's size is judged against 5 a second (`sizeRate`), so it isn't shrunk to chase 15. On the
  CPU the fine pass runs at 640 px from the start (the app's LOW_SIDE) on frames decoded at 960 px
  (the re-check's crops see those); on the GPU at 1280 px, stepping down as normal detection does
  when RetinaFace is slow (then reduced, with its notice).
- What was really detected: judged against 5 a second (`needRate(fps, false, 5, true)`: 80% of it,
  at most of the frames), so a device that keeps up never shows the reduced notice.
- Never silent: its warning under the choice ("Fast detection checks fewer frames, so it's
  quicker. It may briefly miss small or distant faces, or faces at the edge of the frame. Watch the
  result before you share it."), on the check and "no faces" screens and in the editor for as long
  as the video is open (a pill over the video on a phone); the video is flagged like low confidence
  (`isLowConfidence`), so a batch flags it and "blur and save straight" stops at the check screen.
  The stats panel shows the mode.

`web/tests/fusion.cjs` checks on simulated devices (fast and slow GPUs and CPUs) and clips (8 to
120 fps, exact and millisecond times) that the rate stays at 5, the frames detected are 5 a second
(every frame of a slower clip), never measured short, with gaps of at most 0.2 s and a frame, and
that normal detection's pace is unchanged. `web/tests/fast.cjs` measures it in the browser.

## Selfie detection

A fourth mode for a talking head (`selfie` in the analysis request and in templates; the app's
`DetectMode.SELFIE`). MediaPipe's BlazeFace full range (Apache-2.0, `blaze_face_full_range`, the
float16 TFLite model from `storage.googleapis.com/mediapipe-models/.../float16/1/`) on every frame,
and nothing else: no RetinaFace, no YuNet, no crop re-check, no pace to keep. The same tracker and
blur follow. On clips of one close face it found every face on every frame; it misses about 60% of
faces under 48 px, so small or distant faces in the background may stay unblurred, and the app says so.

- The model: the frame is letterboxed into 192 x 192 (scaled to fit, black bars), pixels as
  (v - 127.5) / 127.5; 2304 anchors (one per cell of a 48 x 48 grid, stride 4), 16 numbers each
  (the box and six landmarks) and a score as a logit. Decoding, the score threshold 0.5 and the
  weighted NMS (IoU 0.3, boxes averaged by score) are MediaPipe's own calculators', in
  `web/src/lib/blazeface.ts` and the app's `BlazeFace.kt`. `scripts/blazeface_ref.py` is a Python
  reference that reproduces MediaPipe's FaceDetector task (scores within 1e-6, boxes within 2 px,
  the same faces, on three clips); `web/tests/blazeface-fixture.json` and the app's
  `BlazeFaceFixture.kt` are its results, and both decoders are tested against them.
- The web runs it as `models/blazeface_full_range.onnx` (2 MB), converted from the TFLite file by
  `scripts/blazeface_to_onnx.py` (no TensorFlow; the ONNX gives the TFLite's outputs to within
  2e-4 on real frames), on ONNX Runtime Web's WebAssembly, in the analysis worker (one detector,
  frames decoded at 640 px). The app runs the TFLite file on the LiteRT interpreter on the CPU
  (a 192 px model takes milliseconds; a GPU delegate would need the agreement check the face
  models' GPU pass has).
- The tracker takes BlazeFace's score as the crop re-check's too (`verify = score`): it runs alone,
  so a face needs 4 detections, or 2 over 0.9 for a very short one, as with a re-check.
- Sensitivity: BlazeFace's threshold goes from 0.5 (0: the default exactly) to 0.3 at 1, lower at
  every 5% step. Like the other modes, the default detections are kept whole and the faces from a
  fixed lower pool (0.3) that are not a default face are added step by step (`selfieThreshold`).
- Never silent: its warning under the choice (he: "מצב סלפי מהיר מאוד, אבל מיועד לאדם אחד קרוב
  למצלמה. פנים קטנות או רחוקות ברקע עלולות לא להיטשטש. צפו בתוצאה לפני שמשתפים."; en: "Selfie mode
  is very fast, but it's made for one person close to the camera. Small or distant faces in the
  background may not be blurred. Watch the result before you share it."), kept in view with the
  settings folded, on the check and "no faces" screens and in the editor (a pill on a phone); the
  video is flagged like low confidence, so a batch flags it and "blur and save straight" stops at
  the check screen. In a template: `selfie: true`, with `sensitive` and `fast` false, so an older
  version reads normal detection (never weaker).

`web/tests/blazeface.cjs` (pure) and `web/tests/selfie.cjs` (browser) check it.

## Sensitivity

One number from 0 to 1, in steps of 5% (the slider under the mode; `sensitivity` in templates).
It only ever blurs more: 0 is the default detection exactly (no extra work), and no threshold goes
above its default (a privacy floor). Above 0 each of the tracker's thresholds goes down linearly
(`thresholds()` in `web/src/lib/sensitivity.ts`, the app's `DetectTuning.kt`):

| threshold                                  | 0 (default) | 1 (most) |
|--------------------------------------------|-------------|----------|
| detection cut (fused score, DET_THRESHOLD) | 0.40        | 0.25     |
| tracklet birth                             | 0.45        | 0.30     |
| strong evidence                            | 0.60        | 0.45     |
| crop re-check evidence                     | 0.50        | 0.30     |
| crop re-check, very short tracklet         | 0.90        | 0.70     |

What counts as a clear face (0.8: no re-check needed, no "check the faces" flag) doesn't move.

The detectors' own thresholds don't move with the slider. Above 0, beside the default candidates,
each detection also takes a fixed lower pool from the same runs of the models: RetinaFace's from
0.20 (decoded a second time from the same outputs), YuNet's from 0.25 (YuNet runs once, from that
threshold; its default faces are those from 0.4 up, the same as before, as its NMS keeps the higher
scores first), and a face YuNet alone finds from 0.45. The pool is fused as the default faces are;
its faces from the cut up that are not a face the default detection has (IoU over 0.3) are the
extra detections, re-checked on a crop too (up to 12 a frame, the likeliest first). The default
detections are made, and re-checked, exactly as without the setting.

The tracks: the default tracker on the default detections, as before, and kept whole. Then for each
5% step up to the setting, the tracker with that step's thresholds on the default and extra
detections (from that step's cut up); its tracks are added (`widen`, `combineTracks`): one that is
mostly a face already there (IoU over 0.3 on at least half the frames they share) only adds its
frames before and after that track, any other is another face. A step never changes or takes away a
box, so a higher sensitivity has every box a lower one has, and the extra detections at a higher one
include those at a lower one. A track the extra faces add has no clear detection behind it, so a
batch flags the video for a look.

A sensitivity never makes the default detection lighter on a slow device, and its extra work is
never dropped: the time of that work (the pool's decoding and fusion, the wait for YuNet, the
re-checks of the default faces from the cut up to 0.4 and of the extra ones) is kept out of the
timings the pace follows, as the GPU's check is (`extraMs`), so the rate and the frame's size are
those of sensitivity 0 (YuNet's whole wait counts, more than its extra candidates cost: the pace
can only hold on longer). The app does the same with the frames' wait for a detector: the
detectors' time on the extra work (the pool's decoding, YuNet's whole run, the fusion, the extra
crops) comes out of the wait before its pace steps (`DetectTuning.blockedWithoutExtras`), and the
extra crops neither vouch for nor strike the GPU or fp16. Its YuNet runs once (from the pool's
threshold, the default faces those over 0.4, the same faces by OpenCV's NMS), as here. (One thing on
Android depends on timing whatever the sensitivity: which clear faces skip their re-check (followed
for several detections) and when a GPU detection leaves out its coarse pass (no big face in the
one before), as its workers finish out of order.) A higher
sensitivity re-checks every extra face a lower one does. A template's sensitivity between steps
rounds up to the next 5%.

`web/tests/fusion.cjs` checks that 0 is the default exactly (thresholds, the tracker, no tracker run),
that the thresholds only go down, that YuNet's faces from a threshold up don't depend on the lower
ones, that the extra detections are nested, and on 150 simulated scenes (weak faces, clutter) that a
higher sensitivity never covers fewer face-frames than a lower one and never changes a default track
(and that it does find more), and on simulated devices (CPUs and GPUs that just keep up, and slow
ones) that a sensitivity's extra time changes no step of the pace. The app's `DetectModesTest` does
the same on the JVM, with its adapt steps on simulated devices (each 5% step never lighter than
the one below, the extra work never tipping a device that keeps up), the extra re-checks nested,
and YuNet's faces from the pool's threshold against OpenCV's NMS.
