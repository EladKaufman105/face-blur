package com.elad.faceblur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.NewCue
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.Srt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import an SRT file": a file from the device (the system's picker) replaces the subtitles, as one
 * undoable step. With subtitles (or bleeps, which go with them) already there it asks first.
 */
@Composable
internal fun SrtImportButton(ctrl: EditorController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<NewCue>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        pending = null
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().let { b -> if (b.size > MAX_SRT_BYTES) b.copyOf(MAX_SRT_BYTES) else b }.toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            val list = text?.let { Srt.toNewCues(Srt.parse(it)) }.orEmpty()
            when {
                list.isEmpty() -> error = t("לא נמצאו כתוביות בקובץ")
                ctrl.model.cues.isNotEmpty() || ctrl.model.bleeps.isNotEmpty() -> pending = list
                else -> ctrl.importSubtitles(list)
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        // Any file type: SRT files have no one MIME type across the pickers of different phones.
        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag(Tags.SRT_IMPORT)) { Text(t("ייבוא קובץ SRT")) }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
    pending?.let { list ->
        val bleeps = ctrl.model.bleeps.size
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(t("להחליף את הכתוביות?")) },
            text = {
                Text(
                    if (bleeps > 0) t("הכתוביות הקיימות יוחלפו בקובץ, והצפצופים (%d) יוסרו. אפשר לבטל את הפעולה.").format(bleeps)
                    else t("הכתוביות הקיימות יוחלפו בקובץ. אפשר לבטל את הפעולה."),
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; ctrl.importSubtitles(list) }, modifier = Modifier.testTag(Tags.SRT_IMPORT_YES)) { Text(t("החלף")) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(t("השאר את הקיימות")) } },
        )
    }
}

private const val MAX_SRT_BYTES = 5_000_000
