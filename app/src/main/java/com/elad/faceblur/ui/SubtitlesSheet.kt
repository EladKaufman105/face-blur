package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubtitleEngine
import kotlin.math.roundToInt

internal fun subsStageText(stage: SubtitleEngine.Stage, p: Float): String = when (stage) {
    SubtitleEngine.Stage.AUDIO -> t("מחלץ את הקול…")
    SubtitleEngine.Stage.SPEECH -> t("מזהה דיבור… %d%%").format((p * 100).roundToInt())
    SubtitleEngine.Stage.PACK -> t("מוריד חבילת תרגום (פעם אחת)…")
    SubtitleEngine.Stage.TRANSLATE -> t("מתרגם… %d%%").format((p * 100).roundToInt())
}

private fun mb(bytes: Long) = "‎%d MB".format((bytes / 1e6).roundToInt())

/**
 * Subtitles: pick the speech model (downloaded once), make the subtitles on the device, add one
 * by hand, and choose what goes into the exported video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(ctrl: EditorController, export: ExportOptions, onExportChange: (ExportOptions) -> Unit) {
    val models = ctrl.models
    var choice by remember {
        mutableStateOf(
            if (models != null && models.isReady(SpeechModel.MULTILINGUAL) && !models.isReady(SpeechModel.HEBREW)) {
                SpeechModel.MULTILINGUAL
            } else {
                SpeechModel.HEBREW
            },
        )
    }
    // With subtitles already made, creating them again folds away.
    var createOpen by remember { mutableStateOf(ctrl.model.cues.isEmpty()) }
    ModalBottomSheet(
        onDismissRequest = { ctrl.subsSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            @Suppress("UNUSED_VARIABLE") val v = ctrl.version
            val count = ctrl.model.cues.size
            val busy = ctrl.subsBusy
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(t("כתוביות"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (count == 0) t("עדיין אין כתוביות. המערכת תזהה את הדיבור, הכול במכשיר.") else t("יש %d כתוביות. הקש על כתובית בסרטון או בציר הזמן כדי לערוך.").format(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress and messages stay in view whatever is folded.
            val dl = models?.downloading
            val prog = ctrl.subsProgress
            if (models != null && (dl != null || prog != null)) {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    if (dl != null) {
                        Progress(t("מוריד את המודל… %d%%").format((models.progress * 100).roundToInt()), "download", models.progress) { ctrl.cancelSubtitles() }
                    } else if (prog != null) {
                        Progress(
                            subsStageText(prog.first, prog.second),
                            prog.first,
                            if (prog.first == SubtitleEngine.Stage.PACK) null else prog.second,
                        ) { ctrl.cancelSubtitles() }
                    }
                }
            }
            ctrl.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
            }

            if (count > 0) {
                SectionTitle(t("עריכה"))
                Box(Modifier.padding(horizontal = 20.dp)) { SplitControls(ctrl) }
                ActionRow(AppIcons.ListLines, t("כל הכתוביות"), t("לתקן את הטקסט של כולן ברשימה אחת")) {
                    ctrl.subsSheet = false
                    ctrl.listEditor = true
                }
                ActionRow(AppIcons.TextFormat, t("עיצוב"), t("סגנון, גופן, צבע ואפקטים")) {
                    ctrl.subsSheet = false
                    ctrl.stylePanel = true
                }
                val missing = ctrl.untranslatedCount
                if (missing > 0 && models != null) {
                    ActionRow(
                        AppIcons.Translate,
                        if (ctrl.hasTranslation) t("השלם תרגום") else t("הוסף תרגום"),
                        if (ctrl.hasTranslation) t("%d כתוביות עוד בלי תרגום").format(missing) else t("עברית לאנגלית ואנגלית לעברית, במכשיר"),
                        enabled = !busy,
                    ) { ctrl.translateSubtitles() }
                }
                ActionRow(Icons.Filled.Add, t("הוסף כתובית"), t("במיקום הנוכחי בסרטון"), enabled = !busy) {
                    ctrl.subsSheet = false
                    ctrl.addCueHere()
                }
            }

            if (models != null) {
                if (count > 0) {
                    SectionTitle(t("יצירה מחדש"))
                    if (!createOpen) {
                        ActionRow(AppIcons.Subtitles, t("צור כתוביות מחדש"), t("מזהה את הדיבור שוב ומחליף את הכתוביות"), enabled = !busy) { createOpen = true }
                    }
                }
                if (createOpen) {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Caption(t("מודל זיהוי דיבור"))
                            Segments(
                                listOf(SpeechModel.HEBREW to t("עברית (ivrit.ai)"), SpeechModel.MULTILINGUAL to t("רב-לשוני")),
                                choice,
                            ) { if (!busy) choice = it }
                            Text(
                                when (choice) {
                                    SpeechModel.HEBREW -> t("מותאם לעברית ומבין גם אנגלית.")
                                    SpeechModel.MULTILINGUAL -> t("מזהה כל שפה לבד. פחות מדויק בעברית.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val ready = models.isReady(choice)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (ready) t("המודל מוכן במכשיר") else t("הורדה חד-פעמית של %s (מומלץ Wi-Fi)").format(mb(choice.approxBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ready && !busy) {
                                    TextButton(onClick = { models.delete(choice) }) { Text(t("מחק מודל")) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("הוסף תרגום"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    t("עברית לאנגלית ואנגלית לעברית, במכשיר"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = ctrl.translateOnCreate, onCheckedChange = { ctrl.changeTranslateOnCreate(it) }, enabled = !busy)
                        }
                        if (dl == null && prog == null) {
                            Button(onClick = { ctrl.makeSubtitles(choice) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Text(
                                    when {
                                        !models.isReady(choice) -> t("הורד מודל וצור כתוביות")
                                        count > 0 -> t("צור כתוביות מחדש")
                                        else -> t("צור כתוביות")
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    }
                }
            }
            if (count == 0) {
                ActionRow(Icons.Filled.Add, t("או כתוב כתוביות בעצמך"), t("כתובית ריקה במיקום הנוכחי"), enabled = !busy) {
                    ctrl.subsSheet = false
                    ctrl.addCueHere()
                }
            }

            SectionTitle(t("בייצוא"))
            Box(Modifier.padding(horizontal = 20.dp)) { SubtitleExportOptions(export, onExportChange, ctrl.hasTranslation) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )
    }
}

/** A tappable row: icon, title and a line about it, and a chevron. */
@Composable
internal fun ActionRow(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha), modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** What subtitles go into the exported video, and whether .srt files are saved too. */
@Composable
fun SubtitleExportOptions(export: ExportOptions, onExportChange: (ExportOptions) -> Unit, translated: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("כתוביות בסרטון המיוצא"))
            if (translated) {
                Segments(
                    listOf(
                        SubLang.ORIGINAL to t("כמו שנאמר"),
                        SubLang.TRANSLATED to t("תרגום"),
                        SubLang.BOTH to t("שתיהן"),
                        SubLang.NONE to t("בלי"),
                    ),
                    export.subs,
                ) { onExportChange(export.copy(subs = it)) }
            } else {
                Segments(
                    listOf(SubLang.ORIGINAL to t("לצרוב בסרטון"), SubLang.NONE to t("לא לצרוב")),
                    if (export.subs == SubLang.NONE) SubLang.NONE else SubLang.ORIGINAL,
                ) { onExportChange(export.copy(subs = it)) }
            }
            Text(
                if (export.subs == SubLang.NONE) t("הכתוביות לא ייצרבו בסרטון.") else t("הכתוביות ייצרבו בתמונה ויופיעו בכל נגן."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("שמור גם קובצי SRT"), style = MaterialTheme.typography.titleSmall)
                Text(
                    t("בתיקייה Download/FaceBlur"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = export.saveSrt, onCheckedChange = { onExportChange(export.copy(saveSrt = it)) })
        }
    }
}

@Composable
private fun Progress(text: String, step: Any, progress: Float?, onCancel: () -> Unit) {
    val eta = rememberEta()
    val left = progress?.let { etaText(eta.remainingMs(step, it)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (left != null) {
                    Text(left, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text(t("עצור")) }
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Edit the selected subtitle's text (and its translation, if there are translations). */
@Composable
fun CueEditorDialog(ctrl: EditorController) {
    val c = ctrl.selectedCue
    if (c == null) {
        LaunchedEffect(Unit) { ctrl.cueEditor = false }
        return
    }
    var spoken by remember(c.id) { mutableStateOf(c.srcText) }
    var translation by remember(c.id) { mutableStateOf(c.otherText) }
    val showTranslation = ctrl.hasTranslation
    val dismiss = {
        ctrl.cueEditor = false
        // A new subtitle left empty isn't kept.
        if (c.he.isBlank() && c.en.isBlank()) ctrl.deleteSelectedCue()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("עריכת כתובית")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(spoken, { spoken = it }, label = { Text(t("טקסט")) }, modifier = Modifier.fillMaxWidth())
                if (showTranslation) {
                    OutlinedTextField(translation, { translation = it }, label = { Text(t("תרגום")) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ctrl.editSelectedCueTexts(spoken.trim(), translation.trim())
                ctrl.cueEditor = false
                if (spoken.isBlank() && translation.isBlank()) ctrl.deleteSelectedCue()
            }) { Text(t("שמור")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("ביטול")) } },
    )
}
