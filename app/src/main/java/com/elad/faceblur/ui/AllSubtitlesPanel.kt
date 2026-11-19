package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.Cue
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.i18n.t
import kotlinx.coroutines.launch

/** Choices of spoken words per subtitle (0: whole sentences). */
private val WordCounts = listOf(1, 2, 3, 4, 5, 6, 8, 0)

/** How many spoken words each subtitle has: TikTok-style 1-3 words, up to whole sentences. */
@Composable
fun WordsPerCueChips(ctrl: EditorController) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(t("מילים בכל כתובית"))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (n in WordCounts) {
                FilterChip(
                    selected = ctrl.wordsPerCue == n,
                    onClick = { ctrl.changeWordsPerCue(n) },
                    label = { Text(if (n == 0) t("משפט שלם") else "‎$n") },
                )
            }
        }
    }
}

private fun stamp(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

/**
 * Every subtitle in a list under the video, to fix them quickly: type straight into the Hebrew
 * and English text, tap the time to jump there, join with the next one, delete, add, find and
 * replace, and choose how many words each subtitle has.
 */
@Composable
fun AllSubtitlesPanel(ctrl: EditorController, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    val cues = ctrl.model.cues.sortedBy { it.startMs }
    var findOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nowMs = ctrl.nowMs
    val current = cues.indexOfFirst { nowMs >= it.startMs && nowMs < it.endMs }
    // Follow the playback.
    LaunchedEffect(current, ctrl.playing) {
        if (ctrl.playing && current >= 0) list.animateScrollToItem(current)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("כל הכתוביות (%d)").format(cues.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Filled.Search, t("חפש והחלף")) }
                IconButton(onClick = { ctrl.undo() }) { Icon(AppIcons.Undo, t("בטל")) }
                TextButton(onClick = { ctrl.listEditor = false }) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("סיום"))
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { WordsPerCueChips(ctrl) }
            if (findOpen) FindReplace(ctrl)
            HorizontalDivider(Modifier.padding(top = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
                items(cues, key = { it.id }) { c ->
                    CueRow(ctrl, c, cues.indexOf(c) == current)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    TextButton(
                        onClick = {
                            val id = ctrl.addCueHere(dialog = false)
                            val i = ctrl.model.cues.sortedBy { it.startMs }.indexOfFirst { it.id == id }
                            if (i >= 0) scope.launch { list.animateScrollToItem(i) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("הוסף כתובית במיקום הנוכחי"))
                    }
                }
            }
        }
    }
}

@Composable
private fun FindReplace(ctrl: EditorController) {
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CueField(find, t("חפש"), rtl = null) { find = it; result = null } }
            Box(Modifier.weight(1f)) { CueField(repl, t("החלף ב…"), rtl = null) { repl = it; result = null } }
            FilledTonalButton(onClick = {
                val n = ctrl.replaceInSubtitles(find, repl)
                result = if (n == 0) t("לא נמצא") else t("הוחלפו %d").format(n)
            }, enabled = find.isNotEmpty()) { Text(t("החלף הכל")) }
        }
        result?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun CueRow(ctrl: EditorController, c: Cue, current: Boolean) {
    val bg = if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer
    Row(
        Modifier.fillMaxWidth().background(bg).padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { ctrl.focusCue(c.id) }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("‎" + stamp(c.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "‎%.1fs".format((c.endMs - c.startMs) / 1000f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CueField(c.he, t("עברית"), rtl = true, onFocus = ctrl::beginTextEdit) { ctrl.typeCueText(c.id, it, c.en) }
            CueField(c.en, "English", rtl = false, onFocus = ctrl::beginTextEdit) { ctrl.typeCueText(c.id, c.he, it) }
        }
        Column {
            IconButton(onClick = { ctrl.mergeCueWithNext(c.id) }, modifier = Modifier.size(40.dp)) {
                Icon(AppIcons.MergeDown, t("חבר עם הבאה"), Modifier.size(20.dp))
            }
            IconButton(onClick = { ctrl.deleteCue(c.id) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Delete, t("מחק"), Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A compact text field that follows outside changes (undo, find and replace) without
 * moving the cursor while typing. [rtl] null: by the text.
 */
@Composable
private fun CueField(text: String, hint: String, rtl: Boolean?, onFocus: () -> Unit = {}, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    LaunchedEffect(text) {
        if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    }
    val colors = MaterialTheme.colorScheme
    val dir = when (rtl) {
        true -> LayoutDirection.Rtl
        false -> LayoutDirection.Ltr
        null -> LocalLayoutDirection.current
    }
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                value = nv
                if (nv.text != text) onChange(nv.text)
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colors.onSurface,
                textDirection = if (rtl == null) TextDirection.Content else if (rtl) TextDirection.Rtl else TextDirection.Ltr,
            ),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHigh)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    inner()
                }
            },
        )
    }
}
