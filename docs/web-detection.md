# Face detection in the web app

The web app finds faces as the Android app does: the same RetinaFace fused with the same YuNet
(`face_detection_yunet_2023mar.onnx`, the app's own file, copied to the site at build time), with
ONNX Runtime Web (RetinaFace on WebGPU when the browser has it, checked against the CPU on the
first frames with faces, else WebAssembly; YuNet on WebAssembly), about 15 times per second of
video (every frame in wide detection) at 1280 px on the GPU or 960 px on the CPU.

A device too slow for that detects lighter, stepwise (`web/src/lib/pace.ts`): on the CPU a smaller
frame first (960, then 640 px), then fewer detections a second (down to 4); on the GPU fewer
detections first, and a smaller frame only if the rate's floor (8 a second) can't be met. The
frame steps back up when timings recover. A reduced detection is never silent: the video is marked
`reduced`, the editor (and the "no faces" screen) keep a notice, a batch flags the video, "blur and
save straight" stops at a screen that offers the editor, and the score thresholds drop (RetinaFace
0.25, YuNet 0.3) so a doubtful face is blurred rather than missed.
