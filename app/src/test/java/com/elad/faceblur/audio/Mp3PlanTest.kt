package com.elad.faceblur.audio

import com.elad.faceblur.audio.Mp3Plan.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp3PlanTest {
    private fun route(audio: AudioEdit = AudioEdit(), bleeps: Int = 0, sfx: Int = 0, cuts: Boolean = false) =
        Mp3Plan.route(audio, bleeps, sfx, cuts)

    @Test
    fun noEditsIsTheRawSound() {
        assertEquals(Route.RAW, route())
        // "Loud" with no measured gain changes nothing yet; the tone choice alone is not an edit.
        assertEquals(Route.RAW, route(AudioEdit(loud = true, gainDb = 0f, tone = false)))
    }

    @Test
    fun everyEditTheVideoExportAppliesMakesItEdited() {
        for (v in listOf(VoiceFx.DEEP, VoiceFx.HIGH, VoiceFx.ROBOT, VoiceFx.DISGUISE)) assertEquals(Route.EDITED, route(AudioEdit(voice = v)))
        assertEquals(Route.EDITED, route(AudioEdit(denoise = 2)))
        assertEquals(Route.EDITED, route(AudioEdit(enhance = true)))
        assertEquals(Route.EDITED, route(AudioEdit(loud = true, gainDb = 4f)))
        assertEquals(Route.EDITED, route(bleeps = 1))
        assertEquals(Route.EDITED, route(sfx = 2))
        assertEquals(Route.EDITED, route(cuts = true))
    }

    @Test
    fun mutedWithNothingAddedHasNoSoundToSave() {
        assertEquals(Route.NO_SOUND, route(AudioEdit(voice = VoiceFx.MUTE)))
        assertEquals(Route.NO_SOUND, route(AudioEdit(voice = VoiceFx.MUTE), bleeps = 3, cuts = true))
        // Sound effects still play over the silence, as in the video.
        assertEquals(Route.EDITED, route(AudioEdit(voice = VoiceFx.MUTE), sfx = 1))
    }
}
