package com.elad.faceblur.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elad.faceblur.CrashLog
import com.elad.faceblur.analysis.Series
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.AudioStep
import com.elad.faceblur.pipeline.ExportStats
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * The export's progress screen's stats panel (a tap on the percentage, as the analysis screen's):
 * how fast the frames are encoded, how many are done, what a frame's blur costs, which encoder is
 * likely to run, the sound's steps and the time. The same tiles, (i), "more details" and Copy as
 * AnalysisStatsPanel, with the export's own numbers (the web app has the same panel).
 */

private val XGap = Space.S

private enum class XCell { FPS, FRAMES, BLUR, ENCODER, TIME, AUDIO, SIZE, BITRATE }

private val XMain = listOf(
    CellSize.BIG to listOf(XCell.FPS, XCell.FRAMES),
    CellSize.MID to listOf(XCell.BLUR, XCell.ENCODER),
    CellSize.MID to listOf(XCell.TIME),
)
private val XDetails = listOf(
    CellSize.DETAIL to listOf(XCell.AUDIO),
    CellSize.DETAIL to listOf(XCell.SIZE, XCell.BITRATE),
)

private fun XCell.label(): String = when (this) {
    XCell.FPS -> t("קצב קידוד")
    XCell.FRAMES -> t("פריימים")
    XCell.BLUR -> t("טשטוש לפריים")
    XCell.ENCODER -> t("מקודד")
    XCell.TIME -> t("זמן")
    XCell.AUDIO -> t("שלבי הסאונד")
    XCell.SIZE -> t("גודל")
    XCell.BITRATE -> t("קצב סיביות")
}

private fun XCell.info(): String = when (this) {
    XCell.FPS -> t("כמה פריימים מקודדים בכל שנייה, עכשיו. מעל קצב הסרטון עצמו: הייצוא מהיר מהצפייה בו.")
    XCell.FRAMES -> t("כמה פריימים עברו עיבוד מתוך כל הפריימים של הסרטון (בקירוב). פריים שנחתך נספר גם הוא.")
    XCell.BLUR -> t("הזמן שפריים אחד לוקח בשלב הטשטוש, בתהליך העיבוד של התמונה. העבודה של המעבד הגרפי יכולה להימשך אחר כך.")
    XCell.ENCODER -> t("הקידוד: H.264 או HEVC. \"חומרה (כנראה)\" אומר שהמכשיר מציע מקודד חומרה לסוג הזה ובדרך כלל משתמשים בו; המערכת לא אומרת איזה מקודד נבחר בפועל.")
    XCell.TIME -> t("כמה זמן עבר, וכמה נשאר בהערכה לפי ההתקדמות.")
    XCell.AUDIO -> t("מה קורה לסאונד בייצוא: מועתק כמו שהוא, מקודד מחדש (עם אפקטים או חיתוכים) או נוצר מחדש (שינוי קול).")
    XCell.SIZE -> t("גודל התמונה: מהמקור לתוצאה.")
    XCell.BITRATE -> t("כמה סיביות בשנייה מבקשים מהמקודד.")
}

private fun AudioStep.text(): String = when (this) {
    AudioStep.COPY -> t("הסאונד מועתק כמו שהוא")
    AudioStep.PITCH -> t("קול ששונה")
    AudioStep.VOICE -> t("עיבוד הקול")
    AudioStep.SFX -> t("אפקטי סאונד")
    AudioStep.CUTS -> t("הוצאת קטעים")
    AudioStep.MUTED -> t("אין סאונד")
}

private fun codecName(mime: String) = if (mime.contains("hevc", true) || mime.contains("265")) "HEVC" else "H.264"

private fun encoderText(hardware: Boolean?): String = when (hardware) {
    true -> t("חומרה (כנראה)")
    false -> t("תוכנה")
    null -> t("לא ידוע")
}

private fun XCell.series(e: ExportStats): Series? = when (this) {
    XCell.FPS -> e.fpsHistory
    XCell.BLUR -> e.blurHistory
    else -> null
}

private fun XCell.color(light: Boolean): Color = when (this) {
    XCell.FPS -> if (light) Color(0xFF3366E0) else Color(0xFF5B8CFF)
    XCell.BLUR -> if (light) Feature.FACES.deep else Brand.Coral
    else -> Color.Unspecified
}

private fun XCell.value(e: ExportStats, elapsedMs: Long, leftMs: Long?, small: SpanStyle): AnnotatedString = buildAnnotatedString {
    val f = e.facts
    fun num(n: String, unit: String = "") {
        append(LRI)
        append(n)
        if (unit.isNotEmpty()) withStyle(small) { append(" $unit") }
        append(PDI)
    }
    when (this@value) {
        XCell.FPS -> num(fmt(e.fps), "fps")
        XCell.FRAMES -> num("${minOf(e.latest.frames, f.totalFrames.coerceAtLeast(e.latest.frames))}/${f.totalFrames}")
        XCell.BLUR -> num(fmt(e.blurMs), "ms")
        XCell.ENCODER -> {
            append(LRI)
            append(codecName(f.mime))
            withStyle(small) { append(" · " + encoderText(f.hardware)) }
            append(PDI)
        }
        XCell.TIME -> {
            append(LRI)
            append(mmss(elapsedMs))
            if (leftMs != null) withStyle(small) { append(" · ~" + mmss(leftMs)) }
            append(PDI)
        }
        XCell.AUDIO -> append(f.audio.joinToString(" · ") { it.text() })
        XCell.SIZE -> num("${f.srcWidth}×${f.srcHeight} → ${f.width}×${f.height}", "px")
        XCell.BITRATE -> num(String.format(Locale.US, "%.1f", f.bitrate / 1e6), "Mbps")
    }
}

/** The panel under the progress, opened and closed with the same fold as the analysis' one. [elapsedMs] and [leftMs] are read when drawn. */
@Composable
fun ExportStatsPanel(visible: Boolean, stats: ExportStats?, percent: Int, elapsedMs: Long, leftMs: Long?) {
    var kept by remember { mutableStateOf<ExportStats?>(null) }
    if (stats != null) kept = stats
    val shown = stats ?: kept
    AnimatedVisibility(visible && stats != null, enter = FoldIn, exit = FoldOut) {
        if (shown != null) ExportStatsContent(shown, percent, elapsedMs, leftMs)
    }
}

@Composable
private fun ExportStatsContent(stats: ExportStats, percent: Int, elapsedMs: Long, leftMs: Long?) {
    var more by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val context = LocalContext.current
    val turn by animateFloatAsState(if (more) 180f else 0f, tween(Motion.STATE), label = "chevron")
    Column(Modifier.fillMaxWidth().testTag(Tags.EXPORT_STATS_PANEL), verticalArrangement = Arrangement.spacedBy(XGap)) {
        XMain.forEach { (size, cells) -> XRow(size, cells, stats, elapsedMs, leftMs) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { more = !more }) {
                Text(if (more) t("פחות פרטים") else t("עוד פרטים"), style = AppType.Label)
                Spacer(Modifier.width(Space.XS))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    val text = exportStatsReport(context, stats, elapsedMs, percent)
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ONIT stats", text))
                    }
                    copied = true
                },
                modifier = Modifier.testTag(Tags.STATS_COPY),
            ) {
                Icon(if (copied) Icons.Filled.Check else AppIcons.Copy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.XS))
                Text(if (copied) t("הנתונים הועתקו") else t("העתק"), style = AppType.Label)
            }
        }
        AnimatedVisibility(more, enter = FoldIn, exit = FoldOut) {
            Column(verticalArrangement = Arrangement.spacedBy(XGap)) {
                XDetails.forEach { (size, cells) -> XRow(size, cells, stats, elapsedMs, leftMs) }
            }
        }
    }
}

@Composable
private fun XRow(size: CellSize, cells: List<XCell>, stats: ExportStats, elapsedMs: Long, leftMs: Long?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(XGap)) {
        cells.forEach { XTile(it, size, stats, elapsedMs, leftMs, Modifier.weight(1f)) }
    }
}

@Composable
private fun XTile(cell: XCell, size: CellSize, stats: ExportStats, elapsedMs: Long, leftMs: Long?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val small = SpanStyle(fontSize = size.unitEm.em, fontWeight = FontWeight.Normal, color = colors.onSurfaceVariant)
    val style = size.valueStyle()
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (size.flat) colors.surfaceContainer else colors.surfaceContainerHigh)
            .padding(horizontal = size.padH, vertical = size.padV),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        InfoLabel(cell.label(), cell.info())
        BasicText(
            cell.value(stats, elapsedMs, leftMs, small),
            style = style.copy(color = colors.onSurface),
            maxLines = if (cell == XCell.AUDIO) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
        val series = cell.series(stats)
        if (series != null && size.chart > 0.dp) {
            Sparkline(series, cell.color(colors.isLight), false, Modifier.fillMaxWidth().height(size.chart))
        }
    }
}

/** The stats as plain text to paste to an AI agent: keys in English in every language, values as numbers (the web app's report has the same keys). */
internal fun exportStatsReport(context: Context, e: ExportStats, elapsedMs: Long, percent: Int): String {
    val f = e.facts
    fun line(key: String, now: Float, h: Series): String {
        val empty = h.values.isEmpty()
        return "$key: now ${fmt(now)}, avg ${fmt(if (empty) now else h.avg)}, min ${fmt(if (empty) now else h.min)}, max ${fmt(if (empty) now else h.max)}"
    }
    val fps = String.format(Locale.US, "%.2f", f.fps).trimEnd('0').trimEnd('.')
    val enc = (f.encoder ?: "unknown") + when (f.hardware) {
        true -> ", hardware (likely: the first hardware encoder the device lists for this type)"
        false -> ", software"
        null -> ""
    }
    return buildString {
        appendLine("# ONIT export stats (app)")
        appendLine("app: ${CrashLog.app(context)}")
        appendLine("device: ${CrashLog.device()}")
        appendLine("elapsed: ${mmss(elapsedMs)} · progress: $percent%")
        appendLine("source: ${f.srcWidth}x${f.srcHeight}, $fps fps")
        appendLine("output: ${f.width}x${f.height}, ${codecName(f.mime)}, ${String.format(Locale.US, "%.1f", f.bitrate / 1e6)} Mbps")
        appendLine("encoder: $enc")
        appendLine(line("encode_fps", e.fps, e.fpsHistory))
        appendLine("encode_fps_whole_export: ${fmt(e.fpsAvg)}")
        appendLine("frames: ${e.latest.frames} of about ${f.totalFrames}")
        appendLine(line("blur_ms_per_frame", e.blurMs, e.blurHistory))
        append("audio_steps: ${f.audio.joinToString(", ") { it.name.lowercase() }}")
    }
}
