package com.elad.faceblur.pipeline

import android.media.MediaCodecList
import com.elad.faceblur.analysis.Series

/** What the sound goes through in the export (the progress screen's stats panel lists them in this order). */
enum class AudioStep { COPY, PITCH, VOICE, SFX, CUTS, MUTED }

/** What is known when the export starts. */
data class ExportFacts(
    val srcWidth: Int,
    val srcHeight: Int,
    /** The result's size (px). */
    val width: Int,
    val height: Int,
    /** The video codec's MIME type ("video/avc", "video/hevc"). */
    val mime: String,
    /**
 * The encoder this device lists first for [mime] that can take a [width] x [height] frame at [bitrate] (hardware ones first), and
 * whether it is hardware: what the export most likely uses (the transformer picks the same way, but doesn't say which it did until
 * it is done). With none that fits, the first listed one, so the panel still names something.
 */
fun likelyEncoder(mime: String, width: Int, height: Int, bitrate: Int): Pair<String, Boolean>? = runCatching {
    val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
    fun fits(i: android.media.MediaCodecInfo): Boolean = runCatching {
        val v = i.getCapabilitiesForType(mime).videoCapabilities
        v.isSizeSupported(width, height) && v.bitrateRange.contains(bitrate)
    }.getOrDefault(false)
    fun hw(i: android.media.MediaCodecInfo) = i.isHardwareAccelerated && !i.isSoftwareOnly
    val pick = all.firstOrNull { hw(it) && fits(it) } ?: all.firstOrNull { fits(it) } ?: all.firstOrNull { hw(it) } ?: all.firstOrNull()
    pick?.let { it.name to hw(it) }
}.getOrNull()
