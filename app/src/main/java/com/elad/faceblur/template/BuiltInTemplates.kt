package com.elad.faceblur.template

import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubtitleStyle

/**
 * The looks and destinations that ship with the app. Names are Hebrew (translated in the UI).
 * A look sets only the look (subtitle style, blur look); a destination only the export shape.
 */
object BuiltInTemplates {
    private fun look(id: String, name: String, style: SubtitleStyle, mode: BlurMode, strength: Float, splitChars: Int = 0) = Template(
        id = id, name = name, group = TemplateGroup.LOOK, builtIn = true,
        blur = BlurPart(mode = mode, strength = strength),
        subs = SubsPart(style = style, splitWords = 0, splitChars = splitChars),
    )

    private fun destination(id: String, name: String, export: ExportPart) =
        Template(id = id, name = name, group = TemplateGroup.DESTINATION, builtIn = true, export = export)

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

    /** 9:16, 1080p, 30 fps; subtitles and watermark inside TikTok's safe zone. */
    val TIKTOK = destination("dest.tiktok", "טיקטוק", ExportPart(ExportAspect.VERTICAL, 1080, 30, ExportQuality.HIGH, ExportCodec.SAME, platform = Platform.TIKTOK))

    /** 9:16, 1080p, 30 fps; inside Instagram's Reels safe zone (its sides and top differ from TikTok's). */
    val REELS = destination("dest.reels", "ריילס", ExportPart(ExportAspect.VERTICAL, 1080, 30, ExportQuality.HIGH, ExportCodec.SAME, platform = Platform.REELS))

    /** 9:16, 1080p, the source frame rate (Shorts take up to 60 fps); inside the Shorts safe zone. */
    val SHORTS = destination("dest.shorts", "שורטס", ExportPart(ExportAspect.VERTICAL, 1080, 0, ExportQuality.HIGH, ExportCodec.SAME, platform = Platform.SHORTS))

    /** 720p, a small file. */
    val WHATSAPP = destination("dest.whatsapp", "וואטסאפ", ExportPart(ExportAspect.ORIGINAL, 720, 30, ExportQuality.SAVER, ExportCodec.H264))

    /** No change to the video's shape, size or encoding. */
    val SOURCE = destination("dest.source", "כמו המקור", ExportPart(ExportAspect.ORIGINAL, 0, 0, ExportQuality.ORIGINAL, ExportCodec.SAME))

    val DESTINATIONS = listOf(TIKTOK, REELS, SHORTS, WHATSAPP, SOURCE)

    val all = LOOKS + DESTINATIONS

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }
}
