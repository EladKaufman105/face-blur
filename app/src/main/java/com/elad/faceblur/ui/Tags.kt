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
    const val BLUR_SETTINGS = "ready.blurSettings"
    const val BACK = "editor.back"
    const val PROPERTIES = "editor.properties"
    const val EXPORT = "editor.export"
    const val EXPORT_GO = "export.go"
    const val PLAY = "editor.play"
    const val UNDO = "editor.undo"
    const val SAFE_ZONE = "editor.safeZone"
    const val NO_TEMPLATE = "template.none"
    const val TEMPLATE_EDITOR_CLOSE = "templateEditor.close"
    const val TEMPLATE_EDITOR_SAVE = "templateEditor.save"
    const val TEMPLATE_EDITOR_NAME = "templateEditor.name"
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

    /** A group of tools in the editor's bar. */
    fun group(name: String) = "group." + name.lowercase()

    /** A category's include switch in the template editor. */
    fun templateCategory(c: com.elad.faceblur.template.TemplateCategory) = "templateEditor." + c.name.lowercase()

    /** A panel's tab in its group's panel. */
    fun tab(panel: EditorPanel) = "tab." + panel.name.lowercase()
}
