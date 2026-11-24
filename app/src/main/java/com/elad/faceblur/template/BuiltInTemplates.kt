package com.elad.faceblur.template

import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubtitleStyle

/**
 * The design presets that ship with the app. Names are Hebrew (translated in the UI). A look is
 * a subtitle design plus a blur look, and it creates the subtitles (Hebrew model by default):
 * applying one gives a video its subtitles. The export shape is not set: it stays as it was.
 */
object BuiltInTemplates {
    private fun look(id: String, name: String, style: SubtitleStyle, mode: BlurMode, strength: Float, splitChars: Int = 0) = Template(
        id = id, name = name, group = TemplateGroup.LOOK, builtIn = true,
        blur = BlurPart(mode = mode, strength = strength),
        subs = SubsPart(
            create = true, model = SpeechModel.HEBREW, language = SpeechModel.HEBREW.language, burn = SubLang.ORIGINAL,
            style = style, splitWords = 0, splitChars = splitChars,
        ),
    )

    /** White, subtle text. */
    val CLEAN = look(
        "look.clean", "נקי",
        SubtitleStyle(font = SubFont.HEEBO, size = 1f, outline = 0.06f, shadow = true, effect = SubEffect.FADE, position = 0.82f),
        BlurMode.BLUR, 0.6f,
    )

    /** The word being said pops, highlighted in yellow. */
    val BOLD = look(
        "look.bold", "בולט",
        SubtitleStyle(font = SubFont.RUBIK, size = 1.15f, outline = 0.14f, effect = SubEffect.HIGHLIGHT, position = 0.70f),
        BlurMode.BLUR, 0.85f,
    )

    /** A dark bar behind the text. */
    val NEWS = look(
        "look.news", "חדשות",
        SubtitleStyle(font = SubFont.ASSISTANT, size = 1f, outline = 0f, shadow = false, box = true, boxColor = 0xCC000000.toInt(), effect = SubEffect.NONE, position = 0.84f),
        BlurMode.PIXELATE, 0.7f,
    )

    /** One small line. */
    val MINIMAL = look(
        "look.minimal", "מינימלי",
        SubtitleStyle(font = SubFont.ASSISTANT, size = 0.8f, outline = 0.04f, shadow = false, effect = SubEffect.FADE, position = 0.90f),
        BlurMode.BLUR, 0.5f, splitChars = 32,
    )

    val LOOKS = listOf(CLEAN, BOLD, NEWS, MINIMAL)

    val all = LOOKS

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }
}
