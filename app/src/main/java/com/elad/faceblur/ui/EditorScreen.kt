package com.elad.faceblur.ui

import com.elad.faceblur.i18n.t
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
    subStyle: SubtitleStyle = SubtitleStyle(),
    onSubStyleChange: (SubtitleStyle) -> Unit = {},
) {
    var exportSheet by remember { mutableStateOf(false) }
    BackHandler {
        when {
            ctrl.addMode -> ctrl.addMode = false
            ctrl.stylePanel -> ctrl.stylePanel = false
            ctrl.listEditor -> ctrl.listEditor = false
            ctrl.splitPanel -> ctrl.splitPanel = false
            ctrl.voicePanel -> ctrl.voicePanel = false
            ctrl.bgPanel -> ctrl.bgPanel = false
            ctrl.watermarkPanel -> ctrl.watermarkPanel = false
            ctrl.cutPanel -> ctrl.cutPanel = false
            ctrl.stabilizePanel -> ctrl.stabilizePanel = false
            ctrl.blurPanel -> ctrl.blurPanel = false
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

    // The pieces of the editor; a phone stacks them, a wide screen puts the panels beside the video.
    val topBar: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Everything is saved in the project as it's edited.
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("חזרה")) }
            Text(
                t("עריכה"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            GradientButton(
                t("ייצוא"),
                onClick = { ctrl.pauser(); exportSheet = true },
                enabled = !busy,
                icon = EditorIcons.ExportIcon,
                height = 40.dp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    // One video for both layouts (see EditorVideoArea).
    val videoArea = remember(ctrl) {
        movableContentOf { modifier: Modifier, ex: ExportOptions, st: SubtitleStyle -> EditorVideoArea(ctrl, ex, st, modifier) }
    }
    // The timeline, what belongs to the selected clip, and the tools.
    val timelineArea: @Composable (Boolean, Dp?) -> Unit = { bar, fill ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Timeline(ctrl, busy, subStyle, thumbs, fill) }
        val sel = ctrl.selected
        Column(Modifier.animateContentSize(tween(220))) {
            if (sel != null && ctrl.sizePanel) SizePanel(ctrl, sel, busy)
            ctrl.selectedSfx?.takeIf { ctrl.sfxGainPanel }?.let { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("עוצמה"), style = MaterialTheme.typography.labelLarge)
                    var g by remember(c.id) { mutableFloatStateOf(c.gain) }
                    Slider(
                        value = g,
                        onValueChange = { g = it },
                        onValueChangeFinished = { ctrl.setSelectedSfxGain(g) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text("\u200E${(g * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (ctrl.splitPanel && ctrl.model.cues.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SplitControls(ctrl)
                }
            }
        }
        if (bar) BottomBar(ctrl, sel, ctrl.selectedCue, busy, watermarkOn = export.watermark.enabled(LocalContext.current))
    }
    val panelArea: @Composable (EditorPanel) -> Unit = { p ->
        when (p) {
            EditorPanel.SUBTITLES -> SubtitlesPanel(ctrl, export, onExportChange)
            EditorPanel.LIST -> AllSubtitlesPanel(ctrl, if (LocalSidePanel.current) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.6f), subStyle)
            EditorPanel.VOICE -> VoicePanel(ctrl)
            EditorPanel.BACKGROUND -> BackgroundPanel(ctrl)
            EditorPanel.STYLE -> SubtitleStylePanel(subStyle, onSubStyleChange) { ctrl.stylePanel = false }
            EditorPanel.WATERMARK -> WatermarkPanel(export.watermark, { onExportChange(export.copy(watermark = it)) }) { ctrl.watermarkPanel = false }
            EditorPanel.BLUR -> BlurPanel(ctrl) { ctrl.blurPanel = false }
            EditorPanel.CUT -> CutPanel(ctrl)
            EditorPanel.STABILIZE -> StabilizePanel(ctrl)
        }
    }
    val wide = isWide()
    SideEffect { ctrl.sidePanels = wide }

    // A Surface gives text and icons the theme's light-on-dark content color.
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
    if (wide) {
        // A tablet: the tools in a column at the side, the timeline always in view, and a panel
        // always open beside the video (the tools switch it). Nothing opens over anything else.
        // The panel can be closed (the video gets the room); any tool opens it again.
        var hidden by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(ctrl.panel) { if (ctrl.panel != null) hidden = false }
        val hide = { hidden = true; ctrl.closePanel() }
        val shown = ctrl.panel ?: if (ctrl.session.subtitlesOnly) EditorPanel.SUBTITLES else EditorPanel.BLUR
        val a = ctrl.session.analysis
        val aspect = if (a.uprightH > 0) a.uprightW.toFloat() / a.uprightH else 16f / 9f
        val side: @Composable (Modifier) -> Unit = { modifier ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
                CompositionLocalProvider(LocalSidePanel provides true, LocalPanelHide provides hide) {
                    AnimatedContent(
                        shown,
                        transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(110)) },
                        label = "side panel",
                    ) { p -> Column(Modifier.widthIn(max = 640.dp).fillMaxHeight()) { panelArea(p) } }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val rail = 92.dp
            val fullW = maxWidth
            val fullH = maxHeight
            Row(Modifier.fillMaxSize()) {
                BottomBar(ctrl, ctrl.selected, ctrl.selectedCue, busy, watermarkOn = export.watermark.enabled(LocalContext.current), vertical = true, current = if (hidden) null else shown)
                if (aspect < 0.9f) {
                    // A tall video stays at the side at full height; the panel and the timeline
                    // share the rest of the width. With the panel closed, the timeline takes its
                    // place: every row of blurs in view, like an editor's timeline on a computer.
                    val videoW = minOf((fullH - 56.dp) * aspect, (fullW - rail) * 0.5f)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        Row(Modifier.weight(1f)) {
                            videoArea(Modifier.width(videoW).fillMaxHeight(), export, subStyle)
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                if (!hidden) {
                                    side(Modifier.weight(1f).fillMaxWidth())
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy) }
                                    timelineArea(false, null)
                                } else {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy) }
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
                        videoArea(Modifier.weight(1f), export, subStyle)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy) }
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
        videoArea(Modifier.weight(1f), export, subStyle)

        // Video controls and timelines run left to right even in a Hebrew UI.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Transport(ctrl, busy) }

        // The timeline and its tools, or a panel in their place; they slide in when switching.
        AnimatedContent(
            ctrl.panel,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(300)) { it / 6 }) togetherWith
                    fadeOut(tween(110)) using SizeTransform(clip = false)
            },
            label = "panel",
        ) { p ->
            Column {
                if (p == null) timelineArea(true, null) else panelArea(p)
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
        )
    }
}


/**
 * The video with its overlays and the messages over it. Moved, not rebuilt, when the layout
 * changes (a phone turned on its side): the player and its surface keep going.
 */
@Composable
private fun EditorVideoArea(ctrl: EditorController, export: ExportOptions, subStyle: SubtitleStyle, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoWithOverlay(ctrl, export.subs, subStyle, export.aspect, export.watermark)
        // Messages at the top, where they don't cover the subtitles.
        Column(
            Modifier.align(Alignment.TopCenter).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ctrl.addMode) {
                Banner(t("הקש על הפנים שצריך לטשטש"), action = t("ביטול")) { ctrl.addMode = false }
            } else if (!ctrl.playing && ctrl.cutHere != null) {
                Banner(t("הקטע הזה נחתך מהסרטון"), action = t("החזר")) { ctrl.restoreCutHere() }
            } else if (ctrl.model.tracks.isEmpty() && !ctrl.session.subtitlesOnly && ctrl.notice == null) {
                Banner(t("לא זוהו פנים. אפשר לסמן ידנית עם \"הוסף טשטוש\"."))
            }
            ctrl.notice?.let { msg ->
                LaunchedEffect(msg) {
                    delay(4000)
                    if (ctrl.notice == msg) ctrl.notice = null
                }
            }
            AnimatedContent(
                ctrl.notice,
                transitionSpec = { (fadeIn(tween(200)) + slideInVertically(tween(240)) { -it / 2 }) togetherWith fadeOut(tween(180)) },
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
}

/** Every blur's look, changeable after detection; the preview follows. */
@Composable
private fun BlurPanel(ctrl: EditorController, onDone: () -> Unit) {
    val s = ctrl.session.settings
    // The sliders move freely; the blurs follow when one is let go.
    var strength by remember(s.strength) { mutableFloatStateOf(s.strength) }
    var padding by remember(s.padding) { mutableFloatStateOf(s.padding) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
    PanelHeader(t("טשטוש פנים"), Feature.FACES.tint, onDone)
    Column(
        Modifier
            .fillMaxWidth()
            .then(panelBody(372.dp, upTo = true))
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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

@Composable
private fun ProgressPill(text: String, progress: Float?, onStop: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onStop) { Text(t("עצור")) }
        }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
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
) {
    val context = LocalContext.current
    val analysis = ctrl.session.analysis
    val settings = ctrl.session.settings
    val aspect = if (analysis.uprightH > 0) analysis.uprightW.toFloat() / analysis.uprightH else 9f / 16f
    val times = ctrl.model.times
    val preview = remember(ctrl) { PreviewSource(if (times.isEmpty()) 0L else times[0]) }
    val player = remember(ctrl) {
        // Bleeps and the robot voice are heard live; the pitch is set on the player.
        ExoPlayer.Builder(context, VoicePreviewRenderers(context) { ctrl.voiceDsp }).build().apply {
            // The background (replaced or blurred live), then the faces' blur over it.
            val bg = BackgroundEffect(ctrl.bgState)
            // Steadying last: it moves the finished picture (turned on and off without a restart).
            val steady = StabilizeEffect { ctrl.stabilizer }
            setVideoEffects(if (ctrl.previewAvailable) listOf<Effect>(bg, BlurEffect(preview, { ctrl.session.settings.mode }, { ctrl.session.settings.strength }), steady) else listOf<Effect>(bg, steady))
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
            if (player.isPlaying && SystemClock.uptimeMillis() - sync.lastFrameReportMs > 300) {
                ctrl.onFrameShown(sync.frameAtPosition(player.currentPosition))
            }
        }
    }
    Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.editor_player, null) as PlayerView).also { it.player = player }
            },
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

    fun attach() {
        player.addListener(this)
        player.setVideoFrameMetadataListener(this)
        ctrl.seeker = { seekTo(it) }
        ctrl.pauser = { player.pause() }
        ctrl.playToggler = {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED || ctrl.frame >= times.size - 1) seekTo(0)
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
        ctrl.refresher = {}
        ctrl.playing = false
    }

    fun frameAtPosition(positionMs: Long): Int = ctrl.frameAt(positionMs * 1000 - biasUs)

    private fun seekTo(f: Int) {
        if (times.isEmpty()) return
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
        main.post { onRendered(presentationTimeUs) }
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
        if (player.isPlaying) {
            ctrl.onFrameShown(f)
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

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ctrl.playing = isPlaying
    }

    override fun onPlayerError(error: PlaybackException) {
        // The live blur is the riskiest part of playback: drop it and play plain video.
        if (ctrl.previewAvailable) {
            ctrl.previewAvailable = false
            ctrl.notice = t("תצוגת הטשטוש לא נתמכת כאן. מוצגות מסגרות בלבד.")
            player.setVideoEffects(emptyList())
            player.prepare()
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
            val key = Triple(watermark, fw.toInt(), fh.toInt())
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
private fun Transport(ctrl: EditorController, busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) { append(clock(ctrl.timeOf(ctrl.frame))) }
                append(" / ${clock(ctrl.timeOf(ctrl.frameCount - 1))}")
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
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
                modifier = Modifier.size(44.dp).pressScale(playSource),
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
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
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
            IconButton(onClick = ctrl::undo, enabled = ctrl.model.canUndo && !busy) { Icon(EditorIcons.Undo, t("בטל")) }
        }
    }
}

private fun clock(us: Long): String {
    val tenths = (us / 100_000L).toInt()
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
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
) {
    val cx = width / 2
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
        if (p.x in (x0 - handleW * 1.6f)..(x0 + handleW * 0.4f)) return c.id to true
        if (p.x in (x1 - handleW * 0.4f)..(x1 + handleW * 1.6f)) return c.id to false
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
            if (p.x in (x0 - handleW * 1.6f)..(x0 + handleW * 0.4f)) return id to true
            if (p.x in (x1 - handleW * 0.4f)..(x1 + handleW * 1.6f)) return id to false
        }
        return null
    }
}

@Composable
private fun Timeline(ctrl: EditorController, busy: Boolean, style: SubtitleStyle, thumbs: Thumbnails, fillHeight: Dp? = null) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stripPx = with(density) { STRIP.toPx() }

    val pxPerSec = remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
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
    val rowH = with(density) { 24.dp.toPx() }
    val rowPitch = rowH + with(density) { 5.dp.toPx() }
    val handleW = with(density) { 12.dp.toPx() }
    // Four rows of blurs, or as many as fit the room it's given (a tablet with the panel closed).
    val room = fillHeight?.let { with(density) { it.toPx() } }
    val visibleRows = if (room == null) 4 else max(4, ((room - rowsTop - with(density) { 60.dp.toPx() }) / rowPitch).toInt())
    val heightDp = with(density) { (rowsTop + visibleRows * rowPitch).toDp() } + 4.dp

    val rows = remember(ctrl.version) { packRows(ctrl.model.tracks) }
    val maxScroll = max(0f, (rows.size - visibleRows) * rowPitch)
    if (rowScroll.floatValue > maxScroll) rowScroll.floatValue = maxScroll
    // Keep the selected clip's row in view.
    LaunchedEffect(ctrl.selectedId, rows) {
        val id = ctrl.selectedId ?: return@LaunchedEffect
        val r = rows.indexOfFirst { row -> row.any { it.id == id } }
        if (r < 0) return@LaunchedEffect
        val top = r * rowPitch
        if (top < rowScroll.floatValue) rowScroll.floatValue = top
        if (top + rowPitch > rowScroll.floatValue + visibleRows * rowPitch) {
            rowScroll.floatValue = (top + rowPitch - visibleRows * rowPitch).coerceIn(0f, maxScroll)
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
    val fitted = remember { booleanArrayOf(false) }
    LaunchedEffect(widthPx.floatValue) {
        val w = widthPx.floatValue
        if (fitted[0] || w <= 1f) return@LaunchedEffect
        fitted[0] = true
        val durS = (ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs) / 1e6f
        if (durS > 0f) {
            val fit = w * 0.45f / durS
            if (fit > pxPerSec.floatValue) pxPerSec.floatValue = min(fit, with(density) { 300.dp.toPx() })
        }
    }
    val geo: () -> TimelineGeo = {
        TimelineGeo(ctrl, widthPx.floatValue, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW, subsTop, subsH, sfxTop, sfxH)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .pointerInput(ctrl, rows, busy, maxScroll, hasSubs, hasSfx) {
                if (!busy) timelineGestures(ctrl, geo, pxPerSec, rowScroll, maxScroll, scope, flingJob)
            },
    ) {
        widthPx.floatValue = size.width
        @Suppress("UNUSED_VARIABLE") val tv = thumbs.version
        @Suppress("UNUSED_VARIABLE") val vv = ctrl.version
        val g = TimelineGeo(ctrl, size.width, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW, subsTop, subsH, sfxTop, sfxH)
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
            drawContext.canvas.nativeCanvas.drawText(clockShort(s), x + 3.dp.toPx(), rulerH - 6.dp.toPx(), textPaint)
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
                    drawRoundRect(if (t.manual) ManualClip else BlurClip, Offset(x0, y), Size(x1 - x0, rowH), CornerRadius(5.dp.toPx()))
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

        // Playhead.
        drawLine(Color.White, Offset(g.cx, 0f), Offset(g.cx, size.height), 2.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), Offset(g.cx, 3.dp.toPx()))
    }
}

private fun clockShort(sec: Float): String {
    val whole = floor(sec).toInt()
    return if (sec - whole > 0.01f) "%d:%02d.5".format(whole / 60, whole % 60) else "%d:%02d".format(whole / 60, whole % 60)
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
            "×%.1f".format(t.scale),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun BottomBar(ctrl: EditorController, sel: EditTrack?, cue: Cue?, busy: Boolean, watermarkOn: Boolean = false, vertical: Boolean = false, current: EditorPanel? = null) {
    // What's selected shows as a line in its feature's color along the bar's inner edge.
    val selFeature = when {
        ctrl.selectedSfx != null -> Feature.SOUND
        cue != null -> Feature.SUBTITLES
        sel != null -> Feature.FACES
        else -> null
    }
    val line by animateColorAsState(selFeature?.color ?: Color.Transparent, tween(220), label = "bar")
    val tools = ArrayList<ToolSpec>()
    // The main set has more tools than fit a phone's width: it scrolls there.
    var many = false
    val sfx = ctrl.selectedSfx
    if (sfx != null) {
        tools += ToolSpec(t("השמע"), Icons.Filled.PlayArrow, true, feature = Feature.SOUND) { ctrl.playSelectedSfx() }
        tools += ToolSpec(t("העבר לכאן"), AppIcons.MoveHere, true, feature = Feature.SOUND) { ctrl.moveSelectedSfxHere() }
        tools += ToolSpec(t("עוצמה"), AppIcons.Volume, true, active = ctrl.sfxGainPanel, feature = Feature.SOUND) { ctrl.sfxGainPanel = !ctrl.sfxGainPanel }
        tools += ToolSpec(t("מחק"), Icons.Filled.Delete, true) { ctrl.deleteSelectedSfx() }
        tools += ToolSpec(t("סיום"), Icons.Filled.Check, true) { ctrl.deselect() }
    } else if (cue != null) {
        tools += ToolSpec(t("ערוך טקסט"), Icons.Filled.Edit, true, feature = Feature.SUBTITLES) { ctrl.pauser(); ctrl.cueEditor = true }
        tools += ToolSpec(t("פצל כאן"), EditorIcons.Cut, true, feature = Feature.SUBTITLES) { ctrl.pauser(); ctrl.splitSelectedCue() }
        tools += ToolSpec(t("מזג"), AppIcons.MergeDown, true, feature = Feature.SUBTITLES) { ctrl.mergeSelectedCue() }
        tools += ToolSpec(t("עיצוב"), EditorIcons.TextFormat, true, feature = Feature.SUBTITLES) { ctrl.pauser(); ctrl.stylePanel = true }
        tools += ToolSpec(t("מחק"), Icons.Filled.Delete, true) { ctrl.deleteSelectedCue() }
        tools += ToolSpec(t("סיום"), Icons.Filled.Check, true) { ctrl.deselect() }
    } else if (sel == null) {
        many = true
        val subsOnly = ctrl.session.subtitlesOnly
        val hasCues = ctrl.model.cues.isNotEmpty()
        val subsTools = ArrayList<ToolSpec>()
        subsTools += ToolSpec(t("כתוביות"), EditorIcons.Subtitles, !busy, feature = Feature.SUBTITLES, current = current == EditorPanel.SUBTITLES) { ctrl.pauser(); ctrl.subsSheet = true }
        if (hasCues || subsOnly) subsTools += ToolSpec(t("עיצוב"), EditorIcons.TextFormat, !busy, feature = Feature.SUBTITLES, current = current == EditorPanel.STYLE) { ctrl.pauser(); ctrl.stylePanel = true }
        // Words per subtitle is in the list too ("all subtitles").
        if (hasCues) subsTools += ToolSpec(t("כל הכתוביות"), AppIcons.ListLines, !busy, feature = Feature.SUBTITLES, current = current == EditorPanel.LIST) { ctrl.pauser(); ctrl.listEditor = true }
        subsTools += ToolSpec(t("רקע"), AppIcons.Background, !busy, active = ctrl.background.active, feature = Feature.BACKGROUND, current = current == EditorPanel.BACKGROUND) { ctrl.pauser(); ctrl.bgPanel = true }
        subsTools += ToolSpec(t("סאונד"), AppIcons.Voice, !busy, active = ctrl.audio.voice != VoiceFx.NONE || ctrl.model.bleeps.isNotEmpty() || ctrl.model.sfx.isNotEmpty(), feature = Feature.SOUND, current = current == EditorPanel.VOICE) {
            ctrl.pauser(); ctrl.voicePanel = true
        }
        subsTools += ToolSpec(t("סימן מים"), AppIcons.Watermark, !busy, active = watermarkOn, current = current == EditorPanel.WATERMARK) { ctrl.pauser(); ctrl.watermarkPanel = true }
        subsTools += ToolSpec(t("חיתוך"), EditorIcons.Cut, !busy, active = ctrl.cutCount > 0, current = current == EditorPanel.CUT) { ctrl.pauser(); ctrl.cutPanel = true }
        subsTools += ToolSpec(t("ייצוב"), AppIcons.Steady, !busy, active = ctrl.stabilize, current = current == EditorPanel.STABILIZE) { ctrl.pauser(); ctrl.stabilizePanel = true }
        if (subsOnly) tools += subsTools
        tools += ToolSpec(
            if (ctrl.addMode) t("ביטול") else t("הוסף טשטוש"),
            if (ctrl.addMode) Icons.Filled.Close else Icons.Filled.Add,
            !busy,
            active = ctrl.addMode,
            feature = Feature.FACES,
        ) {
            ctrl.addMode = !ctrl.addMode
            if (ctrl.addMode) ctrl.pauser()
        }
        if (!subsOnly) {
            tools += ToolSpec(t("טשטוש"), AppIcons.Face, !busy, feature = Feature.FACES, current = current == EditorPanel.BLUR) { ctrl.pauser(); ctrl.blurPanel = true }
            tools += subsTools
        }
    } else {
        tools += ToolSpec(t("מחק"), Icons.Filled.Delete, !busy) { ctrl.deleteSelected() }
        tools += ToolSpec(t("פצל"), EditorIcons.Cut, !busy, feature = Feature.FACES) { ctrl.splitSelected() }
        tools += ToolSpec(t("עקוב קדימה"), EditorIcons.TrackForward, !busy, feature = Feature.FACES) { ctrl.trackSelected(1) }
        tools += ToolSpec(t("עקוב אחורה"), EditorIcons.TrackBack, !busy, feature = Feature.FACES) { ctrl.trackSelected(-1) }
        tools += ToolSpec(t("גודל"), EditorIcons.Resize, !busy, active = ctrl.sizePanel, feature = Feature.FACES) { ctrl.sizePanel = !ctrl.sizePanel }
        tools += ToolSpec(t("סיום"), Icons.Filled.Check, true) { ctrl.deselect() }
    }

    if (vertical) {
        // A tablet: the tools in a column beside the video, which gets the screen's height.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.width(92.dp).fillMaxHeight().drawWithContent {
                drawContent()
                drawRect(line, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
            },
        ) {
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (tool in tools) Tool(tool, Modifier.fillMaxWidth())
            }
        }
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            drawRect(line, size = Size(size.width, 2.dp.toPx()))
        },
    ) {
        if (many) {
            // More tools than fit: they keep a usable width and the row scrolls (the last one
            // shows cut off, so it's clear there's more).
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                val w = maxOf(68.dp, maxWidth / tools.size)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (tool in tools) Tool(tool, Modifier.width(w))
                }
            }
        } else {
            // Every tool gets an equal share of the width: nothing hides off screen.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tool in tools) Tool(tool, Modifier.weight(1f))
            }
        }
    }
}

/** What a tool does and how it shows. */
private class ToolSpec(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val active: Boolean = false,
    val feature: Feature? = null,
    /** Its panel is the one showing (a tablet's side panel). */
    val current: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A tool: icon over a one-line label; [active] for a toggle that's on. A tool that works on one
 * [feature] shows its icon in that feature's color.
 */
@Composable
private fun Tool(spec: ToolSpec, modifier: Modifier) {
    val label = spec.label
    val icon = spec.icon
    val enabled = spec.enabled
    val active = spec.active
    val feature = spec.feature
    val onClick = spec.onClick
    val alpha = if (enabled) 1f else 0.38f
    val accent = feature?.color ?: MaterialTheme.colorScheme.primary
    val iconTint = (feature?.color ?: if (active) accent else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha)
    val textTint = (if (active) accent else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha)
    val bg by animateColorAsState(if (active) accent.copy(alpha = 0.2f) else Color.Transparent, tween(200), label = "tool")
    // The tool whose panel is showing sits on a lighter tile.
    val tile by animateColorAsState(if (spec.current) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, tween(200), label = "tile")
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source)
            .clip(RoundedCornerShape(12.dp))
            .background(tile)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = textTint,
        )
    }
}

// ---------------------------------------------------------------------- icons

/** A few Material icons (Apache 2.0) not in the core icon set. */
private val BleepMark = Color(0xFFFF4D6D)
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
    val Undo = icon(
        "undo",
        "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
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
