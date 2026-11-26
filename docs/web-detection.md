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
fewer frames is detected on every frame. Wide detection: every frame.

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
needs no more than that, and the frame isn't shrunk to reach 15.

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
