package com.elad.faceblur.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.pipeline.WatermarkLook
import com.elad.faceblur.pipeline.WatermarkPosition
import com.elad.faceblur.subs.SubFont
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.subs.SubtitleRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A logo picture is there to use: the app's, or (in a template) the template's own. */
private fun hasLogo(context: android.content.Context, w: Watermark, ownLogo: java.io.File?): Boolean =
    if (ownLogo != null) w.logo != null && w.logoFile(context).exists() else w.logoFile(context).exists()

/**
 * Everything about the watermark: none / text / logo, where it sits (3x3, center included),
 * size and opacity, and for text its font, color and how it stands out.
 *
 * With [ownLogo] (the template editor) a picked logo goes to that file and the watermark keeps its
 * path: the app's own logo is not touched. Without it, the pick is the app's logo.
 */
@Composable
fun WatermarkControls(w: Watermark, ownLogo: java.io.File? = null, onChange: (Watermark) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 none, 1 text, 2 logo.
    var kind by remember { mutableIntStateOf(if (w.image) 2 else if (w.text.isNotBlank()) 1 else 0) }
    // Follow changes made elsewhere (the export sheet and the editor panel share the watermark,
    // settings can be reset), but not our own: clearing the text keeps the text field open.
    var sent by remember { mutableStateOf<Watermark?>(null) }
    val change = { n: Watermark ->
        sent = n
        onChange(n)
    }
    LaunchedEffect(w) {
        if (w != sent) kind = if (w.image) 2 else if (w.text.isNotBlank()) 1 else 0
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { if (ownLogo != null) Watermark.importImageTo(context, uri, ownLogo) else Watermark.importImage(context, uri) }.getOrDefault(false)
            }
            if (ok) change(w.copy(image = true, logo = ownLogo?.absolutePath, stamp = System.currentTimeMillis()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segments(listOf(0 to t("בלי"), 1 to t("טקסט"), 2 to t("לוגו")), kind) { k ->
            kind = k
            when (k) {
                0 -> change(w.copy(text = "", image = false))
                1 -> change(w.copy(image = false, text = w.text.ifBlank { "@" }))
                else -> if (hasLogo(context, w, ownLogo)) change(w.copy(image = true)) else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        if (kind == 1) {
            OutlinedTextField(
                w.text, { change(w.copy(text = it.take(40), image = false)) },
                singleLine = true, label = { Text(t("הטקסט")) }, modifier = Modifier.fillMaxWidth(),
                // "@name" reads left to right in a Hebrew screen too.
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            )
        }
        if (kind == 2) {
            val logo by produceState<ImageBitmap?>(null, w.stamp, w.logo) {
                value = withContext(Dispatchers.IO) {
                    w.logoFile(context).takeIf { it.exists() && (ownLogo == null || w.logo != null) }?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
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
        if (kind == 0) return@Column

        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption(t("גודל"))
                ValueSlider(w.size, 0.5f..3f) { change(w.copy(size = it)) }
                Caption(t("שקיפות"))
                // Shown as opacity: 100% is fully visible.
                ValueSlider(w.opacity, 0.15f..1f) { change(w.copy(opacity = it)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption(t("מיקום"))
                PositionGrid(w.position) { change(w.copy(position = it)) }
            }
        }

        if (kind == 1) {
            Caption(t("פונט"))
            FontRow(w.font) { change(w.copy(font = it, weight = 0)) }
            FontAxes(w.font, w.weight, w.round) { wt, r -> change(w.copy(weight = wt, round = r)) }
            Caption(t("צבע"))
            Swatches(SubPresets.colors, w.color) { change(w.copy(color = it)) }
            Caption(t("בולטות"))
            Segments(
                listOf(
                    WatermarkLook.PLAIN to t("רגיל"),
                    WatermarkLook.SHADOW to t("צל"),
                    WatermarkLook.OUTLINE to t("קו מתאר"),
                    WatermarkLook.BOX to t("רקע"),
                ),
                w.look,
            ) { change(w.copy(look = it)) }
        }
    }
}

/** A slider that reports when let go (the watermark is redrawn per change), with its value in %. */
@Composable
private fun ValueSlider(value: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(v, { v = it }, Modifier.weight(1f), valueRange = range, onValueChangeFinished = { onDone(v) })
        Text(
            ltr("${(v * 100).roundToInt()}%"),
            style = MaterialTheme.typography.labelMedium.tnum(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(start = 6.dp),
        )
    }
}

/** 3x3 spots on a little frame; left and right as on the video, whatever the UI language. */
@Composable
private fun PositionGrid(selected: WatermarkPosition, onPick: (WatermarkPosition) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .size(width = 108.dp, height = 78.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        ) {
            for (row in 0..2) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..2) {
                        val p = WatermarkPosition.entries.first { it.col == col && it.row == row }
                        val sel = p == selected
                        Box(
                            Modifier.weight(1f).fillMaxSize().clickable { onPick(p) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (sel) 14.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The subtitle fonts in a row, each written in itself. */
@Composable
private fun FontRow(selected: SubFont, onPick: (SubFont) -> Unit) {
    val renderer = SubtitleRenderer.get(LocalContext.current)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in SubFont.entries) {
            val family = remember(f) { FontFamily(renderer.typeface(f)) }
            val sel = f == selected
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .width(84.dp)
                    .clip(shape)
                    .border(if (sel) 2.5.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(f) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("אבג Ab", fontFamily = family, fontSize = 18.sp, maxLines = 1)
                Text(
                    if (f == SubFont.SYSTEM) t("של המכשיר") else f.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The watermark as it will be burned in, on a frame of the video cut to the export's shape
 * ([aspect]: width / height), drawn by the same code as the export.
 */
@Composable
fun WatermarkPreview(w: Watermark, frame: Bitmap?, aspect: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, w, frame, aspect) {
        value = withContext(Dispatchers.Default) {
            val pw: Int
            val ph: Int
            if (aspect >= 1f) {
                pw = 640; ph = (640 / aspect).roundToInt().coerceAtLeast(2)
            } else {
                ph = 640; pw = (640 * aspect).roundToInt().coerceAtLeast(2)
            }
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (frame != null) {
                // Center crop of the frame to the export's shape.
                val fa = frame.width.toFloat() / frame.height
                val src = if (fa > aspect) {
                    val cw = (frame.height * aspect).roundToInt()
                    Rect((frame.width - cw) / 2, 0, (frame.width + cw) / 2, frame.height)
                } else {
                    val ch = (frame.width / aspect).roundToInt()
                    Rect(0, (frame.height - ch) / 2, frame.width, (frame.height + ch) / 2)
                }
                c.drawBitmap(frame, src, Rect(0, 0, pw, ph), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            } else {
                c.drawColor(android.graphics.Color.rgb(40, 44, 70))
            }
            if (w.enabled(context)) Watermark.draw(context, w, c, pw, ph)
            bmp.asImageBitmap()
        }
    }
    Box(
        modifier
            .heightIn(max = 240.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2F48), Color(0xFF14172C)))),
    ) {
        image?.let { Image(it, t("תצוגה מקדימה של סימן המים"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** The watermark panel in the editor: the video above shows it live. */
@Composable
fun WatermarkPanel(w: Watermark, onChange: (Watermark) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        PanelHeader(t("סימן מים"), MaterialTheme.colorScheme.onSurface, onDone)
        Column(
            Modifier
                .fillMaxWidth()
                .then(panelBody(372.dp, upTo = true))
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WatermarkControls(w, onChange = onChange)
            Spacer(Modifier.height(4.dp))
        }
    }
}
