package com.elad.faceblur.pipeline

import androidx.media3.transformer.ExportException
import com.elad.faceblur.i18n.t

/** A step of the processing never finished (it was stopped). */
class WorkTimeoutException : RuntimeException("work did not finish in time")

/** An MP3 export with the sound muted: nothing to save. */
class MutedSoundException : RuntimeException("sound is muted")

/** A people background is set but its masks are not ready: saving would leave it out. */
class MissingMasksException : RuntimeException("people background has no masks")

/** What went wrong in processing a video, in words to act on (and the technical part, to send). */
object ProcessingErrors {
    /** What went wrong, said so that it's clear what to try next. */
    fun friendly(e: Throwable): String {
        if (e is ExportException) {
            return when (e.errorCode) {
                in 2000..2999 -> t("לא הצלחתי לקרוא או לכתוב את הקובץ. בדוק שיש מקום פנוי במכשיר ושהסרטון עדיין בגלריה, ונסה שוב.")
                in 3000..3999 -> t("המכשיר לא הצליח לפענח את הסרטון הזה. נסה רזולוציה נמוכה יותר בהגדרות הייצוא.")
                in 4000..4999 -> t("המכשיר לא הצליח לשמור ברזולוציה הזו. בחר 1080p או 720p בהגדרות הייצוא ונסה שוב.")
                in 7000..7999 -> t("השמירה נעצרה באמצע. נסה שוב; אם זה חוזר, בחר רזולוציה נמוכה יותר בהגדרות הייצוא.")
                else -> t("העיבוד נכשל. נסה שוב; אם זה חוזר, בחר רזולוציה נמוכה יותר בהגדרות הייצוא.")
            }
        }
        if (e is MissingMasksException) return t("הרקע של האנשים עדיין לא מוכן, ולכן הסרטון לא נשמר. זהה את האנשים או כבה את הרקע ונסה שוב.")
        if (e is MutedSoundException) return t("אין סאונד לשמירה: הסאונד מושתק")
        if (e is ScrubFailedException) return t("לא הצלחתי להסיר מהסרטון את פרטי המיקום והמכשיר, ולכן הוא לא נשמר. נסה שוב.")
        if (e is WorkTimeoutException) return t("אחד משלבי העיבוד לא הסתיים בזמן ונעצר. נסה שוב.")
        if (e is java.io.IOException) return t("לא הצלחתי לקרוא או לכתוב את הקובץ. בדוק שיש מקום פנוי במכשיר ושהסרטון עדיין בגלריה, ונסה שוב.")
        return t("משהו השתבש בעיבוד. נסה שוב; אם זה חוזר, שלח את הפרטים הטכניים.")
    }

    /** The technical details of [e], for sending. */
    fun technical(e: Throwable): String = buildString {
        append(e.javaClass.simpleName)
        if (e is ExportException) append(" ").append(e.errorCodeName).append(" (").append(e.errorCode).append(")")
        (e.cause?.message ?: e.message)?.let { append(": ").append(it) }
    }
}
