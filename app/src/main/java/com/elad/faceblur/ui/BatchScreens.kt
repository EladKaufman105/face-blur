package com.elad.faceblur.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.UiState
import com.elad.faceblur.batch.Batch
import com.elad.faceblur.batch.BatchItem
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.batch.BatchService
import com.elad.faceblur.batch.ItemStatus
import com.elad.faceblur.batch.progressLine
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.VideoInfo
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun bytesText(bytes: Long) = ltr(if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format((bytes / 1e6).roundToInt().coerceAtLeast(1)))

/** "About 12 minutes" / "About 1:20 hours". */
private fun estimateText(ms: Long): String {
    val min = ceil(ms / 60_000.0).toInt().coerceAtLeast(1)
    return if (min < 60) t("כ-%d דקות").format(min) else t("כ-%d:%02d שעות").format(min / 60, min % 60)
}

private fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

// ---------------------------------------------------------------------- setup

/**
 * Several videos picked: the template, the time it will take, the space and the speech model it
 * needs, all checked before starting (so nothing stops half way for a download or a full phone).
 */
@Composable
internal fun BatchSetupContent(vm: MainViewModel, s: UiState.BatchSetup, onAddMore: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = vm.batchSettings
    val models = vm.models
    val modelNeeded = settings.needsSpeechModel
    val modelReady = !modelNeeded || models.isReady(settings.speechModel)
    val estimate = BatchMath.totalMs(s.videos.map { it.durationMs }, settings, vm.batchSpeeds())
    val need = BatchMath.neededBytes(s.videos, settings.export) + (if (modelReady) 0L else settings.speechModel.approxBytes)
    val free = freeBytes()
    val roomOk = free >= need
    val downloading = models.downloading != null
    val canStart = modelReady && roomOk && !downloading

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startBatch() }
    val start = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startBatch()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
        EnterUp(0) {
            SectionHeader(
                t("כמה סרטונים"),
                t("%d מתוך %d סרטונים נבחרו").format(s.videos.size, BatchMath.MAX_VIDEOS),
                Feature.FACES.tint,
            )
        }
        EnterUp(1) {
            ClipCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    for (v in s.videos) VideoRow(v) { vm.removeBatchVideo(v) }
                    if (s.videos.size < BatchMath.MAX_VIDEOS) {
                        AccentOutlinedButton(t("הוסף סרטונים"), onAddMore, Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 44.dp)
                    }
                }
            }
        }
        EnterUp(2) {
            val chosen = vm.batchChoice.id?.let { vm.templates.find(it) }
            TemplatePickerCard(
                vm.templates, vm.batchChoice, vm::chooseBatchTemplates, vm.previewBase(),
                createSubs = if (chosen?.subs != null) settings.createSubs else null,
                onCreateSubs = vm::setBatchSubtitles,
            )
        }
        EnterUp(3) {
            ClipCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                    SectionTitle(t("לפני שמתחילים"), Feature.SUBTITLES.tint)
                    PlanRow(t("זמן משוער"), estimateText(estimate), true)
                    PlanRow(
                        t("מקום פנוי"),
                        t("%s פנוי, צריך כ-%s").format(bytesText(free), bytesText(need)),
                        roomOk,
                    )
                    if (modelNeeded) {
                        PlanRow(
                            t("מודל זיהוי דיבור"),
                            if (modelReady) t("מוכן במכשיר")
                            else if (downloading) t("מוריד… %d%%").format((models.progress * 100).roundToInt())
                            else t("צריך להוריד פעם אחת (%s)").format(bytesText(settings.speechModel.approxBytes)),
                            modelReady,
                        )
                        if (!modelReady && !downloading) {
                            AccentOutlinedButton(
                                t("הורד את המודל עכשיו"), vm::downloadBatchModel, Modifier.fillMaxWidth(),
                                accent = Feature.SUBTITLES.tint, height = 44.dp,
                            )
                        }
                        if (downloading) LinearProgressIndicator(progress = { models.progress }, modifier = Modifier.fillMaxWidth())
                        vm.batchDownloadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    for (n in vm.batchNotes) Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!roomOk) {
                        Text(t("אין מספיק מקום. אפשר להסיר סרטונים או לפנות מקום במכשיר."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        t("העיבוד ממשיך גם כשהמסך כבוי או האפליקציה ברקע. הסרטונים נשמרים ב-Movies/ONIT וגם כפרויקטים."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        EnterUp(4) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                GradientButton(
                    t("התחל עיבוד"), { start() }, Modifier.fillMaxWidth().testTag("batch.start"),
                    icon = EditorIcons.ExportIcon, enabled = canStart,
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(t("חזרה")) }
            }
        }
    }
}

@Composable
private fun PlanRow(title: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BatchThumb(bitmap: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(Radius.Control)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        Alignment.Center,
    ) {
        if (bitmap != null) {
            val img = remember(bitmap) { bitmap.asImageBitmap() }
            Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(AppIcons.Face, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VideoRow(v: VideoInfo, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BatchThumb(v.thumbnail)
        Spacer(Modifier.width(Space.M))
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ltr(mmss(v.durationMs)) + " · " + ltr("${v.displayWidth}×${v.displayHeight}"), style = MaterialTheme.typography.bodySmall.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, t("הסר")) }
    }
}

// ---------------------------------------------------------------------- queue and results

/** The queue while the batch runs (per video status, skip, cancel all), then the results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchRunContent(vm: MainViewModel, batch: Batch) {
    var confirmCancel by remember { mutableStateOf(false) }
    val running = !batch.finished
    Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
        EnterUp(0) {
            ClipCard(accent = if (running) Feature.FACES.color else Feature.SUBTITLES.color) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                    if (running) {
                        val cur = batch.current
                        Text(
                            t("מעבד סרטונים · %d מתוך %d").format((batch.doneCount + 1).coerceAtMost(batch.items.size), batch.items.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (cur != null) cur.name + " · " + BatchService.statusText(cur.status, batch) else t("מתחיל…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TimelineProgress(batch.progress)
                        Text(
                            batch.progressLine(),
                            style = MaterialTheme.typography.titleSmall.tnum(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            t("העיבוד ממשיך גם כשהמסך כבוי או האפליקציה ברקע. התבנית: %s").format(batch.templateName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                            AccentOutlinedButton(t("בטל הכל"), { confirmCancel = true }, Modifier.weight(1f), accent = MaterialTheme.colorScheme.error, height = 48.dp)
                            AccentOutlinedButton(t("למסך הבית"), vm::leaveBatch, Modifier.weight(1f), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SuccessBadge(Icons.Filled.Check)
                            Spacer(Modifier.width(Space.M))
                            Column {
                                Text(if (batch.cancelled) t("העיבוד הופסק") else t("העיבוד הסתיים"), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    t("%d נשמרו").format(batch.savedCount) +
                                        (if (batch.checkCount > 0) " · " + t("%d לבדיקה").format(batch.checkCount) else "") +
                                        (if (batch.failedCount > 0) " · " + t("%d נכשלו").format(batch.failedCount) else ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            t("נשמרו ב-Movies/ONIT וגם כפרויקטים. \"בדיקה\" פותחת את הסרטון בעורך, לתיקון ולייצוא מחדש. כדאי לעבור על מה שמסומן."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                            AccentOutlinedButton(t("סיום"), vm::closeBatch, Modifier.weight(1f), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp)
                            AccentOutlinedButton(t("למסך הבית"), vm::leaveBatch, Modifier.weight(1f), accent = MaterialTheme.colorScheme.onSurfaceVariant, height = 48.dp)
                        }
                    }
                }
            }
        }
        EnterUp(1) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                // What needs a look comes first once it's over.
                val ordered = if (batch.finished) batch.items.sortedBy { if (it.needsCheck) 0 else 1 } else batch.items
                for (item in ordered) BatchRow(vm, batch, item)
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(t("לבטל את העיבוד?")) },
            text = { Text(t("הסרטון שבעיבוד ומה שממתין ייעצרו. סרטונים שכבר נשמרו נשארים.")) },
            confirmButton = { TextButton(onClick = { confirmCancel = false; batch.cancelAll() }) { Text(t("בטל הכל"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(t("המשך")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchRow(vm: MainViewModel, batch: Batch, item: BatchItem) {
    val status = item.status
    val active = !status.done && status != ItemStatus.WAITING
    ClipCard(contentPadding = Space.M, accent = if (batch.finished && item.needsCheck) MaterialTheme.colorScheme.error else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BatchThumb(item.thumbnail, 64.dp)
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ltr(mmss(item.durationMs)), style = MaterialTheme.typography.bodySmall.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        val flag = MaterialTheme.colorScheme.errorContainer
                        val onFlag = MaterialTheme.colorScheme.onErrorContainer
                        when (status) {
                            ItemStatus.FAILED -> InfoChip(t("נכשל"), flag, onFlag)
                            ItemStatus.NO_FACES -> InfoChip(t("לא נמצאו פנים"), flag, onFlag)
                            ItemStatus.SAVED -> InfoChip(t("נשמר"), Feature.SUBTITLES.color.copy(alpha = 0.22f), Feature.SUBTITLES.tint)
                            else -> InfoChip(BatchService.statusText(status, batch))
                        }
                        if (item.lowConfidence) InfoChip(t("זיהוי לא בטוח"), flag, onFlag)
                        if (item.stepFailed) InfoChip(t("שלב בתבנית לא הושלם"), flag, onFlag)
                    }
                }
                when {
                    status == ItemStatus.WAITING || active -> TextButton(onClick = { batch.skip(item) }) { Text(t("דלג")) }
                    // Opening a video in the editor waits for the batch: both use the same temp files and models.
                    batch.finished && item.projectId != null && status != ItemStatus.SKIPPED -> {
                        if (item.needsCheck) {
                            AccentOutlinedButton(t("בדיקה"), { vm.checkBatchItem(item) }, accent = MaterialTheme.colorScheme.error, icon = Icons.Filled.Edit, height = 44.dp)
                        } else {
                            TextButton(onClick = { vm.checkBatchItem(item) }) { Text(t("בדיקה")) }
                        }
                    }
                }
            }
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.S))
                    Text(ltr((item.progress * 100).roundToInt().coerceIn(0, 100).toString() + "%"), style = MaterialTheme.typography.labelMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            for (n in item.notes) Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            item.analysisInfo?.takeIf { status.done }?.let {
                Text(ltr(t("זיהוי פנים") + ": " + it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status == ItemStatus.NO_FACES && item.output == null) {
                Text(t("לא נמצאו פנים, ולא היה מה לשנות, אז לא נשמר עותק. אפשר לסמן ידנית בעורך."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** On the home screen: the batch of this run, to go back to its queue or results. */
@Composable
internal fun BatchBanner(batch: Batch, onOpen: () -> Unit) {
    // The same tile as the home screen's other entries; while it runs, its progress at the end.
    WideTile(
        AppIcons.Videos,
        if (batch.finished) t("תוצאות העיבוד האחרון") else t("מעבד סרטונים · %d מתוך %d").format((batch.doneCount + 1).coerceAtMost(batch.items.size), batch.items.size),
        if (batch.finished) t("%d נשמרו").format(batch.savedCount) + (if (batch.checkCount > 0) " · " + t("%d לבדיקה").format(batch.checkCount) else "")
        else t("הקש לראות את התור"),
        Feature.FACES, onOpen,
        trailing = {
            if (!batch.finished) LinearProgressIndicator(progress = { batch.progress }, modifier = Modifier.width(56.dp))
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
