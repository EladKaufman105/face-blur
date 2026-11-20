package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.pipeline.WatermarkCorner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.prefs.AppPrefs
import com.elad.faceblur.prefs.Palette
import com.elad.faceblur.prefs.ThemeMode
import kotlin.math.roundToInt

/**
 * Segmented choice: a track with a pill that slides to the selected option. (Material's
 * SegmentedButton mirrored its rounded ends in some layouts, and its check mark cut labels short.)
 */
@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(50)
    val idx = options.indexOfFirst { it.first == selected }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(colors.surfaceContainerHighest)
            .padding(3.dp),
    ) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(w * idx.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "segment")
        if (idx >= 0) {
            // offset() follows the layout direction, like the row's order.
            Box(
                Modifier
                    .offset(x = x)
                    .width(w)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(colors.primaryContainer),
            )
        }
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEach { (v, label) ->
                val sel = v == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .selectable(selected = sel, role = Role.RadioButton, onClick = { onSelect(v) })
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Choice chips that wrap to more lines instead of running off the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((v, label) in options) {
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

private fun sizeText(bytes: Long): String =
    // LRM: keep "2 MB" in that order inside Hebrew text.
    "\u200E" + if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format((bytes / 1e6).roundToInt().coerceAtLeast(1))

/** Resolution, frame rate, quality / compression and codec of the exported video. */
@Composable
fun ExportOptionsPanel(info: VideoInfo, options: ExportOptions, onChange: (ExportOptions) -> Unit) {
    val short = minOf(info.displayWidth, info.displayHeight)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("רזולוציה"))
            Chips(
                listOf(0 to t("מקור (%s)").format("${short}p")) + ExportOptions.resolutionChoices(info).map { it to "${it}p" },
                if (options.shortSide in ExportOptions.resolutionChoices(info)) options.shortSide else 0,
            ) { onChange(options.copy(shortSide = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("פורמט"))
            Segments(
                listOf(
                    ExportAspect.ORIGINAL to t("מקור"),
                    ExportAspect.VERTICAL to "9:16",
                    ExportAspect.SQUARE to "1:1",
                    ExportAspect.PORTRAIT to "4:5",
                ),
                options.aspect,
            ) { onChange(options.copy(aspect = it)) }
            if (options.aspect != ExportAspect.ORIGINAL) {
                Text(
                    t("המסגרת עוקבת אחרי הפנים, כמו צלם. אפשר לראות אותה בעריכה."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("קצב פריימים"))
            Chips(
                listOf(0 to t("מקור (%s)").format("\u200E${info.fps.roundToInt()} fps\u200E")) + ExportOptions.fpsChoices(info).map { it to "\u200E$it fps" },
                if (options.fps in ExportOptions.fpsChoices(info)) options.fps else 0,
            ) { onChange(options.copy(fps = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("איכות וגודל קובץ"))
            Segments(
                listOf(
                    ExportQuality.ORIGINAL to t("כמו המקור"),
                    ExportQuality.HIGH to t("גבוהה"),
                    ExportQuality.SAVER to t("חיסכון"),
                ),
                options.quality,
            ) { onChange(options.copy(quality = it)) }
            Text(
                when (options.quality) {
                    ExportQuality.ORIGINAL -> t("בלי כיווץ: אותה איכות כמו הסרטון המקורי.")
                    ExportQuality.HIGH -> t("איכות גבוהה בקובץ קטן יותר.")
                    ExportQuality.SAVER -> t("קובץ קטן לשליחה, באיכות טובה.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption(t("קידוד"))
            Segments(
                listOf(
                    ExportCodec.SAME to t("כמו המקור"),
                    ExportCodec.H264 to "H.264",
                    ExportCodec.HEVC to "HEVC",
                ),
                options.codec,
            ) { onChange(options.copy(codec = it)) }
            Text(
                when (options.codec) {
                    ExportCodec.SAME -> t("אותו פורמט כמו הסרטון המקורי (%s).").format(if (info.isHevc) "HEVC" else "H.264")
                    ExportCodec.H264 -> t("נפתח בכל מכשיר ואתר. סרטון HDR יומר לצבעים רגילים.")
                    ExportCodec.HEVC -> t("קובץ קטן יותר באותה איכות. נתמך ברוב המכשירים החדשים.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WatermarkOptions(options.watermark) { onChange(options.copy(watermark = it)) }
        val (w, h) = options.outputSize(info)
        Text(
            t("יוצא: %s · %s · גודל משוער %s").format(
                "\u200E${w}×$h", "\u200E${options.outputFps(info).roundToInt()} fps", sizeText(options.estimatedBytes(info)),
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A logo or a line of text in a corner of the export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatermarkOptions(w: Watermark, onChange: (Watermark) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 none, 1 text, 2 logo.
    var kind by remember { mutableIntStateOf(if (w.image) 2 else if (w.text.isNotBlank()) 1 else 0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Watermark.importImage(context, uri) }.getOrDefault(false) }
            if (ok) onChange(w.copy(image = true, stamp = System.currentTimeMillis()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption(t("סימן מים"))
        Segments(listOf(0 to t("בלי"), 1 to t("טקסט"), 2 to t("לוגו")), kind) { k ->
            kind = k
            when (k) {
                0 -> onChange(w.copy(text = "", image = false))
                1 -> onChange(w.copy(image = false, text = w.text.ifBlank { "@" }))
                else -> if (Watermark.imageFile(context).exists()) onChange(w.copy(image = true)) else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        if (kind == 1) {
            OutlinedTextField(
                w.text, { onChange(w.copy(text = it.take(40), image = false)) },
                singleLine = true, label = { Text(t("הטקסט")) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (kind == 2) {
            val logo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, w.stamp) {
                value = withContext(Dispatchers.IO) {
                    Watermark.imageFile(context).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A3F4B)),
                    contentAlignment = Alignment.Center,
                ) { logo?.let { Image(it, null, Modifier.size(48.dp), contentScale = ContentScale.Fit) } }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (logo == null) t("בחר תמונה") else t("החלף תמונה"))
                }
            }
        }
        if (kind != 0) {
            Segments(
                listOf(
                    WatermarkCorner.TOP_LEFT to "↖",
                    WatermarkCorner.TOP_RIGHT to "↗",
                    WatermarkCorner.BOTTOM_LEFT to "↙",
                    WatermarkCorner.BOTTOM_RIGHT to "↘",
                ),
                w.corner,
            ) { onChange(w.copy(corner = it)) }
            Segments(listOf(0.7f to t("קטן"), 1f to t("בינוני"), 1.5f to t("גדול")), listOf(0.7f, 1f, 1.5f).minBy { kotlin.math.abs(it - w.size) }) {
                onChange(w.copy(size = it))
            }
        }
    }
}

/** Export options in a bottom sheet, with the export button (from the editor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    info: VideoInfo,
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
    /** The video has subtitles: also show what to do with them. */
    hasSubs: Boolean = false,
    /** ...and some have a translation. */
    hasTranslation: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(t("הגדרות ייצוא"), style = MaterialTheme.typography.titleLarge)
            ExportOptionsPanel(info, options, onChange)
            if (hasSubs) {
                HorizontalDivider()
                SubtitleExportOptions(options, onChange, hasTranslation)
            }
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(t("ייצא")) }
        }
    }
}

/** Language, light / dark mode and color palette. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(prefs: AppPrefs, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(t("הגדרות"), style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(t("שפה"))
                Segments(listOf(Lang.HE to "עברית", Lang.EN to "English"), prefs.lang) { prefs.updateLang(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption(t("מצב תצוגה"))
                Segments(
                    listOf(ThemeMode.SYSTEM to t("לפי המכשיר"), ThemeMode.LIGHT to t("בהיר"), ThemeMode.DARK to t("כהה")),
                    prefs.themeMode,
                ) { prefs.updateThemeMode(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(t("ערכת צבעים"))
                val palettes = listOf(
                    Palette.EDITIT to "EDITIT",
                    Palette.BLUE to t("כחול"),
                    Palette.PURPLE to t("סגול"),
                    Palette.GREEN to t("ירוק"),
                    Palette.ORANGE to t("כתום"),
                    Palette.ROSE to t("ורוד"),
                ) + if (dynamicColorAvailable) listOf(Palette.DYNAMIC to t("מהטפט")) else emptyList()
                Row(Modifier.fillMaxWidth()) {
                    for ((p, label) in palettes) {
                        val sel = prefs.palette == p
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { prefs.updatePalette(p) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val fill = if (p == Palette.EDITIT) {
                                Modifier.background(Brush.sweepGradient(listOf(Brand.Coral, Brand.Amber, Brand.Cyan, Brand.Violet, Brand.Coral)), CircleShape)
                            } else if (p == Palette.DYNAMIC) {
                                Modifier.background(
                                    Brush.sweepGradient(listOf(Color(0xFF3355E0), Color(0xFF1F7A4D), Color(0xFFC2410C), Color(0xFFC2185B), Color(0xFF3355E0))),
                                    CircleShape,
                                )
                            } else {
                                Modifier.background(paletteSwatch(p), CircleShape)
                            }
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .then(fill)
                                    .border(
                                        if (sel) 3.dp else 0.dp,
                                        if (sel) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (sel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
