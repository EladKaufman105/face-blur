package com.elad.faceblur.ui

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
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val pickTool = { tool: QuickTool ->
        pickFor = tool
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val state = vm.state
    val working = state is UiState.Working

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = working) { /* keep the export alive */ }

    if (state is UiState.Editing) {
        EditorTheme(vm.prefs.palette) {
            EditorScreen(
                state.editor,
                onClose = vm::closeEditor,
                onExport = vm::export,
                export = vm.prefs.export,
                onExportChange = vm.prefs::updateExport,
                subStyle = vm.prefs.subStyle,
                onSubStyleChange = vm.prefs::updateSubStyle,
            )
        }
        return
    }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) AppSettingsSheet(vm.prefs) { showSettings = false }
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
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) { Header(onSettings = { showSettings = true }) }
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
                        .padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Column(
                    Modifier
                        .widthIn(max = if (!wide) androidx.compose.ui.unit.Dp.Unspecified else if (s is UiState.Empty || s is UiState.Ready || s is UiState.Done) 1200.dp else 760.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (s) {
                        UiState.Empty -> {
                            val hint: @Composable () -> Unit = {
                                Text(
                                    t("אפשר גם לשתף סרטון מהגלריה ישירות לאפליקציה"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (wide) {
                                // Start a new video on one side, the saved projects on the other.
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        EnterUp(0) { HeroCard(pick) }
                                        EnterUp(1) { ToolsSection(pickTool) }
                                        EnterUp(3) { hint() }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        EnterUp(2) { ProjectsSection(vm.projects, vm::openProject, vm::renameProject, vm::deleteProject) }
                                    }
                                }
                            } else {
                                EnterUp(0) { HeroCard(pick) }
                                EnterUp(1) { ToolsSection(pickTool) }
                                EnterUp(2) { ProjectsSection(vm.projects, vm::openProject, vm::renameProject, vm::deleteProject) }
                                EnterUp(3) { hint() }
                            }
                        }
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
                        is UiState.Ready -> {
                            // Before detection: only what it needs. Export settings come with saving
                            // (in the editor, or in the sheet of "blur and save").
                            var saveSheet by remember { mutableStateOf(false) }
                            val actions: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GradientButton(
                                        t("טשטש פנים"),
                                        { vm.review = true; vm.start() },
                                        Modifier.fillMaxWidth(),
                                        icon = AppIcons.Face,
                                    )
                                    Text(
                                        t("הפנים יזוהו ויטושטשו, ואז ייפתח העורך: אפשר לבדוק, לתקן ולהוסיף כתוביות, רקע, סאונד וסימן מים לפני השמירה."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { saveSheet = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(t("או: טשטש ושמור ישר, בלי עורך"))
                                    }
                                }
                            }
                            if (wide) {
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Column(Modifier.weight(1.3f)) { EnterUp(0) { VideoCard(s.info, onReplace = pick) } }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        EnterUp(1) { SettingsCard(vm) }
                                        EnterUp(2) { actions() }
                                    }
                                }
                            } else {
                                EnterUp(0) { VideoCard(s.info, onReplace = pick) }
                                EnterUp(1) { SettingsCard(vm) }
                                EnterUp(2) { actions() }
                            }
                            if (saveSheet) {
                                ExportSheet(
                                    s.info, vm.prefs.export, vm.prefs::updateExport,
                                    onExport = { saveSheet = false; vm.review = false; vm.start() },
                                    onDismiss = { saveSheet = false },
                                    action = t("טשטש ושמור"),
                                )
                            }
                        }
                        is UiState.Working, is UiState.Editing -> Unit
                        is UiState.Done -> DoneCard(
                            s,
                            wide = wide,
                            onEdit = if (s.canEdit) vm::edit else null,
                            onHome = vm::reset,
                            onNew = { vm.reset(); pick() },
                        )
                        is UiState.NoFaces -> {
                            VideoCard(s.info, onReplace = pick)
                            MessageCard(
                                title = t("לא זוהו פנים בסרטון"),
                                body = t("אם יש בסרטון פנים קטנות, רחוקות או מוסתרות חלקית, נסה שוב עם זיהוי מורחב, או סמן אותן ידנית."),
                                primary = if (s.canEdit) t("סמן טשטוש ידנית") to vm::edit else t("נסה עם זיהוי מורחב") to vm::retrySensitive,
                                secondary = if (s.canEdit) t("נסה עם זיהוי מורחב") to vm::retrySensitive else t("חזרה") to vm::backToSettings,
                                icon = AppIcons.Face,
                                color = Feature.FACES.tint,
                            )
                        }
                        is UiState.Failed -> {
                            s.info?.let { VideoCard(it, onReplace = pick) }
                            MessageCard(
                                title = t("משהו השתבש"),
                                body = s.message,
                                primary = t("בחר סרטון אחר") to { vm.reset(); pick() },
                                secondary = if (s.info != null) t("חזרה להגדרות") to vm::backToSettings else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                }
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ONIT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                // One line: a narrow screen gets it a little smaller rather than broken.
                androidx.compose.foundation.text.BasicText(
                    t("הכל מעובד על המכשיר. שום דבר לא עולה לרשת."),
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                )
            }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, t("הגדרות")) }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ClipCard(modifier) { content() }
}

/** The main tool: a night-blue card with a miniature timeline of blurs. */
@Composable
private fun HeroCard(onPick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth().pressScale(source),
        shape = MaterialTheme.shapes.extraLarge,
        color = Brand.Ink,
        interactionSource = source,
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF33237A), Brand.Night, Brand.Ink)))
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            // Lanes of blurs, as in the editor: found automatically (coral), marked by hand (peach).
            MiniTimeline(
                Modifier.fillMaxWidth().height(58.dp),
                lanes = listOf(
                    Brand.Coral to listOf(0.03f..0.34f, 0.42f..0.74f, 0.80f..0.97f),
                    Color(0xFFFF8A65) to listOf(0.12f..0.30f, 0.52f..0.88f),
                    Brand.Coral.copy(alpha = 0.55f) to listOf(0.06f..0.22f, 0.62f..0.79f),
                ),
            )
            Spacer(Modifier.height(22.dp))
            Text(t("טשטוש פנים בסרטון"), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                t("הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה."),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(20.dp))
            GradientButton(t("בחר סרטון"), onPick, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
        }
    }
}

/** Quick ways into the editor without face detection, one per tool. */
@Composable
private fun ToolsSection(onTool: (QuickTool) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            SectionTitle(t("כלים נוספים"), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                t("פותח את העורך ישר, בלי זיהוי פנים"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolTile(Modifier.weight(1f), AppIcons.Subtitles, t("כתוביות"), Feature.SUBTITLES) { onTool(QuickTool.SUBTITLES) }
            ToolTile(Modifier.weight(1f), AppIcons.Person, t("הסרת רקע"), Feature.BACKGROUND) { onTool(QuickTool.BACKGROUND) }
            ToolTile(Modifier.weight(1f), AppIcons.Voice, t("קול ואפקטים"), Feature.SOUND) { onTool(QuickTool.SOUND) }
        }
    }
}

@Composable
private fun ToolTile(modifier: Modifier, icon: ImageVector, label: String, feature: Feature, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(source).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        interactionSource = source,
    ) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FeatureTile(icon, feature.tint, 48.dp)
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoCard(info: VideoInfo, onReplace: (() -> Unit)?) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info.thumbnail?.let { bmp ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .aspectRatio(info.aspect, matchHeightConstraintsFirst = true),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip("\u200E${info.displayWidth}×${info.displayHeight}")
                        InfoChip(formatDuration(info.durationMs))
                        InfoChip("${info.fps.roundToInt()}fps")
                        if (info.isHdr) InfoChip("HDR", Brand.Amber.copy(alpha = 0.22f), Feature.SUBTITLES.tint)
                    }
                }
                if (onReplace != null) TextButton(onClick = onReplace) { Text(t("החלף")) }
            }
        }
    }
}

@Composable
private fun SettingsCard(vm: MainViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                SectionTitle(t("טשטוש פנים"), Feature.FACES.tint)
                Text(
                    t("אפשר לשנות את המראה גם אחר כך, בעורך."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp),
                )
            }
            BlurLookControls(
                vm.mode, vm.strength, vm.padding,
                onMode = { vm.mode = it },
                onStrength = { vm.strength = it },
                onPadding = { vm.padding = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("זיהוי מורחב"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t("בודק כל פריים ומטשטש גם פנים שהמערכת פחות בטוחה בהן. מפספס פחות, אבל עלול לטשטש גם דברים מיותרים, ואיטי יותר."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = vm.sensitive, onCheckedChange = { vm.sensitive = it })
            }
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeatureTile(if (state.subsOnly) AppIcons.Subtitles else if (exporting) EditorIcons.ExportIcon else AppIcons.Face, feature.tint, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.stage == 0 -> t("מכין את הסרטון…")
                                state.subsOnly -> t("שומר את הסרטון עם הכתוביות")
                                state.stage == 1 && state.auto -> t("שלב 1 מתוך 2 · מזהה פנים")
                                state.stage == 1 -> t("מזהה פנים")
                                state.auto -> t("שלב 2 מתוך 2 · מטשטש ושומר")
                                else -> t("מייצא את הסרטון")
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            t("עברו %s").format(clockText(android.os.SystemClock.elapsedRealtime() - eta.startedAt)) + " · " +
                                (etaText(left) ?: t("מחשב זמן משוער…")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "‎${(overall * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = feature.tint,
                    )
                }
                TimelineProgress(
                    overall,
                    colors = if (state.subsOnly) listOf(Brand.Amber, Color(0xFFFFE08A)) else listOf(Brand.Coral, Brand.Amber),
                )
                if (state.auto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(50)) { Text(t("ביטול")) }
                }
            }
        }
    }
    if (wide) {
        // Side by side: the video as tall as the screen allows, the progress beside it.
        Row(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            frame(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(400.dp)) { progress() }
        }
    } else {
        // The frame and the progress together, in the middle: a wide video leaves no hole between them.
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            frame(Modifier.fillMaxWidth().weight(1f, fill = false))
            progress()
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = colors.primary)
            Spacer(Modifier.width(4.dp))
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
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Brand.Coral))
                    Spacer(Modifier.width(6.dp))
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

/** "1:05" */
private fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "‎%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun DoneCard(state: UiState.Done, onEdit: (() -> Unit)?, onHome: () -> Unit, onNew: () -> Unit, wide: Boolean = false) {
    val context = LocalContext.current
    val uri = state.result.uri
    val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuccessBadge(Icons.Filled.Check)
                Spacer(Modifier.width(14.dp))
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
            Text(
                if (state.subsOnly) {
                    t("כדאי לעבור על הכתוביות לפני פרסום: זיהוי דיבור ותרגום אוטומטיים עלולים לטעות.")
                } else {
                    t("כדאי לעבור על הסרטון לפני פרסום: זיהוי אוטומטי עלול לפספס פנים מסובבות, מוסתרות או קטנות מאוד.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            if (onEdit != null) {
                AccentOutlinedButton(
                    t("חזרה לעריכה"),
                    onEdit,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Edit,
                    accent = (if (state.subsOnly) Feature.SUBTITLES else Feature.FACES).tint,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onHome, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Home, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("למסך הבית"))
                }
                TextButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("סרטון חדש"))
                }
            }
    }
    SectionCard {
        if (wide) {
            // The video on one side, what to do with it on the other.
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.3f)) { player() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    header()
                    actions()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
) {
    ClipCard(accent = color) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeatureTile(icon, color, 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GradientButton(primary.first, primary.second, Modifier.fillMaxWidth(), height = 52.dp)
            if (secondary != null) {
                AccentOutlinedButton(secondary.first, secondary.second, Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** What went wrong last time, to send (the app can't send anything on its own). */
@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("משהו השתבש")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            .padding(10.dp),
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
