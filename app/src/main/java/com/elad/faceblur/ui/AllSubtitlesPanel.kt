package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.Speakers
import com.elad.faceblur.edit.Subtitles
import com.elad.faceblur.subs.SubtitleStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elad.faceblur.i18n.t
import kotlinx.coroutines.launch

private val WordChoices = listOf(1, 2, 3, 4, 5, 6, 8)
private val CharChoices = listOf(10, 15, 20, 25, 32, 42)

/**
 * How the subtitles are cut, after they're made: by spoken words (TikTok-style 1-3) or by
 * characters, up to whole sentences. Every change re-cuts from the whole sentences.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitControls(ctrl: EditorController) {
    var byChars by remember { mutableStateOf(ctrl.split.chars > 0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(t("חלוקת הכתוביות"))
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(180.dp)) {
                Segments(listOf(false to t("מילים"), true to t("תווים")), byChars) { byChars = it }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val choices = if (byChars) CharChoices else WordChoices
            for (n in choices) {
                val rule = if (byChars) SplitRule(chars = n) else SplitRule(words = n)
                FilterChip(
                    selected = ctrl.split == rule,
                    onClick = { ctrl.changeSplit(rule) },
                    label = { Text(if (byChars) t("עד %d").format(n) else "\u200E$n") },
                )
            }
            FilterChip(
                selected = ctrl.split.isSentence,
                onClick = { ctrl.changeSplit(SplitRule()) },
                label = { Text(t("משפט שלם")) },
            )
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
fun AllSubtitlesPanel(ctrl: EditorController, modifier: Modifier = Modifier, style: SubtitleStyle = SubtitleStyle()) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    val cues = ctrl.model.cues.sortedBy { it.startMs }
    var findOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nowMs = ctrl.nowMs
    val current = cues.indexOfFirst { nowMs >= it.startMs && nowMs < it.endMs }
    val translated = ctrl.hasTranslation
    // Follow the playback.
    LaunchedEffect(current, ctrl.playing) {
        if (ctrl.playing && current >= 0) list.animateScrollToItem(current)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("כל הכתוביות (%d)").format(cues.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { ctrl.bleepMode = !ctrl.bleepMode }) {
                    Icon(
                        AppIcons.Mute, t("צפצוף מילים"),
                        tint = if (ctrl.bleepMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Filled.Search, t("חפש והחלף")) }
                IconButton(onClick = { ctrl.undo() }) { Icon(AppIcons.Undo, t("בטל")) }
                TextButton(onClick = { ctrl.listEditor = false; ctrl.bleepMode = false }) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("סיום"))
                }
            }
            if (ctrl.bleepMode) {
                Text(
                    t("הקש על מילה כדי לצפצף אותה בסאונד, ושוב כדי להחזיר."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Box(Modifier.padding(horizontal = 16.dp)) { SplitControls(ctrl) }
            }
            if (findOpen) FindReplace(ctrl)
            HorizontalDivider(Modifier.padding(top = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
                items(cues, key = { it.id }) { c ->
                    CueRow(ctrl, c, cues.indexOf(c) == current, translated, style)
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
private fun CueRow(ctrl: EditorController, c: Cue, current: Boolean, translated: Boolean, style: SubtitleStyle) {
    // Where the cursor is in the spoken text, to split there.
    var caret by remember(c.id) { mutableIntStateOf(-1) }
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
        SpeakerButton(c.speaker, style) { ctrl.setSpeaker(c.id, it) }
        val englishSpoken = c.src == "en" || (c.src == "" && c.he.isBlank() && c.en.isNotBlank())
        if (ctrl.bleepMode) {
            BleepWords(ctrl, c, rtl = !englishSpoken, Modifier.weight(1f).padding(vertical = 4.dp))
        } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CueField(c.srcText, t("טקסט"), rtl = !englishSpoken, onFocus = ctrl::beginTextEdit, onCaret = { caret = it }) {
                ctrl.typeCueText(c.id, it, c.otherText)
            }
            if (translated) {
                CueField(c.otherText, t("תרגום"), rtl = englishSpoken, onFocus = ctrl::beginTextEdit) { ctrl.typeCueText(c.id, c.srcText, it) }
            }
        }
        if (!ctrl.bleepMode) Column {
            IconButton(onClick = { ctrl.splitCueAtCaret(c.id, caret) }, modifier = Modifier.size(36.dp)) {
                Icon(AppIcons.Cut, t("פצל במקום הסמן"), Modifier.size(19.dp))
            }
            IconButton(onClick = { ctrl.mergeCueWithNext(c.id) }, modifier = Modifier.size(36.dp)) {
                Icon(AppIcons.MergeDown, t("חבר עם הבאה"), Modifier.size(19.dp))
            }
            IconButton(onClick = { ctrl.deleteCue(c.id) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, t("מחק"), Modifier.size(19.dp))
            }
        }
    }
}

/** The spoken words as chips: tap one to bleep it out of the sound (red), again to bring it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BleepWords(ctrl: EditorController, c: Cue, rtl: Boolean, modifier: Modifier = Modifier) {
    val words = Subtitles.words(c.srcText)
    val bleeped = ctrl.model.bleepedWords(c)
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            words.forEachIndexed { i, w ->
                val on = i in bleeped
                Surface(
                    onClick = { ctrl.toggleBleep(c.id, i) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (on) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (on) {
                            Icon(AppIcons.Mute, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(w, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (words.isEmpty()) Text(t("אין טקסט"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Who says a subtitle: a dot in the speaker's color; tap to choose. */
@Composable
fun SpeakerButton(speaker: Int, style: SubtitleStyle, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val s = speaker.coerceAtLeast(1)
    Box {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { open = true }
                .semantics { contentDescription = t("דובר %d").format(s) },
            contentAlignment = Alignment.Center,
        ) { SpeakerDot(s, style, 24.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in 1..Speakers.MAX) {
                DropdownMenuItem(
                    text = { Text(t("דובר %d").format(n)) },
                    leadingIcon = { SpeakerDot(n, style, 20.dp) },
                    trailingIcon = if (n == s) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; onChange(n) },
                )
            }
        }
    }
}

/** A speaker's color with its number on it. */
@Composable
fun SpeakerDot(n: Int, style: SubtitleStyle, size: androidx.compose.ui.unit.Dp) {
    val c = Color(style.speakerColor(n))
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$n",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (c.luminance() > 0.5f) Color.Black else Color.White,
        )
    }
}

/**
 * A compact text field that follows outside changes (undo, find and replace) without
 * moving the cursor while typing. [rtl] null: by the text.
 */
@Composable
private fun CueField(
    text: String,
    hint: String,
    rtl: Boolean?,
    onFocus: () -> Unit = {},
    onCaret: (Int) -> Unit = {},
    onChange: (String) -> Unit,
) {
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
                onCaret(nv.selection.start)
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
