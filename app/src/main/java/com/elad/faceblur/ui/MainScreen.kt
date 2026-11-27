package com.elad.faceblur.ui

import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.i18n.t
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.elad.faceblur.LivePreview
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.QuickTool
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.batch.BatchState
import com.elad.faceblur.UiState
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.VideoInfo
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel) {
    // What the picked video is for: blurring (and the settings screen), subtitles only, or a
    // saved project whose video went missing.
    var pickFor by rememberSaveable { mutableStateOf<QuickTool?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (vm.state is UiState.Relink) vm.relink(uri) else vm.load(uri, pickFor)
    }
    val pick = {
        pickFor = null
        if (!vm.blockedByBatch()) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val pickTool = { tool: QuickTool ->
        pickFor = tool
        if (!vm.blockedByBatch()) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BatchMath.MAX_VIDEOS)) { uris ->
        if (uris.isNotEmpty()) vm.pickedForBatch(uris)
    }
    val pickBatch = { if (!vm.blockedByBatch()) batchPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val state = vm.state
    val working = state is UiState.Working
    var saveTemplate by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    // The manager was open when a template was opened for editing: it comes back after.
    var backToManager by remember { mutableStateOf(false) }

    // "Download on mobile data?" and "model ready", over every screen (the editor's too).
    ModelQuestionHost()

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = working) { /* keep the export alive */ }
    // The batch's screens: back goes home (a running batch goes on in the background).
    BackHandler(enabled = state is UiState.BatchSetup || state is UiState.BatchRun) {
        if (state is UiState.BatchRun) vm.leaveBatch() else vm.reset()
    }

    // A new template (Settings): the same editor, on a template that is saved only from it.
    vm.newTemplateDraft?.let { draft ->
        TemplateEditorScreen(
            vm.templates, draft,
            onClose = {
                vm.closeTemplateEditor()
                if (backToManager) { backToManager = false; showTemplates = true }
            },
            onSaved = vm::templateSaved,
            isNew = true,
        )
        return
    }

    // The template editor takes the whole screen (opened from Settings, the Ready screen or the batch setup).
    vm.editingTemplate?.let { id ->
        val tpl = vm.templates.find(id)
        if (tpl == null) {
            // The template is gone: leave the editor (not while composing).
            LaunchedEffect(id) { vm.closeTemplateEditor() }
        } else {
            TemplateEditorScreen(
                vm.templates, tpl,
                onClose = {
                    vm.closeTemplateEditor()
                    if (backToManager) { backToManager = false; showTemplates = true }
                },
                onSaved = vm::templateSaved,
            )
            return
        }
    }

    if (state is UiState.Editing) {
        EditorTheme(vm.prefs.palette) {
            EditorScreen(
                state.editor,
                onClose = vm::closeEditor,
                onExport = vm::export,
                export = vm.exportOptions,
                onExportChange = vm::changeExport,
                subStyle = vm.subtitleStyle,
                onSubStyleChange = vm::changeSubStyle,
                onSaveTemplate = { saveTemplate = true },
                audioOnly = vm.exportAudioOnly,
                onAudioOnly = { vm.exportAudioOnly = it },
            )
            if (saveTemplate) {
                val inUse = remember(state.editor) { vm.categoriesInUse(state.editor) }
                SaveTemplateDialog(
                    inUse,
                    onSave = { name, keep ->
                        vm.saveEditorAsTemplate(state.editor, name, keep)
                        saveTemplate = false
                    },
                    onDismiss = { saveTemplate = false },
                )
            }
        }
        return
    }
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    if (showSettings) {
        AppSettingsSheet(
            vm.prefs, { showSettings = false },
            onTemplates = { showSettings = false; showTemplates = true },
            onModels = { showSettings = false; showModels = true },
        )
    }
    if (showModels) ModelsSettingsSheet(onDismiss = { showModels = false })
    // First launch: "ONIT's models", once (after older versions' files were checked, so it marks
    // what's already there), on the home screen; not when every model is already on the device.
    var introClosed by rememberSaveable { mutableStateOf(false) }
    val everythingThere = vm.modelStore.statuses.values.all { it.usable || it.installed == com.elad.faceblur.models.Installed.PENDING }
    if (!introClosed && !vm.downloads.prefs.introSeen && vm.modelStore.migrated && state is UiState.Empty && !everythingThere) {
        ModelsIntroSheet(onDismiss = { introClosed = true })
    }
    if (showTemplates) {
        TemplateManagerSheet(
            vm.templates,
            onEdit = { showTemplates = false; backToManager = true; vm.editTemplate(it.id) },
            onNew = { showTemplates = false; backToManager = true; vm.startNewTemplate() },
            onRestored = vm::templateSaved,
            onDismiss = { showTemplates = false },
        )
    }
    vm.crashReport?.let { report -> CrashDialog(report, onClose = vm::dismissCrash) }

    val light = MaterialTheme.colorScheme.isLight
    val glow = if (light) Color(0xFFFFD9E2) else Brand.Night
    // A tablet (or a phone on its side): two columns, content not stretched across the screen.
    val wide = isWide()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // A soft glow of the logo's night blue behind the header.
                    drawCircle(
                        Brush.radialGradient(listOf(glow.copy(alpha = if (light) 0.7f else 0.55f), Color.Transparent), Offset(size.width * 0.8f, 0f), size.width * 0.95f),
                        size.width * 0.95f,
                        Offset(size.width * 0.8f, 0f),
                    )
                }
                .padding(insets),
        ) {
            Box(Modifier.padding(start = if (wide) Space.XL else Space.L, end = if (wide) Space.XL else Space.L, top = Space.L)) { Header(onSettings = { showSettings = true }) }
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 80)) + slideInVertically(tween(320, delayMillis = 80)) { it / 12 }) togetherWith
                        fadeOut(tween(140))
                },
                modifier = Modifier.weight(1f),
                label = "screen",
            ) { s ->
                if (s is UiState.Working) {
                    // Fills the screen: the video as it's processed, the progress under it.
                    WorkingScreen(s, vm.livePreview, renderRatio = vm.prefs.renderRatio, onCancel = vm::cancel, wide = wide)
                    return@AnimatedContent
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (wide) Space.XL else Space.L, vertical = Space.L),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Column(
                    Modifier
                        .widthIn(max = if (!wide) (if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) 680.dp else androidx.compose.ui.unit.Dp.Unspecified) else if (s is UiState.Empty || s is UiState.Ready || s is UiState.Done) 1200.dp else 760.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.L),
                ) {
                    when (s) {
                        UiState.Empty -> HomeContent(vm, wide, onPick = pick, onTool = pickTool, onBatch = pickBatch)
                        is UiState.Relink -> MessageCard(
                            title = t("הסרטון של הפרויקט לא נמצא"),
                            body = if (s.wrong) t("זה לא אותו סרטון (האורך שונה). בחר את הסרטון המקורי של \"%s\".").format(s.project.name)
                            else t("ייתכן שהוא הועבר או נמחק, או שההרשאה אליו פגה. בחר אותו שוב כדי להמשיך לערוך את \"%s\".").format(s.project.name),
                            primary = t("בחר את הסרטון") to { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                            secondary = t("חזרה") to vm::reset,
                        )
                        UiState.Loading -> Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
                            LogoMark(64.dp)
                        }
                        is UiState.Ready -> ReadyContent(vm, s, wide, onReplace = pick)
                        is UiState.Working, is UiState.Editing -> Unit
                        is UiState.BatchSetup -> BatchSetupContent(vm, s, onAddMore = pickBatch, onBack = vm::reset)
                        UiState.BatchRun -> {
                            val batch = BatchState.current
                            if (batch != null) BatchRunContent(vm, batch) else androidx.compose.runtime.LaunchedEffect(Unit) { vm.leaveBatch() }
                        }
                        is UiState.Done -> DoneCard(
                            s,
                            wide = wide,
                            onEdit = if (s.canEdit) vm::edit else null,
                            srtCtrl = if (s.canEdit) vm.doneEditor() else null,
                            onHome = vm::reset,
                            onNew = { vm.reset(); pick() },
                        )
                        is UiState.NoFaces -> {
                            VideoCard(s.info, onReplace = pick)
                            MessageCard(
                                title = t("לא זוהו פנים בסרטון"),
                                body = t("אם יש בסרטון פנים קטנות, רחוקות או מוסתרות חלקית, נסה שוב עם זיהוי מורחב, או סמן אותן ידנית.") + (s.note?.let { "\n\n" + it } ?: ""),
                                primary = if (s.canEdit) t("סמן טשטוש ידנית") to vm::edit else t("נסה עם זיהוי מורחב") to vm::retrySensitive,
                                secondary = if (s.canEdit) t("נסה עם זיהוי מורחב") to vm::retrySensitive else t("חזרה") to vm::backToSettings,
                                icon = AppIcons.Face,
                                color = Feature.FACES.tint,
                            )
                            if (s.fast) FastDetectionWarning()
                            if (s.selfie) SelfieDetectionWarning()
                        }
                        is UiState.AudioDone -> {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            MessageCard(
                                title = t("נשמר כ-MP3"),
                                body = t("%s · %.1f MB · בתיקייה Download/ONIT").format(ltr(s.result.name), s.result.bytes / 1e6f),
                                primary = t("שתף") to {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/mpeg"
                                        putExtra(Intent.EXTRA_STREAM, s.result.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, null))
                                },
                                secondary = if (s.canEdit) t("חזרה לעריכה") to vm::edit else t("סיום") to vm::reset,
                                icon = AppIcons.Music,
                                color = Feature.SOUND.tint,
                            )
                        }
                        is UiState.Failed -> {
                            if (!s.canEdit) s.info?.let { VideoCard(it, onReplace = pick) }
                            MessageCard(
                                title = t("משהו השתבש"),
                                body = s.message,
                                // An export that failed: the edits are all there; go back, or try again.
                                primary = when {
                                    s.canEdit && !s.retry -> t("חזרה לעריכה") to vm::edit
                                    s.canEdit -> t("נסה שוב") to vm::export
                                    else -> t("בחר סרטון אחר") to { vm.reset(); pick() }
                                },
                                secondary = when {
                                    s.canEdit && !s.retry -> null
                                    s.canEdit -> t("חזרה לעריכה") to vm::edit
                                    s.info != null -> t("חזרה להגדרות") to vm::backToSettings
                                    else -> null
                                },
                                details = s.details,
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.S))
                }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ClipCard(modifier) { content() }
}

/**
 * The progress screen: the video as it's being processed (faces as they're found, then the
 * finished frames as they're saved, like a render preview), the progress of the whole job and
 * the time left.
 */
@Composable
private fun WorkingScreen(state: UiState.Working, live: LivePreview?, renderRatio: Float, onCancel: () -> Unit, wide: Boolean = false) {
    val eta = rememberEta()
    val ratio = renderRatio.coerceIn(0.2f, 5f)
    val rem = eta.remainingMs(state.stage, state.progress)
    // During analysis, when the export follows on its own, count it in too.
    val raw = if (rem != null && state.stage == 1 && state.auto) rem + ((eta.elapsedMs + rem) * ratio).toLong() else rem
    val left = eta.smooth(raw)
    // Both steps on one bar, each by its usual share of the time.
    val first = 1f / (1f + ratio)
    val overall = when {
        !state.auto -> state.progress
        state.stage == 1 -> state.progress * first
        else -> first + state.progress * (1f - first)
    }
    val exporting = state.stage == 2
    val feature = if (state.subsOnly) Feature.SUBTITLES else Feature.FACES
    // A tap on the percentage shows how the analysis is doing (for tuning on a device).
    var showStats by remember { mutableStateOf(false) }
    val frame: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier, contentAlignment = Alignment.Center) {
            LiveFrame(
                live?.frame ?: state.info.thumbnail,
                live?.faces.orEmpty(),
                isLive = live != null,
                label = if (exporting) t("תצוגה חיה של הייצוא") else if (state.stage == 1) (if (live?.gpu == true) t("מחפש פנים · GPU") else t("מחפש פנים")) else null,
            )
        }
    }
    val progress: @Composable () -> Unit = {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeatureTile(if (state.stage == 3) AppIcons.Music else if (state.subsOnly) AppIcons.Subtitles else if (exporting) EditorIcons.ExportIcon else AppIcons.Face, feature.tint, 44.dp)
                    Spacer(Modifier.width(Space.M))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.stage == 0 -> t("מכין את הסרטון…")
                                state.stage == 4 -> t("מחיל את התבנית")
                                state.stage == 3 -> t("ממיר את הסאונד ל-MP3")
                                state.subsOnly -> t("שומר את הסרטון עם הכתוביות")
                                state.stage == 1 && state.auto -> t("שלב 1 מתוך 2 · מזהה פנים")
                                state.stage == 1 -> t("מזהה פנים")
                                state.auto -> t("שלב 2 מתוך 2 · מטשטש ושומר")
                                else -> t("מייצא את הסרטון")
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            t("עברו %s").format(ltr(mmss(android.os.SystemClock.elapsedRealtime() - eta.startedAt))) + " · " +
                                (etaText(left) ?: t("מחשב זמן משוער…")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(Space.S))
                    Text(
                        ltr("${(overall * 100).roundToInt()}%"),
                        style = MaterialTheme.typography.headlineMedium.tnum(),
                        color = feature.tint,
                        modifier = Modifier.clickable { showStats = !showStats }.testTag(Tags.PROGRESS_PERCENT),
                    )
                }
                AnalysisStatsPanel(
                    visible = showStats && state.stage == 1,
                    stats = live?.stats,
                    info = state.info,
                    percent = (overall * 100).roundToInt(),
                    elapsedMs = { android.os.SystemClock.elapsedRealtime() - eta.startedAt },
                )
                TimelineProgress(
                    overall,
                    colors = if (state.subsOnly) listOf(Brand.Amber, Color(0xFFFFE08A)) else listOf(Brand.Coral, Brand.Amber),
                )
                if (state.auto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        StepChip(t("זיהוי פנים"), done = exporting, current = !exporting)
                        StepChip(t("טשטוש ושמירה"), done = false, current = exporting)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("השאר את האפליקציה פתוחה עד הסוף. המסך יישאר דלוק."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.M))
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(50)) { Text(t("ביטול")) }
                }
            }
        }
    }
    if (wide) {
        // Side by side: the video as tall as the screen allows, the progress beside it.
        Row(
            Modifier.fillMaxSize().padding(horizontal = Space.XL, vertical = Space.L),
            horizontalArrangement = Arrangement.spacedBy(Space.XL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            frame(Modifier.weight(1f).fillMaxHeight())
            // Scrolls when the stats panel makes it taller than the screen.
            Box(Modifier.width(400.dp).verticalScroll(rememberScrollState())) { progress() }
        }
    } else {
        // The frame and the progress together, in the middle: a wide video leaves no hole between them.
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.XL, vertical = Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.L, Alignment.CenterVertically),
        ) {
            frame(Modifier.fillMaxWidth().weight(1f, fill = false))
            // Scrolls when the stats panel makes it taller than the screen (the frame gives way first).
            Box(Modifier.verticalScroll(rememberScrollState())) { progress() }
        }
    }
}

/** A step of a two-step job: done (check), current (filled) or still to come. */
@Composable
private fun StepChip(text: String, done: Boolean, current: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (current) colors.primaryContainer else colors.surfaceContainerHighest)
            .padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = colors.primary)
            Spacer(Modifier.width(Space.XS))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    }
}

/** A frame fitted into the space, with the faces found on it outlined and a small label. */
@Composable
private fun LiveFrame(frame: android.graphics.Bitmap?, faces: List<com.elad.faceblur.analysis.Box>, isLive: Boolean, label: String?) {
    if (frame == null) return
    val image = remember(frame) { frame.asImageBitmap() }
    val a = frame.width.toFloat() / frame.height.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val w = if (maxHeight != androidx.compose.ui.unit.Dp.Infinity && maxWidth / maxHeight > a) maxHeight * a else maxWidth
        val h = w / a
        Box(
            Modifier
                .size(w, h)
                .clip(MaterialTheme.shapes.large)
                .background(Color.Black),
        ) {
            Image(
                image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                alpha = if (isLive) 1f else 0.55f,
            )
            if (faces.isNotEmpty()) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    for (f in faces) {
                        val bw = f.w * size.width
                        val bh = f.h * size.height
                        drawRoundRect(
                            Brand.Coral,
                            Offset(f.cx * size.width - bw / 2, f.cy * size.height - bh / 2),
                            androidx.compose.ui.geometry.Size(bw, bh),
                            androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                            style = stroke,
                        )
                    }
                }
            }
            if (label != null && isLive) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.M)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = Space.M, vertical = Space.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Brand.Coral))
                    Spacer(Modifier.width(Space.S))
                    Text(
                        if (faces.isEmpty()) label else "$label · ${faces.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneCard(state: UiState.Done, onEdit: (() -> Unit)?, srtCtrl: EditorController?, onHome: () -> Unit, onNew: () -> Unit, wide: Boolean = false) {
    val context = LocalContext.current
    val uri = state.result.uri
    val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuccessBadge(Icons.Filled.Check)
                Spacer(Modifier.width(Space.M))
                Column {
                    Text(t("נשמר בגלריה"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            append("Movies/ONIT")
                            if (!state.subsOnly) {
                                append(" · ")
                                append(if (state.result.faces == 1) t("פנים אחת") else t("%d פנים").format(state.result.faces))
                            }
                            if (state.result.keptHdr) append(t(" · HDR נשמר"))
                            if (state.result.srtFiles > 0) append(t(" · כתוביות SRT ב-Download/ONIT"))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    val player: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            ResultPlayer(uri, state.info.aspect)
        }
    }
    val actions: @Composable () -> Unit = {
            if (state.fast) {
                FastDetectionWarning()
            } else if (state.selfie) {
                SelfieDetectionWarning()
            } else Text(
                if (state.subsOnly) {
                    t("כדאי לעבור על הכתוביות לפני פרסום: זיהוי דיבור ותרגום אוטומטיים עלולים לטעות.")
                } else {
                    t("כדאי לעבור על הסרטון לפני פרסום: זיהוי אוטומטי עלול לפספס פנים מסובבות, מוסתרות או קטנות מאוד.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                GradientButton(
                    t("שתף"),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Share,
                    height = 52.dp,
                )
                AccentOutlinedButton(
                    t("פתח"),
                    onClick = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(view) }
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PlayArrow,
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The subtitles as .srt files (the hidden words as in the video, the times of the cut video): always offered when there are subtitles.
            if (srtCtrl != null && srtCtrl.model.cues.isNotEmpty()) {
                val saveSrt = rememberSrtSave(srtCtrl)
                AccentOutlinedButton(
                    t("שמור כתוביות (SRT)"),
                    saveSrt,
                    Modifier.fillMaxWidth().testTag(Tags.SRT_SAVE_DONE),
                    icon = AppIcons.Download,
                    accent = Feature.SUBTITLES.tint,
                )
            }
            if (onEdit != null) {
                AccentOutlinedButton(
                    t("חזרה לעריכה"),
                    onEdit,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Edit,
                    accent = (if (state.subsOnly) Feature.SUBTITLES else Feature.FACES).tint,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                TextButton(onClick = onHome, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Home, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.S))
                    Text(t("למסך הבית"))
                }
                TextButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.S))
                    Text(t("סרטון חדש"))
                }
            }
    }
    SectionCard {
        if (wide) {
            // The video on one side, what to do with it on the other.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.XL), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.3f)) { player() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.M)) {
                    header()
                    actions()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                header()
                player()
                actions()
            }
        }
    }
}

@Composable
private fun ResultPlayer(uri: Uri, aspect: Float) {
    val context = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exo } },
        modifier = Modifier
            .heightIn(max = 420.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(MaterialTheme.shapes.medium),
    )
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
    icon: ImageVector = Icons.Filled.Warning,
    color: Color = MaterialTheme.colorScheme.error,
    /** Technical details, folded away (to send if it keeps happening). */
    details: String? = null,
) {
    ClipCard(accent = color) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeatureTile(icon, color, 44.dp)
                Spacer(Modifier.width(Space.M))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GradientButton(primary.first, primary.second, Modifier.fillMaxWidth(), height = 52.dp)
            if (secondary != null) {
                AccentOutlinedButton(secondary.first, secondary.second, Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp)
            }
            if (details != null) {
                var open by remember { mutableStateOf(false) }
                val context = LocalContext.current
                TextButton(onClick = { open = !open }) { Text(if (open) t("הסתר פרטים טכניים") else t("פרטים טכניים")) }
                if (open) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.Control))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(Space.M),
                        )
                    }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "ONIT: $details")
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }) { Text(t("שלח את הפרטים")) }
                }
            }
        }
    }
}

/** What went wrong last time, to send (the app can't send anything on its own). */
@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("משהו השתבש")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                Text(t("האפליקציה נתקלה בשגיאה. שלח את הפרטים כדי שאפשר יהיה לתקן אותה."), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .verticalScroll(rememberScrollState())
                            .padding(Space.M),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
                onClose()
            }) { Text(t("שלח")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t("סגור")) } },
    )
}
