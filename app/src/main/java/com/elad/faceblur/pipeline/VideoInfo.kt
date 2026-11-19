package com.elad.faceblur.pipeline

import com.elad.faceblur.i18n.t
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

class VideoInfo(
    val uri: Uri,
    val name: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val bitrate: Int,
    val isHdr: Boolean,
    val fps: Float,
    val thumbnail: Bitmap?,
    /** MIME type of the video track, e.g. video/hevc. */
    val videoMime: String = "",
) {
    val isHevc get() = videoMime == "video/hevc"
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width
    val aspect get() = if (displayHeight == 0) 16f / 9f else displayWidth.toFloat() / displayHeight

    companion object {
        fun probe(context: Context, uri: Uri): VideoInfo {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
                val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w == 0 || h == 0) throw IllegalArgumentException(t("הקובץ שנבחר אינו סרטון"))
                val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                val transfer = int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                // 6 = ST2084 (PQ / HDR10 / Dolby Vision), 7 = HLG
                val hdr = transfer == 6 || transfer == 7
                val frames = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                val fps = if (frames > 0 && duration > 0) frames * 1000f / duration else 30f
                val thumb = try {
                    r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960)
                } catch (_: Exception) {
                    null
                }
                return VideoInfo(uri, displayName(context, uri), w, h, rotation, duration, bitrate, hdr, fps, thumb, videoMime(context, uri))
            } finally {
                r.release()
            }
        }

        private fun videoMime(context: Context, uri: Uri): String {
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(context, uri, null)
                (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                    .firstOrNull { it.startsWith("video/") } ?: ""
            } catch (_: Exception) {
                ""
            } finally {
                ex.release()
            }
        }

        private fun displayName(context: Context, uri: Uri): String {
            return try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?.substringBeforeLast('.')
                    ?: "video"
            } catch (_: Exception) {
                "video"
            }
        }
    }
}
