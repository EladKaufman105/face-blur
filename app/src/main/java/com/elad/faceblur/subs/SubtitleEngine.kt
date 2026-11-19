package com.elad.faceblur.subs

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** A transcribed line: time range (ms) and both languages. */
class SubLine(val startMs: Long, val endMs: Long, val he: String, val en: String)

/** @param translated false if the translation step failed (lines have one language only). */
class SubResult(val lines: List<SubLine>, val speechLang: String, val translated: Boolean)

/**
 * Speech -> subtitles, all on the device: decode the audio, transcribe with Whisper, then
 * translate Hebrew <-> English with ML Kit.
 */
object SubtitleEngine {
    enum class Stage { AUDIO, SPEECH, TRANSLATE }

    class NoAudioException : Exception("no audio")

    /**
     * @param lang language of the speech for the model ("he", "en" or "auto").
     * @param translate also fill the other language.
     */
    suspend fun run(
        context: Context,
        uri: Uri,
        modelPath: String,
        lang: String,
        translate: Boolean,
        onProgress: (Stage, Float) -> Unit,
    ): SubResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext().job
        val pcm = AudioDecoder.decode(context, uri, { job.isActive }) { onProgress(Stage.AUDIO, it) }
            ?: throw NoAudioException()
        onProgress(Stage.SPEECH, 0f)
        val h = WhisperNative.init(modelPath)
        if (h == 0L) error("model failed to load")
        val raw = ArrayList<Triple<Long, Long, String>>()
        var detected = lang
        try {
            val threads = Runtime.getRuntime().availableProcessors().let { min(6, max(2, it - 2)) }
            // The native call blocks this thread; a sibling coroutine relays cancellation to it.
            val done = AtomicBoolean(false)
            val r = coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!done.get()) WhisperNative.cancel(h)
                    }
                }
                val code = WhisperNative.transcribe(h, pcm, lang, threads, MAX_CHARS, null) { p -> onProgress(Stage.SPEECH, p / 100f) }
                done.set(true)
                watcher.cancel()
                code
            }
            if (r == 1 || !isActive) throw kotlinx.coroutines.CancellationException()
            if (r != 0) error("transcription failed ($r)")
            detected = WhisperNative.language(h).ifEmpty { lang }
            for (i in 0 until WhisperNative.segments(h)) {
                val text = String(WhisperNative.segmentText(h, i), Charsets.UTF_8).trim()
                if (text.isEmpty() || isNoise(text)) continue
                raw += Triple(WhisperNative.segmentStart(h, i), WhisperNative.segmentEnd(h, i), text)
            }
        } finally {
            WhisperNative.free(h)
        }
        val lines = tidy(raw)
        // People switch languages mid-video (the Hebrew model writes English speech in English),
        // so each line is translated from its own script's language.
        val langs = lines.map { lineLanguage(it.third, detected) }
        var translated = false
        val other = if (translate && lines.isNotEmpty()) {
            onProgress(Stage.TRANSLATE, 0f)
            try {
                Translator.translate(lines.map { it.third }, langs) { onProgress(Stage.TRANSLATE, it) }
                    .also { translated = true }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FaceBlur", "translation failed", e)
                lines.map { "" }
            }
        } else {
            lines.map { "" }
        }
        SubResult(
            lines.mapIndexed { i, (a, b, t) ->
                when (langs[i]) {
                    "en" -> SubLine(a, b, other[i], t)
                    "he" -> SubLine(a, b, t, other[i])
                    else -> SubLine(a, b, t, t)
                }
            },
            detected,
            translated,
        )
    }

    /**
     * "he" or "en" by which script most of the letters are in; "" (don't translate) for other
     * languages and lines without letters.
     */
    fun lineLanguage(text: String, detected: String): String {
        var heb = 0
        var lat = 0
        for (ch in text) {
            if (ch in '\u05D0'..'\u05EA') heb++ else if (ch in 'a'..'z' || ch in 'A'..'Z') lat++
        }
        val otherLanguage = detected.isNotEmpty() && detected != "auto" && detected != "he" && detected != "en"
        return when {
            heb > lat -> "he"
            lat > heb && !otherLanguage -> "en"
            else -> ""
        }
    }

    /** Whisper's non-speech markers: [Music], (applause), ♪… */
    private fun isNoise(t: String) =
        (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")")) || t.all { !it.isLetterOrDigit() }

    /** Readable timing: at least 0.8 s on screen, no overlaps. */
    private fun tidy(raw: List<Triple<Long, Long, String>>): List<Triple<Long, Long, String>> {
        val out = ArrayList<Triple<Long, Long, String>>()
        raw.sortedBy { it.first }.forEachIndexed { i, (a, b, t) ->
            val next = raw.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            val end = min(max(b, a + 800), next)
            out += Triple(a, max(end, a + 200), t)
        }
        return out
    }

    /** Max characters per subtitle (about two short lines). */
    private const val MAX_CHARS = 64
}
