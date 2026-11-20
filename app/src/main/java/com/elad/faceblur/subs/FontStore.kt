package com.elad.faceblur.subs

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Fonts the user imported (.ttf / .otf), kept in the app's own storage. */
object FontStore {
    private const val MAX_BYTES = 40L * 1024 * 1024

    class ImportException(message: String) : Exception(message)

    fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    /** Imported font file names, oldest first. */
    fun list(context: Context): List<String> =
        (dir(context).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("ttf", "otf") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .map { it.name }

    /** "My Font" from "My Font.ttf". */
    fun label(name: String) = name.substringBeforeLast('.')

    /**
     * Copies the font at [uri] in and checks it loads. Returns its file name.
     * @throws ImportException with a message for the user.
     */
    fun import(context: Context, uri: Uri): String {
        val cr = context.contentResolver
        var display = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "font"
        val bytes = cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw ImportException("too big")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImportException("can't read")
        // TrueType / OpenType by their first bytes, whatever the file is called.
        val head = if (bytes.size >= 4) String(bytes, 0, 4, Charsets.ISO_8859_1) else ""
        val ext = when {
            head == "OTTO" -> "otf"
            head == "\u0000\u0001\u0000\u0000" || head == "true" -> "ttf"
            else -> throw ImportException("not a font")
        }
        display = display.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").take(60).ifBlank { "font" }
        var name = "$display.$ext"
        var i = 2
        while (file(context, name).exists()) name = "$display ${i++}.$ext"
        val f = file(context, name)
        f.writeBytes(bytes)
        val tf = try {
            Typeface.Builder(f).build()
        } catch (e: Exception) {
            null
        }
        if (tf == null || tf == Typeface.DEFAULT) {
            f.delete()
            throw ImportException("not a font")
        }
        return name
    }

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    /** The font has Hebrew letters. */
    fun hasHebrew(tf: Typeface): Boolean = Paint().apply { typeface = tf }.hasGlyph("א")
}
