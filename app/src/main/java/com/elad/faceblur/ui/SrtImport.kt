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
    var note by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<NewCue>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        note = null
        pending = null
        scope.launch {
            var truncated = false
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // At most one byte more than the limit is read: enough to know the file is longer.
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(1 shl 14)
                        while (buf.size() <= MAX_SRT_BYTES) {
                            val n = input.read(chunk, 0, minOf(chunk.size, MAX_SRT_BYTES + 1 - buf.size()))
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                        }
                        val all = buf.toByteArray()
                        truncated = all.size > MAX_SRT_BYTES
                        Srt.cutUtf8(all, MAX_SRT_BYTES).toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val list = withContext(Dispatchers.Default) { text?.let { Srt.toNewCues(Srt.parse(it)) }.orEmpty() }
            when {
                list.isEmpty() -> error = t("לא נמצאו כתוביות בקובץ")
                ctrl.model.cues.isNotEmpty() || ctrl.model.bleeps.isNotEmpty() -> pending = list
                else -> ctrl.importSubtitles(list)
            }
            if (truncated && list.isNotEmpty()) note = t("הקובץ גדול מדי: נקרא רק החלק הראשון שלו.")
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        // Any file type: SRT files have no one MIME type across the pickers of different phones.
        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag(Tags.SRT_IMPORT)) { Text(t("ייבוא קובץ SRT")) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
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
