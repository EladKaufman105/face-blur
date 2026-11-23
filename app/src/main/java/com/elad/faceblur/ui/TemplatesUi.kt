package com.elad.faceblur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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

private fun categoryIcon(c: TemplateCategory): ImageVector = when (c) {
    TemplateCategory.BLUR -> AppIcons.Face
    TemplateCategory.SUBTITLES -> AppIcons.Subtitles
    TemplateCategory.VOICE -> AppIcons.Voice
    TemplateCategory.CUTS -> AppIcons.Cut
    TemplateCategory.BACKGROUND -> AppIcons.Background
    TemplateCategory.STABILIZE -> AppIcons.Steady
    TemplateCategory.EXPORT -> EditorIcons.ExportIcon
}

/** What a template includes, in a line. */
internal fun templateSummary(tpl: Template): String = when (tpl.group) {
    TemplateGroup.LOOK -> t("מראה: כתוביות וטשטוש")
    TemplateGroup.DESTINATION -> t("יעד: גודל, פורמט ואיכות")
    TemplateGroup.USER -> tpl.categories.joinToString(" · ") { categoryLabel(it) }.ifEmpty { t("ריקה") }
}

/** The names of what's chosen, or a word for none. */
internal fun choiceSummary(store: TemplateStore, choice: TemplateChoice): String {
    val names = listOfNotNull(choice.user, choice.look, choice.dest).mapNotNull { store.find(it) }.map { templateName(it) }
    return if (names.isEmpty()) t("בלי תבנית") else names.joinToString(" + ")
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
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

/** The picker: the user's own templates, the built-in looks (with a picture) and the destinations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplatePicker(store: TemplateStore, choice: TemplateChoice, onChoose: (TemplateChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        if (store.user.isNotEmpty()) {
            Caption(t("התבניות שלי"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                for (tpl in store.user) {
                    FilterChip(
                        selected = choice.user == tpl.id,
                        onClick = { onChoose(if (choice.user == tpl.id) TemplateChoice() else TemplateChoice(user = tpl.id)) },
                        label = { Text(tpl.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Caption(t("מראה"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            for (look in BuiltInTemplates.LOOKS) {
                LookCard(look, choice.look == look.id) {
                    onChoose(choice.copy(user = null, look = if (choice.look == look.id) null else look.id))
                }
            }
        }
        Caption(t("יעד"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            for (d in BuiltInTemplates.DESTINATIONS) {
                FilterChip(
                    selected = choice.dest == d.id,
                    onClick = { onChoose(choice.copy(user = null, dest = if (choice.dest == d.id) null else d.id)) },
                    label = { Text(templateName(d)) },
                )
            }
        }
        if (!choice.isEmpty) {
            TextButton(onClick = { onChoose(TemplateChoice()) }) { Text(t("בלי תבנית")) }
        }
    }
}

/** A built-in look: its subtitles drawn on a small card, and its name. */
@Composable
private fun LookCard(look: Template, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .clickable(onClick = onClick),
        ) {
            val style = look.subs?.style
            if (style != null) StylePreview(style, Modifier.fillMaxWidth().aspectRatio(1.5f))
        }
        Text(
            templateName(look),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** On the Ready screen: the template, folded under one row that says what's chosen. */
@Composable
internal fun TemplatePickerCard(store: TemplateStore, choice: TemplateChoice, onChoose: (TemplateChoice) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ClipCard(contentPadding = 0.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .clickable { open = !open }
                    .padding(horizontal = Space.L, vertical = Space.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(t("תבנית"), MaterialTheme.colorScheme.primary)
                    Text(
                        choiceSummary(store, choice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SectionTitleInset),
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(Space.L)) {
                    Text(
                        t("ההגדרות מתמלאות לפי התבנית. אפשר לשנות אותן אחר כך."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.M),
                    )
                    TemplatePicker(store, choice, onChoose)
                }
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

/** All templates: the user's own (rename, duplicate, edit, delete) and the built-in ones (duplicate). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateManagerSheet(store: TemplateStore, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Template?>(null) }
    var deleting by remember { mutableStateOf<Template?>(null) }
    var editing by remember { mutableStateOf<Template?>(null) }
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
            Text(t("תבניות"), style = AppType.Title)
            Text(
                t("תבנית היא סט של בחירות לסרטון: מראה, יעד ועיבוד. אפשר לבחור אותה במסך הסרטון ובעיבוד כמה סרטונים. תבנית חדשה נשמרת מהעורך."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Caption(t("התבניות שלי"))
            if (store.user.isEmpty()) {
                Text(t("עדיין אין תבניות. בעורך: ייצוא, ואז \"שמור כתבנית\"."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (tpl in store.user) {
                TemplateRow(
                    tpl,
                    actions = listOf(
                        t("שנה שם") to { renaming = tpl },
                        t("שכפל") to { store.duplicate(tpl.id, t("%s (עותק)").format(tpl.name)) },
                        t("ערוך") to { editing = tpl },
                        t("מחק") to { deleting = tpl },
                    ),
                )
            }
            Caption(t("מובנות"))
            for (tpl in BuiltInTemplates.all) {
                TemplateRow(tpl, actions = listOf(t("שכפל") to { store.duplicate(tpl.id, t("%s (עותק)").format(t(tpl.name))) }))
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
    editing?.let { tpl -> TemplateEditSheet(tpl, store) { editing = null } }
}

@Composable
private fun TemplateRow(tpl: Template, actions: List<Pair<String, () -> Unit>>) {
    var menu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(Space.S), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(Radius.Control)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                Alignment.Center,
            ) {
                val style = tpl.subs?.style
                if (tpl.group == TemplateGroup.LOOK && style != null) StylePreview(style, Modifier.size(width = 72.dp, height = 48.dp))
                else Icon(if (tpl.group == TemplateGroup.DESTINATION) EditorIcons.ExportIcon else AppIcons.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f)) {
                Text(templateName(tpl), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(templateSummary(tpl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

// ---------------------------------------------------------------------- editing a template

@Composable
private fun CategoryBlock(c: TemplateCategory, included: Boolean, onIncluded: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(c), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.M))
            Text(categoryLabel(c), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = included, onCheckedChange = onIncluded)
        }
        if (included) content()
    }
}

/** Every part of a template, each category with its own include switch; the values are changed here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditSheet(template: Template, store: TemplateStore, onDismiss: () -> Unit) {
    var tpl by remember { mutableStateOf(template) }
    var name by remember { mutableStateOf(template.name) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.L)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Text(t("עריכת תבנית"), style = AppType.Title)
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(t("שם התבנית")) }, modifier = Modifier.fillMaxWidth())
            Text(
                t("קטגוריה שכבויה לא משנה כלום: ההגדרות הנוכחיות נשארות."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CategoryBlock(TemplateCategory.BLUR, tpl.blur != null, { tpl = tpl.copy(blur = if (it) BlurPart(false, BlurMode.BLUR, 0.6f, 1.6f, false) else null) }) {
                BlurEdit(tpl.blur ?: BlurPart()) { tpl = tpl.copy(blur = it) }
            }
            CategoryBlock(TemplateCategory.SUBTITLES, tpl.subs != null, { tpl = tpl.copy(subs = if (it) (tpl.subs ?: SubsPart(create = false, translate = false, burn = SubLang.ORIGINAL, saveSrt = true, splitWords = 0, splitChars = 0)) else null) }) {
                SubsEdit(tpl.subs ?: SubsPart()) { tpl = tpl.copy(subs = it) }
            }
            CategoryBlock(TemplateCategory.VOICE, tpl.voice != null, { tpl = tpl.copy(voice = if (it) VoicePart(AudioEdit()) else null) }) {
                VoiceEdit(tpl.voice ?: VoicePart(AudioEdit())) { tpl = tpl.copy(voice = it) }
            }
            CategoryBlock(TemplateCategory.CUTS, tpl.cuts != null, { tpl = tpl.copy(cuts = if (it) CutsPart(silences = true, minPauseMs = 800L, fillers = false) else null) }) {
                CutsEdit(tpl.cuts ?: CutsPart(false, 800L, false)) { tpl = tpl.copy(cuts = it) }
            }
            CategoryBlock(TemplateCategory.BACKGROUND, tpl.background != null, { tpl = tpl.copy(background = if (it) Background(fill = BgFill.BLUR) else null) }) {
                BackgroundEdit(tpl.background ?: Background()) { tpl = tpl.copy(background = it) }
            }
            CategoryBlock(TemplateCategory.STABILIZE, tpl.stabilize != null, { tpl = tpl.copy(stabilize = if (it) 2 else null) }) {
                Segments(listOf(0 to t("כבוי"), 1 to t("קל"), 2 to t("בינוני"), 3 to t("חזק")), tpl.stabilize ?: 0) { tpl = tpl.copy(stabilize = it) }
            }
            CategoryBlock(TemplateCategory.EXPORT, tpl.export != null, { tpl = tpl.copy(export = if (it) ExportPart(ExportAspect.ORIGINAL, 0, 0, ExportQuality.ORIGINAL, ExportCodec.SAME, tpl.export?.watermark) else null) }) {
                ExportEdit(tpl.export ?: ExportPart()) { tpl = tpl.copy(export = it) }
            }
            GradientButton(
                t("שמור"),
                {
                    val bg = tpl.background?.let { store.withOwnImage(it, tpl.id) }
                    store.save(tpl.copy(name = name.trim(), background = bg))
                    onDismiss()
                },
                Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun BlurEdit(p: BlurPart, onChange: (BlurPart) -> Unit) {
    val idx = if (p.none == true) 0 else when (p.mode ?: BlurMode.BLUR) {
        BlurMode.BLUR -> 1
        BlurMode.PIXELATE -> 2
        BlurMode.SOLID -> 3
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Segments(listOf(0 to t("בלי"), 1 to t("טשטוש"), 2 to t("פיקסלים"), 3 to t("כיסוי")), idx) { i ->
            onChange(
                if (i == 0) p.copy(none = true)
                else p.copy(none = false, mode = when (i) { 1 -> BlurMode.BLUR; 2 -> BlurMode.PIXELATE; else -> BlurMode.SOLID }),
            )
        }
        if (idx != 0) {
            if (idx != 3) SliderRow(t("עוצמה"), t("עדין"), t("חזק"), p.strength ?: 0.6f, 0f..1f) { onChange(p.copy(strength = it)) }
            SliderRow(t("גודל האזור סביב הפנים"), t("צמוד"), t("רחב"), p.padding ?: 1.6f, 1.2f..2.0f) { onChange(p.copy(padding = it)) }
            SwitchRow(t("זיהוי מורחב"), p.sensitive ?: false) { onChange(p.copy(sensitive = it)) }
        }
    }
}

@Composable
private fun SubsEdit(p: SubsPart, onChange: (SubsPart) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        SwitchRow(t("ליצור כתוביות אוטומטית"), p.create ?: false, t("זיהוי הדיבור במכשיר, לכל סרטון")) { onChange(p.copy(create = it)) }
        if (p.create == true) {
            Caption(t("מודל זיהוי דיבור"))
            Segments(
                listOf(SpeechModel.HEBREW to t("עברית (ivrit.ai)"), SpeechModel.MULTILINGUAL to t("רב-לשוני")),
                p.model ?: SpeechModel.HEBREW,
            ) { onChange(p.copy(model = it, language = it.language)) }
            SwitchRow(t("הוסף תרגום"), p.translate ?: false, t("עברית לאנגלית ואנגלית לעברית, במכשיר")) { onChange(p.copy(translate = it)) }
        }
        Caption(t("סגנון"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            for (preset in SubPresets.all) {
                val sel = p.style?.copy(speakerColors = preset.style.speakerColors) == preset.style
                val shape = RoundedCornerShape(12.dp)
                Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .border(if (sel) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                            .clickable { onChange(p.copy(style = preset.style)) },
                    ) { StylePreview(preset.style, Modifier.fillMaxWidth().aspectRatio(1.5f)) }
                    Text(t(preset.name), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Caption(t("חלוקה לכתוביות"))
        val split = when {
            (p.splitChars ?: 0) > 0 -> 2
            (p.splitWords ?: 0) > 0 -> 1
            else -> 0
        }
        Segments(listOf(0 to t("משפטים שלמים"), 1 to t("עד 4 מילים"), 2 to t("שורה קצרה")), split) { i ->
            onChange(
                when (i) {
                    0 -> p.copy(splitWords = 0, splitChars = 0)
                    1 -> p.copy(splitWords = 4, splitChars = 0)
                    else -> p.copy(splitWords = 0, splitChars = 32)
                },
            )
        }
        Caption(t("כתוביות בסרטון המיוצא"))
        if (p.translate == true) {
            Segments(
                listOf(SubLang.ORIGINAL to t("כמו שנאמר"), SubLang.TRANSLATED to t("תרגום"), SubLang.BOTH to t("שתיהן"), SubLang.NONE to t("בלי")),
                p.burn ?: SubLang.ORIGINAL,
            ) { onChange(p.copy(burn = it)) }
        } else {
            Segments(
                listOf(SubLang.ORIGINAL to t("לצרוב בסרטון"), SubLang.NONE to t("לא לצרוב")),
                if (p.burn == SubLang.NONE) SubLang.NONE else SubLang.ORIGINAL,
            ) { onChange(p.copy(burn = it)) }
        }
        SwitchRow(t("שמור גם קובצי SRT"), p.saveSrt ?: true) { onChange(p.copy(saveSrt = it)) }
    }
}

@Composable
private fun VoiceEdit(p: VoicePart, onChange: (VoicePart) -> Unit) {
    val a = p.audio
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Caption(t("שינוי קול"))
        Chips(VoiceFx.entries.map { it to voiceLabel(it) }, a.voice) { onChange(VoicePart(a.copy(voice = it))) }
        Caption(t("ניקוי קול"))
        Segments(listOf(0 to t("כבוי"), 1 to t("קל"), 2 to t("בינוני"), 3 to t("חזק")), a.denoise) { onChange(VoicePart(a.copy(denoise = it))) }
        SwitchRow(t("שיפור קול"), a.enhance) { onChange(VoicePart(a.copy(enhance = it))) }
        SwitchRow(t("עוצמה לרשתות"), a.loud, t("כמו שטיקטוק, אינסטגרם ויוטיוב מצפים")) { onChange(VoicePart(a.copy(loud = it))) }
    }
}

@Composable
private fun CutsEdit(p: CutsPart, onChange: (CutsPart) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        SwitchRow(t("הסר שתיקות"), p.silences) { onChange(p.copy(silences = it)) }
        if (p.silences) {
            Chips(
                listOf(500L, 800L, 1200L).map { it to (t("שתיקה מעל") + " " + ltr("%.1f".format(it / 1000f)) + t(" שנ׳")) },
                p.minPauseMs,
            ) { onChange(p.copy(minPauseMs = it)) }
        }
        SwitchRow(t("הסר \"אממ\" ו\"אה\""), p.fillers, t("דורש כתוביות עם זמני מילים")) { onChange(p.copy(fillers = it)) }
    }
}

@Composable
private fun BackgroundEdit(b: Background, onChange: (Background) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { Background.importImage(context, uri) }.getOrNull() }
            if (path != null) onChange(b.copy(fill = BgFill.IMAGE, image = path))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Segments(
            listOf(BgFill.NONE to t("ללא"), BgFill.BLUR to t("טשטוש"), BgFill.COLOR to t("צבע"), BgFill.IMAGE to t("תמונה")),
            b.fill,
        ) { f ->
            if (f == BgFill.IMAGE && b.image == null) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else onChange(b.copy(fill = f))
        }
        when (b.fill) {
            BgFill.COLOR -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                for (c in listOf(0xFF00B140.toInt(), 0xFF0F1115.toInt()) + SubPresets.colors) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(Color(c), CircleShape)
                            .border(if (c == b.color) 3.dp else 1.dp, if (c == b.color) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onChange(b.copy(color = c)) },
                    )
                }
            }
            BgFill.IMAGE -> OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (b.image == null) t("בחר תמונה") else t("החלף תמונה"))
            }
            else -> {}
        }
        if (b.fill != BgFill.NONE) {
            Text(
                t("הרקע מוחלף אחרי שהאנשים בסרטון מזוהים (שלב נוסף בעיבוד)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExportEdit(e: ExportPart, onChange: (ExportPart) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Caption(t("פורמט"))
        Segments(
            listOf(ExportAspect.ORIGINAL to t("מקור"), ExportAspect.VERTICAL to "9:16", ExportAspect.SQUARE to "1:1", ExportAspect.PORTRAIT to "4:5"),
            e.aspect ?: ExportAspect.ORIGINAL,
        ) { onChange(e.copy(aspect = it)) }
        Caption(t("רזולוציה"))
        Chips(listOf(0 to t("מקור"), 1080 to "1080p", 720 to "720p", 480 to "480p"), e.shortSide ?: 0) { onChange(e.copy(shortSide = it)) }
        Caption(t("קצב פריימים"))
        Chips(listOf(0 to t("מקור"), 60 to ltr("60 fps"), 30 to ltr("30 fps"), 24 to ltr("24 fps")), e.fps ?: 0) { onChange(e.copy(fps = it)) }
        Caption(t("איכות וגודל קובץ"))
        Segments(
            listOf(ExportQuality.ORIGINAL to t("כמו המקור"), ExportQuality.HIGH to t("גבוהה"), ExportQuality.SAVER to t("חיסכון")),
            e.quality ?: ExportQuality.ORIGINAL,
        ) { onChange(e.copy(quality = it)) }
        Caption(t("קידוד"))
        Segments(
            listOf(ExportCodec.SAME to t("כמו המקור"), ExportCodec.H264 to "H.264", ExportCodec.HEVC to "HEVC"),
            e.codec ?: ExportCodec.SAME,
        ) { onChange(e.copy(codec = it)) }
    }
}
