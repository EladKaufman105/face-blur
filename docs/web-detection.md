# Face detection in the web app

The web app finds faces as the Android app does: the same RetinaFace fused with the same YuNet
(`face_detection_yunet_2023mar.onnx`, the app's own file, copied to the site at build time), with
ONNX Runtime Web (RetinaFace on WebGPU when the browser has it, checked against the CPU on the
first frames with faces, else WebAssembly; YuNet on WebAssembly), about 15 times per second of
video (every frame in wide detection) at 1280 px on the GPU or 960 px on the CPU.

A device too slow for that detects lighter, stepwise (`web/src/lib/pace.ts`): on the CPU a smaller
frame first (960, then 640 px), then fewer detections a second (down to 4 a second); on the GPU fewer
detections first, and a smaller frame only if the rate's floor (8 a second) can't be met. The
frame steps back up when timings recover. A reduced detection is never silent: the video is marked
`reduced`, the editor (and the "no faces" screen) keep a notice, a batch flags the video, "blur and
save straight" stops at a screen that offers the editor, and the score thresholds drop (RetinaFace
0.25, YuNet 0.3). That is a modest help: the tracker still takes nothing under 0.4, so a weak
face counts only where the other detector agrees with it and the fusion lifts it to 0.4 or more.
Pacing is by whole frames (a detection every N frames, as the app's `round(fps / 15)`), so a
video at 30 fps goes 15, 10, 7.5 ... a second; any longer stride than the app's is `reduced`.
