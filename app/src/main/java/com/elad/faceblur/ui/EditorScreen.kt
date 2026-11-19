package com.elad.faceblur.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.elad.faceblur.R
import com.elad.faceblur.edit.EditModel
import com.elad.faceblur.edit.EditTrack
import com.elad.faceblur.edit.EditorController
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val ManualColor = Color(0xFFFFC857)
private val PlayheadColor = Color(0xFFFF5A5F)

/**
 * Blur editor: the video with every blur drawn on it, a frame-accurate scrubber, and one lane
 * per blur showing when it's on screen. Tap a blur (or its lane) to select it; drag to move it
 * at this frame, pinch to resize; the tool row trims, splits, deletes and re-tracks it. "+"
 * adds a blur where you tap and follows it automatically.
 */
@Composable
fun EditorScreen(ctrl: EditorController, onClose: () -> Unit, onExport: () -> Unit) {
    var confirmClose by remember { mutableStateOf(false) }
    BackHandler {
        when {
            ctrl.addMode -> ctrl.addMode = false
            ctrl.selectedId != null -> ctrl.select(null)
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
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { confirmClose = true }) { Icon(Icons.Filled.Close, "סגור") }
            Text(
                "עריכת טשטושים",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ctrl::undo, enabled = ctrl.model.canUndo && !busy) { Icon(EditorIcons.Undo, "בטל") }
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.padding(end = 8.dp)) { Text("ייצוא") }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            VideoWithOverlay(ctrl)
            Column(
                Modifier.align(Alignment.TopCenter).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ctrl.addMode) {
                    Banner("הקש על הפנים שצריך לטשטש", action = "ביטול") { ctrl.addMode = false }
                } else if (ctrl.model.tracks.isEmpty()) {
                    Banner("לא זוהו פנים. אפשר לסמן טשטוש ידנית עם \"הוסף טשטוש\".")
                }
            }
            if (!busy) {
                Surface(
                    onClick = {
                        ctrl.addMode = !ctrl.addMode
                        if (ctrl.addMode) ctrl.pauser()
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = if (ctrl.addMode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (ctrl.addMode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ctrl.addMode) Icons.Filled.Close else Icons.Filled.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ctrl.addMode) "ביטול" else "הוסף טשטוש", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            ctrl.trackingProgress?.let { p ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("עוקב אחרי הטשטוש…", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = ctrl::cancelTracking) { Text("עצור") }
                    }
                }
            }
        }

        // Video timelines run left to right even in a Hebrew UI.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Transport(ctrl, busy)
        }

        ctrl.notice?.let { msg ->
            LaunchedEffect(msg) {
                delay(4000)
                if (ctrl.notice == msg) ctrl.notice = null
            }
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        val sel = ctrl.selected
        if (sel != null) ToolPanel(ctrl, sel, busy)
        Lanes(ctrl, Modifier.fillMaxWidth().height(if (sel != null) 132.dp else 196.dp))
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
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
    val aspect = if (analysis.uprightH > 0) analysis.uprightW.toFloat() / analysis.uprightH else 9f / 16f
    val player = remember(ctrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(ctrl.session.info.uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        val sync = PlayerSync(player, ctrl.model.times, ctrl)
        sync.attach()
        onDispose {
            sync.detach()
            player.release()
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

    fun attach() {
        player.addListener(this)
        player.setVideoFrameMetadataListener(this)
        ctrl.seeker = { seekTo(it) }
        ctrl.pauser = { player.pause() }
        ctrl.playToggler = {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (ctrl.frame >= times.size - 1) seekTo(0)
                player.play()
            }
        }
    }

    fun detach() {
        player.removeListener(this)
        player.clearVideoFrameMetadataListener(this)
        ctrl.seeker = {}
        ctrl.pauser = {}
        ctrl.playToggler = {}
        ctrl.playing = false
    }

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
        main.post { onRendered(presentationTimeUs) }
    }

    private fun onRendered(pts: Long) {
        if (times.isEmpty()) return
        val first = offsetUs == null
        val off = offsetUs ?: (pts - times[0]).also { offsetUs = it }
        if (first && ctrl.frame != 0) {
            seekTo(ctrl.frame)
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
    Canvas(modifier.pointerInput(ctrl) { editGestures(ctrl) }) {
        @Suppress("UNUSED_VARIABLE") val v = ctrl.version
        val shapes = ctrl.model.shapesAt(ctrl.frame, ctrl.padding)
        label.textSize = 13.dp.toPx()
        val selectedId = ctrl.selectedId
        for (s in shapes.sortedBy { it.id == selectedId }) {
            val sel = s.id == selectedId
            val tl = Offset((s.cx - s.rx) * size.width, (s.cy - s.ry) * size.height)
            val sz = Size(2 * s.rx * size.width, 2 * s.ry * size.height)
            val c = when {
                sel -> primary
                s.manual -> ManualColor
                else -> Color.White
            }
            drawOval(c.copy(alpha = if (sel) 0.30f else 0.16f), tl, sz)
            drawOval(c, tl, sz, style = Stroke(width = if (sel) 3.dp.toPx() else 1.5.dp.toPx()))
            val text = s.id.toString()
            val tw = label.measureText(text)
            drawContext.canvas.nativeCanvas.drawText(
                text, tl.x + sz.width / 2 - tw / 2, (tl.y - 5.dp.toPx()).coerceAtLeast(label.textSize), label,
            )
        }
    }
}

/** Tap: select / add. Drag a blur: move it at this frame. Pinch: resize the blur. */
private suspend fun PointerInputScope.editGestures(ctrl: EditorController) {
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
    val n = ctrl.frameCount
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctrl.step(-1) }, enabled = !busy) { Icon(EditorIcons.ChevronLeft, "פריים קודם") }
            IconButton(onClick = { ctrl.playToggler() }, enabled = !busy) {
                Icon(if (ctrl.playing) EditorIcons.Pause else Icons.Filled.PlayArrow, if (ctrl.playing) "השהה" else "נגן")
            }
            IconButton(onClick = { ctrl.step(1) }, enabled = !busy) { Icon(EditorIcons.ChevronRight, "פריים הבא") }
            Text(
                "${clock(ctrl, ctrl.frame)} / ${clock(ctrl, n - 1)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (n > 1) {
            Slider(
                value = ctrl.frame.toFloat(),
                onValueChange = {
                    if (ctrl.playing) ctrl.pauser()
                    ctrl.seek(it.roundToInt())
                },
                valueRange = 0f..(n - 1).toFloat(),
                enabled = !busy,
                modifier = Modifier.height(32.dp),
            )
        }
    }
}

private fun clock(ctrl: EditorController, f: Int): String {
    val t = ctrl.model.times
    if (t.isEmpty()) return "0:00.0"
    val tenths = ((t[f.coerceIn(0, t.size - 1)] - t[0]) / 100_000L).toInt()
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

// ---------------------------------------------------------------------- tools

@Composable
private fun ToolPanel(ctrl: EditorController, t: EditTrack, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#${t.id}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Tool("מחק", Icons.Filled.Delete, !busy) { ctrl.deleteSelected() }
                Tool("מתחיל כאן", EditorIcons.StartHere, !busy) { ctrl.startHere() }
                Tool("נגמר כאן", EditorIcons.EndHere, !busy) { ctrl.endHere() }
                Tool("עקוב קדימה", EditorIcons.TrackForward, !busy) { ctrl.trackSelected(1) }
                Tool("עקוב אחורה", EditorIcons.TrackBack, !busy) { ctrl.trackSelected(-1) }
                Tool("פצל כאן", EditorIcons.Cut, !busy) { ctrl.splitSelected() }
                Tool("סגור", Icons.Filled.Close, true) { ctrl.select(null) }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("גודל", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = t.scale,
                    onValueChange = { ctrl.setSelectedScale(it) },
                    onValueChangeFinished = { ctrl.endSlider() },
                    valueRange = EditModel.MIN_SCALE..EditModel.MAX_SCALE,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(32.dp),
                )
                Text(
                    "×%.1f".format(t.scale),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

@Composable
private fun Tool(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        Modifier
            .width(64.dp)
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

// ---------------------------------------------------------------------- lanes

@Composable
private fun Lanes(ctrl: EditorController, modifier: Modifier) {
    val sorted = ctrl.model.tracks.sortedWith(compareBy({ it.start }, { it.id }))
    Column(modifier) {
        Text(
            if (sorted.isEmpty()) "אין טשטושים" else "טשטושים (${sorted.size}) · הקש על פס כדי לבחור ולקפוץ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sorted, key = { it.id }) { t -> Lane(ctrl, t, t.id) }
            }
        }
    }
}

@Composable
private fun Lane(ctrl: EditorController, t: EditTrack, number: Int) {
    val sel = ctrl.selectedId == t.id
    val primary = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.surfaceContainerHigh
    val barColor = when {
        sel -> primary
        t.manual -> ManualColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val n = max(1, ctrl.frameCount - 1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(if (sel) primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
            color = if (t.manual && !sel) ManualColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(30.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(t.id) {
                    detectTapGestures { p ->
                        ctrl.select(t.id)
                        ctrl.seek((p.x / size.width * n).roundToInt())
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val v = ctrl.version
            val cy = size.height / 2
            val railH = 6.dp.toPx()
            drawRoundRect(rail, Offset(0f, cy - railH / 2), Size(size.width, railH), CornerRadius(railH / 2))
            val x0 = t.start.toFloat() / n * size.width
            val x1 = t.end.toFloat() / n * size.width
            val barH = 12.dp.toPx()
            drawRoundRect(
                barColor, Offset(x0, cy - barH / 2), Size(max(x1 - x0, 3.dp.toPx()), barH), CornerRadius(barH / 3),
            )
            val px = ctrl.frame.toFloat() / n * size.width
            drawLine(PlayheadColor, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        }
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
    val StartHere = icon("start", "M18.41 16.59L13.82 12l4.59-4.59L17 6l-6 6 6 6zM6 6h2v12H6z")
    val EndHere = icon("end", "M5.59 7.41L10.18 12l-4.59 4.59L7 18l6-6-6-6zM16 6h2v12h-2z")
    val TrackBack = icon("track_back", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")
    val TrackForward = icon("track_fwd", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")
}
