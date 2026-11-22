package com.elad.faceblur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.BgKey
import com.elad.faceblur.i18n.t
import com.elad.faceblur.subs.SubPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Background removal: keep the people (found on the device) or everything but a green / blue
 * screen, and put a blur, a color or a picture behind. Seen live in the player.
 */
@Composable
fun BackgroundPanel(ctrl: EditorController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = ctrl.background
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { Background.importImage(context, uri) }.getOrNull() }
            if (path != null) ctrl.changeBackground(ctrl.background.copy(fill = BgFill.IMAGE, image = path))
            else ctrl.notice = t("לא הצלחתי לקרוא את הקובץ")
        }
    }
    fun pickImage() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            PanelHeader(t("רקע"), Feature.BACKGROUND.color, { ctrl.bgPanel = false })
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(panelBody(300.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(t("מה נשאר בתמונה"))
                Segments(
                    listOf(BgKey.PERSON to t("אנשים"), BgKey.GREEN to t("מסך ירוק"), BgKey.BLUE to t("מסך כחול")),
                    b.key,
                ) { ctrl.changeBackground(b.copy(key = it)) }
                if (b.key == BgKey.PERSON) {
                    val p = ctrl.segmentProgress
                    when {
                        p != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("מזהה אנשים… %d%%").format((p * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = ctrl::cancelFindPeople) { Text(t("ביטול")) }
                            }
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                        !ctrl.masksReady -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                t("קודם מזהים את האנשים בכל פריים: מעבר אחד על הסרטון, על המכשיר."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(onClick = ctrl::findPeople) {
                                Icon(AppIcons.Person, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("זהה אנשים"))
                            }
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("האנשים זוהו בכל הסרטון"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = ctrl::findPeople) { Text(t("זהה שוב")) }
                        }
                    }
                } else {
                    // How far from the screen's color still counts as screen.
                    var tol by remember(b.key) { mutableFloatStateOf(b.tolerance) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("רגישות"), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = tol,
                            onValueChange = { tol = it; ctrl.changeBackground(ctrl.background.copy(tolerance = it)) },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
                }
                Caption(t("מה מאחור"))
                Segments(
                    listOf(BgFill.NONE to t("ללא"), BgFill.BLUR to t("טשטוש"), BgFill.COLOR to t("צבע"), BgFill.IMAGE to t("תמונה")),
                    b.fill,
                ) { f ->
                    if (f == BgFill.IMAGE && b.image == null) pickImage() else ctrl.changeBackground(b.copy(fill = f))
                }
                when (b.fill) {
                    BgFill.COLOR -> Swatches(listOf(0xFF00B140.toInt(), 0xFF0F1115.toInt()) + SubPresets.colors, b.color) {
                        ctrl.changeBackground(b.copy(color = it))
                    }
                    BgFill.IMAGE -> {
                        val img by produceState<ImageBitmap?>(null, b.image) {
                            value = withContext(Dispatchers.IO) {
                                b.image?.let { path -> runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3F4B))) {
                                img?.let { Image(it, null, Modifier.size(72.dp, 48.dp), contentScale = ContentScale.Crop) }
                            }
                            OutlinedButton(onClick = ::pickImage) { Text(t("החלף תמונה")) }
                        }
                    }
                    else -> {}
                }
                if (b.key == BgKey.PERSON && b.fill != BgFill.NONE && !ctrl.masksReady && ctrl.segmentProgress == null) {
                    Text(
                        t("הרקע יוחלף אחרי שהאנשים יזוהו."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
