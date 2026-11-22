package com.elad.faceblur.ui

import kotlin.math.floor

/**
 * Keeps [s] (a number, a time, a size, a file name) whole and left to right wherever it lands:
 * alone, or inside Hebrew text. Unicode isolates (LRI…PDI) instead of a direction mark, so the
 * text around it keeps its own direction and alignment. Times, percents and the like on screen
 * all go through here.
 */
fun ltr(s: String): String = "⁦$s⁩"

/** "1:05.3": minutes, seconds and tenths (the editor's times), from microseconds. */
fun timecode(us: Long): String {
    val tenths = (us.coerceAtLeast(0) / 100_000L).toInt()
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

/** "1:05": minutes and seconds, from milliseconds (lengths, elapsed time). */
fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A timeline ruler mark: "0:12", or "0:12.5" between whole seconds. */
fun rulerLabel(sec: Float): String {
    val whole = floor(sec).toInt()
    return if (sec - whole > 0.01f) "%d:%02d.5".format(whole / 60, whole % 60) else "%d:%02d".format(whole / 60, whole % 60)
}
