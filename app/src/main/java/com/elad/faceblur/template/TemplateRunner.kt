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
     * @return short notes about what could not be done (the video is still processed).
     */
    suspend fun applyProcessing(ctrl: EditorController, s: TemplateSettings): List<String> {
        val notes = ArrayList<String>()

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
                    if (s.export.subs == com.elad.faceblur.edit.SubLang.NONE) notes += t("הכתוביות נוצרו, אבל לא ייצרבו בסרטון (ההגדרה: בלי כתוביות בסרטון)")
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
        return notes
    }
}
