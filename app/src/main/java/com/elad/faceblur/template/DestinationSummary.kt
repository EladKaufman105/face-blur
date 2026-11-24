package com.elad.faceblur.template

import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportQuality

/**
 * What a destination sets, in one line ("9:16 · 1080p · 30fps · high quality"). [tr] translates
 * the Hebrew words (the UI passes `t`); the numbers and codec names are the same in every language.
 */
object DestinationSummary {
    fun parts(e: ExportPart, tr: (String) -> String): List<String> {
        val out = ArrayList<String>()
        out += when (e.aspect) {
            ExportAspect.VERTICAL -> "9:16"
            ExportAspect.SQUARE -> "1:1"
            ExportAspect.PORTRAIT -> "4:5"
            ExportAspect.ORIGINAL, null -> tr("צורה מקורית")
        }
        val side = e.shortSide
        out += if (side == null || side <= 0) tr("רזולוציה מקורית") else "${side}p"
        val fps = e.fps
        out += if (fps == null || fps <= 0) tr("קצב מקורי") else "${fps}fps"
        out += when (e.quality) {
            ExportQuality.HIGH -> tr("איכות גבוהה")
            ExportQuality.SAVER -> tr("קובץ קטן")
            ExportQuality.ORIGINAL, null -> tr("איכות מקורית")
        }
        when (e.codec) {
            ExportCodec.H264 -> out += "H.264"
            ExportCodec.HEVC -> out += "HEVC"
            else -> Unit
        }
        return out
    }

    /** The line: a destination that changes nothing says so; one with a platform adds its safe zone. */
    fun line(e: ExportPart, tr: (String) -> String): String {
        val unchanged = (e.aspect == null || e.aspect == ExportAspect.ORIGINAL) && (e.shortSide ?: 0) <= 0 && (e.fps ?: 0) <= 0 &&
            (e.quality == null || e.quality == ExportQuality.ORIGINAL) && (e.codec == null || e.codec == ExportCodec.SAME)
        val base = if (unchanged) listOf(tr("בלי שינוי בגודל ובאיכות")) else parts(e, tr)
        return (base + listOfNotNull(e.platform?.let { tr("אזור בטוח") })).joinToString(" · ")
    }
}
