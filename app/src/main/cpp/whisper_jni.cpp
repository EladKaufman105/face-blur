// JNI bridge to whisper.cpp for on-device subtitles (com.elad.faceblur.subs.WhisperNative).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FaceBlurWhisper", __VA_ARGS__)

namespace {

struct Ctx {
    whisper_context * w = nullptr;
    std::atomic<bool> cancel{false};
};

struct Call {
    JNIEnv * env;
    jobject listener;
    jmethodID onProgress;
    Ctx * c;
};

Ctx * of(jlong h) { return reinterpret_cast<Ctx *>(h); }

void on_progress(whisper_context *, whisper_state *, int progress, void * data) {
    auto * call = static_cast<Call *>(data);
    if (call->listener && call->onProgress) call->env->CallVoidMethod(call->listener, call->onProgress, progress);
}

bool on_abort(void * data) {
    return static_cast<Ctx *>(data)->cancel.load();
}

bool on_encoder_begin(whisper_context *, whisper_state *, void * data) {
    return !static_cast<Call *>(data)->c->cancel.load();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_elad_faceblur_subs_WhisperNative_init(JNIEnv * env, jobject, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context * w = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!w) return 0;
    auto * c = new Ctx();
    c->w = w;
    LOG("model loaded: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_elad_faceblur_subs_WhisperNative_free(JNIEnv *, jobject, jlong h) {
    Ctx * c = of(h);
    if (!c) return;
    whisper_free(c->w);
    delete c;
}

JNIEXPORT void JNICALL
Java_com_elad_faceblur_subs_WhisperNative_cancel(JNIEnv *, jobject, jlong h) {
    if (Ctx * c = of(h)) c->cancel.store(true);
}

/**
 * Transcribes 16 kHz mono PCM. @param lang "he", "en" or "auto". @param maxLen max characters
 * per segment (0 = whisper's own segments). Returns 0 on success, 1 if cancelled, <0 on error.
 */
JNIEXPORT jint JNICALL
Java_com_elad_faceblur_subs_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong h, jfloatArray pcm, jstring lang, jint threads, jint maxLen,
        jstring prompt, jobject listener) {
    Ctx * c = of(h);
    if (!c) return -1;
    c->cancel.store(false);

    const char * l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);
    std::string initial;
    if (prompt) {
        const char * s = env->GetStringUTFChars(prompt, nullptr);
        initial = s;
        env->ReleaseStringUTFChars(prompt, s);
    }

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wp.beam_search.beam_size = 3;
    wp.n_threads = threads;
    wp.language = language.c_str();
    wp.detect_language = false;
    wp.translate = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.no_context = true;  // like whisper-cli: no repetition loops carried across windows
    if (maxLen > 0) {
        wp.token_timestamps = true;
        wp.max_len = maxLen;
        wp.split_on_word = true;
    }
    if (!initial.empty()) wp.initial_prompt = initial.c_str();

    Call call{env, listener, nullptr, c};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        call.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    }
    wp.progress_callback = on_progress;
    wp.progress_callback_user_data = &call;
    wp.encoder_begin_callback = on_encoder_begin;
    wp.encoder_begin_callback_user_data = &call;
    wp.abort_callback = on_abort;
    wp.abort_callback_user_data = c;

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    int r = whisper_full(c->w, wp, samples.data(), (int) samples.size());
    if (c->cancel.load()) return 1;
    return r == 0 ? 0 : -2;
}

JNIEXPORT jint JNICALL
Java_com_elad_faceblur_subs_WhisperNative_segments(JNIEnv *, jobject, jlong h) {
    Ctx * c = of(h);
    return c ? whisper_full_n_segments(c->w) : 0;
}

/** Segment start / end in milliseconds. */
JNIEXPORT jlong JNICALL
Java_com_elad_faceblur_subs_WhisperNative_segmentStart(JNIEnv *, jobject, jlong h, jint i) {
    return whisper_full_get_segment_t0(of(h)->w, i) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_elad_faceblur_subs_WhisperNative_segmentEnd(JNIEnv *, jobject, jlong h, jint i) {
    return whisper_full_get_segment_t1(of(h)->w, i) * 10;
}

/** Segment text as UTF-8 bytes (decoded on the Kotlin side). */
JNIEXPORT jbyteArray JNICALL
Java_com_elad_faceblur_subs_WhisperNative_segmentText(JNIEnv * env, jobject, jlong h, jint i) {
    const char * t = whisper_full_get_segment_text(of(h)->w, i);
    jsize len = t ? (jsize) strlen(t) : 0;
    jbyteArray out = env->NewByteArray(len);
    if (len > 0) env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(t));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_elad_faceblur_subs_WhisperNative_language(JNIEnv * env, jobject, jlong h) {
    int id = whisper_full_lang_id(of(h)->w);
    const char * s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s ? s : "");
}

}  // extern "C"
