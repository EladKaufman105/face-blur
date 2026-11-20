# Test media for the emulator end-to-end test

Short clips cut from Intel's IoT DevKit sample videos
(https://github.com/intel-iot-devkit/sample-videos), licensed CC BY 4.0.

- walk.mp4: face-demographics-walking.mp4, 0:17-0:27
- headpose.mp4: head-pose-face-detection-female-and-male.mp4, 0:06-0:14
- classroom.mp4: classroom.mp4, 0:03-0:07
- classroom60.mp4: classroom.mp4, 0:03-0:06, resampled to 60 fps
- portrait_rot90.mp4: head-pose clip cropped to portrait and stored sideways with -90 display rotation (like a phone recording)
- stress_cuts.mp4: portrait 720x1280 montage of the clips above with hard cuts, handheld-style shake and zoom (tests drift and cut handling)
- speech_en.mp4: walk.mp4 (640x360, looped to 11 s) with the JFK inaugural address sample from whisper.cpp (samples/jfk.wav, public domain) as 44.1 kHz stereo AAC (tests subtitles)
- speech_two.mp4: speech_en.mp4's picture looped, with two voices: the JFK sample as is (0-11 s), then pitched up 30% (11-22 s), as a second speaker (tests speaker detection)
