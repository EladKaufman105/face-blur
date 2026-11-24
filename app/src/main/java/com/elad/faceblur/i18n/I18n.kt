package com.elad.faceblur.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Lang { HE, EN }

/**
 * UI language. The app is written in Hebrew: every user-facing string is its own Hebrew text,
 * passed through [t], which looks up the English version when English is selected. Reading
 * [lang] from composition makes the UI switch immediately.
 */
object I18n {
    var lang by mutableStateOf(Lang.HE)

    val rtl get() = lang == Lang.HE
}

/** The UI string for Hebrew text [he] in the current language. */
fun t(he: String): String = if (I18n.lang == Lang.HE) he else English[he] ?: he

/**
 * A count in words, singular or plural: [one] when [n] is 1, else [many] (which has `%d` for the
 * count). Both are Hebrew texts with their English in English.kt; [args] fill any other `%s`.
 */
fun tn(n: Int, one: String, many: String, vararg args: Any): String =
    if (n == 1) t(one).format(*args) else t(many).format(n, *args)
