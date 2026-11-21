# Face detector for the GPU

The app's face detector (RetinaFace MobileNetV2, the same model as `app/src/main/assets/retinaface_mv2.onnx`)
as TFLite for LiteRT's GPU delegate, one static model per frame shape: 1280x736 (landscape) and
736x1280 (portrait), the sizes the app's fine detection pass uses for 16:9 / 9:16 video.

Made by `ml/retina_to_tflite.sh` in the main branch: the FPN's two resizes rewritten as fixed 2x upsampling
(identical outputs), onnx2tf with a static input shape, then conv weights stored as float16 with DEQUANTIZE
(float32 inputs, outputs and CPU math; GPU delegates use the float16 weights directly).

Checked against the float32 ONNX model on real video: 118 of 119 faces matched (IoU >= 0.987, score
difference <= 0.02; the one miss sat at the detection threshold), as close as the app's float16 ONNX model.
