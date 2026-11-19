package com.elad.faceblur.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.elad.faceblur.R
import com.elad.faceblur.edit.EditModel
import com.elad.faceblur.edit.EditTrack
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.PreviewSource
import com.elad.faceblur.effects.BlurEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val AutoClip = Color(0xFF4A5FD0)
private val ManualClip = Color(0xFFD99A1E)
private val ManualOutline = Color(0xFFFFC857)

/**
 * Blur editor laid out like a video editor: the player on top (with the real blur applied, or
 * outlines only), transport controls, and a timeline below: a filmstrip with every blur as a
 * clip in rows under it and a fixed playhead in the middle. Drag the timeline to scrub, pinch
 * it to zoom; tap a clip to select it and drag its edges to change when it starts and ends.
 * On the video: drag a blur to move it, pinch to resize. "Add blur" puts one where you tap and
 * follows the face automatically.
 */
@Composable
fun EditorScreen(ctrl: EditorController, onClose: () -> Unit, onExport: () -> Unit) {
    var confirmClose by remember { mutableStateOf(false) }
    BackHandler {
        when {
            ctrl.addMode -> ctrl.addMode = false
            ctrl.selectedId != null -> ctrl.deselect()
            else -> confirmClose = true
        }
    }
    @Suppress("UNUSED_VARIABLE") val version = ctrl.version
    val busy = ctrl.trackingProgress != null

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { confirmClose = true }) { Icon(Icons.Filled.Close, "סגור") }
            Text("עריכת טשטושים", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("ייצוא") }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            VideoWithOverlay(ctrl)
            Column(
                Modifier.align(Alignment.TopCenter).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (ctrl.addMode) {
                    Banner("הקש על הפנים שצריך לטשטש", action = "ביטול") { ctrl.addMode = false }
                } else if (ctrl.model.tracks.isEmpty()) {
                    Banner("לא זוהו פנים. אפשר לסמן ידנית עם \"הוסף טשטוש\".")
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ctrl.notice?.let { msg ->
                    LaunchedEffect(msg) {
                        delay(4000)
                        if (ctrl.notice == msg) ctrl.notice = null
                    }
                    Banner(msg)
                }
                ctrl.trackingProgress?.let { p ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(progress = { p }, modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("עוקב אחרי הפנים…", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = ctrl::cancelTracking) { Text("עצור") }
                        }
                    }
                }
            }
        }

        // Video controls and timelines run left to right even in a Hebrew UI.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Transport(ctrl, busy)
            Timeline(ctrl, busy)
        }

        val sel = ctrl.selected
        if (sel != null && ctrl.sizePanel) SizePanel(ctrl, sel, busy)
        BottomBar(ctrl, sel, busy)
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("לצאת מהעריכה?") },
            text = { Text("השינויים בטשטושים לא יישמרו.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("צא") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("המשך לערוך") } },
        )
    }
}

@Composable
private fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ---------------------------------------------------------------------- video + overlay

@Composable
private fun VideoWithOverlay(ctrl: EditorController) {
    val context = LocalContext.current
    val analysis = ctrl.session.analysis
    val settings = ctrl.session.settings
    val aspect = if (analysis.uprightH > 0) analysis.uprightW.toFloat() / analysis.uprightH else 9f / 16f
    val times = ctrl.model.times
    val preview = remember(ctrl) { PreviewSource(if (times.isEmpty()) 0L else times[0]) }
    val player = remember(ctrl) {
        ExoPlayer.Builder(context).build().apply {
            if (ctrl.previewAvailable) {
                setVideoEffects(listOf<Effect>(BlurEffect(preview, settings.mode, settings.strength)))
            }
            setMediaItem(MediaItem.fromUri(ctrl.session.info.uri))
            prepare()
        }
    }
    val sync = remember(player) { PlayerSync(player, times, ctrl) }
    DisposableEffect(player) {
        ctrl.preview = preview
        sync.attach()
        onDispose {
            sync.detach()
            ctrl.preview = null
            player.release()
        }
    }
    // Fallback clock while playing, in case the player doesn't report rendered frames.
    LaunchedEffect(player, ctrl.playing) {
        if (!ctrl.playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (player.isPlaying && SystemClock.uptimeMillis() - sync.lastFrameReportMs > 300) {
                ctrl.onFrameShown(sync.frameAtPosition(player.currentPosition))
            }
        }
    }
    Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.editor_player, null) as PlayerView).also { it.player = player }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Overlay(ctrl, Modifier.fillMaxSize())
    }
}

/**
 * Keeps the editor's frame index and the player in step. Frames are identified by the
 * presentation time the player reports when it renders them, matched to the analysis frame
 * times; a seek that lands on a neighbouring frame is corrected and repeated.
 */
private class PlayerSync(
    private val player: ExoPlayer,
    private val times: LongArray,
    private val ctrl: EditorController,
) : Player.Listener, VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    private var offsetUs: Long? = null
    private var biasUs = 0L
    private var target = -1
    private var retries = 0
    @Volatile var lastFrameReportMs = 0L

    fun attach() {
        player.addListener(this)
        player.setVideoFrameMetadataListener(this)
        ctrl.seeker = { seekTo(it) }
        ctrl.pauser = { player.pause() }
        ctrl.playToggler = {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED || ctrl.frame >= times.size - 1) seekTo(0)
                player.play()
            }
        }
        ctrl.refresher = {
            if (!player.isPlaying) player.seekTo(player.currentPosition)
        }
    }

    fun detach() {
        player.removeListener(this)
        player.clearVideoFrameMetadataListener(this)
        ctrl.seeker = {}
        ctrl.pauser = {}
        ctrl.playToggler = {}
        ctrl.refresher = {}
        ctrl.playing = false
    }

    fun frameAtPosition(positionMs: Long): Int = ctrl.frameAt(positionMs * 1000 - biasUs)

    private fun seekTo(f: Int) {
        if (times.isEmpty()) return
        target = f
        retries = 0
        doSeek(f)
    }

    private fun doSeek(f: Int) {
        val us = times[f] - times[0] + biasUs
        player.seekTo(max(0L, ceil(us / 1000.0).toLong()))
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        lastFrameReportMs = SystemClock.uptimeMillis()
        main.post { onRendered(presentationTimeUs) }
    }

    private fun onRendered(pts: Long) {
        if (times.isEmpty()) return
        val first = offsetUs == null
        val off = offsetUs ?: (pts - times[0]).also { offsetUs = it }
        if (first && ctrl.shownFrame != 0) {
            seekTo(ctrl.shownFrame)
            return
        }
        val f = nearest(pts - off)
        if (player.isPlaying) {
            ctrl.onFrameShown(f)
            return
        }
        // Only a near miss is an offset to correct; a far frame is a stale, superseded seek.
        if (target >= 0 && f != target && abs(f - target) <= 3 && retries < 3) {
            retries++
            biasUs += times[target] - times[f]
            doSeek(target)
        } else {
            target = -1
        }
    }

    private fun nearest(t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        return if (abs(times[hi] - t) < abs(times[lo] - t)) hi else lo
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ctrl.playing = isPlaying
    }

    override fun onPlayerError(error: PlaybackException) {
        // The live blur is the riskiest part of playback: drop it and play plain video.
        if (ctrl.previewAvailable) {
            ctrl.previewAvailable = false
            ctrl.notice = "תצוגת הטשטוש לא נתמכת כאן. מוצגות מסגרות בלבד."
            player.setVideoEffects(emptyList())
            player.prepare()
        }
    }
}

@Composable
private fun Overlay(ctrl: EditorController, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val label = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier.pointerInput(ctrl) { videoGestures(ctrl) }) {
        @Suppress("UNUSED_VARIABLE") val v = ctrl.version
        val live = ctrl.previewOn && ctrl.previewAvailable
        if (live && ctrl.playing) return@Canvas
        val shapes = ctrl.model.shapesAt(ctrl.shownFrame, ctrl.padding)
        label.textSize = 13.dp.toPx()
        val selectedId = ctrl.selectedId
        for (s in shapes.sortedBy { it.id == selectedId }) {
            val sel = s.id == selectedId
            val tl = Offset((s.cx - s.rx) * size.width, (s.cy - s.ry) * size.height)
            val sz = Size(2 * s.rx * size.width, 2 * s.ry * size.height)
            val c = when {
                sel -> primary
                s.manual -> ManualOutline
                else -> Color.White
            }
            if (live) {
                // The real blur is visible: just mark the blurs, the selected one clearly.
                drawOval(c.copy(alpha = if (sel) 1f else 0.55f), tl, sz, style = Stroke(width = if (sel) 2.5.dp.toPx() else 1.dp.toPx()))
            } else {
                drawOval(c.copy(alpha = if (sel) 0.30f else 0.16f), tl, sz)
                drawOval(c, tl, sz, style = Stroke(width = if (sel) 3.dp.toPx() else 1.5.dp.toPx()))
            }
            if (sel || !live) {
                val text = s.id.toString()
                val tw = label.measureText(text)
                drawContext.canvas.nativeCanvas.drawText(
                    text, tl.x + sz.width / 2 - tw / 2, (tl.y - 5.dp.toPx()).coerceAtLeast(label.textSize), label,
                )
            }
        }
    }
}

/** Tap: select / add. Drag a blur: move it at this frame. Pinch: resize the blur. */
private suspend fun PointerInputScope.videoGestures(ctrl: EditorController) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (ctrl.playing) {
            ctrl.pauser()
            return@awaitEachGesture
        }
        if (ctrl.trackingProgress != null) return@awaitEachGesture
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val hit = ctrl.hitTest(down.position.x / w, down.position.y / h)
        var active: Boolean? = null
        var panAcc = Offset.Zero
        var zoomAcc = 1f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.changes.none { it.pressed }) break
            val pan = ev.calculatePan()
            val zoom = ev.calculateZoom()
            if (active == null) {
                panAcc += pan
                zoomAcc *= zoom
                val multi = ev.changes.count { it.pressed } > 1
                if (panAcc.getDistance() > slop || abs(zoomAcc - 1f) > 0.04f || multi) {
                    val id = hit ?: if (multi) ctrl.selectedId else null
                    active = if (id != null && !ctrl.addMode) {
                        ctrl.select(id)
                        ctrl.beginGesture()
                    } else {
                        false
                    }
                }
            }
            if (active == true) {
                if (pan != Offset.Zero) ctrl.moveSelected(pan.x / w, pan.y / h)
                if (zoom != 1f) ctrl.scaleSelectedBy(zoom)
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
        if (active == null) ctrl.tap(down.position.x / w, down.position.y / h)
    }
}

// ---------------------------------------------------------------------- transport

@Composable
private fun Transport(ctrl: EditorController, busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)) {
        Text(
            "${clock(ctrl.timeOf(ctrl.frame))} / ${clock(ctrl.timeOf(ctrl.frameCount - 1))}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctrl.step(-1) }, enabled = !busy) { Icon(EditorIcons.ChevronLeft, "פריים קודם") }
            Surface(
                onClick = { ctrl.playToggler() },
                enabled = !busy,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (ctrl.playing) EditorIcons.Pause else Icons.Filled.PlayArrow,
                        if (ctrl.playing) "השהה" else "נגן",
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            IconButton(onClick = { ctrl.step(1) }, enabled = !busy) { Icon(EditorIcons.ChevronRight, "פריים הבא") }
        }
        IconButton(
            onClick = ctrl::undo,
            enabled = ctrl.model.canUndo && !busy,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) { Icon(EditorIcons.Undo, "בטל") }
    }
}

private fun clock(us: Long): String {
    val tenths = (us / 100_000L).toInt()
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

// ---------------------------------------------------------------------- timeline

/** Greedy packing of blurs into rows so clips never overlap. */
private fun packRows(tracks: List<EditTrack>): List<List<EditTrack>> {
    val rows = ArrayList<ArrayList<EditTrack>>()
    val ends = ArrayList<Int>()
    for (t in tracks.sortedWith(compareBy({ it.start }, { it.id }))) {
        val r = ends.indexOfFirst { it < t.start - 2 }
        if (r < 0) {
            rows += arrayListOf(t)
            ends += t.end
        } else {
            rows[r] += t
            ends[r] = t.end
        }
    }
    return rows
}

private class TimelineGeo(
    val ctrl: EditorController,
    val width: Float,
    val pxPerSec: Float,
    val rows: List<List<EditTrack>>,
    val rowsTop: Float,
    val rowPitch: Float,
    val rowH: Float,
    val rowScroll: Float,
    val handleW: Float,
) {
    val cx = width / 2
    val nowUs = ctrl.timeOf(ctrl.frame)

    fun x(us: Long) = cx + (us - nowUs) / 1e6f * pxPerSec
    fun usAt(x: Float): Long = nowUs + ((x - cx) / pxPerSec * 1e6f).toLong()
    fun clipX0(t: EditTrack) = x(ctrl.timeOf(t.start))
    fun clipX1(t: EditTrack) = x(ctrl.timeOf(t.end) + ctrl.frameUs)
    fun rowY(r: Int) = rowsTop + r * rowPitch - rowScroll

    fun clipAt(p: Offset): EditTrack? {
        rows.forEachIndexed { r, row ->
            val y = rowY(r)
            if (p.y in y..(y + rowH)) return row.firstOrNull { p.x in clipX0(it)..clipX1(it) }
        }
        return null
    }

    /** (track id, is start edge) of the selected clip's handle under [p]. */
    fun handleAt(p: Offset): Pair<Int, Boolean>? {
        val id = ctrl.selectedId ?: return null
        rows.forEachIndexed { r, row ->
            val t = row.firstOrNull { it.id == id } ?: return@forEachIndexed
            val y = rowY(r)
            if (p.y !in (y - rowH * 0.4f)..(y + rowH * 1.4f)) return null
            val x0 = clipX0(t)
            val x1 = clipX1(t)
            if (p.x in (x0 - handleW * 1.6f)..(x0 + handleW * 0.4f)) return id to true
            if (p.x in (x1 - handleW * 0.4f)..(x1 + handleW * 1.6f)) return id to false
        }
        return null
    }
}

@Composable
private fun Timeline(ctrl: EditorController, busy: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stripPx = with(density) { 44.dp.toPx() }
    val thumbs = remember(ctrl) {
        val dur = ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs
        Thumbnails(context, ctrl.session.info.uri, dur, stepUs = 500_000L, sizePx = stripPx.roundToInt())
    }
    LaunchedEffect(thumbs) { thumbs.load() }

    val pxPerSec = remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
    val rowScroll = remember { mutableFloatStateOf(0f) }
    val flingJob = remember { arrayOfNulls<Job>(1) }

    val rulerH = with(density) { 16.dp.toPx() }
    val stripTop = rulerH + with(density) { 2.dp.toPx() }
    val rowsTop = stripTop + stripPx + with(density) { 8.dp.toPx() }
    val rowH = with(density) { 24.dp.toPx() }
    val rowPitch = rowH + with(density) { 5.dp.toPx() }
    val handleW = with(density) { 12.dp.toPx() }
    val visibleRows = 4
    val heightDp = with(density) { (rowsTop + visibleRows * rowPitch).toDp() } + 4.dp

    val rows = remember(ctrl.version) { packRows(ctrl.model.tracks) }
    val maxScroll = max(0f, (rows.size - visibleRows) * rowPitch)
    if (rowScroll.floatValue > maxScroll) rowScroll.floatValue = maxScroll
    // Keep the selected clip's row in view.
    LaunchedEffect(ctrl.selectedId, rows) {
        val id = ctrl.selectedId ?: return@LaunchedEffect
        val r = rows.indexOfFirst { row -> row.any { it.id == id } }
        if (r < 0) return@LaunchedEffect
        val top = r * rowPitch
        if (top < rowScroll.floatValue) rowScroll.floatValue = top
        if (top + rowPitch > rowScroll.floatValue + visibleRows * rowPitch) {
            rowScroll.floatValue = (top + rowPitch - visibleRows * rowPitch).coerceIn(0f, maxScroll)
        }
    }

    val colors = MaterialTheme.colorScheme
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val widthPx = remember { mutableFloatStateOf(1f) }
    val geo: () -> TimelineGeo = {
        TimelineGeo(ctrl, widthPx.floatValue, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .pointerInput(ctrl, rows, busy, maxScroll) {
                if (!busy) timelineGestures(ctrl, geo, pxPerSec, rowScroll, maxScroll, scope, flingJob)
            },
    ) {
        widthPx.floatValue = size.width
        @Suppress("UNUSED_VARIABLE") val tv = thumbs.version
        @Suppress("UNUSED_VARIABLE") val vv = ctrl.version
        val g = TimelineGeo(ctrl, size.width, pxPerSec.floatValue, rows, rowsTop, rowPitch, rowH, rowScroll.floatValue, handleW)
        val durUs = ctrl.timeOf(ctrl.frameCount - 1) + ctrl.frameUs
        val vx0 = g.x(0)
        val vx1 = g.x(durUs)

        // Ruler.
        textPaint.textSize = 10.dp.toPx()
        textPaint.color = colors.onSurfaceVariant.toArgb()
        val steps = floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 30f, 60f)
        val major = steps.firstOrNull { it * g.pxPerSec >= 64.dp.toPx() } ?: 120f
        var s = max(0f, floor(g.usAt(0f) / 1e6f / major) * major)
        while (s * 1e6f <= durUs && g.x((s * 1e6f).toLong()) <= size.width + 40f) {
            val x = g.x((s * 1e6f).toLong())
            drawLine(colors.outline, Offset(x, rulerH - 5.dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
            val half = g.x(((s + major / 2) * 1e6f).toLong())
            if (half < vx1) drawCircle(colors.outline, 1.2.dp.toPx(), Offset(half, rulerH - 2.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(clockShort(s), x + 3.dp.toPx(), rulerH - 6.dp.toPx(), textPaint)
            s += major
        }

        // Filmstrip.
        drawRoundRect(colors.surfaceContainerHigh, Offset(vx0, stripTop), Size(vx1 - vx0, stripPx), CornerRadius(6.dp.toPx()))
        clipRect(left = max(0f, vx0), top = stripTop, right = min(size.width, vx1), bottom = stripTop + stripPx) {
            val cell = stripPx
            var k = max(0, floor((0f - vx0) / cell).toInt())
            while (vx0 + k * cell < min(size.width, vx1)) {
                val left = vx0 + k * cell
                val us = g.usAt(left + cell / 2).coerceIn(0L, durUs)
                thumbs.near((us / thumbs.stepUs).toInt())?.let { img ->
                    drawImage(
                        img,
                        dstOffset = IntOffset(left.roundToInt(), stripTop.roundToInt()),
                        dstSize = IntSize(cell.roundToInt() + 1, stripPx.roundToInt()),
                    )
                }
                k++
            }
        }

        // Blur clips.
        clipRect(top = rowsTop - 6.dp.toPx(), bottom = size.height) {
            textPaint.textSize = 11.dp.toPx()
            textPaint.color = android.graphics.Color.WHITE
            val selId = ctrl.selectedId
            rows.forEachIndexed { r, row ->
                val y = g.rowY(r)
                if (y > size.height || y + rowH < rowsTop - rowH) return@forEachIndexed
                for (t in row) {
                    val x0 = g.clipX0(t)
                    val x1 = max(g.clipX1(t), x0 + 3.dp.toPx())
                    if (x1 < -handleW || x0 > size.width + handleW) continue
                    val sel = t.id == selId
                    drawRoundRect(if (t.manual) ManualClip else AutoClip, Offset(x0, y), Size(x1 - x0, rowH), CornerRadius(5.dp.toPx()))
                    if (x1 - x0 > 26.dp.toPx()) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "#${t.id}", max(x0, 0f) + 6.dp.toPx(), y + rowH / 2 + textPaint.textSize / 2.8f, textPaint,
                        )
                    }
                    if (sel) {
                        drawRoundRect(Color.White, Offset(x0, y), Size(x1 - x0, rowH), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                        for (left in listOf(true, false)) {
                            val hl = if (left) x0 - handleW else x1
                            drawRoundRect(Color.White, Offset(hl, y - 3.dp.toPx()), Size(handleW, rowH + 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                            val gx = hl + handleW / 2
                            drawLine(Color(0xFF333333), Offset(gx, y + rowH * 0.3f), Offset(gx, y + rowH * 0.7f), 1.5.dp.toPx())
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                textPaint.color = colors.onSurfaceVariant.toArgb()
                textPaint.textSize = 12.dp.toPx()
                val msg = "אין טשטושים"
                drawContext.canvas.nativeCanvas.drawText(msg, size.width / 2 - textPaint.measureText(msg) / 2, rowsTop + rowH * 0.8f, textPaint)
            }
        }
        if (maxScroll > 0) {
            // Scroll hint: more rows below.
            val frac = rowScroll.floatValue / maxScroll
            val trackH = visibleRows * rowPitch - 8.dp.toPx()
            val barH = trackH * (visibleRows.toFloat() / rows.size)
            drawRoundRect(
                colors.outline, Offset(size.width - 4.dp.toPx(), rowsTop + (trackH - barH) * frac), Size(2.5.dp.toPx(), barH),
                CornerRadius(2.dp.toPx()),
            )
        }

        // Playhead.
        drawLine(Color.White, Offset(g.cx, 0f), Offset(g.cx, size.height), 2.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), Offset(g.cx, 3.dp.toPx()))
    }
}

private fun clockShort(sec: Float): String {
    val whole = floor(sec).toInt()
    return if (sec - whole > 0.01f) "%d:%02d.5".format(whole / 60, whole % 60) else "%d:%02d".format(whole / 60, whole % 60)
}

/**
 * Drag: scrub (with fling) or scroll the rows; drag a selected clip's edge: trim it;
 * pinch: zoom; tap: select a clip (or deselect).
 */
private suspend fun PointerInputScope.timelineGestures(
    ctrl: EditorController,
    geo: () -> TimelineGeo,
    pxPerSec: MutableFloatState,
    rowScroll: MutableFloatState,
    maxScroll: Float,
    scope: CoroutineScope,
    flingJob: Array<Job?>,
) {
    val minZoom = 8.dp.toPx()
    val maxZoom = 900.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        flingJob[0]?.cancel()
        if (ctrl.playing) ctrl.pauser()
        val g0 = geo()
        val handle = g0.handleAt(down.position)
        var mode = 0 // 0 undecided, 1 scrub, 2 rows, 3 trim, 4 zoom
        var acc = Offset.Zero
        val durUs = ctrl.timeOf(ctrl.frameCount - 1)
        var tUs = ctrl.timeOf(ctrl.frame).toDouble()
        val vt = VelocityTracker()
        vt.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val c0 = pressed.first()
            vt.addPosition(c0.uptimeMillis, c0.position)
            if (pressed.size > 1) {
                if (mode == 3) ctrl.endTrim()
                mode = 4
                pxPerSec.floatValue = (pxPerSec.floatValue * ev.calculateZoom()).coerceIn(minZoom, maxZoom)
                ev.changes.forEach { it.consume() }
                continue
            }
            if (mode == 4) continue
            val pan = ev.calculatePan()
            if (mode == 0) {
                acc += pan
                if (acc.getDistance() > slop) {
                    mode = when {
                        handle != null -> 3.also { ctrl.beginTrim() }
                        abs(acc.x) >= abs(acc.y) -> 1
                        else -> 2
                    }
                }
            }
            when (mode) {
                1 -> {
                    tUs = (tUs - pan.x / pxPerSec.floatValue * 1e6).coerceIn(0.0, durUs.toDouble())
                    ctrl.seek(ctrl.frameAt(tUs.toLong()))
                }
                2 -> rowScroll.floatValue = (rowScroll.floatValue - pan.y).coerceIn(0f, maxScroll)
                3 -> {
                    val (id, start) = handle!!
                    val us = geo().usAt(c0.position.x) - if (start) 0L else ctrl.frameUs
                    ctrl.trimTo(id, start, ctrl.frameAt(us.coerceIn(0L, durUs)))
                }
            }
            if (mode != 0) ev.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        when (mode) {
            0 -> {
                val hit = g0.clipAt(down.position)
                if (hit != null) ctrl.focus(hit.id) else if (down.position.y > g0.rowsTop - 4f) ctrl.deselect()
            }
            1 -> {
                val v = vt.calculateVelocity().x
                if (abs(v) > 400f) {
                    flingJob[0] = scope.launch {
                        var last = 0f
                        AnimationState(0f, -v).animateDecay(exponentialDecay<Float>()) {
                            val d = value - last
                            last = value
                            tUs = (tUs + d / pxPerSec.floatValue * 1e6).coerceIn(0.0, durUs.toDouble())
                            ctrl.seek(ctrl.frameAt(tUs.toLong()))
                            if (tUs <= 0.0 || tUs >= durUs) cancelAnimation()
                        }
                    }
                }
            }
            3 -> ctrl.endTrim()
        }
    }
}

// ---------------------------------------------------------------------- bottom bar

@Composable
private fun SizePanel(ctrl: EditorController, t: EditTrack, busy: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("גודל", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = t.scale,
            onValueChange = { ctrl.setSelectedScale(it) },
            onValueChangeFinished = { ctrl.endSlider() },
            valueRange = EditModel.MIN_SCALE..EditModel.MAX_SCALE,
            enabled = !busy,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(36.dp),
        )
        Text(
            "×%.1f".format(t.scale),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun BottomBar(ctrl: EditorController, sel: EditTrack?, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (sel == null) {
                Tool(if (ctrl.addMode) "ביטול" else "הוסף טשטוש", if (ctrl.addMode) Icons.Filled.Close else Icons.Filled.Add, !busy, wide = true) {
                    ctrl.addMode = !ctrl.addMode
                    if (ctrl.addMode) ctrl.pauser()
                }
                if (ctrl.previewAvailable) {
                    Tool(
                        if (ctrl.previewOn) "תצוגה: טשטוש" else "תצוגה: מסגרות",
                        if (ctrl.previewOn) EditorIcons.Eye else EditorIcons.EyeOff,
                        !busy,
                        wide = true,
                    ) { ctrl.togglePreview() }
                }
            } else {
                Tool("מחק", Icons.Filled.Delete, !busy) { ctrl.deleteSelected() }
                Tool("פצל", EditorIcons.Cut, !busy) { ctrl.splitSelected() }
                Tool("עקוב קדימה", EditorIcons.TrackForward, !busy) { ctrl.trackSelected(1) }
                Tool("עקוב אחורה", EditorIcons.TrackBack, !busy) { ctrl.trackSelected(-1) }
                Tool("גודל", EditorIcons.Resize, !busy) { ctrl.sizePanel = !ctrl.sizePanel }
                Tool("סיום", Icons.Filled.Check, true) { ctrl.deselect() }
            }
        }
    }
}

@Composable
private fun Tool(label: String, icon: ImageVector, enabled: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        Modifier
            .width(if (wide) 104.dp else 66.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
    }
}

// ---------------------------------------------------------------------- icons

/** A few Material icons (Apache 2.0) not in the core icon set. */
private object EditorIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val Pause = icon("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Undo = icon(
        "undo",
        "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
    )
    val ChevronLeft = icon("prev", "M15.41 16.59L10.83 12l4.58-4.59L14 6l-6 6 6 6 1.41-1.41z")
    val ChevronRight = icon("next", "M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z")
    val Cut = icon(
        "cut",
        "M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm6-7.5c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5zM19 3l-6 6 2 2 7-7V3z",
    )
    val TrackBack = icon("track_back", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")
    val TrackForward = icon("track_fwd", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")
    val Resize = icon(
        "resize",
        "M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z",
    )
    val Eye = icon(
        "eye",
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
    )
    val EyeOff = icon(
        "eye_off",
        "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z",
    )
}
