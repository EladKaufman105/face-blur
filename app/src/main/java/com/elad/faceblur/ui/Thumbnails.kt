package com.elad.faceblur.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Square frame thumbnails every [stepUs] for the editor's filmstrip, loaded in the background. */
class Thumbnails(
    private val context: Context,
    private val uri: Uri,
    durationUs: Long,
    val stepUs: Long,
    private val sizePx: Int,
) {
    val count = max(1, ceil(durationUs.toDouble() / stepUs).toInt() + 1)
    private val images = arrayOfNulls<ImageBitmap>(count)

    /** Bumped as thumbnails arrive. */
    var version by mutableIntStateOf(0)
        private set

    /** Nearest loaded thumbnail to index [i]. */
    fun near(i: Int): ImageBitmap? {
        val c = i.coerceIn(0, count - 1)
        for (d in 0 until count) {
            images.getOrNull(c - d)?.let { return it }
            images.getOrNull(c + d)?.let { return it }
            if (c - d < 0 && c + d >= count) break
        }
        return null
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            // Coarse pass first so the whole strip fills quickly, then the rest.
            val order = (0 until count step 4) + (0 until count).filter { it % 4 != 0 }
            for (i in order) {
                ensureActive()
                val bmp = runCatching {
                    r.getScaledFrameAtTime(i * stepUs, MediaMetadataRetriever.OPTION_CLOSEST, sizePx * 2, sizePx * 2)
                }.getOrNull() ?: continue
                val sq = square(bmp)
                val img = sq.asImageBitmap()
                withContext(Dispatchers.Main) {
                    images[i] = img
                    version++
                }
            }
        } catch (_: Exception) {
            // No filmstrip is fine.
        } finally {
            r.release()
        }
    }

    private fun square(b: Bitmap): Bitmap {
        val s = min(b.width, b.height)
        val c = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(c, sizePx, sizePx, true)
    }
}
