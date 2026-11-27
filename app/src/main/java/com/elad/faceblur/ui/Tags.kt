package com.elad.faceblur.ui

import com.elad.faceblur.edit.EditorPanel

/**
 * Names the tests find the main controls by (Modifier.testTag), so they keep working in either
 * language and when a label is reworded.
 */
object Tags {
    const val SETTINGS = "home.settings"
    const val PICK_VIDEO = "home.pick"
    const val BATCH = "home.batch"
    const val TEMPLATE_PICKER = "ready.template"
    const val BLUR_FACES = "ready.blur"
    const val BLUR_AND_SAVE = "ready.blurAndSave"
    const val NO_BLUR_WARNING = "noBlurWarning"
    const val SRT_IMPORT = "subs.srtImport"
    const val SRT_IMPORT_YES = "subs.srtImportYes"
    const val SRT_SAVE = "subs.srtSave"
    const val SRT_SAVE_DONE = "done.srtSave"
    const val SRT_SAVE_YES = "subs.srtSaveYes"
    const val SRT_SAVE_RESULT = "subs.srtSaveResult"
    const val FIND_FACES = "faces.find"
    const val FACE_ALL = "faces.all"
    const val FACE_NONE = "faces.none"
    const val FACE_TILE = "faces.tile"
    const val BLUR_SETTINGS = "ready.blurSettings"
    const val DETECT_MODE = "ready.detectMode"
    const val SENSITIVITY = "ready.sensitivity"
    const val FAST_WARNING = "fastWarning"
    const val FAST_NOTICE = "editor.fastNotice"
    const val SELFIE_WARNING = "selfieWarning"
    const val SELFIE_NOTICE = "editor.selfieNotice"
    const val BACK = "editor.back"
    const val PROPERTIES = "editor.properties"
    const val EXPORT = "editor.export"
    const val EXPORT_GO = "export.go"
    const val MODELS_SHEET = "models.sheet"
    const val MODELS_DOWNLOAD_ALL = "models.downloadAll"
    const val MODELS_CHOOSE = "models.choose"
    const val MODELS_LATER = "models.later"
    const val MODELS_SETTINGS = "settings.models"
    const val MODELS_SCREEN = "models.screen"

    /** A feature's "download to use" card for a model pack, and its download button. */
    fun modelCard(id: com.elad.faceblur.models.PackId) = "models.card." + id.key
    fun modelDownload(id: com.elad.faceblur.models.PackId) = "models.download." + id.key
    const val PLAY = "editor.play"
    const val UNDO = "editor.undo"
    const val SAFE_ZONE = "editor.safeZone"
    const val NO_TEMPLATE = "template.none"
    const val TEMPLATE_EDITOR_CLOSE = "templateEditor.close"
    const val TEMPLATE_EDITOR_SAVE = "templateEditor.save"
    const val TEMPLATE_EDITOR_NAME = "templateEditor.name"
    const val TEMPLATE_NEW = "templates.new"
    const val ADD_BLUR = "tool.addBlur"
    const val PANEL_CLOSE = "panel.close"
    const val PANEL_DONE = "panel.done"

    const val REDO = "editor.redo"
    const val ZOOM_IN = "timeline.zoomIn"
    const val ZOOM_OUT = "timeline.zoomOut"
    const val DESELECT = "context.done"
    const val SCRUBBER = "panel.scrubber"

    /** The progress screen's percentage (a tap opens the stats panel), the panel and its copy button. */
    const val PROGRESS_PERCENT = "progress.percent"
    const val STATS_PANEL = "progress.stats"
    const val STATS_COPY = "progress.statsCopy"
    const val EXPORT_STATS_PANEL = "progress.exportStats"

    /** A group of tools in the editor's bar. */
    fun group(name: String) = "group." + name.lowercase()

    /** A category's include switch in the template editor. */
    fun templateCategory(c: com.elad.faceblur.template.TemplateCategory) = "templateEditor." + c.name.lowercase()

    /** A panel's tab in its group's panel. */
    fun tab(panel: EditorPanel) = "tab." + panel.name.lowercase()
}
