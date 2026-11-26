# Face detection in the web app

The web app finds faces as the Android app does: the same RetinaFace fused with the same YuNet
(`face_detection_yunet_2023mar.onnx`, the app's own file, copied to the site at build time), with
ONNX Runtime Web (RetinaFace on WebGPU when the browser has it, checked against the CPU on the
first frames with faces, else WebAssembly; YuNet on WebAssembly), about 15 times per second of
video (every frame in wide detection) at 1280 px on the GPU or 960 px on the CPU.

With one detector (on the GPU, or a single one on the CPU), YuNet runs in a worker of its own, at
the same time as RetinaFace (one ONNX Runtime can't run two models at once); if that worker fails
or doesn't answer within 10 s, YuNet runs in the detector's thread from then on (made only then).
In the CPU's detector workers (the cores busy already), after RetinaFace.

A device too slow for that detects lighter, stepwise (`web/src/lib/pace.ts`). The rate follows the
whole detection's time; the frame's size follows RetinaFace's own passes (fine + coarse), as YuNet
runs on a fixed 640 x 640 input whatever the size. On the CPU a smaller frame first (960, then 640
px), then fewer detections a second (down to 4); on the GPU a smaller frame only when RetinaFace is
what is slow (1280 -> 960 px over 80 ms, the app's threshold; 960 -> 640 px over 200 ms, the web's
old rule), else fewer detections. The frame steps back up when timings recover. The timings are
taken in windows of 4 detections after the first few; the GPU's check against the CPU (which runs
until 3 frames with faces were seen, so on a video with none all along) is taken out of a
detection's time. A clip with fewer frames a second than 15 needs no more than that: a rate that
still detects on 80% of its frames isn't reduced, and the frame isn't shrunk to reach 15.

It is never weaker than the web's detection before YuNet (RetinaFace alone at 960 px on the GPU,
640 px on the CPU, 600 ms of detection per second of video): the frame never goes below that
detection's size on the device, and the rate never below the rate it would have had (estimated
from RetinaFace's fine pass, scaled to 640 px, which overestimates that rate). YuNet's cost can
make the analysis take longer, never lighter. `web/tests/fusion.cjs` checks both on simulated
devices (fast and slow GPUs, CPUs with one to four detectors, a cheap or costly YuNet).

A reduced detection is never silent: the video is marked `reduced`, the editor (and the "no faces"
screen) keep a notice, a batch flags the video, "blur and save straight" stops at a screen that
offers the editor (as it does for faces with no clear detection behind them), and the score
thresholds drop (RetinaFace 0.25, YuNet 0.3), so that two weak detections that agree can add up, in
the fusion, to a face the tracker keeps (it keeps faces from 0.4; the re-check of uncertain faces
looks at those from 0.4 up to the clear 0.8).
