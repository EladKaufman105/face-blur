# טשטוש פנים (FaceBlur)

Android app that blurs faces in videos entirely on-device. Tuned for the vivo X300 Pro (arm64, Android 16), works on any arm64 Android 10+ phone.

- **Detection:** RetinaFace with a MobileNetV2 backbone (yakhyo/retinaface-pytorch, MIT, trained on WIDER FACE) fused with YuNet (OpenCV Zoo, MIT), both through OpenCV, about 30 times per second of video at 1280 px, plus a coarse pass for faces that fill the frame. Every detection is re-checked on an upscaled crop around it; that score is used as evidence that it really is a face.
- **Tracking (offline, after the analysis pass):** camera motion is measured on every frame (median optical flow over the whole frame) and hard cuts split the video into shots. Detections are linked into tracks in camera-stabilized coordinates, so a shaky handheld camera doesn't break them; a track counts as a face only with enough evidence (several confident detections, or crop-verified ones). Tracks of the same face split by a detector dropout are joined, gaps are interpolated (moving with the camera), ends are held a little longer, and the result is smoothed over time (position barely, size more).
- Thresholds were tuned against hand-labeled real footage (shaky camera, many people, faces at all angles, hard cuts). "Sensitive" mode detects on every frame and accepts weaker evidence: fewer missed faces, more needless blurs.
- **Rendering:** Media3 Transformer (hardware decode/encode) with a custom GPU effect: per-face Gaussian blur in an atlas, soft ellipse mask and short fades in/out; pixelate and solid modes. Audio is copied, HDR kept when the device can encode it.
- Output is saved to `Movies/FaceBlur`.

CI runs an end-to-end test on an Android emulator over the clips on the `test-media` branch (results on `ci-results`), and only then publishes a release with the APK.
