package com.elad.faceblur.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.i18n.I18n
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.subs.SubtitleStyle

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Palette { ONIT, BLUE, PURPLE, GREEN, ORANGE, ROSE, DYNAMIC }

/** App-wide choices that persist between launches. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("faceblur", Context.MODE_PRIVATE)

    var lang by mutableStateOf(enumOr(sp.getString("lang", null), Lang.HE))
        private set
    var themeMode by mutableStateOf(enumOr(sp.getString("theme_mode", null), ThemeMode.DARK))
        private set
    var palette by mutableStateOf(enumOr(sp.getString("palette", null), Palette.ONIT))
        private set
    var export by mutableStateOf(
        ExportOptions(
            shortSide = sp.getInt("export_short", 0),
            fps = sp.getInt("export_fps", 0),
            quality = enumOr(sp.getString("export_quality", null), ExportQuality.ORIGINAL),
            codec = enumOr(sp.getString("export_codec", null), ExportCodec.SAME),
            subs = enumOr(sp.getString("export_subs", null), SubLang.ORIGINAL),
            saveSrt = sp.getBoolean("export_srt", true),
            aspect = enumOr(sp.getString("export_aspect", null), ExportAspect.ORIGINAL),
            watermark = Watermark.fromJson(sp.getString("export_watermark", null)),
        ),
    )
        private set
    var subStyle by mutableStateOf(SubtitleStyle.fromJson(sp.getString("sub_style", null)))
        private set
    /** Translate new subtitles (Hebrew <-> English) when they're created. */
    var subsTranslate by mutableStateOf(sp.getBoolean("subs_translate", false))
        private set

    init {
        I18n.lang = lang
    }

    fun updateLang(v: Lang) {
        lang = v
        I18n.lang = v
        sp.edit().putString("lang", v.name).apply()
    }

    fun updateThemeMode(v: ThemeMode) {
        themeMode = v
        sp.edit().putString("theme_mode", v.name).apply()
    }

    fun updatePalette(v: Palette) {
        palette = v
        sp.edit().putString("palette", v.name).apply()
    }

    fun updateExport(v: ExportOptions) {
        export = v
        sp.edit()
            .putInt("export_short", v.shortSide)
            .putInt("export_fps", v.fps)
            .putString("export_quality", v.quality.name)
            .putString("export_codec", v.codec.name)
            .putString("export_subs", v.subs.name)
            .putBoolean("export_srt", v.saveSrt)
            .putString("export_aspect", v.aspect.name)
            .putString("export_watermark", v.watermark.toJson())
            .apply()
    }

    fun updateSubsTranslate(v: Boolean) {
        subsTranslate = v
        sp.edit().putBoolean("subs_translate", v).apply()
    }

    fun updateSubStyle(v: SubtitleStyle) {
        subStyle = v
        sp.edit().putString("sub_style", v.toJson()).apply()
    }

    /** Learned time of the render pass relative to the analysis pass. */
    var renderRatio: Float
        get() = sp.getFloat("render_ratio", 0.8f)
        set(v) {
            val old = sp.getFloat("render_ratio", -1f)
            sp.edit().putFloat("render_ratio", if (old <= 0f) v else old * 0.5f + v * 0.5f).apply()
        }

    /** Learned time of the analysis pass per unit of the video's length (for a batch's time estimate). */
    var analysisRatio: Float
        get() = sp.getFloat("analysis_ratio", 1.0f)
        set(v) {
            if (v <= 0f || v > 50f) return
            val old = sp.getFloat("analysis_ratio", -1f)
            sp.edit().putFloat("analysis_ratio", if (old <= 0f) v else old * 0.5f + v * 0.5f).apply()
        }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
