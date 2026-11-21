package com.elad.faceblur.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.project.ProjectMeta
import com.elad.faceblur.project.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

private const val FOLDED = 4

/** Saved projects on the home screen: tap to keep editing; rename or delete from the menu. */
@Composable
fun ProjectsSection(
    projects: List<ProjectMeta>,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (projects.isEmpty()) return
    var all by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(t("הפרויקטים שלי"), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            if (projects.size > FOLDED) {
                TextButton(onClick = { all = !all }) {
                    Text(if (all) t("הצג פחות") else t("הצג הכל (%d)").format(projects.size))
                }
            }
        }
        for (p in if (all) projects else projects.take(FOLDED)) {
            ProjectRow(p, onOpen = { onOpen(p.id) }, onRename = { renaming = p }, onDelete = { deleting = p })
        }
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("שם הפרויקט")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(p.id, name); renaming = null }, enabled = name.isNotBlank()) { Text(t("שמור")) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("ביטול")) } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("למחוק את הפרויקט?")) },
            text = { Text(t("יפונו %s. הסרטון המקורי לא יימחק.").format(sizeText(p.bytes))) },
            confirmButton = {
                TextButton(onClick = { onDelete(p.id); deleting = null }) {
                    Text(t("מחק"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("ביטול")) } },
        )
    }
}

@Composable
private fun ProjectRow(p: ProjectMeta, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, p.id, p.updated) {
        value = withContext(Dispatchers.IO) { ProjectStore.loadThumb(context, p.id)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }
    val feature = if (p.subtitlesOnly) Feature.SUBTITLES else Feature.FACES
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = p.name },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        interactionSource = source,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                Alignment.Center,
            ) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
                    ?: Icon(if (p.subtitlesOnly) AppIcons.Subtitles else AppIcons.Face, null, tint = feature.tint)
                // A clip bar in the project's color along the bottom of the thumbnail.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(feature.color),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    whenText(p.updated) + " · " + durationText(p.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    contentText(p),
                    style = MaterialTheme.typography.labelMedium,
                    color = feature.tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, t("אפשרויות")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(t("שנה שם")) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(t("מחק"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun contentText(p: ProjectMeta): String {
    val parts = ArrayList<String>()
    if (p.subtitlesOnly) parts += t("בלי זיהוי פנים")
    else parts += when (p.blurs) {
        0 -> t("בלי טשטושים")
        1 -> t("טשטוש אחד")
        else -> t("%d טשטושים").format(p.blurs)
    }
    if (p.cues > 0) parts += if (p.cues == 1) t("כתובית אחת") else t("%d כתוביות").format(p.cues)
    return parts.joinToString(" · ")
}

private fun whenText(ms: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val time = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, c)) return t("היום") + " " + time
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(y, c)) return t("אתמול") + " " + time
    return String.format(Locale.US, "%d.%d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100)
}

private fun durationText(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun sizeText(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 10) String.format(Locale.US, "%.0f MB", mb) else String.format(Locale.US, "%.1f MB", mb)
}
