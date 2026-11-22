// MP3 encoding with LAME, for "video to MP3" (see Mp3Encoder.kt).
#include <jni.h>
#include <stdint.h>
#include "lame.h"

static lame_global_flags* gf(jlong h) { return (lame_global_flags*)(intptr_t)h; }

JNIEXPORT jlong JNICALL
Java_com_elad_faceblur_audio_Mp3Encoder_nativeInit(JNIEnv* env, jclass cls, jint rate, jint channels, jint kbps) {
    lame_global_flags* g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, rate);
    lame_set_num_channels(g, channels);
    // MP3 goes up to 48 kHz; LAME resamples anything higher.
    lame_set_out_samplerate(g, rate > 48000 ? 48000 : rate);
    lame_set_brate(g, kbps);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_quality(g, 3);
    lame_set_VBR(g, vbr_off);
    lame_set_bWriteVbrTag(g, 0);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong)(intptr_t)g;
}

JNIEXPORT jint JNICALL
Java_com_elad_faceblur_audio_Mp3Encoder_nativeEncode(JNIEnv* env, jclass cls, jlong h, jshortArray pcm, jint frames, jbyteArray out) {
    lame_global_flags* g = gf(h);
    jsize cap = (*env)->GetArrayLength(env, out);
    jshort* p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte* o = (*env)->GetByteArrayElements(env, out, NULL);
    int n;
    if (lame_get_num_channels(g) == 2) {
        n = lame_encode_buffer_interleaved(g, p, frames, (unsigned char*)o, cap);
    } else {
        n = lame_encode_buffer(g, p, p, frames, (unsigned char*)o, cap);
    }
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_elad_faceblur_audio_Mp3Encoder_nativeFlush(JNIEnv* env, jclass cls, jlong h, jbyteArray out) {
    jsize cap = (*env)->GetArrayLength(env, out);
    jbyte* o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(gf(h), (unsigned char*)o, cap);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_com_elad_faceblur_audio_Mp3Encoder_nativeClose(JNIEnv* env, jclass cls, jlong h) {
    if (h) lame_close(gf(h));
}
