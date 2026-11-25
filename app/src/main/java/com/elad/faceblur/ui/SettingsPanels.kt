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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.Watermark
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
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TextButton

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
            .padding(Space.XS),
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
                        .padding(horizontal = Space.S),
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
internal fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.S),
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
    ltr(if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format((bytes / 1e6).roundToInt().coerceAtLeast(1)))

/** Resolution, frame rate, quality / compression and codec of the exported video. */
@Composable
fun ExportOptionsPanel(
    info: VideoInfo,
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
    /** In the editor: the watermark is edited in its own panel, opened from here. */
    onEditWatermark: (() -> Unit)? = null,
) {
    val short = minOf(info.displayWidth, info.displayHeight)
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Caption(t("רזולוציה"))
            Chips(
                listOf(0 to t("מקור (%s)").format("${short}p")) + ExportOptions.resolutionChoices(info).map { it to "${it}p" },
                if (options.shortSide in ExportOptions.resolutionChoices(info)) options.shortSide else 0,
            ) { onChange(options.copy(shortSide = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Caption(t("קצב פריימים"))
            Chips(
                listOf(0 to t("מקור (%s)").format(ltr("${info.fps.roundToInt()} fps"))) + ExportOptions.fpsChoices(info).map { it to ltr("$it fps") },
                if (options.fps in ExportOptions.fpsChoices(info)) options.fps else 0,
            ) { onChange(options.copy(fps = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
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
        if (onEditWatermark != null) {
            val wm = options.watermark
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(t("סימן מים"))
                    Text(
                        when {
                            !wm.enabled(LocalContext.current) -> t("בלי")
                            wm.image -> t("לוגו")
                            else -> wm.text
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = onEditWatermark) { Text(t("ערוך")) }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Caption(t("סימן מים"))
            if (options.watermark.enabled(LocalContext.current)) {
                val aspect = if (options.aspect == ExportAspect.ORIGINAL) info.displayWidth.toFloat() / info.displayHeight.coerceAtLeast(1) else options.aspect.ratio
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WatermarkPreview(options.watermark, info.thumbnail, aspect)
                }
            }
            WatermarkControls(options.watermark) { onChange(options.copy(watermark = it)) }
        }
        val (w, h) = options.outputSize(info)
        Text(
            t("יוצא: %s · %s · גודל משוער %s").format(
                ltr("${w}×$h"), ltr("${options.outputFps(info).roundToInt()} fps"), sizeText(options.estimatedBytes(info)),
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
    /** The video has subtitles: also show what to do with them. */
    hasSubs: Boolean = false,
    /** ...and some have a translation. */
    hasTranslation: Boolean = false,
    /** What the button says. */
    action: String = t("ייצא"),
    onEditWatermark: (() -> Unit)? = null,
    /** In the editor: save what's set here (and in the editor) as a template. */
    onSaveTemplate: (() -> Unit)? = null,
    /** In the editor: the sound alone as an MP3, with the same edits as the video. Null: no such choice. */
    audioOnly: Boolean = false,
    onAudioOnly: ((Boolean) -> Unit)? = null,
    /** In the editor: no blur is on, so the faces in the video stay as they are. */
    noBlur: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.L)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Text(t("הגדרות ייצוא"), style = AppType.Title)
            if (onAudioOnly != null) {
                Segments(
                    listOf(false to t("סרטון"), true to t("סאונד בלבד (MP3)")),
                    audioOnly,
                    onAudioOnly,
                )
            }
            if (audioOnly) {
                Text(
                    t("הסאונד נשמר כקובץ MP3, עם אותן עריכות כמו בסרטון: שינוי קול, חיתוכים, ביפים, אפקטים וניקוי."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExportOptionsPanel(info, options, onChange, onEditWatermark)
                if (hasSubs) {
                    HorizontalDivider()
                    SubtitleExportOptions(options, onChange, hasTranslation)
                }
            }
            if (noBlur && !audioOnly) NoBlurWarning(t("אין טשטוש פעיל: הפנים בסרטון לא יטושטשו."), Modifier.testTag(Tags.NO_BLUR_WARNING))
            // The one place the video leaves the editor: the brand's main button.
            GradientButton(if (audioOnly) t("שמור MP3") else action, onExport, Modifier.fillMaxWidth().testTag(Tags.EXPORT_GO), icon = EditorIcons.ExportIcon)
            if (onSaveTemplate != null) {
                TextButton(onClick = onSaveTemplate, modifier = Modifier.fillMaxWidth()) { Text(t("שמור כתבנית")) }
            }
        }
    }
}

/** Language, light / dark mode and color palette. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(prefs: AppPrefs, onDismiss: () -> Unit, onTemplates: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Text(t("הגדרות"), style = AppType.Title)
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Caption(t("שפה"))
                Segments(listOf(Lang.HE to "עברית", Lang.EN to "English"), prefs.lang) { prefs.updateLang(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Caption(t("מצב תצוגה"))
                Segments(
                    listOf(ThemeMode.DARK to t("כהה"), ThemeMode.LIGHT to t("בהיר"), ThemeMode.SYSTEM to t("לפי המכשיר")),
                    prefs.themeMode,
                ) { prefs.updateThemeMode(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Caption(t("ערכת צבעים"))
                val palettes = listOf(
                    Palette.ONIT to "ONIT",
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
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { prefs.updatePalette(p) }.padding(vertical = Space.XS),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Space.XS),
                        ) {
                            val fill = if (p == Palette.ONIT) {
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
            if (onTemplates != null) {
                HorizontalDivider()
                ActionRow(AppIcons.Tune, t("תבניות"), t("מראה, יעד ועיבוד לסרטונים: שינוי שם, שכפול, עריכה ומחיקה"), onClick = onTemplates)
            }
        }
    }
}

/** A slider with what its two ends mean under it. */
@Composable
internal fun SliderRow(
    title: String,
    low: String,
    high: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onDone: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
        Row {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The look of every blur: style, strength and the area around the face. */
@Composable
internal fun BlurLookControls(
    mode: com.elad.faceblur.effects.BlurMode,
    strength: Float,
    padding: Float,
    onMode: (com.elad.faceblur.effects.BlurMode) -> Unit,
    onStrength: (Float) -> Unit,
    onPadding: (Float) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(t("סגנון"), style = MaterialTheme.typography.titleSmall)
            Segments(
                listOf(
                    com.elad.faceblur.effects.BlurMode.BLUR to t("טשטוש"),
                    com.elad.faceblur.effects.BlurMode.PIXELATE to t("פיקסלים"),
                    com.elad.faceblur.effects.BlurMode.SOLID to t("כיסוי"),
                ),
                mode,
                onMode,
            )
        }
        if (mode != com.elad.faceblur.effects.BlurMode.SOLID) {
            SliderRow(t("עוצמה"), t("עדין"), t("חזק"), strength, 0f..1f, onDone, onStrength)
        }
        SliderRow(t("גודל האזור סביב הפנים"), t("צמוד"), t("רחב"), padding, 1.2f..2.0f, onDone, onPadding)
    }
}
