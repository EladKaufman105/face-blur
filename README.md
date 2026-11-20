# Speaker embedding model for "who's speaking" subtitles colors

`speaker-wespeaker-r34lm-fp16.onnx`: WeSpeaker ResNet34-LM trained on VoxCeleb
(https://github.com/wenet-e2e/wespeaker), from the sherpa-onnx export
`wespeaker_en_voxceleb_resnet34_LM.onnx`. Licensed CC BY 4.0 (the VoxCeleb pretrained
models follow the dataset's license).

Changed for OpenCV DNN 4.10 on Android: input fixed to 1 x 300 x 80 (3 s of 80-dim Kaldi
fbank, 10 ms hop) and simplified with onnx-simplifier; weights stored as float16 (OpenCV
reads them back as float32). Output: a 256-dim embedding.
