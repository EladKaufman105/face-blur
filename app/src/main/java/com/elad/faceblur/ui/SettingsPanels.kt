package com.elad.faceblur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.VideoInfo
import com.elad.faceblur.prefs.AppPrefs
import com.elad.faceblur.prefs.Palette
import com.elad.faceblur.prefs.ThemeMode
import kotlin.math.roundToInt

@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((v, label) in options) {
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Caption(text: String) {
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

/** Export options in a bottom sheet, with the export button (from the editor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    info: VideoInfo,
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
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
                    Palette.BLUE to t("כחול"),
                    Palette.PURPLE to t("סגול"),
                    Palette.GREEN to t("ירוק"),
                    Palette.ORANGE to t("כתום"),
                    Palette.ROSE to t("ורוד"),
                ) + if (dynamicColorAvailable) listOf(Palette.DYNAMIC to t("מהטפט")) else emptyList()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for ((p, label) in palettes) {
                        val sel = prefs.palette == p
                        Column(
                            Modifier.clickable { prefs.updatePalette(p) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val fill = if (p == Palette.DYNAMIC) {
                                Modifier.background(
                                    Brush.sweepGradient(listOf(Color(0xFF3355E0), Color(0xFF1F7A4D), Color(0xFFC2410C), Color(0xFFC2185B), Color(0xFF3355E0))),
                                    CircleShape,
                                )
                            } else {
                                Modifier.background(paletteSwatch(p), CircleShape)
                            }
                            Box(
                                Modifier
                                    .size(40.dp)
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
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
