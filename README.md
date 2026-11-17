# טשטוש פנים (FaceBlur)

Android app that blurs faces in videos entirely on-device. Tuned for the vivo X300 Pro (arm64, Android 16), works on any arm64 Android 10+ phone.

- **Detection:** ML Kit face detection (bundled model, offline). Optional "extended" mode adds overlapping high-res tiles to catch small/distant faces.
- **Tracking:** detections are linked into tracks, interpolated across missed frames, and held slightly before/after each appearance with a growing safety margin.
- **Rendering:** Media3 Transformer (hardware decode/encode) with a custom GPU effect: blur, pixelate or solid cover, strength relative to each face's size. Audio is copied, HDR kept when the device can encode it.
- Output is saved to `Movies/FaceBlur`.

Builds run on GitHub Actions; every push to `main` publishes a release with the APK.
