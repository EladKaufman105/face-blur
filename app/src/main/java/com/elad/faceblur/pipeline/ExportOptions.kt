package com.elad.faceblur.pipeline

import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.Reframer
import com.elad.faceblur.edit.SubLang
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ExportQuality { ORIGINAL, HIGH, SAVER }

enum class ExportCodec { SAME, H264, HEVC }

/**
 * How the blurred video is encoded.
 *
 * @param shortSide target short side in pixels (0 = as the source), e.g. 1080 for 1080p.
 * @param fps target frame rate (0 = as the source); frames are dropped evenly.
 */
data class ExportOptions(
    val shortSide: Int = 0,
    val fps: Int = 0,
    val quality: ExportQuality = ExportQuality.ORIGINAL,
    val codec: ExportCodec = ExportCodec.SAME,
    /** Which subtitles to burn into the video (if there are any). */
    val subs: SubLang = SubLang.ORIGINAL,
    /** Also save the subtitles as .srt files. */
    val saveSrt: Boolean = true,
    /** Frame shape: as shot, or a vertical / square crop that follows the faces. */
    val aspect: ExportAspect = ExportAspect.ORIGINAL,
    /** A logo or text in a corner. */
    val watermark: Watermark = Watermark(),
) {
    /** The crop for [aspect] as fractions of the frame (1, 1 for as shot). */
    fun crop(info: VideoInfo): Pair<Float, Float> = Reframer.cropFraction(info.displayWidth, info.displayHeight, aspect.ratio)

    /** Output size (display orientation), even dimensions. */
    fun outputSize(info: VideoInfo): Pair<Int, Int> {
        val (cw, ch) = crop(info)
        val w = even(info.displayWidth * cw)
        val h = even(info.displayHeight * ch)
        val short = min(w, h)
        if (shortSide <= 0 || shortSide >= short || short <= 0) return w to h
        val s = shortSide.toFloat() / short
        return even(w * s) to even(h * s)
    }

    fun outputFps(info: VideoInfo): Float =
        if (fps <= 0 || fps >= info.fps - 0.5f) info.fps else fps.toFloat()

    /** Video bitrate to ask the encoder for (bits per second). */
    fun videoBitrate(info: VideoInfo): Int {
        val (w, h) = outputSize(info)
        val f = outputFps(info)
        val hevc = when (codec) {
            ExportCodec.HEVC -> true
            ExportCodec.H264 -> false
            ExportCodec.SAME -> info.isHevc
        }
        // ~YouTube's recommended H.264 rates: 1080p30 ≈ 8 Mbps, 1080p60 ≈ 12 Mbps.
        val recommended = w.toDouble() * h * 30 * 0.13 * (f / 30.0).pow(0.6) * (if (hevc) 0.65 else 1.0)
        val source = sourceVideoBitrate(info)
        val scaledSource = source * (w.toDouble() * h / max(1, info.displayWidth * info.displayHeight)) *
            (f / max(1f, info.fps)).toDouble().pow(0.6)
        val b = when (quality) {
            ExportQuality.ORIGINAL -> if (source > 0) scaledSource else recommended * 1.4
            ExportQuality.HIGH -> if (source > 0) min(recommended, scaledSource) else recommended
            ExportQuality.SAVER -> (if (source > 0) min(recommended, scaledSource) else recommended) * 0.5
        }
        return b.roundToInt().coerceIn(500_000, 150_000_000)
    }

    /** Estimated output file size in bytes. */
    fun estimatedBytes(info: VideoInfo): Long =
        ((videoBitrate(info) + AUDIO_BITRATE).toLong() * info.durationMs / 8000L)

    /** Output MIME type, or null to keep the source's. */
    fun mimeType(): String? = when (codec) {
        ExportCodec.SAME -> null
        ExportCodec.H264 -> MimeTypes.VIDEO_H264
        ExportCodec.HEVC -> MimeTypes.VIDEO_H265
    }

    /** Effects to apply after the blur: the crop that follows the faces ([reframe]), resize and frame rate. */
    fun effects(info: VideoInfo, reframe: Effect? = null): List<Effect> {
        val out = ArrayList<Effect>()
        val (w, h) = outputSize(info)
        val cropped = aspect != ExportAspect.ORIGINAL && reframe != null
        if (cropped) out += reframe!!
        if (aspect != ExportAspect.ORIGINAL && !cropped) {
            // No faces to follow: the middle.
            out += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        } else if (cropped || w != info.displayWidth || h != info.displayHeight) {
            out += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        val f = outputFps(info)
        if (f < info.fps - 0.5f) out += FrameDropEffect.createDefaultFrameDropEffect(f)
        return out
    }

    /** HDR can only be kept in HEVC. */
    fun canKeepHdr(info: VideoInfo) = codec != ExportCodec.H264 && (codec == ExportCodec.HEVC || info.isHevc)

    companion object {
        private const val AUDIO_BITRATE = 128_000

        private fun even(v: Float) = max(2, (v / 2f).roundToInt() * 2)

        private fun sourceVideoBitrate(info: VideoInfo): Double =
            if (info.bitrate > AUDIO_BITRATE * 2) (info.bitrate - AUDIO_BITRATE).toDouble() else 0.0

        /** Short sides offered for this source, largest first (all below the source's). */
        fun resolutionChoices(info: VideoInfo): List<Int> {
            val short = min(info.displayWidth, info.displayHeight)
            return listOf(2160, 1440, 1080, 720, 480).filter { it < short - 8 }
        }

        /** Frame rates offered for this source (all below the source's). */
        fun fpsChoices(info: VideoInfo): List<Int> =
            listOf(60, 50, 30, 25, 24).filter { it < info.fps - 0.5f }
    }
}
