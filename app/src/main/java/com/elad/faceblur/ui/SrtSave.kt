package com.elad.faceblur.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.Srt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Save SRT now": the subtitles as .srt files in Download/ONIT, without exporting the video. They
 * are the files the export saves (one per language, the same hidden words and the cut video's
 * times), named by the video. When bleeped words would be in them as they are (hiding them in
 * the subtitles is switched off) it asks first. Says what was saved, or that it failed.
 *
 * Returns what starts it (for a button or a row); the dialogs are part of this composable.
 */
@Composable
internal fun rememberSrtSave(ctrl: EditorController): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // What was saved (the file names), or null when nothing could be saved.
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val save: () -> Unit = save@{
        if (saving) return@save
        val files = ctrl.srtFiles()
        if (files.isEmpty()) {
            result = false to t("אין טקסט בכתוביות לשמירה.")
            return@save
        }
        val base = ctrl.srtBaseName
        saving = true
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                files.mapNotNull { f -> runCatching { Srt.save(context, base, f) }.getOrNull()?.let { "$base.${f.tag}.srt" } }
            }
            saving = false
            result = if (names.isEmpty()) {
                false to t("לא ניתן לשמור את קובצי ה-SRT.")
            } else {
                true to t("נשמר ב-Download/ONIT:") + "\n" + names.joinToString("\n") { ltr(it) }
            }
        }
    }

    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(t("לשמור SRT בלי הסתרה?")) },
            text = { Text(t("קובץ ה-SRT יכלול את המילים המצופצפות כפי שהן. אל תפרסם אותו.")) },
            confirmButton = {
                TextButton(onClick = { ask = false; save() }, modifier = Modifier.testTag(Tags.SRT_SAVE_YES)) {
                    Text(t("שמור"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text(t("ביטול")) } },
        )
    }
    result?.let { (ok, text) ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(if (ok) t("קובצי ה-SRT נשמרו") else t("ה-SRT לא נשמר")) },
            text = { Text(text, modifier = Modifier.testTag(Tags.SRT_SAVE_RESULT)) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(t("סגור")) } },
        )
    }
    return { if (!saving) { if (ctrl.srtShowsBleeps) ask = true else save() } }
}
