package com.elad.faceblur.subs

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.elad.faceblur.edit.TimedWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * A transcribed sentence: time range (ms), both texts, the spoken language ("he", "ar", "fa", "en"
 * or "") and when each of its spoken words is said. Hebrew, Arabic and Persian speech is in [he],
 * English speech in [en] (see [SubLanguages]).
 */
class SubLine(
    val startMs: Long,
    val endMs: Long,
    val he: String,
    val en: String,
    val src: String = "",
    val words: List<TimedWord>? = null,
)

/** @param translated false if the translation step failed (lines have one language only). */
class SubResult(val lines: List<SubLine>, val speechLang: String, val translated: Boolean)

/**
 * Speech -> subtitles, all on the device: decode the audio, transcribe with Whisper, then
 * translate between Hebrew, English, Arabic and Persian with ML Kit.
 */
object SubtitleEngine {
    /** AUDIO: decoding; SPEECH: recognition; PACK: downloading the translation pack; TRANSLATE. */
    enum class Stage { AUDIO, SPEECH, PACK, TRANSLATE }

    /**
     * Progress from the time taken against the time expected ([x] = 1: as long as expected). On
     * track until near the end; past that it keeps creeping on instead of stopping (a slower
     * device, or a bigger model than last time), and the real end takes it to 100%.
     */
    internal fun clockProgress(x: Float): Float =
        if (x < 0.9f) max(0f, x) else 0.9f + 0.09f * (1f - kotlin.math.exp(-(x - 0.9f) / 0.4f))

    class NoAudioException : Exception("no audio")

    /**
     * @param lang language of the speech for the model ("he", "ar", "fa", "en" or "auto"). A language
     *   that is not auto is forced: no detection.
     * @param translateTo what the lines are translated into (null: English, or Hebrew for English speech).
     * @param translate also fill the other language (else lines have only the spoken one).
     * @param speed expected seconds of work per second of audio: recognition reports progress
     *   only per 30 s window, so in between, progress follows the clock.
     * @param onSpeed the measured speed, to expect next time.
     */
    suspend fun run(
        context: Context,
        uri: Uri,
        modelPath: String,
        lang: String,
        translate: Boolean,
        translateTo: String? = null,
        speed: Float = 0.6f,
        onSpeed: (Float) -> Unit = {},
        onProgress: (Stage, Float) -> Unit,
    ): SubResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext().job
        val pcm = AudioDecoder.decode(context, uri, { job.isActive }) { onProgress(Stage.AUDIO, it) }
            ?: throw NoAudioException()
        onProgress(Stage.SPEECH, 0f)
        val audioSec = pcm.size / AudioDecoder.RATE.toFloat()
        val t0 = SystemClock.elapsedRealtime()
        val h = WhisperNative.init(modelPath)
        if (h == 0L) error("model failed to load")
        val raw = ArrayList<Triple<Long, Long, String>>()
        var detected = lang
        try {
            val threads = Runtime.getRuntime().availableProcessors().let { min(6, max(2, it - 2)) }
            val expectedMs = max(2000f, audioSec * speed * 1000f)
            val reported = java.util.concurrent.atomic.AtomicInteger(0)
            // The native call blocks this thread; sibling coroutines relay cancellation to it
            // and move the progress along with the clock.
            val done = AtomicBoolean(false)
            val r = coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!done.get()) WhisperNative.cancel(h)
                    }
                }
                val ticker = launch {
                    while (isActive) {
                        val byClock = clockProgress((SystemClock.elapsedRealtime() - t0) / expectedMs)
                        onProgress(Stage.SPEECH, max(reported.get() / 100f, byClock))
                        delay(250)
                    }
                }
                // max length 1 = one segment per word, each with its own time.
                val code = WhisperNative.transcribe(h, pcm, lang, threads, 1, null) { p -> reported.set(p) }
                done.set(true)
                ticker.cancel()
                watcher.cancel()
                code
            }
            if (r == 1 || !isActive) throw kotlinx.coroutines.CancellationException()
            if (r != 0) error("transcription failed ($r)")
            if (audioSec > 3f) onSpeed((SystemClock.elapsedRealtime() - t0) / 1000f / audioSec)
            detected = WhisperNative.language(h).ifEmpty { lang }
            for (i in 0 until WhisperNative.segments(h)) {
                val text = String(WhisperNative.segmentText(h, i), Charsets.UTF_8).trim()
                if (text.isEmpty() || isNoise(text)) continue
                raw += Triple(WhisperNative.segmentStart(h, i), WhisperNative.segmentEnd(h, i), text)
            }
        } finally {
            WhisperNative.free(h)
        }
        val lines = sentences(raw).map { s ->
            // People switch languages mid-video (the Hebrew model writes English speech in
            // English), so each sentence keeps its own language.
            val t = s.text
            when (val l = lineLanguage(t, detected)) {
                "en" -> SubLine(s.startMs, s.endMs, "", t, l, s.words)
                "he", "ar", "fa" -> SubLine(s.startMs, s.endMs, t, "", l, s.words)
                else -> SubLine(s.startMs, s.endMs, t, t, "", s.words)
            }
        }
        if (!translate || lines.isEmpty()) return@withContext SubResult(lines, detected, false)
        val out = lines.toMutableList()
        val ok = try {
            translateLines(lines.map { it.he to it.en }, lines.map { it.src }, translateTo, onProgress) { i, he, en -> out[i] = SubLine(lines[i].startMs, lines[i].endMs, he, en, lines[i].src, lines[i].words) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FaceBlur", "translation failed", e)
            false
        }
        SubResult(out, detected, ok)
    }

    /**
     * One text to translate: [text] in [from], into [to] ("he", "en", "ar" or "fa"). [from] "" means
     * the text is kept as it is.
     */
    class Item(val text: String, val from: String, val to: String)

    /**
     * What to translate for the (spoken-side, English-side) text [pairs] with spoken languages
     * [srcs] ("he", "ar", "fa", "en" or ""): the pairs with one side empty. The source language
     * comes from the line's [srcs] entry, else from the letters of its text; the target is
     * [SubLanguages.target] of it with [pick]. Null entries are pairs with nothing to do.
     */
    fun items(pairs: List<Pair<String, String>>, srcs: List<String>, pick: String?): List<Item?> =
        pairs.indices.map { i ->
            val (side, other) = pairs[i]
            if (side.isBlank() == other.isBlank()) return@map null
            val text = side.ifBlank { other }
            val known = srcs.getOrNull(i).orEmpty()
            val from = if (SubLanguages.translatable(known)) known else SubLanguages.scriptLang(text).ifEmpty { if (side.isBlank()) "en" else "he" }
            Item(text, from, SubLanguages.target(from, pick))
        }

    /**
     * Fills the missing text of each (spoken-side, English-side) pair, calling [onLine] as each
     * one is done: the translation goes in the slot that is empty. Throws if a translation pack
     * can't be downloaded or translating fails.
     */
    suspend fun translateLines(
        pairs: List<Pair<String, String>>,
        srcs: List<String>,
        pick: String?,
        onProgress: (Stage, Float) -> Unit,
        onLine: (Int, String, String) -> Unit,
    ) {
        val todo = items(pairs, srcs, pick).withIndex().filter { it.value != null }
        if (todo.isEmpty()) return
        Translator.translate(
            todo.map { it.value!! },
            onDownload = { onProgress(Stage.PACK, 0f) },
            onProgress = { onProgress(Stage.TRANSLATE, it) },
        ) { k, out ->
            val i = todo[k].index
            val (he, en) = pairs[i]
            if (he.isBlank()) onLine(i, out, en) else onLine(i, he, out)
        }
    }

    /**
     * The spoken language of a line by which script most of its letters are in: "he", "ar", "fa"
     * or "en"; "" (don't translate) for lines without letters and for English letters in speech
     * that is in another language ([detected]: the Latin letters are then names or noise).
     */
    fun lineLanguage(text: String, detected: String): String {
        val script = SubLanguages.scriptLang(text)
        val otherLanguage = detected.isNotEmpty() && detected != "auto" && detected != "he" && detected != "en"
        return when {
            script == "en" && otherLanguage -> ""
            // The model knows which of the two it heard (a loan word with پ or گ doesn't make Arabic Persian).
            (script == "ar" || script == "fa") && (detected == "ar" || detected == "fa") -> detected
            else -> script
        }
    }

    /** Whisper's non-speech markers: [Music], (applause), ♪… */
    private fun isNoise(t: String) =
        (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")")) || t.all { !it.isLetterOrDigit() }

    class Sentence(val startMs: Long, val endMs: Long, val text: String, val words: List<TimedWord>)

    /**
     * Words -> sentences: a new one after . ? ! or a pause, or when it would grow past
     * [MAX_CHARS]. Readable timing: at least 0.8 s on screen, no overlaps.
     */
    fun sentences(words: List<Triple<Long, Long, String>>): List<Sentence> {
        val sorted = words.sortedBy { it.first }
        val groups = ArrayList<List<Triple<Long, Long, String>>>()
        var cur = ArrayList<Triple<Long, Long, String>>()
        var chars = 0
        sorted.forEachIndexed { i, w ->
            cur += w
            chars += w.third.length + 1
            val next = sorted.getOrNull(i + 1)
            val end = SubLanguages.endsSentence(w.third)
            val pause = next != null && next.first - w.second > PAUSE_MS
            val full = next != null && chars + next.third.length > MAX_CHARS
            if (next == null || end || pause || full) {
                groups += cur
                cur = ArrayList()
                chars = 0
            }
        }
        val out = ArrayList<Sentence>()
        groups.forEachIndexed { i, g ->
            val a = g.first().first
            val nextStart = groups.getOrNull(i + 1)?.first()?.first ?: Long.MAX_VALUE
            val b = min(max(g.last().second, a + 800), nextStart)
            out += Sentence(a, max(b, a + 200), g.joinToString(" ") { it.third }, g.map { TimedWord(it.first, it.second) })
        }
        return out
    }

    private const val PAUSE_MS = 700L

    /** Max characters per subtitle (about two short lines). */
    private const val MAX_CHARS = 64
}
