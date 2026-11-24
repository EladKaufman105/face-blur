package com.elad.faceblur.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.project.ProjectEntry
import com.elad.faceblur.project.ProjectMeta
import com.elad.faceblur.project.groupByBatch
import com.elad.faceblur.project.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

private const val FOLDED = 4

/**
 * Saved projects on the home screen: tap to keep editing; rename or delete from the menu. The
 * projects of one batch are a single folder (tap to open it and see its videos).
 */
@Composable
fun ProjectsSection(
    projects: List<ProjectMeta>,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
) {
    if (projects.isEmpty()) {
        NoProjects()
        return
    }
    var all by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    var renamingFolder by remember { mutableStateOf<ProjectEntry.Folder<ProjectMeta>?>(null) }
    var deletingFolder by remember { mutableStateOf<ProjectEntry.Folder<ProjectMeta>?>(null) }
    var openFolders by rememberSaveable { mutableStateOf(setOf<String>()) }
    val entries = remember(projects) { groupByBatch(projects, { it.batchId }, { it.updated }, { it.created }) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        SectionHeader(t("הפרויקטים שלי"), t("נשמרים תוך כדי עריכה"), MaterialTheme.colorScheme.primary) {
            if (entries.size > FOLDED) {
                TextButton(onClick = { all = !all }) {
                    Text(if (all) t("הצג פחות") else t("הצג הכל (%d)").format(entries.size))
                }
            }
        }
        for (e in if (all) entries else entries.take(FOLDED)) {
            when (e) {
                is ProjectEntry.Single -> ProjectRow(e.item, onOpen = { onOpen(e.item.id) }, onRename = { renaming = e.item }, onDelete = { deleting = e.item })
                is ProjectEntry.Folder -> {
                    val open = e.batchId in openFolders
                    FolderRow(
                        e, open,
                        onToggle = { openFolders = if (open) openFolders - e.batchId else openFolders + e.batchId },
                        onRename = { renamingFolder = e }, onDelete = { deletingFolder = e },
                    )
                    if (open) {
                        Column(Modifier.padding(start = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                            for (p in e.items) {
                                ProjectRow(p, onOpen = { onOpen(p.id) }, onRename = { renaming = p }, onDelete = { deleting = p })
                            }
                        }
                    }
                }
            }
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
    renamingFolder?.let { f ->
        var name by remember(f.batchId) { mutableStateOf(folderName(f)) }
        AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text(t("שם התיקייה")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRenameFolder(f.batchId, name); renamingFolder = null }, enabled = name.isNotBlank()) { Text(t("שמור")) }
            },
            dismissButton = { TextButton(onClick = { renamingFolder = null }) { Text(t("ביטול")) } },
        )
    }
    deletingFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text(t("למחוק את התיקייה?")) },
            text = {
                Text(t("%d פרויקטים יימחקו (יפונו %s). הסרטונים ששמורים בגלריה, והסרטונים המקוריים, לא יימחקו.").format(f.items.size, sizeText(f.items.sumOf { it.bytes })))
            },
            confirmButton = {
                TextButton(onClick = { onDeleteFolder(f.batchId); deletingFolder = null }) {
                    Text(t("מחק"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text(t("ביטול")) } },
        )
    }
}

/** The folder's name: the one it was given, or the batch's date and time. */
private fun folderName(f: ProjectEntry.Folder<ProjectMeta>): String {
    f.items.firstNotNullOfOrNull { it.batchName }?.let { return it }
    val at = f.items.firstOrNull { it.batchAt > 0 }?.batchAt ?: f.items.minOf { it.created }
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return t("עיבוד") + " " + String.format(Locale.US, "%d.%d.%02d %02d:%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/** A batch's folder: its name, how many videos, and a chevron that opens it. */
@Composable
private fun FolderRow(f: ProjectEntry.Folder<ProjectMeta>, open: Boolean, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    val name = folderName(f)
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = name },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        interactionSource = source,
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.S), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(Radius.Control)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                Alignment.Center,
            ) { Icon(AppIcons.Folder, null, tint = Feature.FACES.tint) }
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    t("%d סרטונים").format(f.items.size),
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(if (open) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        // The row, then what the project has on a line of its own across the card.
        Column(Modifier.padding(Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(Radius.Control))
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
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        whenText(p.updated) + " · " + ltr(durationText(p.durationMs)),
                        style = MaterialTheme.typography.bodySmall.tnum(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            ProjectPills(p)
        }
    }
}

/** One thing a project has, as a pill on its card: [icon] (or none) and [text] in [color]. */
private class Pill(val icon: ImageVector?, val text: String, val color: Color)

/**
 * What a project has, in pills of the tools' colors, on one line if they fit: blurs, subtitles
 * (or "subtitles only"), bleeped words, a changed voice, sound effects, a background, cuts; "no
 * edits yet" if none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectPills(p: ProjectMeta) {
    val subs = Feature.SUBTITLES.tint
    val sound = Feature.SOUND.tint
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pills = ArrayList<Pill>()
    fun count(n: Int, one: String, many: String) = if (n == 1) one else many.format(n)
    // Without face detection (a tool from the home screen) and no blur added by hand: "subtitles
    // only" when it has subtitles, else just what else it has.
    if (p.subtitlesOnly && p.blurs == 0) {
        if (p.cues > 0) pills += Pill(AppIcons.Subtitles, t("כתוביות בלבד · %d").format(p.cues), subs)
    } else {
        if (p.blurs > 0) pills += Pill(AppIcons.Face, count(p.blurs, t("טשטוש אחד"), t("%d טשטושים")), Feature.FACES.tint)
        if (p.cues > 0) pills += Pill(AppIcons.Subtitles, count(p.cues, t("כתובית אחת"), t("%d כתוביות")), subs)
    }
    if (p.bleeped > 0) pills += Pill(AppIcons.Waveform, count(p.bleeped, t("מילה מצופצפת"), t("%d מצופצפות")), subs)
    // "\u200F" (invisible) keeps these apart from "רקע" and "שינוי קול" elsewhere, which read differently in English.
    if (p.voice) pills += Pill(AppIcons.Mic, t("שינוי קול\u200F"), sound)
    if (p.sounds > 0) pills += Pill(AppIcons.Music, count(p.sounds, t("צליל אחד"), t("%d צלילים")), sound)
    if (p.background) pills += Pill(AppIcons.Background, t("רקע\u200F"), Feature.BACKGROUND.tint)
    if (p.removed > 0) pills += Pill(AppIcons.Cut, count(p.removed, t("חיתוך אחד"), t("%d חיתוכים")), muted)
    if (pills.isEmpty()) pills += Pill(null, t("עדיין בלי עריכות"), muted)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (pill in pills) ProjectPill(pill)
    }
}

@Composable
private fun ProjectPill(pill: Pill) {
    val bg = if (MaterialTheme.colorScheme.isLight) 0.11f else 0.16f
    Row(
        Modifier
            .clip(CircleShape)
            .background(pill.color.copy(alpha = bg))
            .border(1.dp, pill.color.copy(alpha = 0.28f), CircleShape)
            .padding(start = if (pill.icon != null) 6.dp else 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pill.icon?.let { Icon(it, null, Modifier.size(13.dp), tint = pill.color) }
        Text(
            pill.text,
            color = pill.color,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium.tnum(),
            maxLines = 1,
        )
    }
}

private fun whenText(ms: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val time = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, c)) return t("היום") + " " + ltr(time)
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(y, c)) return t("אתמול") + " " + ltr(time)
    return ltr(String.format(Locale.US, "%d.%d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100))
}

private fun durationText(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun sizeText(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return ltr(if (mb >= 10) String.format(Locale.US, "%.0f MB", mb) else String.format(Locale.US, "%.1f MB", mb))
}

/**
 * Before anything is saved: where projects will appear, and that they save themselves, so the
 * first one isn't a surprise and nobody looks for a save button.
 */
@Composable
private fun NoProjects() {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        SectionHeader(t("הפרויקטים שלי"), null, MaterialTheme.colorScheme.primary)
        val line = MaterialTheme.colorScheme.outline
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val r = Radius.Card.toPx()
                    drawRoundRect(
                        line,
                        cornerRadius = CornerRadius(r),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                    )
                }
                .padding(Space.L),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Space.M))
            Text(
                t("עדיין אין פרויקטים. כל סרטון שתפתח נשמר כאן תוך כדי עריכה, ואפשר לחזור אליו ולהמשיך מאיפה שעצרת."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
