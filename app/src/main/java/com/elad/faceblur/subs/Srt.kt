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

    /**
     * The first [max] bytes of [bytes] (read up to max + 1), cut so no UTF-8 character is split:
     * a cut inside a character goes back to where it began.
     */
    fun cutUtf8(bytes: ByteArray, max: Int): ByteArray {
        if (bytes.size <= max) return bytes
        var cut = max
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return bytes.copyOf(cut)
    }

    /** Null when a number is too big to be a time. */
    private fun ms(h: String, m: String, s: String, f: String): Long? {
        val hh = h.toLongOrNull() ?: return null
        val mm = m.toLongOrNull() ?: return null
        val ss = s.toLongOrNull() ?: return null
        val ff = f.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        if (hh > 99_999 || mm > 99_999_999 || ss > 99_999_999_999L) return null
        return hh * 3_600_000 + mm * 60_000 + ss * 1000 + ff
    }

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
            val a = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: continue
            val b = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8]) ?: continue
            if (b > a) out += Item(a, b, body.replace("\n", " "))
        }
        return out.sortedBy { it.startMs }
    }

    /** Imported subtitles as new ones: Hebrew, Arabic or Persian text is the spoken text in that language, anything else in English. */
    fun toNewCues(items: List<Item>): List<NewCue> = items.map {
        val lang = SubLanguages.scriptLang(it.text)
        if (lang == "he" || lang == "ar" || lang == "fa") NewCue(it.startMs, it.endMs, it.text, "", lang, null)
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

    /**
     * The language in the file's name: the script most of the subtitles' text is in ("ar", "fa",
     * "he", "en"; text without letters is skipped), else the slot's ("en" or "he").
     */
    fun tag(cues: List<Cue>, english: Boolean): String {
        val counts = LinkedHashMap<String, Int>()
        for (c in cues.sortedBy { it.startMs }) {
            val l = SubLanguages.scriptLang(if (english) c.en else c.he)
            if (l.isNotEmpty()) counts[l] = (counts[l] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: if (english) "en" else "he"
    }

    /** One file to save: the slot ([english]), its name's language tag and its text. */
    class SrtFile(val english: Boolean, val tag: String, val text: String)

    /**
     * The .srt files for [cues]: one per text that has any, named by language. Two slots with the
     * same text give one file; two files of the same language get distinct names.
     */
    fun files(cues: List<Cue>): List<SrtFile> {
        val out = ArrayList<SrtFile>()
        for (english in listOf(false, true)) {
            val text = format(cues, english)
            if (text.isBlank() || out.any { it.text == text }) continue
            var name = tag(cues, english)
            if (out.any { it.tag == name }) name += "2"
            out += SrtFile(english, name, text)
        }
        return out
    }

    /** Saves the files of [cues] to Download/ONIT; returns how many. */
    fun saveAll(context: Context, baseName: String, cues: List<Cue>): Int = files(cues).count { save(context, baseName, it) != null }

    /** Saves to Download/ONIT; returns null if there's nothing to save. */
    fun save(context: Context, baseName: String, f: SrtFile): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.${f.tag}.srt")
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ONIT")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        r.openOutputStream(uri)?.use { it.write(f.text.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
