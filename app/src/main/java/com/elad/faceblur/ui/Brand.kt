package com.elad.faceblur.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.elad.faceblur.R
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shrinks a little while pressed and springs back (cards, big buttons). */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * The logo's mark: an "E" of three timeline clips with a playhead. [animate]: the spine, the
 * clips and the playhead come in one after another, once.
 */
@Composable
fun LogoMark(size: Dp, animate: Boolean = true) {
    // The glass logo, as the app icon, light or dark with the theme. The play button appears,
    // then its second piece slides along the cut.
    val dark = !MaterialTheme.colorScheme.isLight
    val bg = ImageBitmap.imageResource(if (dark) R.drawable.logo_bg_dark else R.drawable.logo_bg_light)
    val left = ImageBitmap.imageResource(if (dark) R.drawable.logo_left_dark else R.drawable.logo_left_light)
    val right = ImageBitmap.imageResource(if (dark) R.drawable.logo_right_dark else R.drawable.logo_right_light)
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    val cut = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) {
        LaunchedEffect(Unit) {
            launch { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
            delay(260)
            cut.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f))) {
        val d = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        drawImage(bg, dstSize = d, filterQuality = FilterQuality.High)
        // The images cover the icon's visible 72 units; the mark's middle is at (32.7, 36).
        val u = this.size.width / 72f
        val k = appear.value.coerceIn(0f, 1.1f)
        val c = cut.value.coerceIn(0f, 1.2f)
        withTransform({ scale(k, k, Offset(32.7f * u, 36f * u)) }) {
            drawImage(left, dstSize = d, filterQuality = FilterQuality.High)
            translate(1.6f * u * c, -2.2f * u * c) {
                drawImage(right, dstSize = d, filterQuality = FilterQuality.High)
            }
        }
    }
}

/** A card shaped like a clip: rounded, with a bar in its [accent] color along the leading edge. */
@Composable
fun ClipCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val source = remember { MutableInteractionSource() }
    val border = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    val inner: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (accent != null) {
                        val w = 5.dp.toPx()
                        val inset = 18.dp.toPx()
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                        drawRoundRect(accent, Offset(x, inset), Size(w, (size.height - 2 * inset).coerceAtLeast(w)), CornerRadius(w / 2))
                    }
                }
                .padding(contentPadding),
            content = content,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().pressScale(source).then(border),
            shape = shape,
            color = color,
            interactionSource = source,
        ) { inner() }
    } else {
        Surface(modifier = modifier.fillMaxWidth().then(border), shape = shape, color = color) { inner() }
    }
}

/** A rounded tile with a feature's icon in its color. */
@Composable
fun FeatureTile(icon: ImageVector, color: Color, size: Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.52f)) }
}

/** The main call to action: a pill with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
    colors: List<Color> = listOf(Brand.Coral, Color(0xFFFF7A59)),
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height).pressScale(source),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        interactionSource = source,
    ) {
        // The surface passes its size down (propagateMinConstraints): the gradient fills it.
        Box(
            Modifier.background(Brush.horizontalGradient(if (enabled) colors else colors.map { it.copy(alpha = 0.35f) })),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 20.dp)) {
                if (icon != null) {
                    Icon(icon, null, tint = Brand.Ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            }
        }
    }
}

/** Progress as a timeline: a track that fills with the brand colors, a playhead at its end. */
@Composable
fun TimelineProgress(progress: Float, modifier: Modifier = Modifier, colors: List<Color> = listOf(Brand.Coral, Brand.Amber)) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450), label = "progress")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val head = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val h = 8.dp.toPx()
        val y = (size.height - h) / 2
        drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        val w = size.width * p
        if (w > 0f) {
            val x0 = if (rtl) size.width - w else 0f
            drawRoundRect(Brush.horizontalGradient(if (rtl) colors.reversed() else colors, startX = x0, endX = x0 + w), Offset(x0, y), Size(w, h), CornerRadius(h / 2))
        }
        val hx = if (rtl) size.width - w else w
        drawRoundRect(head, Offset((hx - 1.5.dp.toPx()).coerceIn(0f, size.width - 3.dp.toPx()), 0f), Size(3.dp.toPx(), size.height), CornerRadius(1.5.dp.toPx()))
    }
}

/** Shows [content] sliding up into place a moment after the screen appears ([index] staggers it). */
@Composable
fun EnterUp(index: Int = 0, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(320, delayMillis = 60 * index)) +
            slideInVertically(tween(420, delayMillis = 60 * index)) { it / 6 },
    ) { content() }
}

/** A small colored dot with a section title. */
@Composable
fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A little timeline (always left to right, like the editor's): a lane per feature with clips in
 * its color and a playhead. The clips grow in and the playhead runs to its place, once.
 */
@Composable
fun MiniTimeline(modifier: Modifier = Modifier, lanes: List<Pair<Color, List<ClosedFloatingPointRange<Float>>>> = DemoLanes) {
    val grow = remember { List(lanes.size) { Animatable(0f) } }
    val head = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        grow.forEachIndexed { i, a -> launch { delay(120L + 90L * i); a.animateTo(1f, tween(650)) } }
        launch { delay(200); head.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessVeryLow)) }
    }
    Canvas(modifier) {
        val n = lanes.size
        val gap = 5.dp.toPx()
        val laneH = (size.height - gap * (n - 1)) / n
        val r = CornerRadius(minOf(laneH / 2, 5.dp.toPx()))
        lanes.forEachIndexed { i, (c, clips) ->
            val y = i * (laneH + gap)
            drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, y), Size(size.width, laneH), r)
            val p = grow[i].value
            for (clip in clips) {
                val x0 = clip.start * size.width
                val w = (clip.endInclusive - clip.start) * size.width * p
                if (w > 0.5f) drawRoundRect(c, Offset(x0, y), Size(w, laneH), r)
            }
        }
        val hx = size.width * 0.56f * head.value
        val hw = 2.5.dp.toPx()
        drawRoundRect(Color.White, Offset(hx - hw / 2, -3.dp.toPx()), Size(hw, size.height + 6.dp.toPx()), CornerRadius(hw / 2))
        drawCircle(Color.White, 4.dp.toPx(), Offset(hx, -3.dp.toPx()))
    }
}

private val DemoLanes = listOf(
    Brand.Coral to listOf(0.03f..0.31f, 0.40f..0.71f),
    Brand.Amber to listOf(0.09f..0.37f, 0.45f..0.63f, 0.69f..0.95f),
    Brand.Cyan to listOf(0.20f..0.27f, 0.57f..0.64f, 0.83f..0.90f),
    Brand.Violet to listOf(0.50f..0.98f),
)

/** A feature's name with a dot of its color. */
@Composable
fun FeatureLabel(text: String, color: Color, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** A small rounded tag (video details and the like). */
@Composable
fun InfoChip(text: String, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = textColor) }
}

/** A check in a gradient circle that pops in once. */
@Composable
fun SuccessBadge(icon: ImageVector, size: Dp = 52.dp) {
    val s = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { s.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = s.value; scaleY = s.value; alpha = ((s.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(size * 0.34f))
            .background(Brush.linearGradient(listOf(Brand.Coral, Brand.Amber))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Brand.Ink, modifier = Modifier.size(size * 0.55f)) }
}

/** A secondary action: an outlined pill with the icon in a feature's color. */
@Composable
fun AccentOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 52.dp,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.height(height).pressScale(source),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accent.copy(alpha = 0.55f)),
        interactionSource = source,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
