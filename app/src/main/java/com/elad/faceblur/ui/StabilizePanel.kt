package com.elad.faceblur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.i18n.t
import kotlin.math.roundToInt

/**
 * Steadying the camera: off, light, medium or strong. Stronger is steadier and crops more of
 * the edges. Works on any video: without face detection the camera's motion is measured first.
 */
@Composable
fun StabilizePanel(ctrl: EditorController) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            PanelHeader(t("ייצוב"), MaterialTheme.colorScheme.onSurface, { ctrl.stabilizePanel = false })
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(panelBody(220.dp, upTo = true))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Segments(
                    listOf(0 to t("כבוי"), 1 to t("קל"), 2 to t("בינוני"), 3 to t("חזק")),
                    ctrl.stabilizeLevel,
                ) { ctrl.chooseStabilize(it) }
                val p = ctrl.stabilizeProgress
                val zoom = ctrl.stabilizeZoomPercent
                when {
                    p != null -> {
                        Text(t("מודד את תנועת המצלמה… %d%%").format((p * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                    ctrl.stabilize && zoom != null -> Text(
                        t("התמונה מוגדלת ב-%d%% כדי להסתיר את השוליים. נראה מיד בנגן.").format(zoom),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        t("קל: רעידות קטנות, כמעט בלי חיתוך. חזק: הליכה וצילום ביד, עם יותר חיתוך של השוליים."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
