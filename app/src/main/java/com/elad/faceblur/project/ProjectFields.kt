package com.elad.faceblur.project

import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.subs.SubtitleStyle
import org.json.JSONObject

/**
 * What the editor changes, for project.json: the subtitle look [style], how subtitles are cut,
 * the sound, frame, background, steadying and the blur's settings. [export] is kept too when
 * the project was made from a template (so reopening it exports the same way).
 */
fun EditorController.projectFields(style: SubtitleStyle, export: ExportOptions? = null): JSONObject = JSONObject()
    .put("style", style.toJson())
    .put("split", JSONObject().put("words", split.words).put("chars", split.chars))
    .put("audio", audio.toJson())
    .put("frame", frame)
    .put("background", background.toJson())
    .put("stabilize", stabilizeLevel)
    .put("settings", session.settings.let { st -> JSONObject().put("mode", st.mode.name).put("strength", st.strength.toDouble()).put("padding", st.padding.toDouble()).put("sensitive", st.sensitive) })
    .also { o -> if (export != null) o.put("export", export.toJson()) }
