package com.elad.faceblur.subs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.NewCue

object Srt {
    /** One subtitle read from an SRT file. */
    class Item(val startMs: Long, val endMs: Long, val text: String)

    private val TIMES = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")
    private val TAGS = Regex("<[^>]+>")
    private val HEBREW = Regex("[\u0590-\u05FF]")

    private fun ms(h: String, m: String, s: String, f: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()

    /**
     * The subtitles of SRT [text], as the web app reads them: blocks split at blank lines, the line
     * with "-->" gives the times, markup tags are dropped, lines are joined with a space, blocks
     * with no text or an end that isn't after the start are skipped. Sorted by start.
     */
    fun parse(text: String): List<Item> {
        val out = ArrayList<Item>()
        for (block in text.removePrefix("\uFEFF").replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = block.split('\n').filter { it.isNotBlank() }
            val k = lines.indexOfFirst { it.contains("-->") }
            if (k < 0) continue
            val m = TIMES.find(lines[k]) ?: continue
            val body = lines.drop(k + 1).joinToString("\n").replace(TAGS, "").trim()
            if (body.isEmpty()) continue
            val a = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val b = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            if (b > a) out += Item(a, b, body.replace("\n", " "))
        }
        return out.sortedBy { it.startMs }
    }

    /** Imported subtitles as new ones: Hebrew text is the spoken text in Hebrew, anything else in English. */
    fun toNewCues(items: List<Item>): List<NewCue> = items.map {
        if (HEBREW.containsMatchIn(it.text)) NewCue(it.startMs, it.endMs, it.text, "", "he", null)
        else NewCue(it.startMs, it.endMs, "", it.text, "en", null)
    }

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

    /** Saves to Download/ONIT; returns null if there's nothing to save. */
    fun save(context: Context, baseName: String, cues: List<Cue>, english: Boolean): Uri? {
        val text = format(cues, english)
        if (text.isBlank()) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.${if (english) "en" else "he"}.srt")
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ONIT")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        r.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
