package com.elad.faceblur.ui

import com.elad.faceblur.i18n.t
import com.elad.faceblur.template.Platform
import com.elad.faceblur.template.SafeZone
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.media3.common.PlaybackParameters
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.effects.BackgroundEffect
import com.elad.faceblur.edit.SfxClip
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.audio.VoicePreviewRenderers
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.border
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.layout.onSizeChanged
import com.elad.faceblur.edit.Reframer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.elad.faceblur.R
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.EditModel
import com.elad.faceblur.edit.EditTrack
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.EditorPanel
import com.elad.faceblur.edit.StabilizeEffect
import com.elad.faceblur.edit.PreviewSource
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.subs.SubtitleEngine
import com.elad.faceblur.subs.SubtitleRenderer
import com.elad.faceblur.subs.SubtitleStyle
import com.elad.faceblur.effects.BlurEffect
import com.elad.faceblur.pipeline.ExportOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll

/** Height of the timeline's filmstrip. */
private val STRIP = 44.dp

// Timeline lanes in the features' colors: blurs coral, subtitles amber, sound effects cyan.
private val BlurClip = Color(0xFFC2304F)
private val ManualClip = Color(0xFFFF8A65)
private val ManualOutline = Color(0xFFFFA48A)
private val SubsClip = Color(0xFFA06F00)

/**
 * Blur editor laid out like a video editor: the player on top (with the real blur applied, or
 * outlines only), transport controls, and a timeline below: a filmstrip with every blur as a
 * clip in rows under it and a fixed playhead in the middle. Drag the timeline to scrub, pinch
 * it to zoom; tap a clip to select it and drag its edges to change when it starts and ends.
 * On the video: drag a blur to move it, pinch to resize. "Add blur" puts one where you tap and
 * follows the face automatically.
 */
@Composable
fun EditorScreen(
    ctrl: EditorController,
    onClose: () -> Unit,
    onExport: () -> Unit,
    export: ExportOptions = ExportOptions(),
    onExportChange: (ExportOptions) -> Unit = {},
    audioOnly: Boolean = false,
    onAudioOnly: ((Boolean) -> Unit)? = null,
    subStyle: SubtitleStyle = SubtitleStyle(),
    onSubStyleChange: (SubtitleStyle) -> Unit = {},
    onSaveTemplate: (() -> Unit)? = null,
) {
    var exportSheet by remember { mutableStateOf(false) }
    // A phone: the selected clip's properties in the timeline's place (the tablet has them beside).
    var propsOpen by remember { mutableStateOf(false) }
    BackHandler {
        when {
            ctrl.addMode -> ctrl.addMode = false
            ctrl.splitPanel -> ctrl.splitPanel = false
            propsOpen -> propsOpen = false
            ctrl.panel != null -> ctrl.closePanel()
            ctrl.selectedId != null || ctrl.selectedCueId != null || ctrl.selectedSfxId != null -> ctrl.deselect()
            else -> onClose()
        }
    }
    @Suppress("UNUSED_VARIABLE") val version = ctrl.version
    val busy = ctrl.trackingProgress != null
    // The filmstrip is loaded once per editor: the timeline leaves the screen while a panel is open.
    val context = LocalContext.current
    val stripSize = with(LocalDensity.current) { STRIP.roundToPx() }
    val thumbs = remember(ctrl) {
        val dur = ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs
        Thumbnails(context, ctrl.session.info.uri, dur, stepUs = 500_000L, sizePx = stripSize)
    }
    LaunchedEffect(thumbs) { thumbs.load() }

    // A tablet's side panel can be closed (the video gets the room); any tool opens it again.
    var hidden by rememberSaveable { mutableStateOf(false) }
    val selecting = ctrl.selected != null || ctrl.selectedCue != null || ctrl.selectedSfx != null
    LaunchedEffect(selecting) { if (!selecting) propsOpen = false }
    val zoom = rememberTimelineZoom(ctrl)
    val watermarkOn = export.watermark.enabled(LocalContext.current)
    // The safe-zone view aid is for vertical frames only (the export's, or the video's own).
    val safeZoneAvailable = SafeZone.appliesTo(export.aspect, ctrl.session.info.displayWidth, ctrl.session.info.displayHeight)
    // While it's on, its platform chooser has a strip of its own directly above the video (see SafeZoneStrip).
    val safeZoneShown = safeZoneAvailable && ctrl.safeZoneOn
    // The width of the video's box on screen, and of the frame in it (the part a vertical export
    // keeps): the safe zone's bar is as wide as the frame.
    val videoBoxW = remember(ctrl) { mutableIntStateOf(0) }
    val safeFrameWidthPx = {
        val a = ctrl.session.analysis
        val crop = if (export.aspect == ExportAspect.ORIGINAL || ctrl.frameCount == 0) 1f else Reframer.cropFraction(a.uprightW, a.uprightH, export.aspect.ratio).first
        videoBoxW.intValue * crop
    }
    // A group opens the tool used last in it (or its first).
    val lastInGroup = remember(ctrl) { mutableStateMapOf<ToolGroup, EditorPanel>() }
    LaunchedEffect(ctrl.panel) { ctrl.panel?.let { lastInGroup[groupOf(it)] = it } }
    val openGroup: (ToolGroup) -> Unit = { g ->
        ctrl.pauser()
        ctrl.deselect()
        val avail = panelsOf(g, ctrl)
        ctrl.openPanel(lastInGroup[g]?.takeIf { it in avail } ?: avail.first())
    }

    // The pieces of the editor; a phone stacks them, a wide screen puts the panels beside the video.
    val topBar: @Composable () -> Unit = {
      // What fits is measured: the icons shown, the export label and the title in this language and
      // text size. Short of room the title goes first, then the export button keeps only its icon.
      val measurer = rememberTextMeasurer()
      val density = LocalDensity.current
      val exportLabel = t("ייצוא")
      val titleLabel = t("עריכה")
      val labelW = with(density) { measurer.measure(exportLabel, MaterialTheme.typography.titleMedium).size.width.toDp() }
      val titleW = with(density) { measurer.measure(titleLabel, AppType.Title).size.width.toDp() }
      val showProps = hidden && selecting && isWide()
      val icons = 3 + (if (ctrl.previewAvailable) 1 else 0) + (if (safeZoneAvailable) 1 else 0)
      // Row padding, the icon buttons, the export button without its word (padding, icon, gap, end
      // margin) and the properties button when it shows.
      val fixed = Space.XS * 2 + 48.dp * icons + Space.M * 2 + 20.dp + Space.S + Space.S + (if (showProps) 150.dp else 0.dp)
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val exportIconOnly = maxWidth < fixed + labelW + Space.XS
        val showTitle = maxWidth >= fixed + labelW + titleW + Space.L
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Everything is saved in the project as it's edited.
            IconButton(onClick = onClose, modifier = Modifier.testTag(Tags.BACK)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("חזרה")) }
            // No room for the title next to the tools and the export button: the space is kept, the
            // word goes (the screen says what it is by itself).
            if (showTitle) {
                Text(
                    titleLabel,
                    style = AppType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Something selected and the side panel closed: its properties are one tap away
            // (the panel never opens by itself, so nothing on screen jumps).
            if (showProps) {
                FilledTonalButton(onClick = { hidden = false }, modifier = Modifier.padding(end = Space.S).testTag(Tags.PROPERTIES)) {
                    Icon(AppIcons.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.S))
                    Text(t("מאפיינים"))
                }
            }
            if (ctrl.previewAvailable) {
                // The real blur in the player, or outlines only.
                IconButton(onClick = ctrl::togglePreview, enabled = !busy) {
                    Icon(
                        if (ctrl.previewOn) EditorIcons.Eye else EditorIcons.EyeOff,
                        t("תצוגה"),
                        tint = if (ctrl.previewOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Where a platform's own screens cover the picture (only drawn here, not in the export). The
            // platform is picked in a small bar of icons in a strip above the video while this is on.
            if (safeZoneAvailable) {
                IconButton(onClick = { ctrl.safeZoneOn = !ctrl.safeZoneOn }, modifier = Modifier.testTag(Tags.SAFE_ZONE)) {
                    Icon(
                        AppIcons.SafeArea,
                        t("אזור בטוח"),
                        tint = if (ctrl.safeZoneOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = ctrl::undo, enabled = ctrl.model.canUndo && !busy, modifier = Modifier.testTag(Tags.UNDO)) { Icon(EditorIcons.Undo, t("בטל")) }
            IconButton(onClick = ctrl::redo, enabled = ctrl.model.canRedo && !busy, modifier = Modifier.testTag(Tags.REDO)) { Icon(EditorIcons.Redo, t("בצע שוב")) }
            GradientButton(
                exportLabel,
                onClick = { ctrl.pauser(); exportSheet = true },
                enabled = !busy,
                icon = EditorIcons.ExportIcon,
                height = 40.dp,
                sidePadding = Space.M,
                iconOnly = exportIconOnly,
                modifier = Modifier.padding(end = Space.S).testTag(Tags.EXPORT),
            )
        }
      }
    }
    // One video for both layouts (see EditorVideoArea).
    val videoArea = remember(ctrl) {
        movableContentOf { modifier: Modifier, ex: ExportOptions, st: SubtitleStyle, messages: Boolean ->
            EditorVideoArea(ctrl, ex, st, modifier, messages) { videoBoxW.intValue = it }
        }
    }
    // The timeline (with the messages and, on a phone, the selected clip's bar over its bottom
    // edge), what belongs to the selected clip, and the groups of tools.
    val timelineArea: @Composable (Boolean, Dp?) -> Unit = { bar, fill ->
        Box(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Timeline(ctrl, busy, subStyle, thumbs, zoom, fill, bottomInset = if (bar && selecting) ContextBarRoom else 0.dp)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EditorMessages(ctrl)
                if (bar) {
                    AnimatedVisibility(
                        visible = selecting,
                        enter = slideInVertically(tween(Motion.STATE)) { it / 2 } + fadeIn(tween(Motion.STATE)),
                        exit = fadeOut(tween(Motion.CONTENT)),
                    ) { ContextBar(ctrl, busy, onProperties = { propsOpen = true }, modifier = Modifier.padding(start = Space.S, end = Space.S, bottom = Space.S)) }
                }
            }
        }
        val sel = ctrl.selected
        Column(Modifier.animateContentSize(tween(220))) {
            if (sel != null && ctrl.sizePanel) SizePanel(ctrl, sel, busy)
            ctrl.selectedSfx?.takeIf { ctrl.sfxGainPanel }?.let { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.L), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("עוצמה"), style = MaterialTheme.typography.labelLarge)
                    var g by remember(c.id) { mutableFloatStateOf(c.gain) }
                    Slider(
                        value = g,
                        onValueChange = { g = it },
                        onValueChangeFinished = { ctrl.setSelectedSfxGain(g) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(ltr("${(g * 100).roundToInt()}%"), style = MaterialTheme.typography.labelMedium.tnum())
                }
            }
            if (ctrl.splitPanel && ctrl.model.cues.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SplitControls(ctrl)
                }
            }
        }
        if (bar) GroupBar(ctrl, export, watermarkOn, busy, vertical = false, current = null, onGroup = openGroup)
    }
    val panelArea: @Composable (EditorPanel) -> Unit = { p ->
        val group = groupOf(p)
        val tabs = PanelTabs(panelsOf(group, ctrl).map { it to panelLabel(it) }, p, group.tint) { ctrl.openPanel(it) }
        CompositionLocalProvider(LocalPanelTabs provides tabs) { when (p) {
            EditorPanel.SUBTITLES -> SubtitlesPanel(ctrl, export, onExportChange)
            EditorPanel.LIST -> AllSubtitlesPanel(ctrl, if (LocalSidePanel.current) Modifier.fillMaxHeight() else Modifier.height(PanelBodyHeight + MinTouch), subStyle)
            EditorPanel.VOICE -> VoicePanel(ctrl)
            EditorPanel.BACKGROUND -> BackgroundPanel(ctrl)
            EditorPanel.STYLE -> SubtitleStylePanel(subStyle, onSubStyleChange) { ctrl.stylePanel = false }
            EditorPanel.WATERMARK -> WatermarkPanel(export.watermark, { onExportChange(export.copy(watermark = it)) }) { ctrl.watermarkPanel = false }
            EditorPanel.BLUR -> BlurPanel(ctrl, busy) { ctrl.blurPanel = false }
            EditorPanel.CUT -> CutPanel(ctrl)
            EditorPanel.STABILIZE -> StabilizePanel(ctrl)
            EditorPanel.FRAME -> FramePanel(export, onExportChange) { ctrl.framePanel = false }
        } }
    }
    val wide = isWide()
    SideEffect { ctrl.sidePanels = wide }

    // A Surface gives text and icons the theme's light-on-dark content color.
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
    if (wide) {
        // A tablet: the tools in a column at the side, the timeline always in view, and a panel
        // always open beside the video (the tools switch it). Nothing opens over anything else.
        // The panel can be closed (the video gets the room); any tool opens it again.
        LaunchedEffect(ctrl.panel) { if (ctrl.panel != null) hidden = false }
        val hide = { hidden = true; ctrl.closePanel() }
        val shown = ctrl.panel ?: if (ctrl.session.subtitlesOnly) EditorPanel.SUBTITLES else EditorPanel.BLUR
        val a = ctrl.session.analysis
        val aspect = if (a.uprightH > 0) a.uprightW.toFloat() / a.uprightH else 16f / 9f
        val side: @Composable (Modifier) -> Unit = { modifier ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
                CompositionLocalProvider(LocalSidePanel provides true, LocalPanelHide provides hide) {
                    // With something selected, the panel shows its properties; when nothing is,
                    // it goes back to the tool that was open. Moving between two selected clips
                    // only changes the values (same key: no transition).
                    AnimatedContent(
                        if (selecting) PROPS else shown,
                        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                        label = "side panel",
                    ) { p -> Column(Modifier.widthIn(max = 640.dp).fillMaxHeight()) { if (p is EditorPanel) panelArea(p) else PropertiesPanel(ctrl, busy, subStyle) } }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val rail = RailWidth
            val fullW = maxWidth
            val fullH = maxHeight
            Row(Modifier.fillMaxSize()) {
                GroupBar(ctrl, export, watermarkOn, busy, vertical = true, current = if (hidden || selecting) null else groupOf(shown)) { g -> openGroup(g); hidden = false }
                if (aspect < 0.9f) {
                    // A tall video stays at the side at full height; the panel and the timeline
                    // share the rest of the width. With the panel closed, the timeline takes its
                    // place: every row of blurs in view, like an editor's timeline on a computer.
                    // The top bar and, while it shows, the safe zone's strip are above the video.
                    val stripH = if (safeZoneShown) SafeZoneStripHeight else 0.dp
                    val videoW = minOf((fullH - 56.dp - stripH) * aspect, (fullW - rail) * 0.5f)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        Row(Modifier.weight(1f)) {
                            // The safe zone's strip goes above the video, in its column (videoW allows for it).
                            Column(Modifier.width(videoW).fillMaxHeight()) {
                                SafeZoneStrip(ctrl, safeZoneShown, safeFrameWidthPx)
                                videoArea(Modifier.weight(1f), export, subStyle, false)
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                if (!hidden) {
                                    side(Modifier.weight(1f).fillMaxWidth())
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy, zoom) }
                                    timelineArea(false, null)
                                } else {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy, zoom) }
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                        val room = maxHeight
                                        Column { timelineArea(false, room) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        SafeZoneStrip(ctrl, safeZoneShown, safeFrameWidthPx)
                        videoArea(Modifier.weight(1f), export, subStyle, false)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy, zoom) }
                        timelineArea(false, null)
                    }
                    AnimatedVisibility(
                        visible = !hidden,
                        enter = expandHorizontally(tween(260)) + fadeIn(tween(200, delayMillis = 60)),
                        exit = shrinkHorizontally(tween(220)) + fadeOut(tween(120)),
                    ) {
                        side(Modifier.width(((fullW - rail) * 0.34f).coerceIn(320.dp, 400.dp)).fillMaxHeight())
                    }
                }
            }
        }
    } else {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        topBar()
        val phoneShows: Any? = if (propsOpen && selecting) PROPS else ctrl.panel
        // Not with a panel open (the video is small then, and the strip would take more of it), nor on a
        // screen this short (a phone on its side): the overlay stays, with the platform chosen last.
        val stripRoom = LocalConfiguration.current.screenHeightDp >= 480
        SafeZoneStrip(ctrl, safeZoneShown && phoneShows == null && stripRoom, safeFrameWidthPx)
        videoArea(Modifier.weight(1f), export, subStyle, phoneShows != null)

        // Video controls and timelines run left to right even in a Hebrew UI.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy, zoom) }

        // The timeline and its tools, or a panel in their place; they slide in when switching.
        AnimatedContent(
            phoneShows,
            transitionSpec = {
                if (initialState != null && targetState != null) {
                    // A tab of the same group: the content changes, nothing moves.
                    fadeIn(tween(Motion.CONTENT)) togetherWith fadeOut(tween(Motion.CONTENT)) using SizeTransform(clip = false)
                } else {
                    (fadeIn(tween(Motion.STATE, delayMillis = 60)) + slideInVertically(tween(Motion.PANEL)) { it / 6 }) togetherWith
                        fadeOut(tween(Motion.CONTENT)) using SizeTransform(clip = false)
                }
            },
            label = "panel",
        ) { p ->
            Column {
                when (p) {
                    null -> timelineArea(true, null)
                    else -> {
                        // A panel in the timeline's place keeps a way to move through the video.
                        MiniScrubber(ctrl, busy)
                        if (p is EditorPanel) panelArea(p) else PropertiesPanel(ctrl, busy, subStyle)
                    }
                }
            }
        }
    }
    }
    }

    if (ctrl.cueEditor) CueEditorDialog(ctrl, subStyle)

    if (exportSheet) {
        ExportSheet(
            ctrl.session.info, export, onExportChange,
            onExport = { exportSheet = false; onExport() },
            onDismiss = { exportSheet = false },
            hasSubs = ctrl.model.cues.isNotEmpty(),
            hasTranslation = ctrl.hasTranslation,
            onEditWatermark = { exportSheet = false; ctrl.pauser(); ctrl.watermarkPanel = true },
            onSaveTemplate = onSaveTemplate?.let { save -> { exportSheet = false; save() } },
            audioOnly = audioOnly,
            onAudioOnly = onAudioOnly,
        )
    }
}


/**
 * The video with its overlays and the messages over it. Moved, not rebuilt, when the layout
 * changes (a phone turned on its side): the player and its surface keep going.
 */
@Composable
private fun EditorVideoArea(ctrl: EditorController, export: ExportOptions, subStyle: SubtitleStyle, modifier: Modifier, messages: Boolean, onBoxWidth: (Int) -> Unit) {
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoWithOverlay(ctrl, export.subs, subStyle, export.aspect, export.watermark, onBoxWidth)
        // With a panel in the timeline's place (a phone), the messages go at the video's foot.
        if (messages) EditorMessages(ctrl, Modifier.align(Alignment.BottomCenter))
    }
}

/** Every blur's look, changeable after detection; the preview follows. */
@Composable
private fun BlurPanel(ctrl: EditorController, busy: Boolean, onDone: () -> Unit) {
    val s = ctrl.session.settings
    // The sliders move freely; the blurs follow when one is let go.
    var strength by remember(s.strength) { mutableFloatStateOf(s.strength) }
    var padding by remember(s.padding) { mutableFloatStateOf(s.padding) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
    PanelHeader(t("טשטוש פנים"), Feature.FACES.tint, onDone) { AddBlurButton(ctrl, busy) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(panelBody(372.dp, upTo = true))
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FacesList(ctrl)
        BlurLookControls(
            s.mode, strength, padding,
            onMode = { ctrl.setBlur(mode = it) },
            onStrength = { strength = it },
            onPadding = { padding = it },
            onDone = { ctrl.setBlur(strength = strength, padding = padding) },
        )
        Spacer(Modifier.height(4.dp))
    }
    }
}

// ---------------------------------------------------------------------- video + overlay

@Composable
private fun VideoWithOverlay(
    ctrl: EditorController,
    subs: SubLang,
    style: SubtitleStyle,
    frameShape: ExportAspect = ExportAspect.ORIGINAL,
    watermark: Watermark = Watermark(),
    onBoxWidth: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val analysis = ctrl.session.analysis
    val settings = ctrl.session.settings
    val aspect = if (analysis.uprightH > 0) analysis.uprightW.toFloat() / analysis.uprightH else 9f / 16f
    val times = ctrl.model.times
    val preview = remember(ctrl) { PreviewSource(if (times.isEmpty()) 0L else times[0]) }
    // Made again with fewer GPU effects if they fail on this device (see effectsLevel).
    val level = ctrl.effectsLevel
    val player = remember(ctrl, level, ctrl.playerEpoch) {
        // Bleeps and the robot voice are heard live; the pitch is set on the player.
        ExoPlayer.Builder(context, VoicePreviewRenderers(context) { ctrl.voiceDsp }).build().apply {
            val blur = if (ctrl.previewAvailable) BlurEffect(preview, { ctrl.session.settings.mode }, { ctrl.session.settings.strength }) else null
            when (level) {
                0 -> {
                    // The background (replaced or blurred live), then the faces' blur over it.
                    val bg = BackgroundEffect(ctrl.bgState)
                    // Steadying last: it moves the finished picture (turned on and off without a restart).
                    val steady = StabilizeEffect { ctrl.stabilizer }
                    setVideoEffects(listOfNotNull<Effect>(bg, blur, steady))
                }
                1 -> if (blur != null) setVideoEffects(listOf<Effect>(blur))
                else -> Unit
            }
            setMediaItem(MediaItem.fromUri(ctrl.session.info.uri))
            prepare()
        }
    }
    val sync = remember(player) { PlayerSync(player, times, ctrl) }
    DisposableEffect(player) {
        ctrl.preview = preview
        sync.attach()
        ctrl.voiceApplier = {
            val v = ctrl.audio.voice
            player.playbackParameters = PlaybackParameters(1f, v.pitch)
            // "No sound" is done in the sound processing, so sound effects still play.
            player.volume = 1f
        }
        ctrl.voiceApplier()
        onDispose {
            ctrl.voiceApplier = {}
            sync.detach()
            ctrl.preview = null
            player.release()
        }
    }
    // Fallback clock while playing, in case the player doesn't report rendered frames.
    LaunchedEffect(player, ctrl.playing) {
        if (!ctrl.playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (!player.isPlaying) continue
            // With cuts, on every frame of the screen: the clock meets a cut without waiting for its frames to be shown.
            // Without, only when the player stops reporting frames.
            val at = sync.frameAtPosition(player.currentPosition)
            // The clock only meets cuts; the frame shown comes from the player's frames (or from the clock when they stop).
            if (ctrl.model.removed.isNotEmpty()) ctrl.checkCutAt(at)
            if (SystemClock.uptimeMillis() - sync.lastFrameReportMs > 300) ctrl.onFrameShown(at)
        }
    }
    Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true).onSizeChanged { onBoxWidth(it.width) }) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.editor_player, null) as PlayerView).also { it.player = player }
            },
            // The player is made again when its effects are cut back: the view must show the new one.
            update = { if (it.player !== player) it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        Overlay(ctrl, subs, style, frameShape, watermark, Modifier.fillMaxSize())
    }
}

/**
 * Keeps the editor's frame index and the player in step. Frames are identified by the
 * presentation time the player reports when it renders them, matched to the analysis frame
 * times; a seek that lands on a neighbouring frame is corrected and repeated.
 */
private class PlayerSync(
    private val player: ExoPlayer,
    private val times: LongArray,
    private val ctrl: EditorController,
) : Player.Listener, VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    private var offsetUs: Long? = null
    private var biasUs = 0L
    private var target = -1
    private var retries = 0
    @Volatile var lastFrameReportMs = 0L
    /** Counts seeks; frame reports made before the latest one are stale and dropped. */
    @Volatile private var seekGen = 0

    fun attach() {
        player.addListener(this)
        player.setVideoFrameMetadataListener(this)
        ctrl.seeker = { seekTo(it) }
        ctrl.pauser = { ctrl.cancelSkip(); player.pause() }
        ctrl.starter = {
            if (player.playbackState == Player.STATE_ENDED) seekTo(ctrl.firstKeptFrame())
            else ctrl.resumeFrame().let { if (it != ctrl.frame) seekTo(it) }
            player.play()
        }
        ctrl.playToggler = {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                ctrl.cancelSkip()
                player.pause()
            } else {
                // From the start (the first frame that isn't cut) when at the end, or after a stop at a cut that runs to the end;
                // from the end of the cut when paused inside one.
                if (player.playbackState == Player.STATE_ENDED || ctrl.frame >= ctrl.lastKeptFrame()) seekTo(ctrl.firstKeptFrame())
                else ctrl.resumeFrame().let { if (it != ctrl.frame) seekTo(it) }
                player.play()
            }
        }
        ctrl.refresher = {
            if (!player.isPlaying) player.seekTo(player.currentPosition)
        }
    }

    fun detach() {
        player.removeListener(this)
        player.clearVideoFrameMetadataListener(this)
        ctrl.seeker = {}
        ctrl.pauser = {}
        ctrl.playToggler = {}
        ctrl.starter = {}
        ctrl.refresher = {}
        ctrl.playing = false
    }

    fun frameAtPosition(positionMs: Long): Int = ctrl.frameAt(positionMs * 1000 - biasUs)

    private fun seekTo(f: Int) {
        if (times.isEmpty()) return
        seekGen++
        target = f
        retries = 0
        doSeek(f)
    }

    private fun doSeek(f: Int) {
        val us = times[f] - times[0] + biasUs
        player.seekTo(max(0L, ceil(us / 1000.0).toLong()))
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        lastFrameReportMs = SystemClock.uptimeMillis()
        val gen = seekGen
        main.post { if (gen == seekGen) onRendered(presentationTimeUs) }
    }

    private fun onRendered(pts: Long) {
        if (times.isEmpty()) return
        val first = offsetUs == null
        val off = offsetUs ?: (pts - times[0]).also { offsetUs = it }
        if (first && ctrl.shownFrame != 0) {
            seekTo(ctrl.shownFrame)
            return
        }
        val f = nearest(pts - off)
        // A jump over a cut buffers for a moment: that is still playing.
        if (player.isPlaying || (ctrl.skipPending && player.playWhenReady)) {
            ctrl.onFrameShown(f)
            syncPlaying()
            return
        }
        // Only a near miss is an offset to correct; a far frame is a stale, superseded seek.
        if (target >= 0 && f != target && abs(f - target) <= 3 && retries < 3) {
            retries++
            biasUs += times[target] - times[f]
            doSeek(target)
        } else {
            target = -1
        }
    }

    private fun nearest(t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
    }

    /**
     * The play state the editor shows: playing, or buffering after a seek while it was playing (a jump
     * over a cut, a sound effect's play): the state holds through the buffering, so nothing flickers.
     */
    private fun syncPlaying() {
        ctrl.playing = player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && (ctrl.skipPending || ctrl.playing))
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncPlaying()

    override fun onPlaybackStateChanged(playbackState: Int) = syncPlaying()

    override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlaying()

    override fun onPlayerError(error: PlaybackException) {
        android.util.Log.e("ONIT", "editor player error", error)
        // What failed, short, so a screenshot of the message says it.
        var root: Throwable = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        // The cause, and the calls that led to it (an argument check has no message of its own,
        // so the checks themselves are skipped), whether the video is HDR, and its colour tags.
        val at = root.stackTrace.filter { !it.methodName.startsWith("check") }.take(3)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        val detail = error.errorCodeName +
            (if (root !== error) " · ${root.javaClass.simpleName}${root.message?.let { ": " + it.take(100) }.orEmpty()}" else "") +
            (if (ctrl.session.info.isHdr) " · HDR" else "") +
            (player.videoFormat?.colorInfo?.let { " · " + it.toLogString() } ?: "") +
            (if (at.isNotEmpty()) "\n$at" else "")
        // The GPU effects are the riskiest part of playback: first the live blur alone is kept
        // (the background and the steadying go), then none (the plain video, outlines over it).
        when (ctrl.effectsLevel) {
            0 -> {
                ctrl.effectsLevel = 1
                ctrl.notice = t("תצוגת הרקע והייצוב לא נתמכת כאן; הטשטוש מוצג.") + "\n" + detail
            }
            1 -> {
                ctrl.effectsLevel = 2
                ctrl.previewAvailable = false
                ctrl.notice = t("תצוגת הטשטוש לא נתמכת כאן. מוצגות מסגרות בלבד.") + "\n" + detail
            }
            else -> ctrl.notice = t("לא הצלחתי להציג את הסרטון") + "\n" + detail
        }
    }
}

@Composable
private fun Overlay(ctrl: EditorController, subs: SubLang, style: SubtitleStyle, aspect: ExportAspect, watermark: Watermark, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = SubtitleRenderer.get(context)
    // The watermark as it will be burned in, drawn once per size.
    val wmCache = remember { arrayOfNulls<Any>(2) }
    val label = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val subHit = remember { SubtitleHit() }
    // The safe zone's margins glide to a newly chosen platform's (read while drawing).
    val safeZone = glidingSafeZone(ctrl.safePlatform)
    Canvas(modifier.pointerInput(ctrl) { videoGestures(ctrl, subHit) }) {
        @Suppress("UNUSED_VARIABLE") val v = ctrl.version
        val live = ctrl.previewOn && ctrl.previewAvailable
        // Subtitles as they'll be burned in (faded when they won't be; a sample while styling).
        val nowMs = ctrl.nowMs
        val cue = ctrl.model.cueAt(nowMs)
        val faded = subs == SubLang.NONE
        val lang = if (faded) SubLang.BOTH else subs
        val hide = if (cue != null && ctrl.audio.maskWords) ctrl.model.bleepedWords(cue) else emptySet()
        val blocks = cue?.blocks(lang, hide) ?: if (ctrl.stylePanel) {
            when (lang) {
                SubLang.ORIGINAL -> SampleBlocks.take(1)
                SubLang.TRANSLATED -> SampleBlocks.drop(1)
                else -> SampleBlocks
            }
        } else {
            emptyList()
        }
        subHit.rect = null
        // A vertical / square export: what's outside its window is dimmed, subtitles go inside it.
        val win = ctrl.reframeWindow(aspect, ctrl.shownFrame)
        val fl = (win?.left ?: 0f) * size.width
        val ft = (win?.top ?: 0f) * size.height
        val fw = (win?.width() ?: 1f) * size.width
        val fh = (win?.height() ?: 1f) * size.height
        if (win != null) {
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset.Zero, Size(fl, size.height))
            drawRect(dim, Offset(fl + fw, 0f), Size(size.width - fl - fw, size.height))
            drawRect(dim, Offset(fl, 0f), Size(fw, ft))
            drawRect(dim, Offset(fl, ft + fh), Size(fw, size.height - ft - fh))
            drawRect(Color.White.copy(alpha = 0.8f), Offset(fl, ft), Size(fw, fh), style = Stroke(1.5.dp.toPx()))
        }
        if (watermark.enabled(context) && fw >= 2f && fh >= 2f) {
            val key = listOf(watermark, fw.toInt(), fh.toInt())
            if (wmCache[0] != key) {
                (wmCache[1] as? android.graphics.Bitmap)?.recycle()
                wmCache[1] = android.graphics.Bitmap.createBitmap(fw.toInt(), fh.toInt(), android.graphics.Bitmap.Config.ARGB_8888).also {
                    Watermark.draw(context, watermark, android.graphics.Canvas(it), it.width, it.height)
                }
                wmCache[0] = key
            }
            (wmCache[1] as? android.graphics.Bitmap)?.let { drawContext.canvas.nativeCanvas.drawBitmap(it, fl, ft, null) }
        }
        if (blocks.isNotEmpty()) {
            val nc = drawContext.canvas.nativeCanvas
            if (faded) nc.saveLayerAlpha(0f, 0f, size.width, size.height, 120)
            nc.save()
            nc.translate(fl, ft)
            if (cue != null) {
                subHit.rect = renderer.draw(
                    nc, blocks, cue.startMs * 1000, cue.endMs * 1000, nowMs * 1000, style.forSpeaker(cue.speaker),
                    fw.toInt(), fh.toInt(), cue.blockTimes(lang),
                )?.apply { offset(fl, ft) }
                subHit.cueId = cue.id
            } else {
                subHit.rect = null
                renderer.draw(nc, blocks, 0L, 2_000_000L, 1_100_000L, style, fw.toInt(), fh.toInt())
            }
            nc.restore()
            if (faded) nc.restore()
        }
        if (ctrl.safeZoneOn && fw >= 2f && fh >= 2f && SafeZone.appliesTo(aspect, ctrl.session.info.displayWidth, ctrl.session.info.displayHeight)) {
            drawSafeZone(ctrl.safePlatform, safeZone(), Offset(fl, ft), Size(fw, fh))
        }
        if (live && ctrl.playing) return@Canvas
        val shapes = ctrl.model.shapesAt(ctrl.shownFrame, ctrl.padding)
        label.textSize = 13.dp.toPx()
        val selectedId = ctrl.selectedId
        for (s in shapes.sortedBy { it.id == selectedId }) {
            val sel = s.id == selectedId
            val tl = Offset((s.cx - s.rx) * size.width, (s.cy - s.ry) * size.height)
            val sz = Size(2 * s.rx * size.width, 2 * s.ry * size.height)
            val c = when {
                sel -> Brand.Coral
                s.manual -> ManualOutline
                else -> Color.White
            }
            if (live) {
                // The real blur is visible: just mark the blurs, the selected one clearly.
                drawOval(c.copy(alpha = if (sel) 1f else 0.55f), tl, sz, style = Stroke(width = if (sel) 2.5.dp.toPx() else 1.dp.toPx()))
            } else {
                drawOval(c.copy(alpha = if (sel) 0.30f else 0.16f), tl, sz)
                drawOval(c, tl, sz, style = Stroke(width = if (sel) 3.dp.toPx() else 1.5.dp.toPx()))
            }
            if (sel || !live) {
                val text = s.id.toString()
                val tw = label.measureText(text)
                drawContext.canvas.nativeCanvas.drawText(
                    text, tl.x + sz.width / 2 - tw / 2, (tl.y - 5.dp.toPx()).coerceAtLeast(label.textSize), label,
                )
            }
        }
    }
}

/** Where the subtitle on the video was drawn, so tapping it opens it for editing. */
private class SubtitleHit {
    var rect: android.graphics.RectF? = null
    var cueId: Int? = null
}

/** Tap: select / add, or edit the subtitle. Drag a blur: move it at this frame. Pinch: resize the blur. */
private suspend fun PointerInputScope.videoGestures(ctrl: EditorController, subHit: SubtitleHit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (ctrl.playing) {
            ctrl.pauser()
            return@awaitEachGesture
        }
        if (ctrl.trackingProgress != null) return@awaitEachGesture
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val hit = ctrl.hitTest(down.position.x / w, down.position.y / h)
        var active: Boolean? = null
        var panAcc = Offset.Zero
        var zoomAcc = 1f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.changes.none { it.pressed }) break
            val pan = ev.calculatePan()
            val zoom = ev.calculateZoom()
            if (active == null) {
                panAcc += pan
                zoomAcc *= zoom
                val multi = ev.changes.count { it.pressed } > 1
                if (panAcc.getDistance() > slop || abs(zoomAcc - 1f) > 0.04f || multi) {
                    val id = hit ?: if (multi) ctrl.selectedId else null
                    active = if (id != null && !ctrl.addMode) {
                        ctrl.select(id)
                        ctrl.beginGesture()
                    } else {
                        false
                    }
                }
            }
            if (active == true) {
                if (pan != Offset.Zero) ctrl.moveSelected(pan.x / w, pan.y / h)
                if (zoom != 1f) ctrl.scaleSelectedBy(zoom)
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
        if (active == null) {
            val r = subHit.rect
            val id = subHit.cueId
            val pad = 12.dp.toPx()
            val onText = r != null && id != null && !ctrl.addMode && hit == null &&
                down.position.x in (r.left - pad)..(r.right + pad) && down.position.y in (r.top - pad)..(r.bottom + pad)
            if (onText && ctrl.model.cue(id!!) != null) {
                ctrl.selectCue(id)
                ctrl.cueEditor = true
            } else {
                ctrl.tap(down.position.x / w, down.position.y / h)
            }
        }
    }
}

// ---------------------------------------------------------------------- transport

@Composable
private fun Transport(ctrl: EditorController, busy: Boolean, zoom: TimelineZoom) {
    val scope = rememberCoroutineScope()
    val zoomTo: (Float) -> Unit = { f ->
        val from = zoom.pxPerSec.floatValue
        val to = zoom.target(f)
        scope.launch { animate(from, to, animationSpec = tween(Motion.STATE)) { v, _ -> zoom.pxPerSec.floatValue = v } }
    }
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.S)) {
        Text(
            // Played / total, left to right as in any player (also in Hebrew), digits of one width.
            buildAnnotatedString {
                append("\u2066")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) { append(timecode(ctrl.timeOf(ctrl.frame))) }
                append(" / ${timecode(ctrl.timeOf(ctrl.frameCount - 1))}")
                append("\u2069")
            },
            style = MaterialTheme.typography.labelMedium.tnum(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctrl.step(-1) }, enabled = !busy) { Icon(EditorIcons.ChevronLeft, t("פריים קודם")) }
            val playSource = remember { MutableInteractionSource() }
            Surface(
                onClick = { ctrl.playToggler() },
                enabled = !busy,
                shape = CircleShape,
                color = if (busy) MaterialTheme.colorScheme.surfaceContainerHigh else Brand.Coral,
                contentColor = Brand.Ink,
                interactionSource = playSource,
                modifier = Modifier.size(44.dp).pressScale(playSource).testTag(Tags.PLAY),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // The icon turns as it changes between play and pause.
                    AnimatedContent(
                        ctrl.playing,
                        transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(180), 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(120), 0.6f)) },
                        label = "play",
                    ) { playing ->
                        Icon(
                            if (playing) EditorIcons.Pause else Icons.Filled.PlayArrow,
                            if (playing) t("השהה") else t("נגן"),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { ctrl.step(1) }, enabled = !busy) { Icon(EditorIcons.ChevronRight, t("פריים הבא")) }
        }
        // The timeline's zoom (a pinch on it does the same).
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { zoomTo(1f / 1.6f) }, modifier = Modifier.testTag(Tags.ZOOM_OUT)) { Icon(EditorIcons.ZoomOut, t("הקטן את ציר הזמן")) }
            IconButton(onClick = { zoomTo(1.6f) }, modifier = Modifier.testTag(Tags.ZOOM_IN)) { Icon(EditorIcons.ZoomIn, t("הגדל את ציר הזמן")) }
        }
    }
}

// ---------------------------------------------------------------------- timeline

/** Greedy packing of blurs into rows so clips never overlap. */
private fun packRows(tracks: List<EditTrack>): List<List<EditTrack>> {
    val rows = ArrayList<ArrayList<EditTrack>>()
    val ends = ArrayList<Int>()
    for (t in tracks.sortedWith(compareBy({ it.start }, { it.id }))) {
        val r = ends.indexOfFirst { it < t.start - 2 }
        if (r < 0) {
            rows += arrayListOf(t)
            ends += t.end
        } else {
            rows[r] += t
            ends[r] = t.end
        }
    }
    return rows
}

private class TimelineGeo(
    val ctrl: EditorController,
    val width: Float,
    val pxPerSec: Float,
    val rows: List<List<EditTrack>>,
    val rowsTop: Float,
    val rowPitch: Float,
    val rowH: Float,
    val rowScroll: Float,
    val handleW: Float,
    /** Top of the subtitle lane, or < 0 when there are no subtitles. */
    val subsTop: Float = -1f,
    val subsH: Float = 0f,
    /** Top of the sound effects lane, or < 0 when there are none. */
    val sfxTop: Float = -1f,
    val sfxH: Float = 0f,
    /** How wide a handle's grab area is (a finger's width), centered on the handle. */
    val grab: Float = handleW * 2,
) {
    val cx = width / 2

    /** Whether [x] is on the handle at a clip's start ([x0], drawn before it) or end ([x1], after it). */
    private fun onStartHandle(x: Float, x0: Float) = x in (x0 - handleW / 2 - grab / 2)..(x0 - handleW / 2 + grab / 2)
    private fun onEndHandle(x: Float, x1: Float) = x in (x1 + handleW / 2 - grab / 2)..(x1 + handleW / 2 + grab / 2)
    val nowUs = ctrl.timeOf(ctrl.frame)

    fun x(us: Long) = cx + (us - nowUs) / 1e6f * pxPerSec
    fun usAt(x: Float): Long = nowUs + ((x - cx) / pxPerSec * 1e6f).toLong()
    fun clipX0(t: EditTrack) = x(ctrl.timeOf(t.start))
    fun clipX1(t: EditTrack) = x(ctrl.timeOf(t.end) + ctrl.frameUs)
    fun rowY(r: Int) = rowsTop + r * rowPitch - rowScroll

    fun clipAt(p: Offset): EditTrack? {
        rows.forEachIndexed { r, row ->
            val y = rowY(r)
            if (p.y in y..(y + rowH)) return row.firstOrNull { p.x in clipX0(it)..clipX1(it) }
        }
        return null
    }

    fun cueX0(c: Cue) = x(ctrl.cueRelUs(c.startMs))
    fun cueX1(c: Cue) = x(ctrl.cueRelUs(c.endMs))

    fun sfxX0(c: SfxClip) = x(ctrl.cueRelUs(c.startMs))
    fun sfxX1(c: SfxClip) = max(x(ctrl.cueRelUs(c.endMs)), sfxX0(c) + sfxH * 1.6f)

    fun sfxAt(p: Offset): SfxClip? {
        if (sfxTop < 0 || p.y !in sfxTop..(sfxTop + sfxH)) return null
        return ctrl.model.sfx.lastOrNull { p.x in sfxX0(it)..sfxX1(it) }
    }

    fun cueAt(p: Offset): Cue? {
        if (subsTop < 0 || p.y !in subsTop..(subsTop + subsH)) return null
        return ctrl.model.cues.firstOrNull { p.x in cueX0(it)..cueX1(it) }
    }

    /** (cue id, is start edge) of the selected subtitle's handle under [p]. */
    fun cueHandleAt(p: Offset): Pair<Int, Boolean>? {
        val c = ctrl.selectedCue ?: return null
        if (subsTop < 0 || p.y !in (subsTop - subsH * 0.4f)..(subsTop + subsH * 1.4f)) return null
        val x0 = cueX0(c)
        val x1 = cueX1(c)
        // On a short clip the two areas overlap: the nearer edge wins.
        val s = onStartHandle(p.x, x0)
        val e = onEndHandle(p.x, x1)
        if (s && e) return c.id to (abs(p.x - x0) <= abs(p.x - x1))
        if (s) return c.id to true
        if (e) return c.id to false
        return null
    }

    /** (track id, is start edge) of the selected clip's handle under [p]. */
    fun handleAt(p: Offset): Pair<Int, Boolean>? {
        val id = ctrl.selectedId ?: return null
        rows.forEachIndexed { r, row ->
            val t = row.firstOrNull { it.id == id } ?: return@forEachIndexed
            val y = rowY(r)
            if (p.y !in (y - rowH * 0.4f)..(y + rowH * 1.4f)) return null
            val x0 = clipX0(t)
            val x1 = clipX1(t)
            val s = onStartHandle(p.x, x0)
            val e = onEndHandle(p.x, x1)
            if (s && e) return id to (abs(p.x - x0) <= abs(p.x - x1))
            if (s) return id to true
            if (e) return id to false
        }
        return null
    }
}

@Composable
private fun Timeline(
    ctrl: EditorController,
    busy: Boolean,
    style: SubtitleStyle,
    thumbs: Thumbnails,
    zoom: TimelineZoom,
    fillHeight: Dp? = null,
    /** Room at the bottom taken by something over it (the selected clip's bar). */
    bottomInset: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stripPx = with(density) { STRIP.toPx() }

    val pxPerSec = zoom.pxPerSec
    val rowScroll = remember { mutableFloatStateOf(0f) }
    val flingJob = remember { arrayOfNulls<Job>(1) }

    val rulerH = with(density) { 16.dp.toPx() }
    val stripTop = rulerH + with(density) { 2.dp.toPx() }
    @Suppress("UNUSED_VARIABLE") val vers = ctrl.version
    val hasSubs = ctrl.model.cues.isNotEmpty()
    val subsTop = if (hasSubs) stripTop + stripPx + with(density) { 6.dp.toPx() } else -1f
    val subsH = with(density) { 22.dp.toPx() }
    val hasSfx = ctrl.model.sfx.isNotEmpty()
    val sfxH = with(density) { 18.dp.toPx() }
    val sfxTop = if (hasSfx) (if (hasSubs) subsTop + subsH else stripTop + stripPx) + with(density) { 5.dp.toPx() } else -1f
    val rowsTop = (if (hasSfx) sfxTop + sfxH else if (hasSubs) subsTop + subsH else stripTop + stripPx) + with(density) { 8.dp.toPx() }
    val rowH = with(density) { 28.dp.toPx() }
    val rowPitch = rowH + with(density) { 4.dp.toPx() }
    val handleW = with(density) { 14.dp.toPx() }
    // A handle is grabbed anywhere within a finger's width of it.
    val grab = with(density) { MinTouch.toPx() }
    val inset = with(density) { bottomInset.toPx() }
    // Four rows of blurs, or as many as fit the room it's given (a tablet with the panel closed).
    val room = fillHeight?.let { with(density) { it.toPx() } }
    val visibleRows = if (room == null) 4 else max(4, ((room - rowsTop - with(density) { 60.dp.toPx() }) / rowPitch).toInt())
    val heightDp = with(density) { (rowsTop + visibleRows * rowPitch).toDp() } + 4.dp

    val rows = remember(ctrl.version) { packRows(ctrl.model.tracks) }
    // Rows under the selected clip's bar can be scrolled up past it.
    val maxScroll = max(0f, rows.size * rowPitch - max(rowPitch, visibleRows * rowPitch - inset))
    if (rowScroll.floatValue > maxScroll) rowScroll.floatValue = maxScroll
    // Keep the selected clip's row in view (above anything over the timeline's bottom).
    LaunchedEffect(ctrl.selectedId, rows, inset) {
        val id = ctrl.selectedId ?: return@LaunchedEffect
        val r = rows.indexOfFirst { row -> row.any { it.id == id } }
        if (r < 0) return@LaunchedEffect
        val top = r * rowPitch
        val room = max(rowPitch, visibleRows * rowPitch - inset)
        if (top < rowScroll.floatValue) rowScroll.floatValue = top
        if (top + rowPitch > rowScroll.floatValue + room) {
            rowScroll.floatValue = (top + rowPitch - room).coerceIn(0f, maxScroll)
        }
    }

    val colors = MaterialTheme.colorScheme
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val widthPx = remember { mutableFloatStateOf(1f) }
    // At first a short video fills the half after the playhead (not a little strip in the
    // middle of a wide screen); zooming after that is the user's.
    LaunchedEffect(widthPx.floatValue) {
        val w = widthPx.floatValue
        if (zoom.fitted || w <= 1f) return@LaunchedEffect
        zoom.fitted = true
        val durS = (ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs) / 1e6f
        if (durS > 0f) {
            val fit = w * 0.45f / durS
            if (fit > pxPerSec.floatValue) pxPerSec.floatValue = min(fit, with(density) { 300.dp.toPx() })
        }
    }
    val geo: () -> TimelineGeo = {
        TimelineGeo(ctrl, widthPx.floatValue, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW, subsTop, subsH, sfxTop, sfxH, grab)
    }
    // What each lane holds, at its start: blurs, subtitles, sound effects.
    val laneIcons = listOf(
        rememberVectorPainter(AppIcons.Face),
        rememberVectorPainter(EditorIcons.Subtitles),
        rememberVectorPainter(AppIcons.Music),
    )

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            // Nothing drawn past the timeline's edges (the rows run on beyond the screen).
            .clipToBounds()
            .pointerInput(ctrl, rows, busy, maxScroll, hasSubs, hasSfx) {
                if (!busy) timelineGestures(ctrl, geo, pxPerSec, rowScroll, maxScroll, scope, flingJob)
            },
    ) {
        widthPx.floatValue = size.width
        @Suppress("UNUSED_VARIABLE") val tv = thumbs.version
        @Suppress("UNUSED_VARIABLE") val vv = ctrl.version
        val g = TimelineGeo(ctrl, size.width, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW, subsTop, subsH, sfxTop, sfxH, grab)
        val durUs = ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs
        val vx0 = g.x(0)
        val vx1 = g.x(durUs)

        // Ruler.
        textPaint.textSize = 10.dp.toPx()
        textPaint.color = colors.onSurfaceVariant.toArgb()
        val steps = floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 30f, 60f)
        val major = steps.firstOrNull { it * g.pxPerSec >= 64.dp.toPx() } ?: 120f
        var s = max(0f, floor(g.usAt(0f) / 1e6f / major) * major)
        while (s * 1e6f <= durUs && g.x((s * 1e6f).toLong()) <= size.width + 40f) {
            val x = g.x((s * 1e6f).toLong())
            drawLine(colors.outline, Offset(x, rulerH - 5.dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
            val half = g.x(((s + major / 2) * 1e6f).toLong())
            if (half < vx1) drawCircle(colors.outline, 1.2.dp.toPx(), Offset(half, rulerH - 2.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(rulerLabel(s), x + 3.dp.toPx(), rulerH - 6.dp.toPx(), textPaint)
            s += major
        }

        // Filmstrip.
        drawRoundRect(colors.surfaceContainerHigh, Offset(vx0, stripTop), Size(vx1 - vx0, stripPx), CornerRadius(6.dp.toPx()))
        clipRect(left = max(0f, vx0), top = stripTop, right = min(size.width, vx1), bottom = stripTop + stripPx) {
            val cell = stripPx
            var k = max(0, floor((0f - vx0) / cell).toInt())
            while (vx0 + k * cell < min(size.width, vx1)) {
                val left = vx0 + k * cell
                val us = g.usAt(left + cell / 2).coerceIn(0L, durUs)
                thumbs.near((us / thumbs.stepUs).toInt())?.let { img ->
                    drawImage(
                        img,
                        dstOffset = IntOffset(left.roundToInt(), stripTop.roundToInt()),
                        dstSize = IntSize(cell.roundToInt() + 1, stripPx.roundToInt()),
                    )
                }
                k++
            }
        }

        // Subtitles.
        if (g.subsTop >= 0) {
            textPaint.textSize = 11.dp.toPx()
            textPaint.color = android.graphics.Color.WHITE
            val selCue = ctrl.selectedCueId
            for (c in ctrl.model.cues) {
                val x0 = g.cueX0(c)
                val x1 = max(g.cueX1(c), x0 + 3.dp.toPx())
                if (x1 < -handleW || x0 > size.width + handleW) continue
                val y = g.subsTop
                drawRoundRect(SubsClip, Offset(x0, y), Size(x1 - x0, subsH), CornerRadius(5.dp.toPx()))
                // Who says it: a bar in the speaker's color.
                if (c.speaker > 0) {
                    drawRoundRect(Color(style.speakerColor(c.speaker)), Offset(x0, y + subsH - 3.dp.toPx()), Size(x1 - x0, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                }
                val label = c.srcText
                if (x1 - x0 > 20.dp.toPx() && label.isNotBlank()) {
                    clipRect(left = x0 + 4.dp.toPx(), top = y, right = x1 - 4.dp.toPx(), bottom = y + subsH) {
                        drawContext.canvas.nativeCanvas.drawText(
                            label, max(x0, 0f) + 6.dp.toPx(), y + subsH / 2 + textPaint.textSize / 2.8f, textPaint,
                        )
                    }
                }
                if (c.id == selCue) {
                    drawRoundRect(Color.White, Offset(x0, y), Size(x1 - x0, subsH), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                    for (left in listOf(true, false)) {
                        val hl = if (left) x0 - handleW else x1
                        drawRoundRect(Color.White, Offset(hl, y - 3.dp.toPx()), Size(handleW, subsH + 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                        val gx = hl + handleW / 2
                        drawLine(Color(0xFF333333), Offset(gx, y + subsH * 0.3f), Offset(gx, y + subsH * 0.7f), 1.5.dp.toPx())
                    }
                }
            }
            // Bleeped stretches of the sound.
            for ((a, b) in ctrl.model.bleeps) {
                val x0 = g.x(ctrl.cueRelUs(a))
                val x1 = max(g.x(ctrl.cueRelUs(b)), x0 + 2.dp.toPx())
                if (x1 < 0 || x0 > size.width) continue
                drawRect(BleepMark, Offset(x0, g.subsTop - 4.dp.toPx()), Size(x1 - x0, 3.dp.toPx()))
            }
        }

        // Sound effects.
        if (g.sfxTop >= 0) {
            textPaint.textSize = 10.dp.toPx()
            textPaint.color = android.graphics.Color.BLACK
            val selSfx = ctrl.selectedSfxId
            for (c in ctrl.model.sfx) {
                val x0 = g.sfxX0(c)
                val x1 = g.sfxX1(c)
                if (x1 < 0 || x0 > size.width) continue
                val y = g.sfxTop
                drawRoundRect(SfxClipColor.copy(alpha = c.gain.coerceIn(0.35f, 1f)), Offset(x0, y), Size(x1 - x0, sfxH), CornerRadius(4.dp.toPx()))
                val label = "♪ " + t(SfxLibrary.name(c.sound))
                if (x1 - x0 > 18.dp.toPx()) {
                    clipRect(left = x0 + 3.dp.toPx(), top = y, right = x1 - 3.dp.toPx(), bottom = y + sfxH) {
                        drawContext.canvas.nativeCanvas.drawText(label, max(x0, 0f) + 5.dp.toPx(), y + sfxH / 2 + textPaint.textSize / 2.8f, textPaint)
                    }
                }
                if (c.id == selSfx) drawRoundRect(Color.White, Offset(x0, y), Size(x1 - x0, sfxH), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }

        // Blur clips.
        clipRect(top = rowsTop - 6.dp.toPx(), bottom = size.height) {
            textPaint.textSize = 11.dp.toPx()
            textPaint.color = android.graphics.Color.WHITE
            val selId = ctrl.selectedId
            rows.forEachIndexed { r, row ->
                val y = g.rowY(r)
                if (y > size.height || y + rowH < rowsTop - rowH) return@forEachIndexed
                for (t in row) {
                    val x0 = g.clipX0(t)
                    val x1 = max(g.clipX1(t), x0 + 3.dp.toPx())
                    if (x1 < -handleW || x0 > size.width + handleW) continue
                    val sel = t.id == selId
                    drawRoundRect((if (t.manual) ManualClip else BlurClip).copy(alpha = if (t.off) 0.3f else 1f), Offset(x0, y), Size(x1 - x0, rowH), CornerRadius(5.dp.toPx()))
                    // The number stays in view while the clip is, and never past the clip's end:
                    // near the end it rides along with it, then goes when there's no room.
                    val text = "#${t.id}"
                    val pad = 6.dp.toPx()
                    val tw = textPaint.measureText(text)
                    val lx = min(max(x0, 0f) + pad, x1 - pad - tw)
                    if (lx >= x0 + pad - 0.5f) {
                        textPaint.color = if (t.manual) Brand.Ink.toArgb() else android.graphics.Color.WHITE
                        drawContext.canvas.nativeCanvas.drawText(
                            text, lx, y + rowH / 2 + textPaint.textSize / 2.8f, textPaint,
                        )
                    }
                    if (sel) {
                        drawRoundRect(Color.White, Offset(x0, y), Size(x1 - x0, rowH), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                        for (left in listOf(true, false)) {
                            val hl = if (left) x0 - handleW else x1
                            drawRoundRect(Color.White, Offset(hl, y - 3.dp.toPx()), Size(handleW, rowH + 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                            val gx = hl + handleW / 2
                            drawLine(Color(0xFF333333), Offset(gx, y + rowH * 0.3f), Offset(gx, y + rowH * 0.7f), 1.5.dp.toPx())
                        }
                    }
                }
            }
            if (rows.isEmpty() && !ctrl.session.subtitlesOnly) {
                textPaint.color = colors.onSurfaceVariant.toArgb()
                textPaint.textSize = 12.dp.toPx()
                val msg = t("אין טשטושים")
                drawContext.canvas.nativeCanvas.drawText(msg, size.width / 2 - textPaint.measureText(msg) / 2, rowsTop + rowH * 0.8f, textPaint)
            }
        }
        if (maxScroll > 0) {
            // Scroll hint: more rows below.
            val frac = rowScroll.floatValue / maxScroll
            val trackH = visibleRows * rowPitch - 8.dp.toPx()
            val barH = trackH * (visibleRows.toFloat() / rows.size)
            drawRoundRect(
                colors.outline, Offset(size.width - 4.dp.toPx(), rowsTop + (trackH - barH) * frac), Size(2.5.dp.toPx(), barH),
                CornerRadius(2.dp.toPx()),
            )
        }

        // Stretches cut out of the video: shaded over every lane, with a line at each edge.
        if (ctrl.model.removed.isNotEmpty()) {
            val top = stripTop
            for ((a, b) in ctrl.model.removed) {
                val x0 = g.x(ctrl.cueRelUs(a))
                val x1 = max(g.x(ctrl.cueRelUs(b)), x0 + 2.dp.toPx())
                if (x1 < 0 || x0 > size.width) continue
                drawRect(CutShade, Offset(x0, top), Size(x1 - x0, size.height - top))
                clipRect(left = x0, top = top, right = x1, bottom = size.height) {
                    // Diagonal hatching.
                    val gap = 7.dp.toPx()
                    var hx = x0 - (size.height - top)
                    while (hx < x1) {
                        drawLine(CutHatch, Offset(hx, size.height), Offset(hx + (size.height - top), top), 1.dp.toPx())
                        hx += gap
                    }
                }
                drawLine(CutEdge, Offset(x0, top), Offset(x0, size.height), 1.5.dp.toPx())
                drawLine(CutEdge, Offset(x1, top), Offset(x1, size.height), 1.5.dp.toPx())
            }
        }
        val pendingCut = ctrl.cutStartMs
        if (pendingCut != null) {
            val x0 = g.x(ctrl.cueRelUs(pendingCut))
            drawLine(CutEdge, Offset(x0, stripTop), Offset(x0, size.height), 2.dp.toPx())
            val xa = min(x0, g.cx)
            drawRect(CutShade.copy(alpha = 0.35f), Offset(xa, stripTop), Size(kotlin.math.abs(g.cx - x0), size.height - stripTop))
        }

        // Lane labels at the start edge, over a fade so they read over the clips.
        val labelW = 32.dp.toPx()
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colors.background, colors.background.copy(alpha = 0f)), 0f, labelW),
            Offset(0f, stripTop + stripPx), Size(labelW, size.height - stripTop - stripPx),
        )
        val icon = 14.dp.toPx()
        fun lane(painter: androidx.compose.ui.graphics.painter.Painter, color: Color, top: Float, h: Float) {
            translate(left = 6.dp.toPx(), top = top + (h - icon) / 2) {
                with(painter) { draw(Size(icon, icon), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)) }
            }
        }
        if (g.subsTop >= 0) lane(laneIcons[1], Feature.SUBTITLES.color, g.subsTop, subsH)
        if (g.sfxTop >= 0) lane(laneIcons[2], Feature.SOUND.color, g.sfxTop, sfxH)
        if (rows.isNotEmpty()) lane(laneIcons[0], Feature.FACES.color, rowsTop, rowH)

        // Playhead.
        drawLine(Color.White, Offset(g.cx, 0f), Offset(g.cx, size.height), 2.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), Offset(g.cx, 3.dp.toPx()))
    }
}

/**
 * Drag: scrub (with fling) or scroll the rows; drag a selected clip's edge: trim it;
 * pinch: zoom; tap: select a clip (or deselect).
 */
private suspend fun PointerInputScope.timelineGestures(
    ctrl: EditorController,
    geo: () -> TimelineGeo,
    pxPerSec: MutableFloatState,
    rowScroll: MutableFloatState,
    maxScroll: Float,
    scope: CoroutineScope,
    flingJob: Array<Job?>,
) {
    val minZoom = 8.dp.toPx()
    val maxZoom = 900.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        flingJob[0]?.cancel()
        if (ctrl.playing) ctrl.pauser()
        val g0 = geo()
        val cueHandle = g0.cueHandleAt(down.position)
        val handle = if (cueHandle == null) g0.handleAt(down.position) else null
        // Dragging the selected sound effect moves it.
        val sfxDrag = g0.sfxAt(down.position)?.takeIf { it.id == ctrl.selectedSfxId }
        val sfxStart = sfxDrag?.startMs ?: 0L
        var mode = 0 // 0 undecided, 1 scrub, 2 rows, 3 trim, 4 zoom
        var acc = Offset.Zero
        val durUs = ctrl.timeOf(ctrl.frameCount - 1)
        var tUs = ctrl.timeOf(ctrl.frame).toDouble()
        val vt = VelocityTracker()
        vt.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val c0 = pressed.first()
            vt.addPosition(c0.uptimeMillis, c0.position)
            if (pressed.size > 1) {
                if (mode == 3 || mode == 5) ctrl.endTrim()
                mode = 4
                pxPerSec.floatValue = (pxPerSec.floatValue * ev.calculateZoom()).coerceIn(minZoom, maxZoom)
                ev.changes.forEach { it.consume() }
                continue
            }
            if (mode == 4) continue
            val pan = ev.calculatePan()
            if (mode == 0) {
                acc += pan
                if (acc.getDistance() > slop) {
                    mode = when {
                        sfxDrag != null && abs(acc.x) >= abs(acc.y) -> 6.also { ctrl.beginSfxDrag() }
                        cueHandle != null -> 5.also { ctrl.beginCueTrim() }
                        handle != null -> 3.also { ctrl.beginTrim() }
                        abs(acc.x) >= abs(acc.y) -> 1
                        else -> 2
                    }
                }
            }
            when (mode) {
                1 -> {
                    tUs = (tUs - pan.x / pxPerSec.floatValue * 1e6).coerceIn(0.0, durUs.toDouble())
                    ctrl.seek(ctrl.frameAt(tUs.toLong()))
                }
                2 -> rowScroll.floatValue = (rowScroll.floatValue - pan.y).coerceIn(0f, maxScroll)
                6 -> {
                    val dx = c0.position.x - down.position.x
                    ctrl.dragSfxTo(sfxDrag!!.id, sfxStart + (dx / pxPerSec.floatValue * 1000f).toLong())
                }
                5 -> {
                    val (id, start) = cueHandle!!
                    ctrl.trimCueTo(id, start, geo().usAt(c0.position.x).coerceIn(0L, durUs + ctrl.frameUs))
                }
                3 -> {
                    val (id, start) = handle!!
                    val us = geo().usAt(c0.position.x) - if (start) 0L else ctrl.frameUs
                    ctrl.trimTo(id, start, ctrl.frameAt(us.coerceIn(0L, durUs)))
                }
            }
            if (mode != 0) ev.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        when (mode) {
            0 -> {
                val sfxHit = g0.sfxAt(down.position)
                val cue = if (sfxHit == null) g0.cueAt(down.position) else null
                val hit = if (cue == null && sfxHit == null) g0.clipAt(down.position) else null
                when {
                    sfxHit != null -> ctrl.selectSfx(sfxHit.id)
                    cue != null -> ctrl.focusCue(cue.id)
                    hit != null -> ctrl.focus(hit.id)
                    down.position.y > g0.rowsTop - 4f -> ctrl.deselect()
                }
            }
            1 -> {
                val v = vt.calculateVelocity().x
                if (abs(v) > 400f) {
                    flingJob[0] = scope.launch {
                        var last = 0f
                        AnimationState(0f, -v).animateDecay(exponentialDecay<Float>()) {
                            val d = value - last
                            last = value
                            tUs = (tUs + d / pxPerSec.floatValue * 1e6).coerceIn(0.0, durUs.toDouble())
                            ctrl.seek(ctrl.frameAt(tUs.toLong()))
                            if (tUs <= 0.0 || tUs >= durUs) cancelAnimation()
                        }
                    }
                }
            }
            3 -> ctrl.endTrim()
            5 -> ctrl.endCueTrim()
        }
    }
}

// ---------------------------------------------------------------------- bottom bar

@Composable
private fun SizePanel(ctrl: EditorController, t: EditTrack, busy: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("גודל"), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = t.scale,
            onValueChange = { ctrl.setSelectedScale(it) },
            onValueChangeFinished = { ctrl.endSlider() },
            valueRange = EditModel.MIN_SCALE..EditModel.MAX_SCALE,
            enabled = !busy,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(36.dp),
        )
        Text(
            ltr("×%.1f".format(t.scale)),
            style = MaterialTheme.typography.labelMedium.tnum(),
            modifier = Modifier.width(40.dp),
        )
    }
}

// ---------------------------------------------------------------------- icons

/** A few Material icons (Apache 2.0) not in the core icon set. */
private val BleepMark = Color(0xFFFF4D6D)
/** The side panel's key for the selected clip's properties. */
private const val PROPS = "props"
private val CutShade = Color(0xCC05060C)
private val CutHatch = Color(0x66FF4D6D)
private val CutEdge = Color(0xFFFF4D6D)
private val SfxClipColor = Brand.Cyan

internal object EditorIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val Pause = icon("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val ExportIcon = icon("ios_share", "M16 5l-1.42 1.42-1.59-1.59V16h-1.98V4.83L9.42 6.42 8 5l4-4 4 4zm4 5v11c0 1.1-.9 2-2 2H6c-1.11 0-2-.9-2-2V10c0-1.11.89-2 2-2h3v2H6v11h12V10h-3V8h3c1.1 0 2 .89 2 2z")
    val TextFormat = icon("text_format", "M5 17v2h14v-2H5zm4.5-4.2h5l.9 2.2h2.1L12.75 4h-1.5L6.5 15h2.1l.9-2.2zM12 5.98L13.87 11h-3.74L12 5.98z")
    val Subtitles = icon(
        "subtitles",
        "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z",
    )
    // Undo and redo turn around in a right-to-left layout (back is to the right there).
    private fun mirrored(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = true)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
    val Undo = mirrored(
        "undo",
        "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
    )
    val Redo = mirrored(
        "redo",
        "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z",
    )
    val ZoomIn = icon(
        "zoom_in",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zm2.5-4h-2v2H9v-2H7V9h2V7h1v2h2v1z",
    )
    val ZoomOut = icon(
        "zoom_out",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM7 9h5v1H7z",
    )
    val ChevronLeft = icon("prev", "M15.41 16.59L10.83 12l4.58-4.59L14 6l-6 6 6 6 1.41-1.41z")
    val ChevronRight = icon("next", "M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z")
    val Cut = icon(
        "cut",
        "M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm6-7.5c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5zM19 3l-6 6 2 2 7-7V3z",
    )
    val TrackBack = icon("track_back", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")
    val TrackForward = icon("track_fwd", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")
    val Resize = icon(
        "resize",
        "M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z",
    )
    val Eye = icon(
        "eye",
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
    )
    val EyeOff = icon(
        "eye_off",
        "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
    )
}

/**
 * The selected clip's properties, in the tablet's side panel: a blur's times, size and
 * following; a subtitle's text, speaker and times; a sound's volume. What the tools in the side
 * column do to it, with the values in view.
 */
@Composable
private fun PropertiesPanel(ctrl: EditorController, busy: Boolean, style: SubtitleStyle) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    val sel = ctrl.selected
    val cue = ctrl.selectedCue
    val sfx = ctrl.selectedSfx
    val title = when {
        sfx != null -> "\u266A " + t(SfxLibrary.name(sfx.sound))
        cue != null -> t("כתובית")
        sel != null -> t("טשטוש #%d").format(sel.id)
        else -> ""
    }
    val color = when {
        sfx != null -> Feature.SOUND.color
        cue != null -> Feature.SUBTITLES.color
        else -> Feature.FACES.tint
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            PanelHeader(title, color, { ctrl.deselect() })
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(panelBody(300.dp, upTo = true))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    sfx != null -> {
                        PropTimes(t("מתחיל ב-%s").format(ltr(timecode(ctrl.cueRelUs(sfx.startMs)))), null)
                        Caption(t("עוצמה"))
                        var g by remember(sfx.id) { mutableFloatStateOf(sfx.gain) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = g,
                                onValueChange = { g = it },
                                onValueChangeFinished = { ctrl.setSelectedSfxGain(g) },
                                valueRange = 0f..2f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(ltr("${(g * 100).roundToInt()}%"), style = MaterialTheme.typography.labelMedium.tnum(), modifier = Modifier.width(48.dp))
                        }
                        PropActions {
                            FilledTonalButton(onClick = { ctrl.playSelectedSfx() }) { Text(t("השמע")) }
                            OutlinedButton(onClick = { ctrl.moveSelectedSfxHere() }) { Text(t("העבר לכאן")) }
                            TextButton(onClick = { ctrl.deleteSelectedSfx() }) { Text(t("מחק")) }
                        }
                    }
                    cue != null -> {
                        val englishSpoken = cue.src == "en" || (cue.src == "" && cue.he.isBlank() && cue.en.isNotBlank())
                        CueField(cue.srcText, t("טקסט"), rtl = !englishSpoken, onFocus = ctrl::beginTextEdit) { ctrl.typeCueText(cue.id, it, cue.otherText) }
                        if (ctrl.hasTranslation) {
                            CueField(cue.otherText, t("תרגום"), rtl = englishSpoken, onFocus = ctrl::beginTextEdit) { ctrl.typeCueText(cue.id, cue.srcText, it) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t("דובר"), style = MaterialTheme.typography.labelLarge)
                            SpeakerButton(cue.speaker, style) { ctrl.setSpeaker(cue.id, it) }
                        }
                        PropTimes(
                            ltr(timecode(ctrl.cueRelUs(cue.startMs))) + " – " + ltr(timecode(ctrl.cueRelUs(cue.endMs))),
                            (cue.endMs - cue.startMs) / 1000f,
                        )
                        PropActions {
                            OutlinedButton(onClick = { ctrl.setSelectedCueEdgeHere(true) }) { Text(t("התחלה כאן")) }
                            OutlinedButton(onClick = { ctrl.setSelectedCueEdgeHere(false) }) { Text(t("סוף כאן")) }
                        }
                        PropActions {
                            FilledTonalButton(onClick = { ctrl.pauser(); ctrl.splitSelectedCue() }) { Text(t("פצל כאן")) }
                            OutlinedButton(onClick = { ctrl.mergeSelectedCue() }) { Text(t("מזג")) }
                            TextButton(onClick = { ctrl.deleteSelectedCue() }) { Text(t("מחק")) }
                        }
                    }
                    sel != null -> {
                        val a = ctrl.timeOf(sel.start)
                        val b = ctrl.timeOf(sel.end) + ctrl.frameUs
                        PropTimes(ltr(timecode(a)) + " – " + ltr(timecode(b)), (b - a) / 1e6f)
                        PropActions {
                            OutlinedButton(onClick = { ctrl.setSelectedEdgeHere(true) }, enabled = !busy) { Text(t("התחלה כאן")) }
                            OutlinedButton(onClick = { ctrl.setSelectedEdgeHere(false) }, enabled = !busy) { Text(t("סוף כאן")) }
                        }
                        Caption(t("גודל"))
                        SizePanel(ctrl, sel, busy)
                        Caption(t("מעקב אחרי הפנים"))
                        PropActions {
                            FilledTonalButton(onClick = { ctrl.trackSelected(-1) }, enabled = !busy) { Text(t("עקוב אחורה")) }
                            FilledTonalButton(onClick = { ctrl.trackSelected(1) }, enabled = !busy) { Text(t("עקוב קדימה")) }
                        }
                        PropActions {
                            OutlinedButton(onClick = { ctrl.splitSelected() }, enabled = !busy) { Text(t("פצל כאן")) }
                            TextButton(onClick = { ctrl.deleteSelected() }, enabled = !busy) { Text(t("מחק")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropTimes(range: String, seconds: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(range, style = MaterialTheme.typography.titleMedium.tnum(), modifier = Modifier.weight(1f))
        if (seconds != null) Text(t("%.1f שנ׳").format(seconds), style = MaterialTheme.typography.bodyMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}


/** Height of the safe zone's strip: the chooser's slim pill (a 30 dp segment, 2 dp padding) with room above and below,
 *  48 dp so each segment's 48 dp touch height fits inside the strip (which clips while it opens and closes). */
private val SafeZoneStripHeight: Dp = 48.dp

/** The chooser's visible segments and the gap between them. */
private val SegmentHeight: Dp = 30.dp
private val SegmentGap: Dp = 4.dp

/**
 * The safe zone's own strip directly above the video (not over it: the whole frame stays in view),
 * holding the platform chooser, as wide as the video's frame ([frameWidthPx]; at least 240 dp, at
 * most the strip less 12 dp a side).
 *
 * It grows and shrinks like the app's other folds, and the video gives it the height while it does.
 * This one strip consciously departs from Motion's "nothing slides the layout around" (the owner's
 * choice: the bar opening reads better than a fade).
 */
@Composable
private fun SafeZoneStrip(ctrl: EditorController, visible: Boolean, frameWidthPx: () -> Float) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(Motion.PANEL)) + fadeIn(tween(Motion.CONTENT)),
        exit = shrinkVertically(tween(Motion.STATE)) + fadeOut(tween(Motion.CONTENT)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(SafeZoneStripHeight), contentAlignment = Alignment.Center) {
            val frame = with(LocalDensity.current) { frameWidthPx().toDp() }
            val pillW = frame.coerceAtLeast(240.dp).coerceAtMost((maxWidth - Space.M * 2).coerceAtLeast(0.dp))
            PlatformChooser(ctrl.safePlatform, { ctrl.safePlatform = it }, Modifier.width(pillW))
        }
    }
}

/**
 * The safe zone's platform chooser: a pill of three equal segments with the platforms' icons (no
 * text) in the theme's colors, the chosen one on a high-contrast stadium that slides from the old
 * choice to the new one while the icons' colors cross-fade with it. Each segment shows 30 dp but
 * takes touches over 48 dp of height (reaching past the pill).
 *
 * The icons are the platforms' own logos, single-colour, from Simple Icons (CC0; trademarks of their
 * owners, used only to label the platform): vector drawables in res/drawable named platform_tiktok.xml,
 * platform_reels.xml and platform_shorts.xml. Replacing a file changes its icon, with no code change.
 * A PNG with the same name in res/drawable-nodpi works too once the XML is removed (white on a
 * transparent background, about 96 px square: it is scaled plainly). Either is drawn tinted in one
 * color (the alpha is the shape).
 */
@Composable
private fun PlatformChooser(selected: Platform, onSelect: (Platform) -> Unit, modifier: Modifier) {
    // Where the highlight is, in segments from the start; a short spring with a hint of overshoot.
    val at = animateFloatAsState(
        Platform.entries.indexOf(selected).toFloat(),
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "platform",
    )
    // In the theme's colors (it sits on the editor's background, not on the video): a slightly raised
    // pill, a highlight in the strongest contrast (light in the dark theme, dark in the light one) and
    // the chosen icon cut out of it in the background's color.
    val colors = MaterialTheme.colorScheme
    val iconOff = colors.onSurfaceVariant
    val iconOn = colors.surface
    val stadium = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier
            .background(colors.surfaceContainerHigh, stadium)
            .border(1.dp, colors.outlineVariant, stadium)
            .padding(2.dp),
    ) {
        // In whole pixels from the pill's measured width, as the row places its segments.
        val density = LocalDensity.current
        val gapPx = with(density) { SegmentGap.roundToPx() }
        val segPx = ((constraints.maxWidth - 2 * gapPx) / 3).coerceAtLeast(0)
        val step = segPx + gapPx
        // One highlight behind the segments. Modifier.offset follows the layout direction, like the
        // row: right to left in Hebrew.
        Box(
            Modifier
                .offset { IntOffset((at.value.coerceIn(0f, 2f) * step).roundToInt(), 0) }
                .size(with(density) { segPx.toDp() }, SegmentHeight)
                .background(colors.onSurface, stadium),
        )
        Row(
            Modifier.fillMaxWidth().height(SegmentHeight).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SegmentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Platform.entries.forEachIndexed { i, p ->
                val on = p == selected
                // The icon's color cross-fades with the highlight under it: the cut-out color where the
                // highlight is, the plain one elsewhere (so no icon vanishes into it as it passes).
                val tint = lerp(iconOff, iconOn, (1f - abs(at.value - i)).coerceIn(0f, 1f))
                val touch = remember { MutableInteractionSource() }
                // A 48 dp tall touch target (centered on the 30 dp segment, so it reaches past the
                // pill); the press ripple stays inside the visible segment.
                Box(
                    Modifier
                        .weight(1f)
                        .requiredHeight(MinTouch)
                        .selectable(selected = on, interactionSource = touch, indication = null, role = Role.RadioButton, onClick = { onSelect(p) })
                        .testTag(Tags.SAFE_ZONE + "." + p.name),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxWidth().height(SegmentHeight).clip(stadium).indication(touch, LocalIndication.current))
                    Icon(painterResource(platformIconRes(p)), platformName(p), Modifier.size(20.dp), tint = tint)
                }
            }
        }
    }
}

/** The file of each platform's icon (see [PlatformChooser]). */
private fun platformIconRes(p: Platform): Int = when (p) {
    Platform.TIKTOK -> R.drawable.platform_tiktok
    Platform.REELS -> R.drawable.platform_reels
    Platform.SHORTS -> R.drawable.platform_shorts
}
