package com.elad.faceblur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.SubFont
import kotlin.math.roundToInt

/**
 * A variable font's own controls: weight (any variable font) and roundness (Allo). Changes are
 * reported when a slider is let go: subtitles and the watermark are redrawn per change.
 *
 * @param weight 0 for the font's default.
 */
@Composable
internal fun FontAxes(font: SubFont, weight: Int, round: Int, onChange: (weight: Int, round: Int) -> Unit) {
    if (!font.variable) return
    val w = font.weightOf(weight)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption(t("עובי"))
        StepSlider(w.toFloat(), font.minWeight.toFloat()..font.maxWeight.toFloat(), (font.maxWeight - font.minWeight) / 50 - 1, { "${it.roundToInt()}" }) {
            onChange(it.roundToInt(), round)
        }
        if (font.rounded) {
            Caption(t("עיגול"))
            StepSlider(round.toFloat(), 0f..100f, 19, { ltr("${it.roundToInt()}%") }) { onChange(w, it.roundToInt()) }
        }
    }
}

@Composable
private fun StepSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: (Float) -> String, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(v, { v = it }, Modifier.weight(1f), valueRange = range, steps = steps.coerceAtLeast(0), onValueChangeFinished = { onDone(v) })
        Text(
            label(v),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(start = 6.dp),
        )
    }
}
