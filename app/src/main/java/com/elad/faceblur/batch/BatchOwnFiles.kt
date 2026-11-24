package com.elad.faceblur.batch

import com.elad.faceblur.template.TemplateSettings
import java.io.File

/**
 * The files a running batch reads for every video (the template's logo and background picture) are
 * copied once into a folder the batch owns, so saving, restoring or deleting the template meanwhile
 * changes nothing in it. The copies are removed when the batch ends.
 */
object BatchOwnFiles {
    fun dir(filesDir: File) = File(filesDir, "batches")

    /** [s] with its logo and background picture pointing at copies in [dir] named for [batchId]. */
    fun own(s: TemplateSettings, dir: File, batchId: String): TemplateSettings {
        dir.mkdirs()
        // A batch that was cut short by the process dying leaves its copies behind.
        dir.listFiles()?.forEach { if (!it.name.startsWith("$batchId.")) it.delete() }
        var out = s
        val w = s.export.watermark
        val logo = w.logo
        if (w.image && logo != null) {
            val src = File(logo)
            if (src.exists()) {
                val dst = File(dir, "$batchId.wm.png")
                src.copyTo(dst, overwrite = true)
                out = out.copy(export = out.export.copy(watermark = w.copy(logo = dst.absolutePath)))
            }
        }
        val pic = s.background.image
        if (pic != null) {
            val src = File(pic)
            if (src.exists()) {
                val dst = File(dir, "$batchId.bg.jpg")
                src.copyTo(dst, overwrite = true)
                out = out.copy(background = s.background.copy(image = dst.absolutePath))
            }
        }
        return out
    }

    /** Removes what [own] copied for [batchId]. */
    fun delete(dir: File, batchId: String) {
        File(dir, "$batchId.wm.png").delete()
        File(dir, "$batchId.bg.jpg").delete()
    }
}
