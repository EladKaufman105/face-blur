package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.elad.faceblur.analysis.DetectMode
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.RerunMerge
import com.elad.faceblur.edit.RerunMode
import com.elad.faceblur.i18n.t
import com.elad.faceblur.models.ModelStore
import com.elad.faceblur.models.PackId

/**
 * "Run detection again", in the faces panel (also when no face was found): the button opens [RerunSheet]; with blurs there, and no
 * remembered choice, [RerunAskDialog] asks what to do with them; then the detection runs (its progress and Stop are the editor's pill,
 * its result and Undo the editor's message).
 */
@Composable
internal fun RerunAction(ctrl: EditorController) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    var sheet by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<DetectMode, Float>?>(null) }
    val idle = ctrl.findFacesProgress == null && ctrl.segmentProgress == null && ctrl.trackingProgress == null
    FilledTonalButton(
        onClick = { ctrl.pauser(); sheet = true },
        enabled = idle,
        modifier = Modifier.fillMaxWidth().testTag(Tags.RERUN),
    ) { Text(t("זהה פנים שוב")) }
    if (sheet) {
        RerunSheet(ctrl, onDismiss = { sheet = false }) { detect, sensitivity ->
            sheet = false
            val known = RerunMode.of(ctrl.choices?.get(RerunMerge.CHOICE_KEY))
            when {
                ctrl.model.tracks.isEmpty() -> ctrl.rerunDetection(detect, sensitivity, RerunMode.ADD)
                known != null -> ctrl.rerunDetection(detect, sensitivity, known)
                else -> asking = detect to sensitivity
            }
        }
    }
    asking?.let { (detect, sensitivity) ->
        RerunAskDialog(
            count = ctrl.model.tracks.size,
            onChoose = { mode, keep ->
                asking = null
                if (keep) ctrl.choices?.set(RerunMerge.CHOICE_KEY, mode.id)
                ctrl.rerunDetection(detect, sensitivity, mode)
            },
            onDismiss = { asking = null },
        )
    }
}

/**
 * The detection to run again: the mode (with fast's and selfie's warnings) and the sensitivity, as on the ready screen, starting
 * where this video was detected. A mode whose model pack is missing shows "download to use" and cannot run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RerunSheet(ctrl: EditorController, onDismiss: () -> Unit, onRun: (DetectMode, Float) -> Unit) {
    var detect by remember { mutableStateOf(ctrl.session.settings.detect) }
    var sensitivity by remember { mutableStateOf(ctrl.session.settings.sensitivity) }
    val ctx = LocalContext.current
    val pack = if (detect == DetectMode.SELFIE) PackId.SELFIE else PackId.FACES
    val store = remember { ModelStore.get(ctx) }
    val ready = store.statuses[pack]?.usable == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Text(t("זהה פנים שוב"), style = AppType.Title)
            DetectionControls(detect, sensitivity, onDetect = { detect = it }, onSensitivity = { sensitivity = it })
            // Never without the whole pack of the mode chosen: the "download to use" card first.
            ModelCard(pack)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(t("ביטול")) }
                Spacer(Modifier.width(Space.S))
                Button(onClick = { onRun(detect, sensitivity) }, enabled = ready, modifier = Modifier.testTag(Tags.RERUN_RUN)) { Text(t("הרץ")) }
            }
        }
    }
}

/**
 * What to do with the blurs that are there: "Add" (first, recommended: never fewer blurs) or "Replace" (with its warning), and
 * "Remember my choice". [onChoose] gets the choice and whether to remember it.
 */
@Composable
private fun RerunAskDialog(count: Int, onChoose: (RerunMode, Boolean) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(RerunMode.ADD) }
    var keep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("מה לעשות בטשטושים הקיימים?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.M)) {
                Text(t("יש %d טשטושים בסרטון.").format(count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    AskOption(
                        selected = mode == RerunMode.ADD,
                        title = t("הוסף לטשטושים הקיימים"),
                        recommended = t("מומלץ"),
                        detail = t("אף פעם לא פחות טשטושים. עריכות ידניות נשמרות."),
                        warning = null,
                        onSelect = { mode = RerunMode.ADD },
                        tag = Tags.RERUN_ADD,
                    )
                    AskOption(
                        selected = mode == RerunMode.REPLACE,
                        title = t("החלף את הטשטושים הקיימים"),
                        recommended = null,
                        detail = t("עריכות ידניות נשמרות."),
                        warning = t("שים לב: טשטושים שזוהו אוטומטית ולא נמצאו שוב יוסרו."),
                        onSelect = { mode = RerunMode.REPLACE },
                        tag = Tags.RERUN_REPLACE,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = keep, role = Role.Checkbox, onClick = { keep = !keep }).testTag(Tags.RERUN_REMEMBER),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keep, onCheckedChange = null)
                    Spacer(Modifier.width(Space.M))
                    Text(t("זכור את הבחירה שלי"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onChoose(mode, keep) }, modifier = Modifier.testTag(Tags.RERUN_ASK_GO)) { Text(t("המשך")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("ביטול")) } },
    )
}

@Composable
private fun AskOption(selected: Boolean, title: String, recommended: String?, detail: String, warning: String?, onSelect: () -> Unit, tag: String) {
    val shape = RoundedCornerShape(Radius.Card)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(Space.M)
            .testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Space.M))
        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (recommended != null) {
                    Spacer(Modifier.width(Space.S))
                    Text(
                        recommended,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = Space.S),
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (warning != null) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.Control))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = Space.M, vertical = Space.S),
                )
            }
        }
    }
}
