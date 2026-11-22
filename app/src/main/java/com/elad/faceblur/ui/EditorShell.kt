package com.elad.faceblur.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.EditorPanel
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.subs.SubtitleEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The editor's tools by the task they're for, in the order of the work: hide who's in it, cut
 * and steady, the picture, subtitles, sound, and the brand. Each opens one panel, with a tab for
 * each of its tools; six fit a phone's width.
 */
internal enum class ToolGroup { PRIVACY, EDIT, PICTURE, SUBTITLES, SOUND, BRAND }

internal val ToolGroup.feature: Feature?
    get() = when (this) {
        ToolGroup.PRIVACY -> Feature.FACES
        ToolGroup.PICTURE -> Feature.BACKGROUND
        ToolGroup.SUBTITLES -> Feature.SUBTITLES
        ToolGroup.SOUND -> Feature.SOUND
        ToolGroup.EDIT, ToolGroup.BRAND -> null
    }

/** The group's color for text and icons: its feature's, or the accent for the others. */
internal val ToolGroup.tint: Color
    @Composable get() = feature?.tint ?: MaterialTheme.colorScheme.primary

internal val ToolGroup.label: String
    get() = when (this) {
        ToolGroup.PRIVACY -> t("פרטיות")
        ToolGroup.EDIT -> t("עריכה")
        ToolGroup.PICTURE -> t("תמונה")
        ToolGroup.SUBTITLES -> t("כתוביות")
        ToolGroup.SOUND -> t("סאונד")
        ToolGroup.BRAND -> t("מיתוג")
    }

internal val ToolGroup.icon: ImageVector
    get() = when (this) {
        ToolGroup.PRIVACY -> AppIcons.Face
        ToolGroup.EDIT -> EditorIcons.Cut
        ToolGroup.PICTURE -> AppIcons.Background
        ToolGroup.SUBTITLES -> EditorIcons.Subtitles
        ToolGroup.SOUND -> AppIcons.Voice
        ToolGroup.BRAND -> AppIcons.Watermark
    }

internal fun groupOf(p: EditorPanel): ToolGroup = when (p) {
    EditorPanel.BLUR -> ToolGroup.PRIVACY
    EditorPanel.CUT, EditorPanel.STABILIZE -> ToolGroup.EDIT
    EditorPanel.BACKGROUND, EditorPanel.FRAME -> ToolGroup.PICTURE
    EditorPanel.SUBTITLES, EditorPanel.LIST, EditorPanel.STYLE -> ToolGroup.SUBTITLES
    EditorPanel.VOICE -> ToolGroup.SOUND
    EditorPanel.WATERMARK -> ToolGroup.BRAND
}

/** The group's panels that apply now: the subtitle list and look once there are subtitles. */
internal fun panelsOf(g: ToolGroup, ctrl: EditorController): List<EditorPanel> {
    val hasCues = ctrl.model.cues.isNotEmpty()
    return when (g) {
        ToolGroup.PRIVACY -> listOf(EditorPanel.BLUR)
        ToolGroup.EDIT -> listOf(EditorPanel.CUT, EditorPanel.STABILIZE)
        ToolGroup.PICTURE -> listOf(EditorPanel.BACKGROUND, EditorPanel.FRAME)
        ToolGroup.SUBTITLES -> listOfNotNull(
            EditorPanel.SUBTITLES,
            EditorPanel.LIST.takeIf { hasCues },
            EditorPanel.STYLE.takeIf { hasCues || ctrl.session.subtitlesOnly },
        )
        ToolGroup.SOUND -> listOf(EditorPanel.VOICE)
        ToolGroup.BRAND -> listOf(EditorPanel.WATERMARK)
    }
}

/** A panel's name as its group's tab. */
internal fun panelLabel(p: EditorPanel): String = when (p) {
    EditorPanel.BLUR -> t("טשטוש פנים")
    EditorPanel.CUT -> t("חיתוך")
    EditorPanel.STABILIZE -> t("ייצוב")
    EditorPanel.BACKGROUND -> t("רקע")
    EditorPanel.FRAME -> t("פורמט")
    EditorPanel.SUBTITLES -> t("יצירה ותרגום")
    EditorPanel.LIST -> t("רשימה")
    EditorPanel.STYLE -> t("עיצוב")
    EditorPanel.VOICE -> t("סאונד")
    EditorPanel.WATERMARK -> t("סימן מים")
}

/** The group changes the video now: a dot on its button. */
internal fun groupOn(g: ToolGroup, ctrl: EditorController, export: ExportOptions, watermarkOn: Boolean): Boolean = when (g) {
    ToolGroup.PRIVACY -> false
    ToolGroup.EDIT -> ctrl.cutCount > 0 || ctrl.stabilize
    ToolGroup.PICTURE -> ctrl.background.active || export.aspect != ExportAspect.ORIGINAL
    ToolGroup.SUBTITLES -> ctrl.model.cues.isNotEmpty()
    ToolGroup.SOUND -> ctrl.audio.voice != VoiceFx.NONE || ctrl.audio.denoise > 0 || ctrl.audio.enhance || ctrl.audio.loud ||
        ctrl.model.bleeps.isNotEmpty() || ctrl.model.sfx.isNotEmpty()
    ToolGroup.BRAND -> watermarkOn
}

/** The tablet's column of groups. */
internal val RailWidth: Dp = 88.dp

/**
 * The six groups: a row along the bottom on a phone, a column beside the video on a tablet.
 * [current]: the group whose panel is showing.
 */
@Composable
internal fun GroupBar(
    ctrl: EditorController,
    export: ExportOptions,
    watermarkOn: Boolean,
    busy: Boolean,
    vertical: Boolean,
    current: ToolGroup?,
    onGroup: (ToolGroup) -> Unit,
) {
    val button: @Composable (ToolGroup, Modifier) -> Unit = { g, m ->
        GroupButton(g, on = groupOn(g, ctrl, export, watermarkOn), current = g == current, enabled = !busy, modifier = m) { onGroup(g) }
    }
    if (vertical) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(RailWidth).fillMaxHeight()) {
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = Space.XS, vertical = Space.S),
                verticalArrangement = Arrangement.spacedBy(Space.XS, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (g in ToolGroup.entries) button(g, Modifier.fillMaxWidth())
            }
        }
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS, vertical = Space.XS), verticalAlignment = Alignment.CenterVertically) {
                for (g in ToolGroup.entries) button(g, Modifier.weight(1f))
            }
        }
    }
}

/** A group: its icon in its color over its name; a dot when it's changing the video. */
@Composable
private fun GroupButton(g: ToolGroup, on: Boolean, current: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = g.tint
    val alpha = if (enabled) 1f else 0.38f
    val pill by animateColorAsState(if (current) tint.copy(alpha = 0.2f) else Color.Transparent, tween(Motion.STATE), label = "group")
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .testTag(Tags.group(g.name))
            .semantics {
                selected = current
                if (on) stateDescription = t("פעיל")
            }
            .clip(RoundedCornerShape(Radius.Card))
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = Space.XS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier.size(width = 48.dp, height = 32.dp).clip(RoundedCornerShape(50)).background(pill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(g.icon, null, tint = (g.feature?.color ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha), modifier = Modifier.size(22.dp))
            }
            if (on) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-6).dp, y = 3.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(g.feature?.color ?: MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(Space.XS))
        Text(
            g.label,
            style = MaterialTheme.typography.labelMedium,
            color = (if (current) tint else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One thing to do with what's selected. */
internal class ContextAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * What can be done with the selected blur, subtitle or sound effect; [onProperties] opens all of
 * its settings (times, size, volume, text), in the timeline's place.
 */
internal fun contextActions(ctrl: EditorController, busy: Boolean, onProperties: () -> Unit): Pair<Feature, List<ContextAction>>? {
    val props = ContextAction(t("מאפיינים"), AppIcons.Tune, !busy) { ctrl.pauser(); onProperties() }
    ctrl.selectedSfx?.let {
        return Feature.SOUND to listOf(
            props,
            ContextAction(t("השמע"), Icons.Filled.PlayArrow) { ctrl.playSelectedSfx() },
            ContextAction(t("העבר לכאן"), AppIcons.MoveHere) { ctrl.moveSelectedSfxHere() },
            ContextAction(t("מחק"), Icons.Filled.Delete, danger = true) { ctrl.deleteSelectedSfx() },
        )
    }
    if (ctrl.selectedCue != null) {
        return Feature.SUBTITLES to listOf(
            props,
            ContextAction(t("ערוך טקסט"), Icons.Filled.Edit) { ctrl.pauser(); ctrl.cueEditor = true },
            ContextAction(t("פצל כאן"), EditorIcons.Cut) { ctrl.pauser(); ctrl.splitSelectedCue() },
            ContextAction(t("מזג"), AppIcons.MergeDown) { ctrl.mergeSelectedCue() },
            ContextAction(t("מחק"), Icons.Filled.Delete, danger = true) { ctrl.deleteSelectedCue() },
        )
    }
    if (ctrl.selected != null) {
        return Feature.FACES to listOf(
            props,
            ContextAction(t("פצל"), EditorIcons.Cut, !busy) { ctrl.splitSelected() },
            ContextAction(t("עקוב קדימה"), EditorIcons.TrackForward, !busy) { ctrl.trackSelected(1) },
            ContextAction(t("עקוב אחורה"), EditorIcons.TrackBack, !busy) { ctrl.trackSelected(-1) },
            ContextAction(t("מחק"), Icons.Filled.Delete, !busy, danger = true) { ctrl.deleteSelected() },
        )
    }
    return null
}

/** The height the context bar takes over the timeline (with its margin). */
internal val ContextBarRoom: Dp = 72.dp

/**
 * A phone's bar for the selected clip, floating over the timeline's bottom edge: the groups stay
 * where they are, and nothing on the screen moves when something is selected.
 */
@Composable
internal fun ContextBar(ctrl: EditorController, busy: Boolean, onProperties: () -> Unit, modifier: Modifier = Modifier) {
    val (feature, actions) = contextActions(ctrl, busy, onProperties) ?: return
    Surface(
        shape = RoundedCornerShape(Radius.Panel),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = Space.S, end = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 32.dp).clip(RoundedCornerShape(2.dp)).background(feature.color))
            for (a in actions) ActionButton(a, feature, Modifier.weight(1f))
            IconButton(onClick = ctrl::deselect, modifier = Modifier.testTag(Tags.DESELECT)) {
                Icon(Icons.Filled.Check, t("סיום"))
            }
        }
    }
}

@Composable
private fun ActionButton(a: ContextAction, feature: Feature, modifier: Modifier) {
    val alpha = if (a.enabled) 1f else 0.38f
    val color = when {
        a.danger -> MaterialTheme.colorScheme.error
        a.active -> feature.color
        else -> MaterialTheme.colorScheme.onSurface
    }.copy(alpha = alpha)
    val pill by animateColorAsState(if (a.active) feature.color.copy(alpha = 0.2f) else Color.Transparent, tween(Motion.STATE), label = "action")
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.Card))
            .clickable(enabled = a.enabled, onClick = a.onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = Space.XS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(50)).background(pill), contentAlignment = Alignment.Center) {
            Icon(a.icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(a.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * Messages, and the progress of work that runs in the background (following a face, making
 * subtitles): off the faces, at the edge of the video or over the timeline.
 */
@Composable
internal fun EditorMessages(ctrl: EditorController, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(Space.S),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (ctrl.addMode) {
            Banner(t("הקש על הפנים שצריך לטשטש"), action = t("ביטול")) { ctrl.addMode = false }
        } else if (!ctrl.playing && ctrl.cutHere != null) {
            Banner(t("הקטע הזה נחתך מהסרטון"), action = t("החזר")) { ctrl.restoreCutHere() }
        } else if (ctrl.model.tracks.isEmpty() && !ctrl.session.subtitlesOnly && ctrl.notice == null && ctrl.panel == null) {
            Banner(t("לא זוהו פנים"), action = t("הוסף טשטוש")) { ctrl.pauser(); ctrl.addMode = true }
        }
        ctrl.notice?.let { msg ->
            LaunchedEffect(msg) {
                delay(4000)
                if (ctrl.notice == msg) ctrl.notice = null
            }
        }
        AnimatedContent(
            ctrl.notice,
            transitionSpec = { (fadeIn(tween(Motion.STATE)) + slideInVertically(tween(Motion.STATE)) { it / 2 }) togetherWith fadeOut(tween(Motion.CONTENT)) },
            label = "notice",
        ) { msg -> if (msg != null) Banner(msg) }
        ctrl.trackingProgress?.let { p ->
            ProgressPill(t("עוקב אחרי הפנים…"), p, ctrl::cancelTracking)
        }
        if (!ctrl.subsSheet) {
            ctrl.speakersProgress?.let { p -> ProgressPill(t("מזהה דוברים…"), p, ctrl::cancelSpeakers) }
        }
        if (!ctrl.bgPanel) {
            ctrl.segmentProgress?.let { p -> ProgressPill(t("מזהה אנשים…"), p, ctrl::cancelFindPeople) }
        }
        if (!ctrl.subsSheet) {
            val models = ctrl.models
            val sp = ctrl.subsProgress
            val eta = rememberEta()
            if (models?.downloading != null) {
                val left = etaText(eta.remainingMs("download", models.progress))
                ProgressPill(
                    t("מוריד את המודל… %d%%").format((models.progress * 100).roundToInt()) + (left?.let { " · $it" } ?: ""),
                    models.progress, ctrl::cancelSubtitles,
                )
            } else if (sp != null) {
                val determinate = sp.first != SubtitleEngine.Stage.PACK
                val left = if (determinate) etaText(eta.remainingMs(sp.first, sp.second)) else null
                ProgressPill(
                    subsStageText(sp.first, sp.second) + (left?.let { " · $it" } ?: ""),
                    if (determinate) sp.second else null, ctrl::cancelSubtitles,
                )
            }
        }
    }
}

@Composable
private fun ProgressPill(text: String, progress: Float?, onStop: () -> Unit) {
    Surface(shape = RoundedCornerShape(Radius.Panel), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), shadowElevation = 4.dp) {
        Row(Modifier.padding(start = Space.M, end = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.width(Space.S))
            Text(text, style = MaterialTheme.typography.bodyMedium.tnum())
            TextButton(onClick = onStop) { Text(t("עצור")) }
        }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(Radius.Card), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), shadowElevation = 4.dp) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(start = Space.M, end = if (action != null) Space.XS else Space.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** How far the timeline is zoomed (pixels per second): kept by the editor, changed by a pinch or the buttons. */
@Stable
internal class TimelineZoom(initial: Float, val min: Float, val max: Float) {
    val pxPerSec = mutableFloatStateOf(initial)

    /** Zoomed once to fit a short video; after that, the zoom is the user's. */
    var fitted = false

    fun target(factor: Float) = (pxPerSec.floatValue * factor).coerceIn(min, max)
}

@Composable
internal fun rememberTimelineZoom(key: Any): TimelineZoom {
    val d = LocalDensity.current
    return remember(key) { with(d) { TimelineZoom(56.dp.toPx(), 8.dp.toPx(), 900.dp.toPx()) } }
}

/**
 * The exported frame: the video as it is, or 9:16, 1:1 or 4:5 with the frame following the faces
 * like a camera operator. Shown on the video while editing.
 */
@Composable
internal fun FramePanel(export: ExportOptions, onChange: (ExportOptions) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        PanelHeader(t("פורמט"), Feature.BACKGROUND.tint, onDone)
        Column(
            Modifier
                .fillMaxWidth()
                .then(panelBody(200.dp, upTo = true))
                .verticalScroll(rememberScrollState())
                .padding(start = Space.L, end = Space.L, bottom = Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Segments(
                listOf(
                    ExportAspect.ORIGINAL to t("מקור"),
                    ExportAspect.VERTICAL to "9:16",
                    ExportAspect.SQUARE to "1:1",
                    ExportAspect.PORTRAIT to "4:5",
                ),
                export.aspect,
            ) { onChange(export.copy(aspect = it)) }
            Text(
                if (export.aspect == ExportAspect.ORIGINAL) t("הסרטון יישמר בצורה המקורית שלו.")
                else t("המסגרת עוקבת אחרי הפנים, כמו צלם. רואים אותה על הסרטון."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Add blur" for the privacy panel: a blur where you tap on the video, then follows the face. */
@Composable
internal fun AddBlurButton(ctrl: EditorController, busy: Boolean) {
    TextButton(
        onClick = {
            ctrl.pauser()
            ctrl.addMode = true
            // On a phone the panel makes way for the video.
            if (!ctrl.sidePanels) ctrl.blurPanel = false
        },
        enabled = !busy,
        modifier = Modifier.testTag(Tags.ADD_BLUR),
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Space.XS))
        Text(t("הוסף טשטוש"))
    }
}

/**
 * A phone's panel takes the timeline's place: this thin scrubber stays over it, so moving through
 * the video (to set a time, to see a change somewhere else) never means closing the panel.
 */
@Composable
internal fun MiniScrubber(ctrl: EditorController, busy: Boolean) {
    val last = (ctrl.frameCount - 1).coerceAtLeast(1)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            androidx.compose.material3.Slider(
                value = ctrl.frame.toFloat(),
                onValueChange = {
                    if (ctrl.playing) ctrl.pauser()
                    ctrl.seek(it.roundToInt())
                },
                valueRange = 0f..last.toFloat(),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.L).testTag(Tags.SCRUBBER),
            )
        }
    }
}
