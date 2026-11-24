package com.elad.faceblur.template

import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elad.faceblur.i18n.t

/**
 * Applies a template's processing to an open [EditorController] with no screen: the same
 * controller steps the editor's panels run (subtitles, voice, loudness, cuts, background,
 * steadying), one after the other, each waited for. The face blur's look and the export
 * settings are not here: they go into the analysis and the render.
 */
object TemplateRunner {
    /**
     * Runs the steps of [s] on [ctrl] and waits for each.
     * @return what could not be done ([ProcessingNotes.failures]: the video is still processed) and
     * what is only worth knowing ([ProcessingNotes.warnings]).
     */
    suspend fun applyProcessing(ctrl: EditorController, s: TemplateSettings): ProcessingNotes {
        val notes = ArrayList<String>()
        val warnings = ArrayList<String>()

        // Subtitles first: filler words and silences read their word times.
        if (s.createSubs) {
            val models = ctrl.models
            if (models == null || !models.isReady(s.speechModel)) {
                notes += t("מודל זיהוי הדיבור לא מוכן, אז לא נוצרו כתוביות")
            } else {
                ctrl.setTranslateTransient(s.translate)
                ctrl.generateSubtitles(models.file(s.speechModel).absolutePath, s.language)
                ctrl.awaitIdle()
                if (ctrl.model.cues.isEmpty()) {
                    notes += ctrl.notice ?: t("לא נמצא דיבור בסרטון")
                } else {
                    if (!s.split.isSentence) ctrl.changeSplit(SplitRule(s.split.words, s.split.chars))
                    // Not a failure: the template asked for it.
                    if (s.export.subs == com.elad.faceblur.edit.SubLang.NONE) warnings += t("הכתוביות נוצרו, אבל לא ייצרבו בסרטון (ההגדרה: בלי כתוביות בסרטון)")
                }
            }
        }

        // Voice: the settings first (instant), then the loudness (the video is measured once).
        val a = s.audio
        ctrl.changeAudio(ctrl.audio.copy(voice = a.voice, denoise = a.denoise, enhance = a.enhance))
        if (a.loud) {
            ctrl.setLoud(true)
            ctrl.awaitIdle()
            if (!ctrl.audio.loud) notes += t("לא נמצא דיבור למדידת העוצמה")
        }

        if (s.cutSilences) {
            ctrl.cutSilences(s.minPauseMs)
            ctrl.awaitIdle()
        }
        if (s.cutFillers) {
            if (ctrl.model.cues.isEmpty()) notes += t("אין כתוביות, אז לא נחתכו מילות מילוי") else ctrl.cutFillers()
        }

        if (s.background.active) {
            val needsPeople = s.background.key == BgKey.PERSON
            // The project gets its own copy of a picture: the template can be deleted or changed later.
            val ctx = ctrl.context
            val bg = if (ctx != null) withContext(Dispatchers.IO) { runCatching { Background.withOwnCopy(ctx, s.background) }.getOrDefault(s.background) } else s.background
            ctrl.changeBackground(bg)
            if (needsPeople) {
                ctrl.findPeople()
                ctrl.awaitIdle()
                if (!ctrl.masksReady) notes += t("זיהוי האנשים נכשל, אז הרקע לא הוחלף")
            }
        }

        if (s.stabilize > 0) {
            ctrl.chooseStabilize(s.stabilize)
            ctrl.awaitIdle()
            if (ctrl.stabilizer == null) notes += t("הייצוב לא הופעל")
        }
        return ProcessingNotes(notes, warnings)
    }

    /**
     * Whether the video, after the processing, is any different from the original: subtitles to
     * burn (or an .srt to save), a changed voice, cuts, a new background or steadying. A video
     * with no faces and no change isn't worth saving a copy of.
     */
    fun changesVideo(ctrl: EditorController, s: TemplateSettings): Boolean {
        val subs = ctrl.model.cues.isNotEmpty() && (s.export.subs != com.elad.faceblur.edit.SubLang.NONE || s.export.saveSrt)
        val a = ctrl.audio
        val voice = a.voice != com.elad.faceblur.audio.VoiceFx.NONE || a.denoise > 0 || a.enhance || a.loud
        val bg = ctrl.background.active && (ctrl.background.key != BgKey.PERSON || ctrl.masksReady)
        return subs || voice || ctrl.cutCount > 0 || bg || ctrl.stabilizer != null
    }

    /** The export asks for a different video than the source (shape, size, rate, quality, codec or a watermark). */
    fun exportChangesVideo(ctx: android.content.Context, e: com.elad.faceblur.pipeline.ExportOptions): Boolean =
        e.aspect != com.elad.faceblur.edit.ExportAspect.ORIGINAL || e.shortSide > 0 || e.fps > 0 ||
            e.quality != com.elad.faceblur.pipeline.ExportQuality.ORIGINAL || e.codec != com.elad.faceblur.pipeline.ExportCodec.SAME ||
            e.watermark.enabled(ctx)
}

/**
 * What the template's processing reported. A [failures] entry is a step that didn't work out (the
 * video needs a look); a [warnings] entry is only worth knowing (nothing went wrong).
 */
data class ProcessingNotes(val failures: List<String> = emptyList(), val warnings: List<String> = emptyList()) {
    val hasFailures: Boolean get() = failures.isNotEmpty()

    /** One line for a notice: a failure first, else a warning. */
    fun first(): String? = failures.firstOrNull() ?: warnings.firstOrNull()
}
