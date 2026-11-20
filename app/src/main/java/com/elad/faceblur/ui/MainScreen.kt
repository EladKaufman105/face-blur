package com.elad.faceblur.ui

import com.elad.faceblur.i18n.t
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.elad.faceblur.R
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

private val CardShape = RoundedCornerShape(20.dp)

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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onSettings = { showSettings = true })
            when (state) {
                UiState.Empty -> {
                    PickCard(pick)
                    SubtitlesCard(pickSubs)
                    ProjectsSection(vm.projects, vm::openProject, vm::renameProject, vm::deleteProject)
                    Text(
                        t("אפשר גם לשתף סרטון מהגלריה ישירות לאפליקציה"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                is UiState.Relink -> MessageCard(
                    title = t("הסרטון של הפרויקט לא נמצא"),
                    body = if (state.wrong) t("זה לא אותו סרטון (האורך שונה). בחר את הסרטון המקורי של \"%s\".").format(state.project.name)
                    else t("ייתכן שהוא הועבר או נמחק, או שההרשאה אליו פגה. בחר אותו שוב כדי להמשיך לערוך את \"%s\".").format(state.project.name),
                    primary = t("בחר את הסרטון") to { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    secondary = t("חזרה") to vm::reset,
                )
                UiState.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Ready -> {
                    VideoCard(state.info, onReplace = pick)
                    SettingsCard(vm)
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(t("הגדרות ייצוא"), style = MaterialTheme.typography.titleMedium)
                            ExportOptionsPanel(state.info, vm.prefs.export, vm.prefs::updateExport)
                        }
                    }
                    Button(
                        onClick = vm::start,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(t("טשטש פנים"), style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        onClick = vm::startSubtitles,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(t("כתוביות בלבד, בלי טשטוש"), style = MaterialTheme.typography.titleSmall) }
                }
                is UiState.Working -> {
                    VideoCard(state.info, onReplace = null)
                    WorkingCard(state, renderAfter = if (vm.review) null else vm.prefs.renderRatio, onCancel = vm::cancel)
                }
                is UiState.Editing -> Unit
                is UiState.Done -> DoneCard(
                    state,
                    onEdit = if (state.canEdit) vm::edit else null,
                    onAgain = vm::backToSettings,
                    onNew = { vm.reset(); pick() },
                )
                is UiState.NoFaces -> {
                    VideoCard(state.info, onReplace = pick)
                    MessageCard(
                        title = t("לא זוהו פנים בסרטון"),
                        body = t("אם יש בסרטון פנים קטנות, רחוקות או מוסתרות חלקית, נסה שוב עם זיהוי מורחב, או סמן אותן ידנית."),
                        primary = if (state.canEdit) t("סמן טשטוש ידנית") to vm::edit else t("נסה עם זיהוי מורחב") to vm::retrySensitive,
                        secondary = if (state.canEdit) t("נסה עם זיהוי מורחב") to vm::retrySensitive else t("חזרה") to vm::backToSettings,
                    )
                }
                is UiState.Failed -> {
                    state.info?.let { VideoCard(it, onReplace = pick) }
                    MessageCard(
                        title = t("משהו השתבש"),
                        body = state.message,
                        primary = t("בחר סרטון אחר") to { vm.reset(); pick() },
                        secondary = if (state.info != null) t("חזרה להגדרות") to vm::backToSettings else null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Text("EDITIT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                t("הכל מעובד על המכשיר. שום דבר לא עולה לרשת."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, t("הגדרות")) }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                .padding(18.dp)
        ) { content() }
    }
}

@Composable
private fun PickCard(onPick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp)
            .clip(CardShape)
            .clickable(onClick = onPick),
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary),
                Alignment.Center,
            ) { Icon(AppIcons.Face, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(t("טשטוש פנים בסרטון"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                t("הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("בחר סרטון"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun SubtitlesCard(onPick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onPick),
        shape = CardShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.primary),
                Alignment.Center,
            ) { Icon(AppIcons.Subtitles, null, tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t("כתוביות לסרטון"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun VideoCard(info: VideoInfo, onReplace: (() -> Unit)?) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info.thumbnail?.let { bmp ->
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .aspectRatio(info.aspect, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        listOfNotNull(
                            "${info.displayWidth}×${info.displayHeight}",
                            formatDuration(info.durationMs),
                            "${info.fps.roundToInt()}fps",
                            if (info.isHdr) "HDR" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.stage == 0 -> t("מכין את הסרטון…")
                        state.subsOnly -> t("שומר את הסרטון עם הכתוביות")
                        state.stage == 1 -> t("שלב 1 מתוך 2 · מזהה פנים")
                        else -> t("שלב 2 מתוך 2 · מטשטש ושומר")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text("${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall)
            }
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text(
                etaText(left) ?: t("מחשב זמן משוער…"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                t("השאר את האפליקציה פתוחה עד הסוף. המסך יישאר דלוק."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(t("ביטול")) }
        }
    }
}

@Composable
private fun DoneCard(state: UiState.Done, onEdit: (() -> Unit)?, onAgain: () -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val uri = state.result.uri
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                ResultPlayer(uri, state.info.aspect)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(t("נשמר בגלריה"), style = MaterialTheme.typography.titleSmall)
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
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("שתף"))
                }
                OutlinedButton(
                    onClick = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(view) }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("פתח"))
                }
            }
            if (onEdit != null) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.subsOnly) t("ערוך וייצא שוב") else t("ערוך טשטושים וייצא שוב"))
                }
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
            .clip(RoundedCornerShape(14.dp)),
    )
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = primary.second, modifier = Modifier.fillMaxWidth()) { Text(primary.first) }
            if (secondary != null) {
                OutlinedButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth()) { Text(secondary.first) }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
