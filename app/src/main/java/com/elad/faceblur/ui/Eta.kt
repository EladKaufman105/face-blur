package com.elad.faceblur.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.elad.faceblur.i18n.t
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Time left for a step from how fast its progress has moved since the step began. Gives
 * nothing until there's enough to go on (a couple of seconds and a few percent).
 */
class Eta {
    private var key: Any? = null
    private var t0 = 0L
    private var p0 = 0f

    /** Milliseconds since the current step began. */
    var elapsedMs = 0L
        private set

    fun remainingMs(stepKey: Any?, p: Float, now: Long = SystemClock.elapsedRealtime()): Long? {
        if (stepKey != key) {
            key = stepKey
            t0 = now
            p0 = p
        }
        elapsedMs = now - t0
        val dp = p - p0
        if (elapsedMs < 2000 || dp < 0.02f) return null
        return ((1f - p) / dp * elapsedMs).roundToLong().coerceAtLeast(0)
    }
}

@Composable
fun rememberEta(): Eta = remember { Eta() }

/** "About 40 seconds left" / "About 2:30 minutes left". */
fun etaText(ms: Long?): String? {
    if (ms == null) return null
    val s = ms / 1000.0
    return when {
        s < 10 -> t("עוד כמה שניות")
        s < 60 -> t("נותרו כ-%d שניות").format((ceil(s / 5) * 5).toInt())
        else -> {
            val total = (ceil(s / 10) * 10).toInt()
            t("נותרו כ-%d:%02d דקות").format(total / 60, total % 60)
        }
    }
}
