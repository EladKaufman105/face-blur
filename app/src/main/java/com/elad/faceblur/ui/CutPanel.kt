package com.elad.faceblur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.Subtitles
import com.elad.faceblur.i18n.t

/**
 * Cutting stretches out of the video: pauses and filler sounds automatically, a stretch by
 * hand, or words and sentences by tapping them in the text. Nothing is lost: every cut can be
 * put back, and the player jumps over them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CutPanel(ctrl: EditorController) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    var pause by remember { mutableLongStateOf(800L) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            PanelHeader(t("חיתוך"), MaterialTheme.colorScheme.onSurface, { ctrl.cutPanel = false; ctrl.cutStartMs = null }) {
                IconButton(onClick = { ctrl.undo() }) { Icon(AppIcons.Undo, t("בטל")) }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(panelBody(340.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val totalMs = (ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs) / 1000
                Text(
                    if (ctrl.cutCount == 0) {
                        t("עדיין לא נחתך כלום. חיתוך לא מוחק מהמקור, ואפשר להחזיר כל קטע.")
                    } else {
                        t("נחתכו %d קטעים · הסרטון %s במקום %s").format(ctrl.cutCount, mmss(totalMs - ctrl.cutTotalMs), mmss(totalMs))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Caption(t("אוטומטי"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("שתיקה מעל"), style = MaterialTheme.typography.bodySmall)
                    for (ms in listOf(500L, 800L, 1200L)) {
                        FilterChip(selected = pause == ms, onClick = { pause = ms }, label = { Text("‎%.1f".format(ms / 1000f) + t(" שנ׳")) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { ctrl.cutSilences(pause) }, enabled = !ctrl.cutBusy) { Text(t("הסר שתיקות")) }
                    FilledTonalButton(onClick = { ctrl.cutFillers() }, enabled = !ctrl.cutBusy && ctrl.model.cues.isNotEmpty()) { Text(t("הסר \"אממ\" ו\"אה\"")) }
                    if (ctrl.cutBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                }

                Caption(t("ידני"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val start = ctrl.cutStartMs
                    if (start == null) {
                        FilledTonalButton(onClick = { ctrl.startCutHere() }) { Text(t("התחל חיתוך כאן")) }
                    } else {
                        Button(onClick = { ctrl.endCutHere() }) {
                            Text(t("סיים כאן (%.1f שנ׳)").format(kotlin.math.abs(ctrl.nowMs - start) / 1000f))
                        }
                        TextButton(onClick = { ctrl.cutStartMs = null }) { Text(t("ביטול")) }
                    }
                }
                if (ctrl.cutStartMs != null) {
                    Text(t("הזז את ציר הזמן לסוף הקטע ולחץ \"סיים כאן\"."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ctrl.cutHere != null) TextButton(onClick = { ctrl.restoreCutHere() }) { Text(t("החזר את הקטע כאן")) }
                    if (ctrl.cutCount > 0) TextButton(onClick = { ctrl.clearCuts() }) { Text(t("החזר הכל")) }
                }

                HorizontalDivider()
                Caption(t("לפי הטקסט"))
                val cues = ctrl.model.cues.sortedBy { it.startMs }
                if (cues.isEmpty()) {
                    Text(
                        t("כדי לחתוך לפי הטקסט צריך קודם כתוביות: הן מסמנות מתי כל מילה נאמרת."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { ctrl.cutPanel = false; ctrl.subsSheet = true }) { Text(t("צור כתוביות")) }
                } else {
                    Text(
                        t("הקש על מילה כדי לחתוך אותה מהסרטון, ושוב כדי להחזיר. המספריים חותכים משפט שלם."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (c in cues) CutCueRow(ctrl, c)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CutCueRow(ctrl: EditorController, c: Cue) {
    val words = Subtitles.words(c.srcText)
    val cut = ctrl.model.cutWords(c)
    val all = words.isNotEmpty() && cut.size == words.size
    val englishSpoken = c.src == "en" || (c.src == "" && c.he.isBlank() && c.en.isNotBlank())
    Row(verticalAlignment = Alignment.Top) {
        IconButton(onClick = { ctrl.toggleCutCue(c.id) }, modifier = Modifier.size(36.dp)) {
            Icon(
                EditorIcons.Cut, t("חתוך את כל המשפט"), Modifier.size(19.dp),
                tint = if (all) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        CompositionLocalProvider(LocalLayoutDirection provides if (englishSpoken) LayoutDirection.Ltr else LayoutDirection.Rtl) {
            FlowRow(Modifier.weight(1f).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                words.forEachIndexed { i, w ->
                    val on = i in cut
                    Surface(
                        onClick = { ctrl.toggleCutWord(c.id, i) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (on) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            w,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (on) TextDecoration.LineThrough else null,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return "‎%d:%02d".format(s / 60, s % 60)
}
