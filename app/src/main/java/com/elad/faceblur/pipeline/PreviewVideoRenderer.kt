package com.elad.faceblur.pipeline

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoSink

/**
 * The format with a colour tag the effects pipeline can't handle replaced by a plain SDR one.
 *
 * With effects on, ExoPlayer's pipeline outputs in the video's own colour transfer, and its
 * shader only accepts the standard SDR one there (DefaultShaderProgram.createWithSampler
 * rejects any other with an IllegalArgumentException). Some files, on some phones, are tagged
 * with another SDR transfer (sRGB, gamma 2.2), or a linear one, which fails the whole preview.
 * The shader doesn't use the transfer for SDR input, so tagging it as standard SDR changes
 * nothing in the picture. HDR, untagged and already-standard formats are returned as they are.
 */
internal fun withPreviewSafeColor(format: Format): Format {
    val color = format.colorInfo ?: return format
    if (ColorInfo.isTransferHdr(color) || !color.isDataSpaceValid()) return format
    if (color.colorTransfer == C.COLOR_TRANSFER_SDR) return format
    val fixed = color.buildUpon().setColorTransfer(C.COLOR_TRANSFER_SDR).build()
    return format.buildUpon().setColorInfo(fixed).build()
}

/**
 * The editor's video renderer: the same as ExoPlayer's, but the colour tag is cleaned up
 * (see [withPreviewSafeColor]) before the video effects pipeline sees the format. The codec
 * itself still gets the file's own format.
 */
internal class PreviewVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {

    override fun maybeInitializeProcessingPipeline(format: Format): Boolean =
        super.maybeInitializeProcessingPipeline(withPreviewSafeColor(format))

    override fun changeVideoSinkInputStream(videoSink: VideoSink, inputType: Int, format: Format) {
        super.changeVideoSinkInputStream(videoSink, inputType, withPreviewSafeColor(format))
    }
}
