package com.elad.faceblur.subs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.elad.faceblur.edit.Cue

object Srt {
    private fun ts(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    /** SubRip text for one language (cues without text in it are skipped). */
    fun format(cues: List<Cue>, english: Boolean): String = buildString {
        var n = 1
        for (c in cues.sortedBy { it.startMs }) {
            val text = (if (english) c.en else c.he).trim()
            if (text.isEmpty()) continue
            append(n++).append('\n')
            append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append('\n')
            append(text).append("\n\n")
        }
    }

    /** Saves to Download/FaceBlur; returns null if there's nothing to save. */
    fun save(context: Context, baseName: String, cues: List<Cue>, english: Boolean): Uri? {
        val text = format(cues, english)
        if (text.isBlank()) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.${if (english) "en" else "he"}.srt")
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FaceBlur")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        r.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
