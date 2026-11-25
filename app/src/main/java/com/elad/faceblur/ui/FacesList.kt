package com.elad.faceblur.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.edit.FaceThumbs
import com.elad.faceblur.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every face as a tile, in the privacy panel: tap one to turn its blur off or back on, All / None
 * for everyone at once. Each is one undo step. In a session opened without face detection it
 * offers "Find faces" in their place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacesList(ctrl: EditorController) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    val tracks = ctrl.model.tracks
    val progress = ctrl.findFacesProgress
    if (ctrl.session.subtitlesOnly && tracks.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(
                t("הפנים בסרטון עוד לא זוהו. אפשר לזהות ולטשטש אותן עכשיו; הכתוביות, הסאונד וסימן המים יישארו."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
                    Text(t("מזהה פנים… %d%%").format((progress * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ctrl::cancelFindFaces) { Text(t("עצור")) }
                }
            } else {
                FilledTonalButton(onClick = ctrl::findFaces, modifier = Modifier.fillMaxWidth().testTag(Tags.FIND_FACES)) { Text(t("זהה פנים")) }
            }
        }
        return
    }
    if (tracks.isEmpty()) {
        Text(t("לא נמצאו פנים בסרטון."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val context = LocalContext.current
    val ids = tracks.map { it.id }
    // The pictures come from the video, once for the set of blurs.
    val pictures by produceState<Map<Int, Bitmap>>(emptyMap(), ctrl.session, ids) {
        value = withContext(Dispatchers.IO) { FaceThumbs.load(context, ctrl.session.info.uri, ctrl.model, ids) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(
            t("%d מתוך %d מטושטשות").format(ctrl.model.blursOn, tracks.size),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            tracks.forEachIndexed { k, tr ->
                FaceTile(
                    picture = pictures[tr.id],
                    label = if (tr.manual) t("טשטוש %d").format(tr.id) else t("פנים %d").format(k + 1),
                    off = tr.off,
                    manual = tr.manual,
                    onToggle = { ctrl.toggleBlurOff(tr.id) },
                )
            }
        }
        Text(
            t("הקש על פנים כדי לבטל או להחזיר את הטשטוש שלהן."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            FilledTonalButton(onClick = { ctrl.setAllBlursOff(false) }, modifier = Modifier.testTag(Tags.FACE_ALL)) { Text(t("הכל")) }
            FilledTonalButton(onClick = { ctrl.setAllBlursOff(true) }, modifier = Modifier.testTag(Tags.FACE_NONE)) { Text(t("אף אחד")) }
        }
    }
}

@Composable
private fun FaceTile(picture: Bitmap?, label: String, off: Boolean, manual: Boolean, onToggle: () -> Unit) {
    val state = if (off) t("גלוי") else t("מטושטש")
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(Radius.Control))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(2.dp, if (off) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Control))
            .toggleable(value = !off, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label; stateDescription = state }
            .testTag(Tags.FACE_TILE),
    ) {
        if (picture != null) {
            Image(picture.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (off) 0.4f else 1f), contentScale = ContentScale.Crop)
        }
        Text(
            if (off) t("גלוי") else if (manual) t("ידני") else t("מטושטש"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .padding(vertical = 1.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
