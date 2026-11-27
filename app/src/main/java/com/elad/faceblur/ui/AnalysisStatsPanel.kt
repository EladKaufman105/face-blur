package com.elad.faceblur.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.elad.faceblur.CrashLog
import com.elad.faceblur.analysis.AnalysisStats
import com.elad.faceblur.analysis.Series
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.VideoInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The progress screen's stats panel (a tap on the percentage while faces are found): where the
 * time goes, now and over the last minute, for whoever tunes detection on a device.
 */

/** The panel's layout, in one place: rows of cells, top to bottom, and how big each row is. */
private object StatsLayout {
    val Gap = Space.S
    val Main = listOf(
        CellSize.BIG to listOf(Cell.DETECT, Cell.WAIT),
        CellSize.MID to listOf(Cell.FRAME, Cell.RESOLUTION, Cell.SAMPLING),
    )
    val SelfieHidden = setOf(Cell.COARSE, Cell.GPU_CALL, Cell.YUNET, Cell.CROPS)
    val Details = listOf(
        CellSize.DETAIL to listOf(Cell.BACKEND, Cell.MODE),
        CellSize.DETAIL to listOf(Cell.WORKERS, Cell.FINE, Cell.COARSE),
        CellSize.DETAIL to listOf(Cell.GPU_CALL, Cell.YUNET, Cell.CROPS),
    )
}

/**
 * A row's cells: the chart's height (0: none), the padding, whether it's a flat detail cell, and
 * the units' size next to the value (relative, so they shrink with it).
 */
private enum class CellSize(val chart: Dp, val padH: Dp, val padV: Dp, val flat: Boolean, val unitEm: Float) {
    BIG(42.dp, Space.M, Space.M, false, 0.45f),
    MID(26.dp, Space.M, Space.S, false, 0.6f),
    DETAIL(0.dp, Space.M, Space.S, true, 0.8f),
}

/** The value's largest size; a value too long for its cell gets smaller, down to [MIN_TEXT]. */
@Composable
private fun CellSize.valueStyle(): TextStyle = when (this) {
    CellSize.BIG -> AppType.Display
    CellSize.MID -> AppType.Title.copy(fontSize = 18.sp, lineHeight = 24.sp)
    CellSize.DETAIL -> MaterialTheme.typography.titleSmall
}.tnum()

private val MIN_TEXT = 8.sp

/** What a cell shows. Resolution and sampling change in jumps: step charts. */
private enum class Cell(val step: Boolean = false) {
    DETECT, WAIT, FRAME, RESOLUTION(step = true), SAMPLING(step = true),
    BACKEND, MODE, WORKERS, FINE, COARSE, GPU_CALL, YUNET, CROPS,
}

private fun Cell.label(): String = when (this) {
    Cell.DETECT -> t("זיהוי")
    Cell.WAIT -> t("המתנה")
    Cell.FRAME -> t("פריים")
    Cell.RESOLUTION -> t("רזולוציה")
    Cell.SAMPLING -> t("דגימה")
    Cell.BACKEND -> t("מנוע")
    Cell.MODE -> t("סוג הזיהוי")
    Cell.WORKERS -> t("תהליכונים")
    Cell.FINE -> t("מעבר עדין")
    Cell.COARSE -> t("מעבר גס")
    Cell.GPU_CALL -> t("זמן GPU")
    Cell.YUNET -> t("YuNet")
    Cell.CROPS -> t("בדיקות חיתוך")
}

private fun Cell.info(): String = when (this) {
    Cell.DETECT -> t("כמה זמן לוקח זיהוי פנים אחד. זה החלק הכבד.")
    Cell.WAIT -> t("כמה זמן פריים מחכה לגלאי פנוי. גבוה: המכשיר לא עומד בקצב.")
    Cell.FRAME -> t("הזמן לעיבוד פריים אחד.")
    Cell.RESOLUTION -> t("גודל התמונה שגלאי הפנים בודק. יורד אוטומטית במכשיר איטי.")
    Cell.SAMPLING -> t("כל כמה פריימים מחפשים פנים. \"קל\": בדיקה מהירה יותר; המעקב משלים בין הבדיקות.")
    Cell.BACKEND -> t("איפה רץ זיהוי הפנים: במעבד הגרפי (GPU) או במעבד, ובאיזה דיוק. כשאין GPU, כתוב למה.")
    Cell.MODE -> t("רגיל, מורחב (כל פריים), מהיר (5 בשנייה) או סלפי, והרגישות שנבחרה.")
    Cell.WORKERS -> t("כמה גלאים עובדים במקביל, מתוך המקסימום.")
    Cell.FINE -> t("זמן המעבר המפורט בכל זיהוי.")
    Cell.COARSE -> t("זמן המעבר המהיר שמוצא פנים גדולות.")
    Cell.GPU_CALL -> t("זמן הקריאות למעבד הגרפי בכל זיהוי.")
    Cell.YUNET -> t("זמן הגלאי השני (YuNet) בכל זיהוי.")
    Cell.CROPS -> t("כמה בדיקות חוזרות על פנים לא ודאיות בכל זיהוי, וזמן כל אחת.")
}

private fun Cell.series(s: AnalysisStats): Series? = when (this) {
    Cell.DETECT -> s.detectHistory
    Cell.WAIT -> s.waitHistory
    Cell.FRAME -> s.frameHistory
    Cell.RESOLUTION -> s.sideHistory
    Cell.SAMPLING -> s.everyHistory
    else -> null
}

/** The chart's color, from the app's palette: the bright one on dark, a deeper one on light. */
private fun Cell.color(light: Boolean): Color = when (this) {
    Cell.DETECT -> if (light) Color(0xFF3366E0) else Color(0xFF5B8CFF)
    Cell.WAIT -> if (light) Feature.BACKGROUND.deep else Color(0xFF9B7BFF)
    Cell.FRAME -> if (light) Feature.FACES.deep else Brand.Coral
    Cell.RESOLUTION -> if (light) Feature.SUBTITLES.deep else Brand.Amber
    Cell.SAMPLING -> if (light) Color(0xFF0E8C78) else Color(0xFF3CCFB4)
    else -> Color.Unspecified
}

/** The cell's value: numbers in the row's size, units and words small; numbers left to right. */
private fun Cell.value(s: AnalysisStats, small: SpanStyle): AnnotatedString = buildAnnotatedString {
    val l = s.latest
    fun num(n: String, unit: String = "") {
        // As [ltr]: the number and its unit stay together, left to right.
        append(LRI)
        append(n)
        if (unit.isNotEmpty()) withStyle(small) { append(" $unit") }
        append(PDI)
    }
    fun word(w: String) = withStyle(small) { append(w) }
    when (this@value) {
        Cell.DETECT -> num(fmt(s.detectMs), "ms")
        Cell.WAIT -> num(fmt(s.waitMs), "ms")
        Cell.FRAME -> num(fmt(s.frameMs), "ms")
        Cell.RESOLUTION -> num("${l.side}", "px")
        Cell.SAMPLING -> {
            val parts = t("כל %s").split("%s")
            word(parts[0])
            num("${l.every}")
            word(parts.getOrElse(1) { "" })
            // Its own key: plain "קל" is "Light" (a strength) elsewhere.
            if (l.lite) word(" " + t("· קל"))
        }
        Cell.BACKEND -> append(ltr(backend(s)))
        Cell.MODE -> {
            append(
                when (l.mode) {
                    "fast" -> t("מהיר")
                    "selfie" -> t("סלפי")
                    "extended" -> t("מורחב")
                    else -> t("רגיל")
                },
            )
            if (l.sensitivity > 0f) word(" · ${(l.sensitivity * 100).roundToInt()}%")
        }
        Cell.WORKERS -> num("${l.workers}/${l.maxWorkers}")
        Cell.FINE -> num(fmt(s.fineMs), "ms")
        Cell.COARSE -> num(fmt(s.coarseMs), "ms")
        Cell.GPU_CALL -> if (!l.onGpu) append("—") else {
            num(fmt(s.gpuCallMs), "ms")
            if (l.gpuQueueMs > 0f) word(" · " + t("תור %s").format(ltr(fmt(l.gpuQueueMs) + " ms")))
        }
        Cell.YUNET -> num(fmt(s.yunetMs), "ms")
        Cell.CROPS -> {
            append(LRI)
            append(fmt(s.cropsPerDetection))
            word(" × ")
            append(fmt(s.cropMs))
            word(" ms")
            append(PDI)
        }
    }
}

private const val LRI = "\u2066"
private const val PDI = "\u2069"

/** A time or a count: whole from 10 up, else one decimal (always with a dot). */
private fun fmt(v: Float): String = if (v >= 9.95f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

/** Why not the GPU, for the panel: selfie mode's reason translated (the others are the detector's own words). */
private fun whyNoGpu(why: String?): String? = why?.takeIf { it.isNotEmpty() }?.let { if (it == "selfie mode runs on the CPU") t("מצב סלפי רץ על המעבד") else it }

/** "GPU · fp32 · 14 ms" or "CPU · fp16 · <why not the GPU>". */
private fun backend(s: AnalysisStats): String {
    val l = s.latest
    return if (l.onGpu) {
        listOfNotNull(if (l.accelerator == "gpu") "GPU" else "GPU (checking)", l.precision, l.gpuMs.takeIf { it > 0f }?.let { fmt(it) + " ms" })
    } else {
        listOfNotNull("CPU", l.precision, whyNoGpu(l.noGpu))
    }.joinToString(" · ")
}

// Keeps the last stats while the panel closes after they're gone (the export started).
private class Kept {
    var stats: AnalysisStats? = null
}

/**
 * The stats panel under the progress, opened and closed with the app's fold animation. Shown
 * while [visible] and there are [stats]; [percent] and [elapsedMs] go into the copied text.
 */
@Composable
fun AnalysisStatsPanel(visible: Boolean, stats: AnalysisStats?, info: VideoInfo, percent: Int, elapsedMs: () -> Long) {
    val kept = remember { Kept() }
    if (stats != null) kept.stats = stats
    val shown = stats ?: kept.stats
    AnimatedVisibility(
        visible && stats != null,
        enter = FoldIn,
        exit = FoldOut,
    ) {
        if (shown != null) StatsContent(shown, info, percent, elapsedMs)
    }
}

// The same fold as the app's other collapsible sections (see BlurSettings), for the panel and its details.
private val FoldIn = expandVertically(tween(Motion.PANEL)) + fadeIn(tween(Motion.CONTENT))
private val FoldOut = shrinkVertically(tween(Motion.STATE)) + fadeOut(tween(Motion.CONTENT))

@Composable
private fun StatsContent(stats: AnalysisStats, info: VideoInfo, percent: Int, elapsedMs: () -> Long) {
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
    Column(Modifier.fillMaxWidth().testTag(Tags.STATS_PANEL), verticalArrangement = Arrangement.spacedBy(StatsLayout.Gap)) {
        StatsLayout.Main.forEach { (size, cells) -> CellRow(size, cells, stats) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { more = !more }) {
                Text(if (more) t("פחות פרטים") else t("עוד פרטים"), style = AppType.Label)
                Spacer(Modifier.width(Space.XS))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    val text = statsReport(context, stats, info, elapsedMs(), percent)
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
            Column(verticalArrangement = Arrangement.spacedBy(StatsLayout.Gap)) {
                // Selfie mode has one detector and none of the rest (no coarse pass, no GPU, no YuNet, no crop re-check): those cells are left out.
                StatsLayout.Details.map { (size, cells) -> size to (if (stats.latest.mode == "selfie") cells.filter { it !in StatsLayout.SelfieHidden } else cells) }
                    .filter { (_, cells) -> cells.isNotEmpty() }
                    .forEach { (size, cells) -> CellRow(size, cells, stats) }
            }
        }
    }
}

@Composable
private fun CellRow(size: CellSize, cells: List<Cell>, stats: AnalysisStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StatsLayout.Gap)) {
        cells.forEach { StatCell(it, size, stats, Modifier.weight(1f)) }
    }
}

/** A tile: the label with its (i), the value, and the last minute's chart. */
@Composable
private fun StatCell(cell: Cell, size: CellSize, stats: AnalysisStats, modifier: Modifier) {
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
        // Smaller rather than cut off (a narrow phone, a big font setting), as the home screen's tagline.
        BasicText(
            cell.value(stats, small),
            style = style.copy(color = colors.onSurface),
            maxLines = if (cell == Cell.BACKEND) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
        val series = cell.series(stats)
        if (series != null && size.chart > 0.dp) {
            Sparkline(series, cell.color(colors.isLight), cell.step, Modifier.fillMaxWidth().height(size.chart))
        }
    }
}

/** The cell's name and an (i); a tap shows [info] in a small bubble, a tap anywhere else hides it. */
@Composable
private fun InfoLabel(label: String, info: String) {
    var open by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { Space.XS.roundToPx() }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = info, role = Role.Button) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Measured after the (i), so the (i) always shows; the name gets smaller to fit.
            val labelStyle = AppType.Label
            BasicText(
                label,
                style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Space.XS))
            Icon(AppIcons.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchor(gap) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = Space.M, vertical = Space.S),
                    )
                }
            }
        }
    }
}

/** Puts a bubble just above its anchor, centered on it and kept inside the window (below it when there's no room). */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + gap)
    }
}

/**
 * The last samples of [series] (about a second apart) as an area under a line, newest at the
 * right edge (a full chart is [KEEP] samples wide), redrawn as values come. Times as a smooth
 * curve that never overshoots a sample (monotone cubic); [step]: the value holds until it jumps.
 */
@Composable
private fun Sparkline(series: Series, color: Color, step: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val v = series.values
        if (v.size < 2) return@Canvas
        val stroke = 1.5.dp.toPx()
        val top = stroke
        val bottom = size.height - stroke / 2
        // Times from 0 up; the stepped values around their range.
        val lo = if (step) series.min else 0f
        val hi = series.max
        val span = hi - lo
        val ys = FloatArray(v.size) { i ->
            when {
                span <= 0f -> if (step) size.height / 2 else if (hi > 0f) top else bottom
                step -> bottom - (0.15f + 0.7f * (v[i] - lo) / span) * (bottom - top)
                else -> bottom - (v[i] - lo) / span * (bottom - top)
            }
        }
        val dx = size.width / (KEEP - 1)
        val x0 = size.width - (v.size - 1) * dx
        val slopes = if (step) null else monotoneSlopes(ys, dx)
        fun trace(p: Path) {
            for (i in 1 until ys.size) {
                val xa = x0 + (i - 1) * dx
                val xb = xa + dx
                if (slopes == null) {
                    p.lineTo(xb, ys[i - 1])
                    p.lineTo(xb, ys[i])
                } else {
                    p.cubicTo(xa + dx / 3, ys[i - 1] + slopes[i - 1] * dx / 3, xb - dx / 3, ys[i] - slopes[i] * dx / 3, xb, ys[i])
                }
            }
        }
        val line = Path()
        line.moveTo(x0, ys[0])
        trace(line)
        val area = Path()
        area.moveTo(x0, size.height)
        area.lineTo(x0, ys[0])
        trace(area)
        area.lineTo(x0 + (v.size - 1) * dx, size.height)
        area.close()
        drawPath(area, color.copy(alpha = 0.2f))
        drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Tangents at points [y] [dx] apart for a curve through them that doesn't overshoot
 * (Fritsch–Carlson): the mean of the neighbouring slopes, flat where the slope changes sign,
 * scaled down where they'd make the curve bulge.
 */
private fun monotoneSlopes(y: FloatArray, dx: Float): FloatArray {
    val n = y.size
    val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / dx }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
    for (k in 0 until n - 1) {
        if (d[k] == 0f) {
            m[k] = 0f
            m[k + 1] = 0f
            continue
        }
        val a = m[k] / d[k]
        val b = m[k + 1] / d[k]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / sqrt(s)
            m[k] = t * a * d[k]
            m[k + 1] = t * b * d[k]
        }
    }
    return m
}

/** Samples a chart spans (as many as [com.elad.faceblur.analysis.StatsHistory] keeps). */
private const val KEEP = 60

/**
 * The stats as plain text to paste to an AI agent: keys in English in every language, values as
 * numbers; avg/min/max over the kept samples (the last 60, about a second apart).
 */
internal fun statsReport(context: Context, s: AnalysisStats, info: VideoInfo, elapsedMs: Long, percent: Int): String {
    val l = s.latest
    fun line(key: String, now: Float, h: Series): String {
        val empty = h.values.isEmpty()
        return "$key: now ${fmt(now)}, avg ${fmt(if (empty) now else h.avg)}, min ${fmt(if (empty) now else h.min)}, max ${fmt(if (empty) now else h.max)}"
    }
    // The sizes the detector went through, each once in a row.
    val sides = ArrayList<Int>()
    for (f in s.sideHistory.values) {
        val px = f.roundToInt()
        if (sides.lastOrNull() != px) sides += px
    }
    if (sides.isEmpty()) sides += l.side
    val gpu = when {
        l.onGpu -> if (l.gpuMs > 0f) fmt(l.gpuMs) + " ms" else "-"
        else -> whyNoGpu(l.noGpu) ?: "-"
    }
    val gpuCall = if (l.onGpu) fmt(s.gpuCallMs) + (if (l.gpuQueueMs > 0f) " (queue ${fmt(l.gpuQueueMs)})" else "") else "-"
    val fps = String.format(Locale.US, "%.2f", info.fps).trimEnd('0').trimEnd('.')
    return buildString {
        appendLine("# ONIT face detection stats")
        appendLine("app: ${CrashLog.app(context)}")
        appendLine("device: ${CrashLog.device()}")
        appendLine("video: ${info.displayWidth}x${info.displayHeight}, $fps fps, ${mmss(info.durationMs)}")
        appendLine("elapsed: ${mmss(elapsedMs)} · progress: $percent%")
        appendLine("backend: ${l.accelerator} ${l.precision} (gpu: $gpu)")
        appendLine("sampling: every ${l.every}${if (l.lite) ", lite" else ""}")
        appendLine("resolution_px: ${l.side} (last 60 samples, ~1 s apart: ${sides.joinToString(" -> ")})")
        appendLine(line("detect_ms", s.detectMs, s.detectHistory))
        appendLine(line("wait_ms_per_frame", s.waitMs, s.waitHistory))
        appendLine(line("frame_ms", s.frameMs, s.frameHistory))
        appendLine("workers: ${l.workers}/${l.maxWorkers}")
        appendLine("per_detection_ms: fine ${fmt(s.fineMs)}, coarse ${fmt(s.coarseMs)}, gpu_call $gpuCall, yunet ${fmt(s.yunetMs)}")
        append("crop_checks: ${fmt(s.cropsPerDetection)} per detection x ${fmt(s.cropMs)} ms")
    }
}
