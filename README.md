# טשטוש פנים (FaceBlur)

Android app that blurs faces in videos entirely on-device. Tuned for the vivo X300 Pro (arm64, Android 16), works on any arm64 Android 10+ phone.

- **Detection:** RetinaFace with a MobileNetV2 backbone (yakhyo/retinaface-pytorch, MIT, trained on WIDER FACE) through OpenCV DNN, about 30 times per second of video, at the analysis resolution plus a coarse pass for faces that fill the frame.
- **Tracking:** detections are matched to median-flow predictions. Missed frames are bridged by optical flow for a short time, guarded by an appearance check, drift and scale limits. New faces are followed a little backwards in time. Hard cuts end all tracks. A track needs several confident detections to count; tracks of the same face that the detector briefly lost are joined.
- **Rendering:** Media3 Transformer (hardware decode/encode) with a custom GPU effect: per-face Gaussian blur in an atlas, soft rotated ellipse mask, time-smoothed motion and short fades in/out; pixelate and solid modes. Audio is copied, HDR kept when the device can encode it.
- Output is saved to `Movies/FaceBlur`.

CI runs an end-to-end test on an Android emulator over the clips on the `test-media` branch (results on `ci-results`), and only then publishes a release with the APK.
