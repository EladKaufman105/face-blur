package com.elad.faceblur.template

import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject

/** The categories a template can include; each has its own switch. */
enum class TemplateCategory { BLUR, SUBTITLES, VOICE, CUTS, BACKGROUND, STABILIZE, EXPORT }

/** Where a template comes from: the user's own, or a built-in look (a design preset). */
enum class TemplateGroup { USER, LOOK }

/** Face blur. A null field is left as it is; [none]: no face blur at all. */
data class BlurPart(
    val none: Boolean? = null,
    val mode: BlurMode? = null,
    val strength: Float? = null,
    /** The area around the face. */
    val padding: Float? = null,
    /** Extended (sensitive) detection. */
    val sensitive: Boolean? = null,
    /** Fast detection (5 a second); [sensitive] wins if both are true. */
    val fast: Boolean? = null,
    /** 0..1: more faces blurred, never fewer (see [com.elad.faceblur.analysis.DetectTuning]). */
    val sensitivity: Float? = null,
) {
    /** The detection mode this part sets, or null when it leaves it (neither [sensitive] nor [fast] given). */
    val detect: com.elad.faceblur.analysis.DetectMode?
        get() = if (sensitive == null && fast == null) null else com.elad.faceblur.analysis.DetectMode.of(sensitive == true, fast == true)
}

/** Subtitles. A null field is left as it is. */
data class SubsPart(
    /** Create them automatically. */
    val create: Boolean? = null,
    val model: SpeechModel? = null,
    /** "he", "ar", "en", "fa" or "auto" (the model's own by default). */
    val language: String? = null,
    val translate: Boolean? = null,
    val style: SubtitleStyle? = null,
    val splitWords: Int? = null,
    val splitChars: Int? = null,
    /** Which language is burned in. */
    val burn: SubLang? = null,
    val saveSrt: Boolean? = null,
)

/** Voice and loudness: the sound's edit (the measured gain is not kept: it is measured per video). */
data class VoicePart(val audio: AudioEdit)

/** Cuts made automatically. */
data class CutsPart(val silences: Boolean, val minPauseMs: Long = 800L, val fillers: Boolean)

/** Frame shape, resolution, frame rate, quality, codec and the watermark. A null field is left as it is. */
data class ExportPart(
    val aspect: ExportAspect? = null,
    val shortSide: Int? = null,
    val fps: Int? = null,
    val quality: ExportQuality? = null,
    val codec: ExportCodec? = null,
    val watermark: Watermark? = null,
)

/**
 * A named set of editing choices. A category that is null is not in the template: it changes
 * nothing, so the current settings stay.
 */
data class Template(
    val id: String,
    val name: String,
    val group: TemplateGroup = TemplateGroup.USER,
    /** Shipped with the app: can't be changed or deleted (it can be duplicated). */
    val builtIn: Boolean = false,
    val blur: BlurPart? = null,
    val subs: SubsPart? = null,
    val voice: VoicePart? = null,
    val cuts: CutsPart? = null,
    val background: Background? = null,
    val stabilize: Int? = null,
    val export: ExportPart? = null,
) {
    fun includes(c: TemplateCategory): Boolean = when (c) {
        TemplateCategory.BLUR -> blur != null
        TemplateCategory.SUBTITLES -> subs != null
        TemplateCategory.VOICE -> voice != null
        TemplateCategory.CUTS -> cuts != null
        TemplateCategory.BACKGROUND -> background != null
        TemplateCategory.STABILIZE -> stabilize != null
        TemplateCategory.EXPORT -> export != null
    }

    /** The template without the categories not in [keep]. */
    fun only(keep: Set<TemplateCategory>): Template = copy(
        blur = blur.takeIf { TemplateCategory.BLUR in keep },
        subs = subs.takeIf { TemplateCategory.SUBTITLES in keep },
        voice = voice.takeIf { TemplateCategory.VOICE in keep },
        cuts = cuts.takeIf { TemplateCategory.CUTS in keep },
        background = background.takeIf { TemplateCategory.BACKGROUND in keep },
        stabilize = stabilize.takeIf { TemplateCategory.STABILIZE in keep },
        export = export.takeIf { TemplateCategory.EXPORT in keep },
    )

    val categories: Set<TemplateCategory> get() = TemplateCategory.entries.filter { includes(it) }.toSet()

    fun toJson(): JSONObject {
        val o = JSONObject().put("v", 1).put("id", id).put("name", name).put("group", group.name)
        blur?.let { b ->
            o.put("blur", JSONObject().apply {
                b.none?.let { put("none", it) }
                b.mode?.let { put("mode", it.name) }
                b.strength?.let { put("strength", it.toDouble()) }
                b.padding?.let { put("padding", it.toDouble()) }
                b.sensitive?.let { put("sensitive", it) }
                // Older versions read only "sensitive": a fast template is normal detection there (never weaker).
                b.fast?.let { put("fast", it) }
                b.sensitivity?.let { put("sensitivity", it.toDouble()) }
            })
        }
        subs?.let { s ->
            o.put("subs", JSONObject().apply {
                s.create?.let { put("create", it) }
                s.model?.let { put("model", it.name) }
                s.language?.let { put("language", it) }
                s.translate?.let { put("translate", it) }
                s.style?.let { put("style", it.toJson()) }
                s.splitWords?.let { put("splitWords", it) }
                s.splitChars?.let { put("splitChars", it) }
                s.burn?.let { put("burn", it.name) }
                s.saveSrt?.let { put("saveSrt", it) }
            })
        }
        voice?.let { o.put("voice", it.audio.copy(gainDb = 0f).toJson()) }
        cuts?.let { o.put("cuts", JSONObject().put("silences", it.silences).put("minPauseMs", it.minPauseMs).put("fillers", it.fillers)) }
        background?.let { o.put("background", it.toJson()) }
        stabilize?.let { o.put("stabilize", it) }
        export?.let { e ->
            o.put("export", JSONObject().apply {
                e.aspect?.let { put("aspect", it.name) }
                e.shortSide?.let { put("shortSide", it) }
                e.fps?.let { put("fps", it) }
                e.quality?.let { put("quality", it.name) }
                e.codec?.let { put("codec", it.name) }
                e.watermark?.let { put("watermark", it.toJson()) }
            })
        }
        return o
    }

    companion object {
        private inline fun <reified E : Enum<E>> JSONObject.enumOrNull(key: String): E? =
            if (has(key)) enumValues<E>().firstOrNull { it.name == optString(key) } else null

        private fun JSONObject.boolOrNull(key: String): Boolean? = if (has(key)) optBoolean(key) else null
        private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null
        private fun JSONObject.floatOrNull(key: String): Float? = if (has(key)) optDouble(key).toFloat() else null

        /** Reads a template back; null if [o] isn't one. */
        fun fromJson(o: JSONObject?): Template? {
            if (o == null) return null
            val id = o.optString("id").ifBlank { return null }
            return Template(
                id = id,
                name = o.optString("name"),
                group = o.enumOrNull<TemplateGroup>("group") ?: TemplateGroup.USER,
                blur = o.optJSONObject("blur")?.let { b ->
                    BlurPart(
                        b.boolOrNull("none"), b.enumOrNull<BlurMode>("mode"), b.floatOrNull("strength"), b.floatOrNull("padding"), b.boolOrNull("sensitive"),
                        b.boolOrNull("fast"), b.floatOrNull("sensitivity")?.takeIf { !it.isNaN() }?.let { com.elad.faceblur.analysis.DetectTuning.roundUp(it) },
                    )
                },
                subs = o.optJSONObject("subs")?.let { s ->
                    SubsPart(
                        create = s.boolOrNull("create"),
                        model = s.enumOrNull<SpeechModel>("model"),
                        language = if (s.has("language")) s.optString("language") else null,
                        translate = s.boolOrNull("translate"),
                        style = if (s.has("style")) SubtitleStyle.fromJson(s.optString("style")) else null,
                        splitWords = s.intOrNull("splitWords"),
                        splitChars = s.intOrNull("splitChars"),
                        burn = s.enumOrNull<SubLang>("burn"),
                        saveSrt = s.boolOrNull("saveSrt"),
                    )
                },
                voice = o.optJSONObject("voice")?.let { VoicePart(AudioEdit.fromJson(it)) },
                cuts = o.optJSONObject("cuts")?.let { CutsPart(it.optBoolean("silences"), it.optLong("minPauseMs", 800L), it.optBoolean("fillers")) },
                background = o.optJSONObject("background")?.let { Background.fromJson(it) },
                stabilize = o.intOrNull("stabilize"),
                export = o.optJSONObject("export")?.let { e ->
                    ExportPart(
                        e.enumOrNull<ExportAspect>("aspect"), e.intOrNull("shortSide"), e.intOrNull("fps"),
                        e.enumOrNull<ExportQuality>("quality"), e.enumOrNull<ExportCodec>("codec"),
                        if (e.has("watermark")) Watermark.fromJson(e.optString("watermark")) else null,
                    )
                },
            )
        }
    }
}

/**
 * Everything a template can set, as plain values: what a video would be processed with. The
 * template parts are laid over it ([applyTo]); an excluded category leaves its values alone.
 */
data class TemplateSettings(
    val blurOff: Boolean = false,
    val mode: BlurMode = BlurMode.BLUR,
    val strength: Float = 0.6f,
    val padding: Float = 1.6f,
    val sensitive: Boolean = false,
    /** Fast detection; [sensitive] wins if both are true. */
    val fast: Boolean = false,
    /** 0..1 (see [com.elad.faceblur.analysis.DetectTuning]). */
    val sensitivity: Float = 0f,
    val createSubs: Boolean = false,
    val speechModel: SpeechModel = SpeechModel.HEBREW,
    val language: String = SpeechModel.HEBREW.language,
    val translate: Boolean = false,
    val subStyle: SubtitleStyle = SubtitleStyle(),
    val split: SplitRule = SplitRule(),
    /** Voice change, noise reduction, voice enhance and even loudness (the gain is measured per video). */
    val audio: AudioEdit = AudioEdit(),
    val cutSilences: Boolean = false,
    val minPauseMs: Long = 800L,
    val cutFillers: Boolean = false,
    val background: Background = Background(),
    val stabilize: Int = 0,
    val export: ExportOptions = ExportOptions(),
) {
    /** Something is done to the video beside the face blur (so a video without faces is still worth saving). */
    val hasProcessing: Boolean
        get() = createSubs || audio.voice != com.elad.faceblur.audio.VoiceFx.NONE || audio.denoise > 0 || audio.enhance || audio.loud ||
            cutSilences || cutFillers || background.active || stabilize > 0

    /** The speech model is needed (subtitles are created). */
    val needsSpeechModel: Boolean get() = createSubs

    /** The model packs it needs: the faces (face blur), the people (a people background), the speech model (subtitles). */
    val neededPacks: List<com.elad.faceblur.models.PackId>
        get() = buildList {
            if (!blurOff) add(com.elad.faceblur.models.PackId.FACES)
            if (background.active && background.key == com.elad.faceblur.effects.BgKey.PERSON) add(com.elad.faceblur.models.PackId.PEOPLE)
            if (createSubs) add(speechModel.pack)
        }
}

/**
 * [s] with the speech model that is on the phone, when the one it asks for isn't (so subtitles
 * don't wait for a download). The multilingual model also understands Hebrew, so a Hebrew
 * template can use it and keeps its spoken language; the multilingual model is
 * swapped for the Hebrew only when the spoken language is Hebrew. Otherwise [s] as it is (the
 * model is downloaded).
 */
fun fitSpeechModel(s: TemplateSettings, isReady: (SpeechModel) -> Boolean): TemplateSettings {
    if (!s.createSubs || isReady(s.speechModel)) return s
    return when (s.speechModel) {
        SpeechModel.HEBREW -> if (isReady(SpeechModel.MULTILINGUAL)) s.copy(speechModel = SpeechModel.MULTILINGUAL) else s
        SpeechModel.MULTILINGUAL -> if (s.language == "he" && isReady(SpeechModel.HEBREW)) s.copy(speechModel = SpeechModel.HEBREW) else s
    }
}

/** Lays [t] over [base]: what the template includes is replaced, the rest stays. */
fun Template.applyTo(base: TemplateSettings): TemplateSettings {
    var s = base
    blur?.let { b ->
        s = s.copy(
            blurOff = b.none ?: s.blurOff,
            mode = b.mode ?: s.mode,
            strength = b.strength ?: s.strength,
            padding = b.padding ?: s.padding,
        )
        b.detect?.let { d ->
            s = s.copy(sensitive = d == com.elad.faceblur.analysis.DetectMode.EXTENDED, fast = d == com.elad.faceblur.analysis.DetectMode.FAST)
        }
        b.sensitivity?.let { s = s.copy(sensitivity = it.coerceIn(0f, 1f)) }
    }
    subs?.let { p ->
        val model = p.model ?: s.speechModel
        s = s.copy(
            createSubs = p.create ?: s.createSubs,
            speechModel = model,
            language = p.language ?: if (p.model != null) model.language else s.language,
            translate = p.translate ?: s.translate,
            subStyle = p.style ?: s.subStyle,
            split = SplitRule(p.splitWords ?: s.split.words, p.splitChars ?: s.split.chars),
            export = s.export.copy(subs = p.burn ?: s.export.subs, saveSrt = p.saveSrt ?: s.export.saveSrt),
        )
    }
    voice?.let { s = s.copy(audio = s.audio.copy(voice = it.audio.voice, denoise = it.audio.denoise, enhance = it.audio.enhance, loud = it.audio.loud)) }
    cuts?.let { s = s.copy(cutSilences = it.silences, minPauseMs = it.minPauseMs, cutFillers = it.fillers) }
    background?.let { s = s.copy(background = it) }
    stabilize?.let { s = s.copy(stabilize = it.coerceIn(0, 3)) }
    export?.let { e ->
        s = s.copy(
            export = s.export.copy(
                aspect = e.aspect ?: s.export.aspect,
                shortSide = e.shortSide ?: s.export.shortSide,
                fps = e.fps ?: s.export.fps,
                quality = e.quality ?: s.export.quality,
                codec = e.codec ?: s.export.codec,
                watermark = e.watermark ?: s.export.watermark,
            ),
        )
    }
    return s
}

/** Lays several templates over [base], in order. */
fun applyAll(base: TemplateSettings, templates: List<Template>): TemplateSettings = templates.fold(base) { s, t -> t.applyTo(s) }

/**
 * The template made of [s]'s values, with only the [keep] categories (the editor's "Save as
 * template"). The cuts are stored as the automatic kind (silences; filler words when there are subtitles).
 */
fun TemplateSettings.toTemplate(id: String, name: String, keep: Set<TemplateCategory>): Template = Template(
    id = id,
    name = name,
    group = TemplateGroup.USER,
    blur = BlurPart(blurOff, mode, strength, padding, sensitive, fast && !sensitive, sensitivity),
    subs = SubsPart(createSubs, speechModel, language, translate, subStyle, split.words, split.chars, export.subs, export.saveSrt),
    voice = VoicePart(audio.copy(gainDb = 0f)),
    cuts = CutsPart(cutSilences, minPauseMs, cutFillers),
    background = background,
    stabilize = stabilize,
    export = ExportPart(export.aspect, export.shortSide, export.fps, export.quality, export.codec, export.watermark),
).only(keep)

/**
 * "New template" (Settings): the default settings' face blur and subtitles, the other categories
 * off (as the web app's new template). It is a user template, saved only from the editor.
 */
fun blankTemplate(id: String, name: String): Template =
    TemplateSettings().toTemplate(id, name, setOf(TemplateCategory.BLUR, TemplateCategory.SUBTITLES))

/** What is chosen on the Ready screen and the batch setup: one template, the user's or a built-in look (null: none). */
data class TemplateChoice(val id: String? = null) {
    val isEmpty get() = id == null
}

/** The ids of the templates a choice stands for, in the order they are laid over the settings. */
val TemplateChoice.ids: List<String> get() = listOfNotNull(id)
