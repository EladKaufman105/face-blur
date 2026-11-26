package com.elad.faceblur.subs

/**
 * The languages of the subtitles (pure, no Android): what the multilingual Whisper can be forced
 * to, what the translator handles, and how a text's language and direction are read from its
 * letters. The web app has the same four (`web/src/lib/subtitles.ts`, `translate.ts`).
 *
 * A subtitle keeps two texts, the spoken one and its translation, in the two slots `he` and `en`
 * of [com.elad.faceblur.edit.Cue]. The spoken text of Hebrew, Arabic and Persian speech sits in
 * the `he` slot (its `src` says which language); English speech sits in the `en` slot. The other
 * slot is the translation, in whatever language was chosen: the language of a text is read from
 * its letters, not from the slot.
 */
object SubLanguages {
    const val HEBREW = "he"
    const val ENGLISH = "en"
    const val ARABIC = "ar"
    const val PERSIAN = "fa"
    const val AUTO = "auto"

    /** The languages translation works between. */
    val TRANSLATION = listOf(HEBREW, ENGLISH, ARABIC, PERSIAN)

    /** The languages a speech can be forced to for the multilingual model, in the web's order. */
    val SPEECH = listOf(AUTO, HEBREW, ARABIC, ENGLISH, PERSIAN)

    /** The code Whisper is given for a person's pick: a known language, else "auto" (detect). */
    fun speechCode(pick: String?): String = if (pick != null && pick in SPEECH) pick else AUTO

    /** Languages written right to left. */
    fun isRtl(lang: String) = lang == HEBREW || lang == ARABIC || lang == PERSIAN

    /** Whether [lang] is one of the translation languages. */
    fun translatable(lang: String) = lang in TRANSLATION

    /** The ML Kit language tag for [lang] (the same codes as ours), null if it has none. */
    fun mlKitTag(lang: String): String? = lang.takeIf { translatable(it) }

    /**
     * What a subtitle is translated into: [pick] (a translation language other than [from]) or,
     * when there is none, English for Hebrew, Arabic and Persian and Hebrew for English.
     */
    fun target(from: String, pick: String?): String =
        if (pick != null && translatable(pick) && pick != from) pick else if (from == ENGLISH) HEBREW else ENGLISH

    private fun isHebrewLetter(c: Char) = c in 'א'..'ת'
    /** An Arabic-script letter: assigned letters of the Arabic blocks only (not the BOM at the end of Presentation Forms-B, non-characters, symbols or digits). */
    private fun isArabicLetter(c: Char) = Character.isLetter(c) && (
        c in '\u0620'..'\u064A' || c in '\u066E'..'\u06D3' || c in '\u06FA'..'\u06FF' || c in '\u0750'..'\u077F' ||
            c in '\u08A0'..'\u08FF' || c in '\uFB50'..'\uFBB1' || c in '\uFBD3'..'\uFD3D' || c in '\uFD50'..'\uFD8F' ||
            c in '\uFD92'..'\uFDC7' || c in '\uFDF0'..'\uFDFB' || c in '\uFE70'..'\uFE74' || c in '\uFE76'..'\uFEFC'
        )
    private fun isLatinLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Letters only Persian has (پ چ ژ گ ک ی): Arabic writes ك and ي instead of ک and ی. */
    private const val PERSIAN_LETTERS = "پچژگکی"

    /**
     * "he", "ar", "fa" or "en" by which script most of the letters are in (Arabic script with any
     * Persian-only letter is Persian); "" for text without letters of these scripts.
     */
    fun scriptLang(text: String): String {
        var heb = 0
        var ara = 0
        var lat = 0
        var fa = false
        for (ch in text) {
            when {
                isHebrewLetter(ch) -> heb++
                isArabicLetter(ch) -> { ara++; if (ch in PERSIAN_LETTERS) fa = true }
                isLatinLetter(ch) -> lat++
            }
        }
        return when {
            heb == 0 && ara == 0 && lat == 0 -> ""
            heb >= ara && heb >= lat -> HEBREW
            ara >= lat -> if (fa) PERSIAN else ARABIC
            else -> ENGLISH
        }
    }

    /** Whether [text] starts with a right-to-left letter (the first letter decides, as on the web). */
    fun isRtlText(text: String): Boolean {
        for (ch in text) {
            if (isHebrewLetter(ch) || isArabicLetter(ch)) return true
            if (isLatinLetter(ch)) return false
        }
        return false
    }

    private fun keepsWord(c: Char) = c.isLetterOrDigit() || Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    /** Whether [word] ends a sentence: . ? ! … and the Arabic question mark and full stop. */
    fun endsSentence(word: String): Boolean = word.trimEnd().lastOrNull()?.let { it in ".?!…؟۔" } == true

    /** A word with its letters kept as they are: marks count as part of the word. */
    fun isWordChar(c: Char) = keepsWord(c)
}
