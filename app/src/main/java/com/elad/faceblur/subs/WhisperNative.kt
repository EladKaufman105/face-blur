package com.elad.faceblur.subs

/** whisper.cpp through JNI (app/src/main/cpp/whisper_jni.cpp). Handles are native pointers. */
object WhisperNative {
    init {
        System.loadLibrary("faceblur_whisper")
    }

    fun interface Listener {
        /** 0..100, called on the transcribing thread. */
        fun onProgress(percent: Int)
    }

    external fun init(modelPath: String): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)

    /** @return 0 ok, 1 cancelled, < 0 error. */
    external fun transcribe(
        handle: Long,
        pcm: FloatArray,
        lang: String,
        threads: Int,
        maxLen: Int,
        prompt: String?,
        listener: Listener?,
    ): Int

    external fun segments(handle: Long): Int
    external fun segmentStart(handle: Long, i: Int): Long
    external fun segmentEnd(handle: Long, i: Int): Long
    external fun segmentText(handle: Long, i: Int): ByteArray
    external fun language(handle: Long): String
}
