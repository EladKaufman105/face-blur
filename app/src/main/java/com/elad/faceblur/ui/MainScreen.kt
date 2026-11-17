package com.elad.faceblur.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val state = vm.state
    val working = state is UiState.Working

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = working) { /* keep the export alive */ }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            when (state) {
                UiState.Empty -> PickCard(pick)
                UiState.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Ready -> {
                    VideoCard(state.info, onReplace = pick)
                    SettingsCard(vm)
                    Button(
                        onClick = vm::start,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("טשטש פנים", style = MaterialTheme.typography.titleMedium) }
                }
                is UiState.Working -> {
                    VideoCard(state.info, onReplace = null)
                    WorkingCard(state, onCancel = vm::cancel)
                }
                is UiState.Done -> DoneCard(state, onAgain = vm::backToSettings, onNew = { vm.reset(); pick() })
                is UiState.NoFaces -> {
                    VideoCard(state.info, onReplace = pick)
                    MessageCard(
                        title = "לא זוהו פנים בסרטון",
                        body = "אם יש בסרטון פנים קטנות או רחוקות, נסה שוב עם זיהוי מורחב.",
                        primary = "נסה עם זיהוי מורחב" to vm::retrySensitive,
                        secondary = "חזרה" to vm::backToSettings,
                    )
                }
                is UiState.Failed -> {
                    state.info?.let { VideoCard(it, onReplace = pick) }
                    MessageCard(
                        title = "משהו השתבש",
                        body = state.message,
                        primary = "בחר סרטון אחר" to { vm.reset(); pick() },
                        secondary = if (state.info != null) "חזרה להגדרות" to vm::backToSettings else null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text("טשטוש פנים", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "הכל מעובד על המכשיר. שום דבר לא עולה לרשת.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .height(240.dp)
            .clip(CardShape)
            .clickable(onClick = onPick),
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary),
                Alignment.Center,
            ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.height(14.dp))
            Text("בחר סרטון", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "או שתף סרטון מהגלריה ישירות לאפליקציה",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                if (onReplace != null) TextButton(onClick = onReplace) { Text("החלף") }
            }
        }
    }
}

@Composable
private fun SettingsCard(vm: MainViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("סגנון")
                val options = listOf(BlurMode.BLUR to "טשטוש", BlurMode.PIXELATE to "פיקסלים", BlurMode.SOLID to "כיסוי")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = vm.mode == mode,
                            onClick = { vm.mode = mode },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }
            if (vm.mode != BlurMode.SOLID) {
                SliderRow("עוצמה", "עדין", "חזק", vm.strength, 0f..1f) { vm.strength = it }
            }
            SliderRow("גודל האזור סביב הפנים", "צמוד", "רחב", vm.padding, 1.0f..1.8f) { vm.padding = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("זיהוי מורחב", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "מנתח ברזולוציה גבוהה יותר כדי למצוא גם פנים זעירות (קהל, צילום רחב). איטי יותר.",
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
private fun WorkingCard(state: UiState.Working, onCancel: () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.stage == 1) "שלב 1 מתוך 2 · מזהה פנים" else "שלב 2 מתוך 2 · מטשטש ושומר",
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
                "השאר את האפליקציה פתוחה עד הסוף. המסך יישאר דלוק.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("ביטול") }
        }
    }
}

@Composable
private fun DoneCard(state: UiState.Done, onAgain: () -> Unit, onNew: () -> Unit) {
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
                    Text("נשמר בגלריה", style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append("Movies/FaceBlur · ")
                            append(if (state.result.faces == 1) "פנים אחת" else "${state.result.faces} פנים")
                            if (state.result.keptHdr) append(" · HDR נשמר")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "כדאי לעבור על הסרטון לפני פרסום: זיהוי אוטומטי עלול לפספס פנים מסובבות, מוסתרות או קטנות מאוד.",
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
                    Text("שתף")
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
                    Text("פתח")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onAgain, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("עבד שוב")
                }
                TextButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("סרטון חדש")
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
