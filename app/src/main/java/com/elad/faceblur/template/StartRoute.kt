package com.elad.faceblur.template

/**
 * Where "blur faces" and "blur and save straight" on the Ready screen lead, decided from the chosen
 * template and whether the editor opens (pure, so it is unit-tested).
 */
object StartRoute {
    enum class Route {
        /** Face detection, then the editor. */
        DETECT_THEN_EDIT,

        /** Face detection, then the video is saved with no editor. */
        DETECT_THEN_SAVE,

        /** A template with no face blur: no detection, the editor opens (the template is applied there). */
        EDIT_NO_BLUR,

        /** A template with no face blur: no detection and no editor, the video is saved (its processing applied). */
        SAVE_NO_BLUR,
    }

    fun of(blurOff: Boolean, save: Boolean): Route = when {
        blurOff && save -> Route.SAVE_NO_BLUR
        blurOff -> Route.EDIT_NO_BLUR
        save -> Route.DETECT_THEN_SAVE
        else -> Route.DETECT_THEN_EDIT
    }
}
