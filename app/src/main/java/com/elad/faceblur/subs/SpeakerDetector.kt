package com.elad.faceblur.subs

import android.content.Context
import android.net.Uri
import com.elad.faceblur.analysis.OpenCv
import com.elad.faceblur.edit.Speakers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.dnn.Dnn
import kotlin.math.max
import kotlin.math.min

/**
 * Who speaks when: a voice embedding (WeSpeaker ResNet34, through OpenCV) for each sentence,
 * then sentences with alike voices are grouped into speakers. All on the device.
 */
object SpeakerDetector {
    /** Frames per model input (3 s). */
    private const val T = 300
    /** Shorter speech than this can't be told apart (0.6 s). */
    private const val MIN_FRAMES = 60
    private const val MAX_WINDOWS = 6
    private const val SAMPLES_PER_MS = AudioDecoder.RATE / 1000

    /** A stretch of speech (media time). */
    class Span(val startMs: Long, val endMs: Long)

    /**
     * The speaker (1..) of each span.
     * @param count the number of speakers if the user knows it.
     */
    suspend fun detect(
        context: Context,
        uri: Uri,
        modelPath: String,
        spans: List<Span>,
        count: Int?,
        onProgress: (Float) -> Unit,
    ): IntArray = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext().job
        val pcm = AudioDecoder.decode(context, uri, { job.isActive }) { onProgress(it * 0.25f) }
            ?: throw SubtitleEngine.NoAudioException()
        ensureActive()
        OpenCv.ensureLoaded()
        val net = Dnn.readNetFromONNX(modelPath)
        val input = Mat(intArrayOf(1, T, Fbank.BINS), CvType.CV_32F)
        try {
            val embs = spans.mapIndexed { i, s ->
                ensureActive()
                onProgress(0.25f + 0.75f * i / max(1, spans.size))
                embed(net, input, pcm, s)
            }
            onProgress(1f)
            SpeakerClusters.assign(embs, count, Speakers.MAX)
        } finally {
            input.release()
        }
    }

    /** The voice of [s]: normalized embeddings of 3 s windows over it, averaged. Null if too short. */
    private fun embed(net: org.opencv.dnn.Net, input: Mat, pcm: FloatArray, s: Span): FloatArray? {
        val a = (s.startMs * SAMPLES_PER_MS).toInt().coerceIn(0, pcm.size)
        val b = (s.endMs * SAMPLES_PER_MS).toInt().coerceIn(a, pcm.size)
        val f = Fbank.compute(pcm, a, b)
        val bins = Fbank.BINS
        val n = f.size / bins
        if (n < MIN_FRAMES) return null
        // Each bin's mean over the span is taken out (as the model was trained).
        val mean = FloatArray(bins)
        for (t in 0 until n) for (k in 0 until bins) mean[k] += f[t * bins + k]
        for (k in 0 until bins) mean[k] /= n
        for (t in 0 until n) for (k in 0 until bins) f[t * bins + k] -= mean[k]
        // Short speech is repeated to fill a window.
        val frames = if (n >= T) f else FloatArray(T * bins) { f[it % (n * bins)] }
        val total = max(n, T)
        val k = min(MAX_WINDOWS, 1 + (total - T + 149) / 150)
        var acc: FloatArray? = null
        val win = FloatArray(T * bins)
        for (w in 0 until k) {
            val start = if (k == 1) 0 else ((total - T).toLong() * w / (k - 1)).toInt()
            System.arraycopy(frames, start * bins, win, 0, T * bins)
            input.put(intArrayOf(0, 0, 0), win)
            net.setInput(input)
            val out = net.forward()
            val e = FloatArray(out.total().toInt())
            out.reshape(1, 1).get(0, 0, e)
            out.release()
            val en = SpeakerClusters.normalize(e)
            val sum = acc ?: FloatArray(en.size).also { acc = it }
            for (i in en.indices) sum[i] += en[i]
        }
        return acc
    }
}
