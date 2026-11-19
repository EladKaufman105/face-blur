package com.elad.faceblur.ui

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
    ModalBottomSheet(
        onDismissRequest = { ctrl.subsSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            @Suppress("UNUSED_VARIABLE") val v = ctrl.version
            val count = ctrl.model.cues.size
            Text(t("כתוביות"), style = MaterialTheme.typography.titleLarge)
            Text(
                if (count == 0) t("עדיין אין כתוביות. המערכת תזהה את הדיבור, הכול במכשיר.") else t("יש %d כתוביות. הקש על כתובית בציר הזמן כדי לערוך.").format(count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (models != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Caption(t("מודל זיהוי דיבור"))
                    Segments(
                        listOf(SpeechModel.HEBREW to t("עברית (ivrit.ai)"), SpeechModel.MULTILINGUAL to t("רב-לשוני")),
                        choice,
                    ) { if (!ctrl.subsBusy) choice = it }
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
                        if (ready && !ctrl.subsBusy) {
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
                    Switch(checked = ctrl.translateOnCreate, onCheckedChange = { ctrl.changeTranslateOnCreate(it) }, enabled = !ctrl.subsBusy)
                }
                val dl = models.downloading
                val prog = ctrl.subsProgress
                when {
                    dl != null -> Progress(t("מוריד את המודל… %d%%").format((models.progress * 100).roundToInt()), "download", models.progress) { ctrl.cancelSubtitles() }
                    prog != null -> Progress(
                        subsStageText(prog.first, prog.second),
                        prog.first,
                        if (prog.first == SubtitleEngine.Stage.PACK) null else prog.second,
                    ) { ctrl.cancelSubtitles() }
                    else -> Button(onClick = { ctrl.makeSubtitles(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                !models.isReady(choice) -> t("הורד מודל וצור כתוביות")
                                count > 0 -> t("צור כתוביות מחדש")
                                else -> t("צור כתוביות")
                            },
                        )
                    }
                }
                ctrl.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                val missing = ctrl.untranslatedCount
                if (count > 0 && missing > 0 && !ctrl.subsBusy) {
                    OutlinedButton(onClick = { ctrl.translateSubtitles() }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (ctrl.hasTranslation) {
                                t("תרגם %d כתוביות שעוד לא תורגמו").format(missing)
                            } else {
                                t("תרגם את הכתוביות (עברית ↔ אנגלית)")
                            },
                        )
                    }
                }
            }
            if (count > 0) {
                HorizontalDivider()
                SplitControls(ctrl)
                OutlinedButton(
                    onClick = { ctrl.subsSheet = false; ctrl.listEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("עריכת כל הכתוביות ברשימה")) }
                OutlinedButton(
                    onClick = { ctrl.subsSheet = false; ctrl.stylePanel = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("עיצוב הכתוביות: סגנון, גופן, צבע ואפקטים")) }
            }
            OutlinedButton(
                onClick = { ctrl.subsSheet = false; ctrl.addCueHere() },
                enabled = !ctrl.subsBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("הוסף כתובית ידנית במיקום הנוכחי")) }

            HorizontalDivider()
            SubtitleExportOptions(export, onExportChange, ctrl.hasTranslation)
        }
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
