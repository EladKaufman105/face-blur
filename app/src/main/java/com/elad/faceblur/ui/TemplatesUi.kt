package com.elad.faceblur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.key
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.template.BlurPart
import com.elad.faceblur.template.BuiltInTemplates
import com.elad.faceblur.template.CutsPart
import com.elad.faceblur.template.ExportPart
import com.elad.faceblur.template.SubsPart
import com.elad.faceblur.template.Template
import com.elad.faceblur.template.TemplateCategory
import com.elad.faceblur.template.TemplateChoice
import com.elad.faceblur.template.TemplateGroup
import com.elad.faceblur.template.TemplateStore
import com.elad.faceblur.template.TemplateSettings
import com.elad.faceblur.template.applyAll
import com.elad.faceblur.template.ids
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import com.elad.faceblur.template.VoicePart
import com.elad.faceblur.audio.AudioEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A template's name as shown: the built-in ones are translated. */
internal fun templateName(tpl: Template): String = if (tpl.builtIn) t(tpl.name) else tpl.name

internal fun categoryLabel(c: TemplateCategory): String = when (c) {
    TemplateCategory.BLUR -> t("טשטוש פנים")
    TemplateCategory.SUBTITLES -> t("כתוביות")
    TemplateCategory.VOICE -> t("קול")
    TemplateCategory.CUTS -> t("חיתוכים")
    TemplateCategory.BACKGROUND -> t("רקע")
    TemplateCategory.STABILIZE -> t("ייצוב")
    TemplateCategory.EXPORT -> t("ייצוא")
}

/** What a template includes, in a line (a built-in look as shipped has its own line). */
internal fun templateSummary(tpl: Template): String =
    if (tpl.group == TemplateGroup.LOOK && tpl == BuiltInTemplates.byId(tpl.id)) t("עיצוב כתוביות וטשטוש, ויוצר כתוביות")
    else tpl.categories.joinToString(" · ") { categoryLabel(it) }.ifEmpty { t("ריקה") }

/** The name of what's chosen, or a word for none. */
internal fun choiceSummary(store: TemplateStore, choice: TemplateChoice): String =
    choice.id?.let { store.find(it) }?.let { templateName(it) } ?: t("בלי תבנית")

@Composable
internal fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Space.M))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------- picking a template

/** What the template [id] comes to over [base] (a font that's gone falls back to the default one). */
internal fun previewSettings(store: TemplateStore, base: TemplateSettings, ids: List<String>): TemplateSettings =
    applyAll(base, ids.mapNotNull { store.find(it) }.map { store.usable(it) })

private val CardWidth = 112.dp
private val CardStage = 92.dp

/**
 * The picker: one row of cards you scroll sideways, "no template" first, then the user's own and
 * the built-in looks, each with a preview rendered the way the video will look. The card after
 * the last one showing peeks in from the edge, so it reads as a row that goes on. [frame]: the
 * open video's frame to preview on (null: a sample scene); [sourceAspect]: that video's shape.
 */
@Composable
internal fun TemplatePicker(
    store: TemplateStore,
    choice: TemplateChoice,
    onChoose: (TemplateChoice) -> Unit,
    base: TemplateSettings,
    frame: android.graphics.Bitmap? = null,
    sourceAspect: Float? = null,
    /** Long-pressing a card opens the template in the editor. */
    onEdit: ((Template) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.L),
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        NoTemplateCard(choice.isEmpty) { onChoose(TemplateChoice()) }
        val all = store.user + store.builtIns
        for (tpl in all) key(tpl.id) {
            val s = remember(tpl, base) { previewSettings(store, base, listOf(tpl.id)) }
            ChoiceCard(
                s.preview(sourceAspect, 2f), frame, templateName(tpl), choice.id == tpl.id,
                onLongClick = onEdit?.let { edit -> { edit(tpl) } },
            ) { onChoose(TemplateChoice(tpl.id)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardFrame(selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .size(CardWidth, CardStage + Space.XS * 2)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .combinedClickable(role = androidx.compose.ui.semantics.Role.RadioButton, onLongClick = onLongClick, onClick = onClick)
            .padding(Space.XS),
        Alignment.Center,
    ) {
        content()
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@Composable
private fun CardName(name: String, selected: Boolean) {
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = Space.XS),
    )
}

/** "No template": the first card; nothing is applied. */
@Composable
private fun NoTemplateCard(selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(CardWidth).testTag(Tags.NO_TEMPLATE)) {
        CardFrame(selected, onClick) {
            Icon(Icons.Filled.Close, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardName(t("בלי תבנית"), selected)
    }
}

/** A choice: its preview on a stage, and its name. */
@Composable
private fun ChoiceCard(spec: PreviewSpec, frame: android.graphics.Bitmap?, name: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(CardWidth)) {
        CardFrame(selected, onClick, onLongClick) { PreviewStage(spec, frame, CardWidth - Space.XS * 2 - 2.dp, CardStage) }
        CardName(name, selected)
    }
}

/**
 * On the Ready screen and the batch setup: the template as one card, a row of choices, a line
 * about what the chosen one does, and (when it has subtitles) a switch for creating them.
 * [createSubs] null: the chosen template has nothing to do with subtitles (no switch).
 */
@Composable
internal fun TemplatePickerCard(
    store: TemplateStore,
    choice: TemplateChoice,
    onChoose: (TemplateChoice) -> Unit,
    base: TemplateSettings,
    frame: android.graphics.Bitmap? = null,
    sourceAspect: Float? = null,
    createSubs: Boolean? = null,
    onCreateSubs: (Boolean) -> Unit = {},
    /** Opens a template in the editor (long press on a card, or "Edit" for the chosen one). */
    onEdit: ((Template) -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    ClipCard(contentPadding = 0.dp, modifier = Modifier.testTag(Tags.TEMPLATE_PICKER)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.M), modifier = Modifier.padding(vertical = Space.L)) {
            val tpl = choice.id?.let { store.find(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.L), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(t("תבנית"), Feature.FACES.tint, Modifier.weight(1f))
                if (tpl != null && onEdit != null) TextButton(onClick = { onEdit(tpl) }) { Text(t("ערוך")) }
            }
            TemplatePicker(store, choice, onChoose, base, frame, sourceAspect, onEdit)
            Column(Modifier.padding(horizontal = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(
                    if (tpl == null) t("בלי תבנית: ההגדרות נשארות כמו שהן.") else templateSummary(tpl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (createSubs != null) {
                    SwitchRow(t("ליצור כתוביות אוטומטית"), createSubs, t("זיהוי הדיבור במכשיר"), onCreateSubs)
                }
                footer()
            }
        }
    }
}

// ---------------------------------------------------------------------- saving from the editor

/** "Save as template" in the editor: a name, and which of what's set in the editor goes into it. */
@Composable
internal fun SaveTemplateDialog(inUse: Set<TemplateCategory>, onSave: (String, Set<TemplateCategory>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(inUse) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("שמירה כתבנית")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(t("שם התבנית")) })
                Text(
                    t("מה ייכנס לתבנית: הבחירות של הסרטון הזה, לפי הקטגוריות שנבחרו. מה שלא נכנס לא ישתנה כשמחילים אותה."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (c in TemplateCategory.entries) {
                    SwitchRow(categoryLabel(c), c in keep) { on -> keep = if (on) keep + c else keep - c }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, keep) }, enabled = name.isNotBlank() && keep.isNotEmpty()) { Text(t("שמור")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("ביטול")) } },
    )
}

// ---------------------------------------------------------------------- the manager (Settings)

/**
 * All templates: the user's own (rename, duplicate, edit, delete) and the built-in ones (duplicate),
 * and "New template" ([onNew]: the editor on a template from scratch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateManagerSheet(store: TemplateStore, onEdit: (Template) -> Unit, onNew: () -> Unit, onRestored: (Template) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Template?>(null) }
    var deleting by remember { mutableStateOf<Template?>(null) }
    var restoring by remember { mutableStateOf<Template?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.L)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("תבניות"), style = AppType.Title, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onNew, modifier = Modifier.testTag(Tags.TEMPLATE_NEW)) { Text(t("תבנית חדשה")) }
            }
            Text(
                t("תבנית היא עיצוב ועיבוד לסרטון: כתוביות, טשטוש, קול, חיתוכים וייצוא. אפשר לבחור אותה במסך הסרטון ובעיבוד כמה סרטונים. אפשר ליצור תבנית חדשה כאן או לשמור אותה מהעורך, ולערוך כל תבנית, גם מובנית."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Caption(t("התבניות שלי"))
            if (store.user.isEmpty()) {
                Text(t("עדיין אין תבניות. בעורך: ייצוא, ואז \"שמור כתבנית\"."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (tpl in store.user) key(tpl.id) {
                TemplateRow(
                    tpl,
                    actions = listOf(
                        t("ערוך") to { onEdit(tpl) },
                        t("שנה שם") to { renaming = tpl },
                        t("שכפל") to { store.duplicate(tpl.id, t("%s (עותק)").format(tpl.name)) },
                        t("מחק") to { deleting = tpl },
                    ),
                    onClick = { onEdit(tpl) },
                )
            }
            Caption(t("מובנות"))
            for (tpl in store.builtIns) key(tpl.id) {
                val edited = store.isOverridden(tpl.id)
                TemplateRow(
                    tpl,
                    actions = listOfNotNull(
                        t("ערוך") to { onEdit(tpl) },
                        t("שכפל") to { store.duplicate(tpl.id, t("%s (עותק)").format(templateName(tpl))) },
                        if (edited) t("שחזר את המקור") to { restoring = tpl } else null,
                    ),
                    edited = edited,
                    onClick = { onEdit(tpl) },
                )
            }
        }
    }
    renaming?.let { tpl ->
        var name by remember(tpl.id) { mutableStateOf(tpl.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("שם התבנית")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { store.rename(tpl.id, name); renaming = null }, enabled = name.isNotBlank()) { Text(t("שמור")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("ביטול")) } },
        )
    }
    deleting?.let { tpl ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("למחוק את התבנית?")) },
            text = { Text(t("התבנית \"%s\" תימחק. סרטונים שכבר עובדו לא ישתנו.").format(tpl.name)) },
            confirmButton = { TextButton(onClick = { store.delete(tpl.id); deleting = null }) { Text(t("מחק"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("ביטול")) } },
        )
    }
    restoring?.let { tpl ->
        RestoreOriginalDialog(templateName(tpl), onConfirm = {
            store.restoreBuiltIn(tpl.id)
            restoring = null
            // A screen that has this template chosen takes the shipped one again.
            store.find(tpl.id)?.let(onRestored)
        }, onDismiss = { restoring = null })
    }
}

/** Asks before the user's changes to a built-in look are thrown away. */
@Composable
internal fun RestoreOriginalDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("לשחזר את התבנית המקורית?")) },
        text = { Text(t("השינויים שעשית ב\"%s\" יימחקו, והתבנית תחזור למה שהגיע עם האפליקציה.").format(name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t("שחזר"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("ביטול")) } },
    )
}

@Composable
private fun TemplateRow(tpl: Template, actions: List<Pair<String, () -> Unit>>, edited: Boolean = false, onClick: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(Space.S), verticalAlignment = Alignment.CenterVertically) {
            val settings = remember(tpl) { applyAll(TemplateSettings(), listOf(tpl)) }
            Box(
                Modifier.size(width = 72.dp, height = 64.dp).clip(RoundedCornerShape(Radius.Control)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                Alignment.Center,
            ) { PreviewStage(settings.preview(), null, 68.dp, 60.dp) }
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f)) {
                Text(templateName(tpl), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (edited) t("ערוכה") + " · " else "") + templateSummary(tpl),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, t("אפשרויות")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((label, act) in actions) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; act() })
                    }
                }
            }
        }
    }
}
