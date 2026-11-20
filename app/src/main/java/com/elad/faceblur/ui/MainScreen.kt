package com.elad.faceblur.ui

import com.elad.faceblur.i18n.t
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.UiState
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.pipeline.VideoInfo
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel) {
    // What the picked video is for: blurring (and the settings screen), subtitles only, or a
    // saved project whose video went missing.
    var pickForSubs by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (vm.state is UiState.Relink) vm.relink(uri) else vm.load(uri, thenSubtitles = pickForSubs)
    }
    val pick = {
        pickForSubs = false
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val pickSubs = {
        pickForSubs = true
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

    val light = MaterialTheme.colorScheme.isLight
    val glow = if (light) Color(0xFFFFD9E2) else Brand.Night
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
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onSettings = { showSettings = true })
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 80)) + slideInVertically(tween(320, delayMillis = 80)) { it / 12 }) togetherWith
                        fadeOut(tween(140)) using SizeTransform(clip = false)
                },
                label = "screen",
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        UiState.Empty -> {
                            EnterUp(0) { HeroCard(pick) }
                            EnterUp(1) { SubtitlesCard(pickSubs) }
                            EnterUp(2) { ProjectsSection(vm.projects, vm::openProject, vm::renameProject, vm::deleteProject) }
                            EnterUp(3) {
                                Text(
                                    t("אפשר גם לשתף סרטון מהגלריה ישירות לאפליקציה"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
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
                            EnterUp(0) { VideoCard(s.info, onReplace = pick) }
                            EnterUp(1) { SettingsCard(vm) }
                            EnterUp(2) {
                                SectionCard {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SectionTitle(t("הגדרות ייצוא"), MaterialTheme.colorScheme.onSurfaceVariant)
                                        ExportOptionsPanel(s.info, vm.prefs.export, vm.prefs::updateExport)
                                    }
                                }
                            }
                            EnterUp(3) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GradientButton(t("טשטש פנים"), vm::start, Modifier.fillMaxWidth(), icon = AppIcons.Face)
                                    AccentOutlinedButton(
                                        t("כתוביות בלבד, בלי טשטוש"), vm::startSubtitles, Modifier.fillMaxWidth(),
                                        icon = AppIcons.Subtitles, accent = Feature.SUBTITLES.tint,
                                    )
                                }
                            }
                        }
                        is UiState.Working -> {
                            VideoCard(s.info, onReplace = null)
                            WorkingCard(s, renderAfter = if (vm.review) null else vm.prefs.renderRatio, onCancel = vm::cancel)
                        }
                        is UiState.Editing -> Unit
                        is UiState.Done -> DoneCard(
                            s,
                            onEdit = if (s.canEdit) vm::edit else null,
                            onAgain = vm::backToSettings,
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
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("EDITIT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    t("הכל מעובד על המכשיר. שום דבר לא עולה לרשת."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** The way in: a night-blue card with the editor's timeline in miniature. */
@OptIn(ExperimentalLayoutApi::class)
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
            MiniTimeline(Modifier.fillMaxWidth().height(70.dp))
            Spacer(Modifier.height(22.dp))
            Text(t("טשטוש פנים בסרטון"), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                t("הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה."),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(14.dp))
            // What the lanes above are: the editor's tools, each in its color.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = Color.White.copy(alpha = 0.8f)
                FeatureLabel(t("טשטוש"), Brand.Coral, c)
                FeatureLabel(t("כתוביות"), Brand.Amber, c)
                FeatureLabel(t("סאונד"), Brand.Cyan, c)
                FeatureLabel(t("רקע"), Brand.Violet, c)
            }
            Spacer(Modifier.height(20.dp))
            GradientButton(t("בחר סרטון"), onPick, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
        }
    }
}

@Composable
private fun SubtitlesCard(onPick: () -> Unit) {
    val color = Feature.SUBTITLES.tint
    ClipCard(accent = color, onClick = onPick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureTile(AppIcons.Subtitles, color, 50.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t("כתוביות לסרטון"), style = MaterialTheme.typography.titleMedium)
                Text(
                    t("זיהוי דיבור ועיצוב בסגנון טיקטוק, בלי טשטוש."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SectionTitle(t("הגדרות טשטוש"), Feature.FACES.tint)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(t("סגנון"))
                Segments(
                    listOf(BlurMode.BLUR to t("טשטוש"), BlurMode.PIXELATE to t("פיקסלים"), BlurMode.SOLID to t("כיסוי")),
                    vm.mode,
                ) { vm.mode = it }
            }
            if (vm.mode != BlurMode.SOLID) {
                SliderRow(t("עוצמה"), t("עדין"), t("חזק"), vm.strength, 0f..1f) { vm.strength = it }
            }
            SliderRow(t("גודל האזור סביב הפנים"), t("צמוד"), t("רחב"), vm.padding, 1.2f..2.0f) { vm.padding = it }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("בדיקה ועריכה לפני שמירה"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t("אחרי הזיהוי נפתח עורך: אפשר להוסיף, למחוק, להזיז ולשנות גודל של טשטושים ולקבוע מתי הם מתחילים ונגמרים."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = vm.review, onCheckedChange = { vm.review = it })
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SliderRow(
    title: String,
    low: String,
    high: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Label(title)
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Row {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkingCard(state: UiState.Working, renderAfter: Float?, onCancel: () -> Unit) {
    val eta = rememberEta()
    val rem = eta.remainingMs(state.stage, state.progress)
    // During analysis, when the export follows on its own, count it in too.
    val left = if (rem != null && state.stage == 1 && renderAfter != null) rem + ((eta.elapsedMs + rem) * renderAfter).toLong() else rem
    val feature = if (state.subsOnly) Feature.SUBTITLES else Feature.FACES
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeatureTile(if (state.subsOnly) AppIcons.Subtitles else AppIcons.Face, feature.tint, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.stage == 0 -> t("מכין את הסרטון…")
                            state.subsOnly -> t("שומר את הסרטון עם הכתוביות")
                            state.stage == 1 -> t("שלב 1 מתוך 2 · מזהה פנים")
                            else -> t("שלב 2 מתוך 2 · מטשטש ושומר")
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AnimatedContent(etaText(left) ?: t("מחשב זמן משוער…"), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "eta") { text ->
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "\u200E${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = feature.tint,
                )
            }
            TimelineProgress(
                state.progress,
                colors = if (state.subsOnly) listOf(Brand.Amber, Color(0xFFFFE08A)) else listOf(Brand.Coral, Brand.Amber),
            )
            Text(
                t("השאר את האפליקציה פתוחה עד הסוף. המסך יישאר דלוק."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50)) { Text(t("ביטול")) }
        }
    }
}

@Composable
private fun DoneCard(state: UiState.Done, onEdit: (() -> Unit)?, onAgain: () -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val uri = state.result.uri
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuccessBadge(Icons.Filled.Check)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t("נשמר בגלריה"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            append("Movies/EDITIT")
                            if (!state.subsOnly) {
                                append(" · ")
                                append(if (state.result.faces == 1) t("פנים אחת") else t("%d פנים").format(state.result.faces))
                            }
                            if (state.result.keptHdr) append(t(" · HDR נשמר"))
                            if (state.result.srtFiles > 0) append(t(" · כתוביות SRT ב-Download/EDITIT"))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                ResultPlayer(uri, state.info.aspect)
            }
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
                    if (state.subsOnly) t("ערוך וייצא שוב") else t("ערוך טשטושים וייצא שוב"),
                    onEdit,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Edit,
                    accent = (if (state.subsOnly) Feature.SUBTITLES else Feature.FACES).tint,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onAgain, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("עבד שוב"))
                }
                TextButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("סרטון חדש"))
                }
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
