package com.elad.faceblur.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.elad.faceblur.subs.FontStore
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.SubEffect
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.subs.SubtitleRenderer
import com.elad.faceblur.subs.SubtitleStyle
import kotlin.math.roundToInt

private val PreviewBg = Brush.linearGradient(listOf(Color(0xFF39404F), Color(0xFF1E222B)))

/** Sample text for previews, Hebrew and English. */
internal val SampleBlocks = listOf("ככה ייראו הכתוביות", "This is how it looks")

/**
 * Subtitle look, like a video editor's text panel: ready-made styles, font, colors, effect,
 * size and position. Changes show on the video above right away.
 */
@Composable
fun SubtitleStylePanel(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit, onDone: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("עיצוב כתוביות"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("סיום"))
                }
            }
            val tabs = listOf(t("סגנונות"), t("גופן"), t("צבע"), t("אפקט"), t("גודל ומיקום"))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEachIndexed { i, label ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                when (tab) {
                    0 -> PresetsTab(style, onChange)
                    1 -> FontsTab(style, onChange)
                    2 -> ColorsTab(style, onChange)
                    3 -> EffectsTab(style, onChange)
                    else -> LayoutTab(style, onChange)
                }
            }
        }
    }
}

/** Equal-width cells, [columns] per row. */
@Composable
private fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in row) Box(Modifier.weight(1f)) { cell(item) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A style drawn on a small dark card, the way it will look. */
@Composable
internal fun StylePreview(style: SubtitleStyle, modifier: Modifier = Modifier, timeUs: () -> Long = { 1_100_000L }) {
    val renderer = SubtitleRenderer.get(LocalContext.current)
    Canvas(modifier.background(PreviewBg)) {
        // The card is small: scale the text up to fill it, centered.
        val s = style.copy(size = style.size.coerceIn(0.8f, 1.6f) * 2.6f, position = 0.5f)
        renderer.draw(drawContext.canvas.nativeCanvas, SampleBlocks.take(1), 200_000L, 2_200_000L, timeUs(), s, size.width.toInt(), size.height.toInt())
    }
}

@Composable
private fun Selectable(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick),
    ) { content() }
}

@Composable
private fun PresetsTab(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Grid(SubPresets.all, 3) { p ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Selectable(style == p.style, { onChange(p.style) }) {
                StylePreview(p.style, Modifier.fillMaxWidth().aspectRatio(1.5f))
            }
            Text(
                t(p.name),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun FontsTab(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    val ctx = LocalContext.current
    val renderer = SubtitleRenderer.get(ctx)
    var imported by remember { mutableStateOf(FontStore.list(ctx)) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FontStore.import(ctx, uri) } }
            r.onSuccess { name ->
                imported = FontStore.list(ctx)
                val tf = renderer.customTypeface(name)
                message = if (tf != null && !FontStore.hasHebrew(tf)) {
                    t("הגופן נוסף, אבל אין בו אותיות עבריות: טקסט בעברית יוצג בגופן אחר.")
                } else {
                    t("הגופן נוסף.")
                }
                onChange(style.copy(customFont = name))
            }.onFailure {
                message = t("לא הצלחתי לייבא: צריך קובץ גופן TTF או OTF.")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("ייבוא גופן מהמכשיר (TTF / OTF)"))
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        if (imported.isNotEmpty()) {
            Caption(t("הגופנים שלי"))
            Grid(imported, 3) { name ->
                val tf = remember(name) { renderer.customTypeface(name) }
                Box {
                    FontCell(
                        tf?.let { FontFamily(it) },
                        FontStore.label(name),
                        style.customFont == name,
                    ) { onChange(style.copy(customFont = name)) }
                    // Delete, in the corner.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable {
                                FontStore.delete(ctx, name)
                                renderer.forget(name)
                                imported = FontStore.list(ctx)
                                if (style.customFont == name) onChange(style.copy(customFont = null))
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, t("מחק"), Modifier.size(14.dp)) }
                }
            }
            Caption(t("גופנים מובנים"))
        }
        Grid(SubFont.entries.toList(), 3) { f ->
            val family = remember(f) { FontFamily(renderer.typeface(f)) }
            FontCell(family, if (f == SubFont.SYSTEM) t("של המכשיר") else f.label, style.customFont == null && style.font == f) {
                onChange(style.copy(font = f, customFont = null))
            }
        }
    }
}

@Composable
private fun FontCell(family: FontFamily?, label: String, selected: Boolean, onClick: () -> Unit) {
    Selectable(selected, onClick) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("אבג Abc", fontFamily = family, fontSize = 20.sp, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in colors.chunked(5)) {
            Row(Modifier.fillMaxWidth()) {
                for (c in row) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val sel = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x55FFFFFF), CircleShape)
                                .clickable { onPick(c) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorsTab(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(t("צבע הטקסט"))
        Swatches(SubPresets.colors, style.textColor) { onChange(style.copy(textColor = it)) }
        if (style.effect == SubEffect.HIGHLIGHT || style.effect == SubEffect.WORD_BOX) {
            Caption(t("צבע ההדגשה"))
            Swatches(SubPresets.colors, style.accentColor) { onChange(style.copy(accentColor = it)) }
        }
        Caption(t("קו מתאר"))
        Segments(
            listOf(0f to t("בלי"), 0.08f to t("דק"), 0.16f to t("בינוני"), 0.24f to t("עבה")),
            listOf(0f, 0.08f, 0.16f, 0.24f).minBy { kotlin.math.abs(it - style.outline) },
        ) { onChange(style.copy(outline = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = style.shadow, onClick = { onChange(style.copy(shadow = !style.shadow)) }, label = { Text(t("צל")) })
            FilterChip(selected = style.box, onClick = { onChange(style.copy(box = !style.box)) }, label = { Text(t("רקע")) })
            FilterChip(selected = style.glow, onClick = { onChange(style.copy(glow = !style.glow)) }, label = { Text(t("זוהר")) })
        }
        if (style.box) {
            Caption(t("צבע הרקע"))
            Swatches(SubPresets.colors, style.boxColor) { c ->
                // Keep the box a little see-through.
                onChange(style.copy(boxColor = (c and 0x00FFFFFF) or (0xD0 shl 24)))
            }
        }
    }
}

@Composable
private fun EffectsTab(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    val effects = listOf(
        SubEffect.NONE to t("בלי"),
        SubEffect.FADE to t("הופעה רכה"),
        SubEffect.POP to t("קפיצה"),
        SubEffect.SLIDE_UP to t("החלקה למעלה"),
        SubEffect.ZOOM to t("זום"),
        SubEffect.ROTATE to t("סיבוב"),
        SubEffect.GLITCH to t("גליץ'"),
        SubEffect.FLICKER to t("ניאון מהבהב"),
        SubEffect.HIGHLIGHT to t("מילה מודגשת"),
        SubEffect.WORD_BOX to t("תיבה על המילה"),
        SubEffect.ACTIVE_ZOOM to t("זום למילה"),
        SubEffect.BOUNCE to t("קפיצה למילה"),
        SubEffect.SHAKE to t("רעידה"),
        SubEffect.ONE_WORD to t("מילה אחת"),
        SubEffect.WORD_POP to t("מילים קופצות"),
        SubEffect.WORD_FADE to t("מילים בהדרגה"),
        SubEffect.TYPEWRITER to t("מכונת כתיבה"),
        SubEffect.LETTERS to t("אות אחר אות"),
    )
    // The cards play their effect over and over.
    val loop by rememberInfiniteTransition(label = "effects").animateFloat(
        0f, 2600f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t",
    )
    Grid(effects, 3) { (e, label) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Selectable(style.effect == e, { onChange(style.copy(effect = e)) }) {
                StylePreview(style.copy(effect = e), Modifier.fillMaxWidth().aspectRatio(1.6f)) { (loop * 1000).toLong() }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun LayoutTab(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(t("גודל"))
            Spacer(Modifier.weight(1f))
            Text("‎${(style.size * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = style.size, onValueChange = { onChange(style.copy(size = it)) }, valueRange = 0.6f..2.2f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(t("מיקום"))
            Spacer(Modifier.weight(1f))
        }
        Segments(
            listOf(0.15f to t("למעלה"), 0.5f to t("באמצע"), 0.7f to t("שליש תחתון"), 0.86f to t("למטה")),
            listOf(0.15f, 0.5f, 0.7f, 0.86f).firstOrNull { kotlin.math.abs(it - style.position) < 0.01f } ?: -1f,
        ) { onChange(style.copy(position = it)) }
        Text(t("גובה מדויק"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
        Slider(value = style.position, onValueChange = { onChange(style.copy(position = it)) }, valueRange = 0.08f..0.95f)
    }
}
