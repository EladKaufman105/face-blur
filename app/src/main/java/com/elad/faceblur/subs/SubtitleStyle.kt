package com.elad.faceblur.subs

import org.json.JSONObject

/** Bundled fonts with Hebrew and Latin (SIL Open Font License, from Google Fonts). */
enum class SubFont(val asset: String?, val label: String, val weight: Int) {
    RUBIK("fonts/rubik.ttf", "Rubik", 800),
    HEEBO("fonts/heebo.ttf", "Heebo", 800),
    ASSISTANT("fonts/assistant.ttf", "Assistant", 800),
    SECULAR("fonts/secular_one.ttf", "Secular One", 0),
    SUEZ("fonts/suez_one.ttf", "Suez One", 0),
    VARELA("fonts/varela_round.ttf", "Varela Round", 0),
    FREDOKA("fonts/fredoka.ttf", "Fredoka", 700),
    KARANTINA("fonts/karantina.ttf", "Karantina", 0),
    AMATIC("fonts/amatic_sc.ttf", "Amatic SC", 0),
    SYSTEM(null, "System", 0),
}

/** How a subtitle appears and moves. */
enum class SubEffect {
    NONE,

    /** Fades in and out. */
    FADE,

    /** Pops in with a little bounce. */
    POP,

    /** The word being said is in the accent color. */
    HIGHLIGHT,

    /** The word being said sits on a box in the accent color. */
    WORD_BOX,

    /** One word at a time, big, each one popping in. */
    ONE_WORD,

    /** Words appear one after another. */
    TYPEWRITER,

    /** Slides up into place, fades out at the end. */
    SLIDE_UP,

    /** Zooms down from big. */
    ZOOM,

    /** Spins in. */
    ROTATE,

    /** Red and cyan glitch as it appears. */
    GLITCH,

    /** Flickers on like a neon sign. */
    FLICKER,

    /** Letter by letter, as it's said. */
    LETTERS,

    /** Each word pops in as it's said. */
    WORD_POP,

    /** Each word fades in as it's said. */
    WORD_FADE,

    /** The word being said is bigger, in the accent color. */
    ACTIVE_ZOOM,

    /** The word being said jumps up. */
    BOUNCE,

    /** The word being said shakes. */
    SHAKE,
}

/**
 * Look of burned-in subtitles (also the editor's preview).
 *
 * @param size multiplier of the base text size (5.5% of the frame's short side).
 * @param outline stroke width as a fraction of the text size (0 = none).
 * @param position vertical center of the text, 0 = top of the frame, 1 = bottom.
 */
data class SubtitleStyle(
    val font: SubFont = SubFont.HEEBO,
    /** An imported font file (in the app's fonts folder); overrides [font]. */
    val customFont: String? = null,
    val size: Float = 1f,
    val textColor: Int = WHITE,
    val accentColor: Int = YELLOW,
    val outline: Float = 0.16f,
    val outlineColor: Int = BLACK,
    val shadow: Boolean = true,
    val glow: Boolean = false,
    val box: Boolean = false,
    val boxColor: Int = 0xB8000000.toInt(),
    val effect: SubEffect = SubEffect.NONE,
    val position: Float = 0.86f,
) {
    fun toJson(): String = JSONObject().apply {
        put("font", font.name)
        customFont?.let { put("customFont", it) }
        put("size", size.toDouble())
        put("text", textColor)
        put("accent", accentColor)
        put("outline", outline.toDouble())
        put("outlineColor", outlineColor)
        put("shadow", shadow)
        put("glow", glow)
        put("box", box)
        put("boxColor", boxColor)
        put("effect", effect.name)
        put("position", position.toDouble())
    }.toString()

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val YELLOW = 0xFFFFE135.toInt()

        fun fromJson(s: String?): SubtitleStyle {
            if (s.isNullOrBlank()) return SubtitleStyle()
            return try {
                val o = JSONObject(s)
                val d = SubtitleStyle()
                SubtitleStyle(
                    font = runCatching { SubFont.valueOf(o.optString("font")) }.getOrDefault(d.font),
                    customFont = o.optString("customFont").ifBlank { null },
                    size = o.optDouble("size", d.size.toDouble()).toFloat(),
                    textColor = o.optInt("text", d.textColor),
                    accentColor = o.optInt("accent", d.accentColor),
                    outline = o.optDouble("outline", d.outline.toDouble()).toFloat(),
                    outlineColor = o.optInt("outlineColor", d.outlineColor),
                    shadow = o.optBoolean("shadow", d.shadow),
                    glow = o.optBoolean("glow", d.glow),
                    box = o.optBoolean("box", d.box),
                    boxColor = o.optInt("boxColor", d.boxColor),
                    effect = runCatching { SubEffect.valueOf(o.optString("effect")) }.getOrDefault(d.effect),
                    position = o.optDouble("position", d.position.toDouble()).toFloat(),
                )
            } catch (e: Exception) {
                SubtitleStyle()
            }
        }
    }
}

/** A ready-made look. [name] is Hebrew (translated in the UI). */
class SubPreset(val name: String, val style: SubtitleStyle)

object SubPresets {
    private const val RED = 0xFFFF2D55.toInt()
    private const val CYAN = 0xFF3DF2FF.toInt()
    private const val GREEN = 0xFF34E07A.toInt()
    private const val PURPLE = 0xFF9B5CFF.toInt()

    val all = listOf(
        SubPreset("קלאסי", SubtitleStyle()),
        SubPreset("טיקטוק", SubtitleStyle(font = SubFont.RUBIK, size = 1.15f, outline = 0.12f, effect = SubEffect.POP, position = 0.70f)),
        SubPreset("מילה מודגשת", SubtitleStyle(font = SubFont.RUBIK, size = 1.15f, outline = 0.14f, effect = SubEffect.HIGHLIGHT, position = 0.70f)),
        SubPreset("תיבה צבעונית", SubtitleStyle(font = SubFont.RUBIK, size = 1.1f, outline = 0f, shadow = true, accentColor = RED, effect = SubEffect.WORD_BOX, position = 0.70f)),
        SubPreset("מילה אחת", SubtitleStyle(font = SubFont.SECULAR, size = 1.7f, outline = 0.14f, effect = SubEffect.ONE_WORD, position = 0.55f)),
        SubPreset("ויראלי צהוב", SubtitleStyle(font = SubFont.SECULAR, size = 1.25f, textColor = YELLOW_TEXT, outline = 0.2f, effect = SubEffect.POP, position = 0.66f)),
        SubPreset("רקע שחור", SubtitleStyle(font = SubFont.ASSISTANT, size = 1f, outline = 0f, shadow = false, box = true, effect = SubEffect.FADE, position = 0.82f)),
        SubPreset("רקע לבן", SubtitleStyle(font = SubFont.RUBIK, size = 1f, textColor = SubtitleStyle.BLACK, outline = 0f, shadow = false, box = true, boxColor = 0xF2FFFFFF.toInt(), effect = SubEffect.POP, position = 0.75f)),
        SubPreset("מכונת כתיבה", SubtitleStyle(font = SubFont.VARELA, size = 1f, outline = 0f, shadow = false, box = true, effect = SubEffect.TYPEWRITER, position = 0.80f)),
        SubPreset("ניאון", SubtitleStyle(font = SubFont.FREDOKA, size = 1.15f, textColor = CYAN, outline = 0f, shadow = false, glow = true, effect = SubEffect.FADE, position = 0.70f)),
        SubPreset("ירוק קריוקי", SubtitleStyle(font = SubFont.HEEBO, size = 1.1f, outline = 0.16f, accentColor = GREEN, effect = SubEffect.HIGHLIGHT, position = 0.72f)),
        SubPreset("סגול", SubtitleStyle(font = SubFont.FREDOKA, size = 1.1f, outline = 0f, accentColor = PURPLE, effect = SubEffect.WORD_BOX, position = 0.72f)),
        SubPreset("כותרת", SubtitleStyle(font = SubFont.KARANTINA, size = 1.6f, outline = 0.12f, effect = SubEffect.POP, position = 0.5f)),
        SubPreset("כתב יד", SubtitleStyle(font = SubFont.AMATIC, size = 1.5f, outline = 0.1f, effect = SubEffect.FADE, position = 0.78f)),
        SubPreset("מילים קופצות", SubtitleStyle(font = SubFont.RUBIK, size = 1.2f, outline = 0.14f, effect = SubEffect.WORD_POP, position = 0.68f)),
        SubPreset("זום למילה", SubtitleStyle(font = SubFont.RUBIK, size = 1.15f, outline = 0.14f, accentColor = GREEN, effect = SubEffect.ACTIVE_ZOOM, position = 0.68f)),
        SubPreset("קפיצה למילה", SubtitleStyle(font = SubFont.SECULAR, size = 1.2f, outline = 0.16f, effect = SubEffect.BOUNCE, position = 0.68f)),
        SubPreset("רעידה", SubtitleStyle(font = SubFont.SECULAR, size = 1.2f, outline = 0.16f, accentColor = RED, effect = SubEffect.SHAKE, position = 0.66f)),
        SubPreset("גליץ'", SubtitleStyle(font = SubFont.KARANTINA, size = 1.5f, outline = 0f, shadow = true, effect = SubEffect.GLITCH, position = 0.6f)),
        SubPreset("החלקה", SubtitleStyle(font = SubFont.ASSISTANT, size = 1.05f, outline = 0f, shadow = false, box = true, effect = SubEffect.SLIDE_UP, position = 0.8f)),
        SubPreset("זום", SubtitleStyle(font = SubFont.HEEBO, size = 1.2f, outline = 0.16f, effect = SubEffect.ZOOM, position = 0.7f)),
        SubPreset("סיבוב", SubtitleStyle(font = SubFont.FREDOKA, size = 1.2f, textColor = YELLOW_TEXT, outline = 0.14f, effect = SubEffect.ROTATE, position = 0.66f)),
        SubPreset("ניאון מהבהב", SubtitleStyle(font = SubFont.FREDOKA, size = 1.15f, textColor = 0xFFFF6FB5.toInt(), outline = 0f, shadow = false, glow = true, effect = SubEffect.FLICKER, position = 0.7f)),
        SubPreset("אות אחר אות", SubtitleStyle(font = SubFont.VARELA, size = 1.05f, outline = 0.12f, effect = SubEffect.LETTERS, position = 0.78f)),
        SubPreset("מילים בהדרגה", SubtitleStyle(font = SubFont.ASSISTANT, size = 1.1f, outline = 0.1f, shadow = true, effect = SubEffect.WORD_FADE, position = 0.75f)),
    )

    private const val YELLOW_TEXT = 0xFFFFE135.toInt()

    /** Text colors offered. */
    val colors = listOf(
        0xFFFFFFFF.toInt(), 0xFFFFE135.toInt(), 0xFFFFB020.toInt(), RED, 0xFFFF6FB5.toInt(),
        PURPLE, 0xFF4D8DFF.toInt(), CYAN, GREEN, 0xFF000000.toInt(),
    )
}
