package com.elad.faceblur.audio

/**
 * What an MP3 export has to do, decided from the sound's edits (no Android needed, so it is
 * unit-tested). The same edits as the video export applies: voice change, cuts, bleeps, sound
 * effects and cleanup.
 */
object Mp3Plan {
    enum class Route {
        /** No edit at all: the sound is converted as it is. */
        RAW,

        /** Edited: the sound goes through the export's audio chain first, and a failure there fails the export. */
        EDITED,

        /** Everything is muted and nothing is added: there is no sound to save. */
        NO_SOUND,
    }

    fun route(audio: AudioEdit, bleeps: Int, sfx: Int, cuts: Boolean): Route {
        if (audio.voice == VoiceFx.MUTE && sfx == 0) return Route.NO_SOUND
        val edited = audio.voice != VoiceFx.NONE || audio.denoise > 0 || audio.enhance || audio.gain != 1f ||
            bleeps > 0 || sfx > 0 || cuts
        return if (edited) Route.EDITED else Route.RAW
    }

    /**
     * An edited export that could not apply its edits must never save the original sound (the
     * voice change is there for anonymity). Raw conversion is only for [Route.RAW].
     */
    fun mayFallBackToRaw(route: Route): Boolean = route == Route.RAW
}
