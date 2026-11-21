# EDITIT release shrinking (R8).

# Names stay readable (crash reports, JNI by name); R8 still drops unused code.
-dontobfuscate

# The app's own code is small: all of it is kept (reflection-free, but JNI and callbacks
# are reached by name).
-keep class com.elad.faceblur.** { *; }

# OpenCV's Java classes are bound to its native library by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# LiteRT (TensorFlow Lite) and its GPU delegate: bound to native code by name.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
