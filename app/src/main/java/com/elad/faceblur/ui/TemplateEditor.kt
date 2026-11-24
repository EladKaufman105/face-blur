package com.elad.faceblur.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elad.faceblur.audio.AudioEdit
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.edit.ExportAspect
import com.elad.faceblur.edit.SplitRule
import com.elad.faceblur.edit.SubLang
import com.elad.faceblur.effects.BgFill
import com.elad.faceblur.effects.BgKey
import com.elad.faceblur.effects.Background
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.ExportCodec
import com.elad.faceblur.pipeline.ExportOptions
import com.elad.faceblur.pipeline.ExportQuality
import com.elad.faceblur.pipeline.Watermark
import com.elad.faceblur.subs.SpeechModel
import com.elad.faceblur.subs.SubPresets
import com.elad.faceblur.subs.SubtitleStyle
import com.elad.faceblur.template.BlurPart
import com.elad.faceblur.template.BuiltInTemplates
import com.elad.faceblur.template.CutsPart
import com.elad.faceblur.template.ExportPart
import com.elad.faceblur.template.SubsPart
import com.elad.faceblur.template.Template
import com.elad.faceblur.template.TemplateCategory
import com.elad.faceblur.template.TemplateSettings
import com.elad.faceblur.template.TemplateStore
import com.elad.faceblur.template.VoicePart
import com.elad.faceblur.template.applyAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun categoryIcon(c: TemplateCategory): ImageVector = when (c) {
    TemplateCategory.BLUR -> AppIcons.Face
    TemplateCategory.SUBTITLES -> AppIcons.Subtitles
    TemplateCategory.VOICE -> AppIcons.Voice
    TemplateCategory.CUTS -> AppIcons.Cut
    TemplateCategory.BACKGROUND -> AppIcons.Background
    TemplateCategory.STABILIZE -> AppIcons.Steady
    TemplateCategory.EXPORT -> EditorIcons.ExportIcon
}

/** [c] of this template: off (null), or on with [stash]'s version of it (what was there before it was switched off) or a plain start. */
private fun Template.withCategory(c: TemplateCategory, on: Boolean, stash: Template): Template = when (c) {
    TemplateCategory.BLUR -> copy(blur = if (on) stash.blur ?: BlurPart(false, BlurMode.BLUR, 0.6f, 1.6f, false) else null)
    TemplateCategory.SUBTITLES -> copy(
        subs = if (on) stash.subs ?: SubsPart(create = false, translate = false, burn = SubLang.ORIGINAL, saveSrt = true, splitWords = 0, splitChars = 0) else null,
    )
    TemplateCategory.VOICE -> copy(voice = if (on) stash.voice ?: VoicePart(AudioEdit()) else null)
    TemplateCategory.CUTS -> copy(cuts = if (on) stash.cuts ?: CutsPart(silences = true, minPauseMs = 800L, fillers = false) else null)
    TemplateCategory.BACKGROUND -> copy(background = if (on) stash.background ?: Background(fill = BgFill.BLUR) else null)
    TemplateCategory.STABILIZE -> copy(stabilize = if (on) stash.stabilize ?: 2 else null)
    TemplateCategory.EXPORT -> copy(
        export = if (on) stash.export ?: ExportPart(ExportAspect.ORIGINAL, 0, 0, ExportQuality.ORIGINAL, ExportCodec.SAME, null) else null,
    )
}

/** This template's parts, with [o]'s where [o] has one: the memory of what was switched off. */
private fun Template.rememberingPartsOf(o: Template): Template = copy(
    blur = o.blur ?: blur, subs = o.subs ?: subs, voice = o.voice ?: voice, cuts = o.cuts ?: cuts,
    background = o.background ?: background, stabilize = o.stabilize ?: stabilize, export = o.export ?: export,
)

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
            Switch(checked = included, onCheckedChange = onIncluded, modifier = Modifier.testTag(Tags.templateCategory(c)))
        }
        if (included) content()
    }
}

/**
 * The template editor, a page of its own: the name, a live preview that follows every change,
 * and one block per category (its include switch, then every property the category keeps, in
 * the app's own controls). A built-in look is edited the same way; saving it keeps the user's
 * version (same id) and "restore the original" brings the shipped one back.
 *
 * [onSaved] is told the saved or restored template (so a screen using it takes it again);
 * [onClose] leaves the editor.
 */
@Composable
internal fun TemplateEditorScreen(store: TemplateStore, template: Template, onClose: () -> Unit, onSaved: (Template) -> Unit) {
    val shipped = BuiltInTemplates.byId(template.id)
    var base by remember(template.id) { mutableStateOf(template) }
    var draft by remember(template.id) { mutableStateOf(template) }
    var baseName by remember(template.id) { mutableStateOf(templateName(template)) }
    var nameText by remember(template.id) { mutableStateOf(templateName(template)) }
    // The parts switched off while editing, to bring back with their values when switched on again.
    var stash by remember(template.id) { mutableStateOf(template) }
    var askLeave by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val dirty = draft != base || nameText != baseName
    val canSave = dirty && nameText.isNotBlank()

    // A built-in keeps its Hebrew name (shown translated) unless the user typed another.
    fun nameToSave(): String = if (draft.builtIn && nameText.trim() == templateName(draft)) draft.name else nameText.trim().take(60)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var styleOpen by rememberSaveable { mutableStateOf(false) }
    // A logo picked here waits in a file of its own: the template's logo (and the app's) change only on save.
    val stage = remember(template.id) { java.io.File(context.cacheDir, "wm_stage_${template.id}.png") }
    DisposableEffect(stage) { onDispose { stage.delete() } }

    val save: () -> Unit = {
        if (!saving) {
            saving = true
            val snapshot = draft
            val name = nameToSave()
            scope.launch {
                val out = withContext(Dispatchers.IO) {
                    runCatching {
                        val bg = snapshot.background?.let { store.withOwnImage(it, snapshot.id) }
                        val own = store.withOwnLogo(snapshot.copy(name = name, background = bg))
                        if (own.builtIn) store.saveBuiltIn(own) else store.save(own)
                        own
                    }.getOrNull()
                }
                saving = false
                if (out == null) {
                    saveFailed = true
                } else {
                    onSaved(out)
                    onClose()
                }
            }
        }
    }
    val leave: () -> Unit = { if (dirty) { askLeave = true } else { onClose() } }
    // Back closes the subtitle style panel first.
    BackHandler { if (styleOpen) styleOpen = false else leave() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.S), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave, modifier = Modifier.testTag(Tags.TEMPLATE_EDITOR_CLOSE)) { Icon(Icons.Filled.Close, t("סגור")) }
                Text(t("עריכת תבנית"), style = AppType.Title, modifier = Modifier.weight(1f))
                if (shipped != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, t("אפשרויות")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t("שחזר את המקור")) },
                                enabled = store.isOverridden(draft.id),
                                onClick = { menu = false; askRestore = true },
                            )
                        }
                    }
                }
                GradientButton(t("שמור"), save, Modifier.testTag(Tags.TEMPLATE_EDITOR_SAVE), height = 44.dp, enabled = canSave && !saving)
                Spacer(Modifier.width(Space.S))
            }
            EditorPreview(store, draft)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.L)
                    .padding(bottom = Space.L)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                OutlinedTextField(
                    nameText, { nameText = it }, singleLine = true, label = { Text(t("שם התבנית")) },
                    modifier = Modifier.fillMaxWidth().testTag(Tags.TEMPLATE_EDITOR_NAME),
                )
                if (shipped != null) {
                    Text(
                        if (store.isOverridden(draft.id)) t("תבנית מובנית, ערוכה: השינויים שלך מחליפים אותה בכל מקום. \"שחזר את המקור\" בתפריט מחזיר את המקורית.")
                        else t("תבנית מובנית: אחרי השמירה, הגרסה שלך מחליפה אותה בכל מקום. אפשר תמיד לשחזר את המקור."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    t("קטגוריה שכבויה לא משנה כלום: ההגדרות הנוכחיות נשארות."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (c in TemplateCategory.entries) {
                    CategoryBlock(
                        c, draft.includes(c),
                        onIncluded = { on ->
                            if (!on) stash = stash.rememberingPartsOf(draft)
                            draft = draft.withCategory(c, on, stash)
                        },
                    ) {
                        when (c) {
                            TemplateCategory.BLUR -> BlurEdit(draft.blur ?: BlurPart()) { draft = draft.copy(blur = it) }
                            TemplateCategory.SUBTITLES -> SubsEdit(draft.subs ?: SubsPart(), styleOpen, { styleOpen = it }) { draft = draft.copy(subs = it) }
                            TemplateCategory.VOICE -> VoiceEdit(draft.voice ?: VoicePart(AudioEdit())) { draft = draft.copy(voice = it) }
                            TemplateCategory.CUTS -> CutsEdit(draft.cuts ?: CutsPart(false, 800L, false)) { draft = draft.copy(cuts = it) }
                            TemplateCategory.BACKGROUND -> BackgroundEdit(draft.background ?: Background()) { draft = draft.copy(background = it) }
                            TemplateCategory.STABILIZE -> StabilizeEdit(draft.stabilize ?: 0) { draft = draft.copy(stabilize = it) }
                            TemplateCategory.EXPORT -> ExportEdit(draft.export ?: ExportPart(), stage) { draft = draft.copy(export = it) }
                        }
                    }
                }
            }
        }
    }

    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { saveFailed = false },
            title = { Text(t("התבנית לא נשמרה")) },
            text = { Text(t("לא הצלחנו לשמור את התבנית. נסה שוב.")) },
            confirmButton = { TextButton(onClick = { saveFailed = false }) { Text(t("סגור")) } },
        )
    }
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(t("לשמור את השינויים?")) },
            text = { Text(t("עשית שינויים בתבנית שלא נשמרו.")) },
            confirmButton = {
                TextButton(onClick = { askLeave = false; save() }, enabled = nameText.isNotBlank()) { Text(t("שמור")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false }) { Text(t("המשך לערוך")) }
                    TextButton(onClick = { askLeave = false; onClose() }) { Text(t("בטל שינויים"), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (askRestore && shipped != null) {
        RestoreOriginalDialog(
            templateName(shipped),
            onConfirm = {
                askRestore = false
                store.restoreBuiltIn(shipped.id)
                val back = store.find(shipped.id) ?: shipped
                draft = back; base = back; stash = back
                baseName = templateName(back); nameText = baseName
                onSaved(back)
            },
            onDismiss = { askRestore = false },
        )
    }
}

/** The look of the template as it is now (over the default settings): the blur, the subtitles, the frame and the watermark. */
@Composable
private fun EditorPreview(store: TemplateStore, draft: Template) {
    val spec = remember(draft) { applyAll(TemplateSettings(), listOf(store.usable(draft))).preview(null, 1.4f) }
    // Dragging a slider changes it many times a second: draw once it settles.
    val shown by produceState(spec, spec) {
        delay(120)
        value = spec
    }
    val height = if (isWide()) 240.dp else 188.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.S), contentAlignment = Alignment.Center) {
        PreviewStage(shown, null, maxWidth, height)
    }
}

// ---------------------------------------------------------------------- the categories

@Composable
private fun BlurEdit(p: BlurPart, onChange: (BlurPart) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        SwitchRow(t("טשטוש פנים"), p.none != true, t("כבוי: הפנים לא מטושטשות (רק עיבוד אחר)")) { onChange(p.copy(none = !it)) }
        if (p.none != true) {
            BlurLookControls(
                p.mode ?: BlurMode.BLUR, p.strength ?: 0.6f, p.padding ?: 1.6f,
                onMode = { onChange(p.copy(mode = it)) },
                onStrength = { onChange(p.copy(strength = it)) },
                onPadding = { onChange(p.copy(padding = it)) },
            )
            SwitchRow(t("זיהוי מורחב"), p.sensitive ?: false) { onChange(p.copy(sensitive = it)) }
        }
    }
}

@Composable
private fun SubsEdit(p: SubsPart, styleOpen: Boolean, onStyleOpen: (Boolean) -> Unit, onChange: (SubsPart) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        SwitchRow(t("ליצור כתוביות אוטומטית"), p.create ?: false, t("זיהוי הדיבור במכשיר, לכל סרטון")) { onChange(p.copy(create = it)) }
        if (p.create == true) {
            Caption(t("מודל זיהוי דיבור"))
            Segments(
                listOf(SpeechModel.HEBREW to t("עברית (ivrit.ai)"), SpeechModel.MULTILINGUAL to t("רב-לשוני")),
                p.model ?: SpeechModel.HEBREW,
            ) { onChange(p.copy(model = it, language = null)) }
            Caption(t("שפת הדיבור"))
            // The Hebrew model speaks Hebrew only: the other languages are for the multilingual one.
            val languages = listOf<Pair<String?, String>>(null to t("לפי המודל"), "he" to t("עברית")) +
                (if ((p.model ?: SpeechModel.HEBREW) == SpeechModel.HEBREW) emptyList<Pair<String?, String>>()
                else listOf<Pair<String?, String>>("en" to t("אנגלית"), "auto" to t("זיהוי אוטומטי")))
            Chips(languages, p.language) { onChange(p.copy(language = it)) }
            SwitchRow(t("הוסף תרגום"), p.translate ?: false, t("עברית לאנגלית ואנגלית לעברית, במכשיר")) { onChange(p.copy(translate = it)) }
        }
        // The same style panel as the editor's: styles, fonts (imported too), colors, effect, size and position.
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).clickable { onStyleOpen(!styleOpen) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("עיצוב הכתוביות"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(if (styleOpen) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (styleOpen) {
            Box(Modifier.clip(MaterialTheme.shapes.medium)) {
                SubtitleStylePanel(p.style ?: SubtitleStyle(), { onChange(p.copy(style = it)) }) { onStyleOpen(false) }
            }
        }
        SplitControls(SplitRule(p.splitWords ?: 0, p.splitChars ?: 0)) { r -> onChange(p.copy(splitWords = r.words, splitChars = r.chars)) }
        SubtitleExportOptions(
            ExportOptions(subs = p.burn ?: SubLang.ORIGINAL, saveSrt = p.saveSrt ?: true),
            { o -> onChange(p.copy(burn = o.subs, saveSrt = o.saveSrt)) },
            p.translate == true,
        )
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
        Caption(t("מה נשאר בתמונה"))
        Segments(
            listOf(BgKey.PERSON to t("אנשים"), BgKey.GREEN to t("מסך ירוק"), BgKey.BLUE to t("מסך כחול")),
            b.key,
        ) { onChange(b.copy(key = it)) }
        if (b.key != BgKey.PERSON) {
            SliderRow(t("רגישות"), t("צמוד"), t("רחב"), b.tolerance, 0f..1f) { onChange(b.copy(tolerance = it)) }
        }
        Caption(t("מה מאחור"))
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
        if (b.fill != BgFill.NONE && b.key == BgKey.PERSON) {
            Text(
                t("הרקע מוחלף אחרי שהאנשים בסרטון מזוהים (שלב נוסף בעיבוד)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StabilizeEdit(level: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Segments(listOf(0 to t("כבוי"), 1 to t("קל"), 2 to t("בינוני"), 3 to t("חזק")), level, onChange)
        Text(
            t("קל: רעידות קטנות, כמעט בלי חיתוך. חזק: הליכה וצילום ביד, עם יותר חיתוך של השוליים."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportEdit(e: ExportPart, logoStage: java.io.File, onChange: (ExportPart) -> Unit) {
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
        // The watermark's own controls (the editor's): off leaves the watermark as it is.
        val wm = e.watermark
        SwitchRow(t("סימן מים"), wm != null, t("כבוי: סימן המים נשאר כמו שהוא")) { onChange(e.copy(watermark = if (it) Watermark() else null)) }
        if (wm != null) WatermarkControls(wm, { onChange(e.copy(watermark = it)) }, ownLogo = logoStage)
    }
}
