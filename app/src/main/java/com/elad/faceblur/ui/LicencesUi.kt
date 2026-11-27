package com.elad.faceblur.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.models.LicenceCatalog
import com.elad.faceblur.models.LicenceEntry
import com.elad.faceblur.models.LicenceInfo
import com.elad.faceblur.models.LicenceText
import com.elad.faceblur.models.ModelDownloads
import com.elad.faceblur.models.NetDecision
import com.elad.faceblur.models.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Licence texts are the upstream's own English (and often legal) wording: shown as written, left to right,
// whatever the app's language. What the app says around them is in Hebrew and English like everything else.

/** What a licence screen reads: the text bundled at [path] in the assets (offline); null while it loads, "" when there is none. */
@Composable
private fun rememberAssetText(path: String, load: Boolean = true): String? {
    val ctx = LocalContext.current
    val state = produceState<String?>(null, path, load) {
        if (load) {
            value = if (path.isEmpty()) "" else withContext(Dispatchers.IO) {
                runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
            }
        }
    }
    return state.value
}

@Composable
private fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/**
 * One licence: the model's name, its copyright line and licence name (and the change note), where it
 * was checked, and the full terms behind a toggle ([expanded]: open at first). Read-only, selectable.
 */
@Composable
internal fun LicenceBlock(info: LicenceInfo, expanded: Boolean) {
    var open by rememberSaveable(info.title, info.licenceFile) { mutableStateOf(expanded) }
    val terms = rememberAssetText(info.licenceFile, load = open)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        if (info.title.isNotBlank()) Text(info.title, style = MaterialTheme.typography.titleSmall)
        Ltr {
            SelectionContainer {
                Text(LicenceText.header(info), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (info.source.isNotBlank()) {
            Text(
                t("מקור המידע") + ": " + ltr(info.source),
                style = AppType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (info.licenceFile.isBlank()) {
            Text(t("הנוסח המלא של התנאים נמצא בכתובת המקור."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { open = !open }) {
                Text(if (open) t("הסתר את הרישיון המלא") else t("הצג את הרישיון המלא"))
            }
            if (open) {
                when {
                    terms == null -> Unit
                    terms.isEmpty() -> Text(t("נוסח הרישיון לא נמצא באפליקציה."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Ltr {
                        SelectionContainer {
                            Text(terms.trim(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- consent before a download

/**
 * The consent before a download, for packs whose terms need accepting (registry:
 * requiresAcceptance). One dialog for everything one tap asked for ("Download all" lists each pack
 * that needs it). Put once at the top of the app's screens, with [ModelQuestionHost].
 */
@Composable
internal fun LicenceConsentHost() {
    val ctx = LocalContext.current
    val d = remember { ModelDownloads.get(ctx) }
    val ask = d.consentAsk ?: return
    val packs = ask.needed
    AlertDialog(
        onDismissRequest = d::declineConsent,
        title = {
            Text(if (packs.size == 1) t("רישיון מודל: %s").format(packName(packs[0].id)) else t("רישיונות מודלים"))
        },
        text = { ConsentBody(packs) },
        confirmButton = {
            Button(
                onClick = {
                    // The tap goes on as it was: offline, it says the download starts when a network comes.
                    if (d.acceptConsent() == NetDecision.WAIT_NETWORK) Toast.makeText(ctx, t("אין חיבור. ההורדה תתחיל כשתהיה רשת."), Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.testTag(Tags.LICENCE_ACCEPT),
            ) { Text(t("אשר והורד")) }
        },
        dismissButton = { TextButton(onClick = d::declineConsent, modifier = Modifier.testTag(Tags.LICENCE_CANCEL)) { Text(t("ביטול")) } },
        modifier = Modifier.testTag(Tags.LICENCE_CONSENT),
    )
}

@Composable
private fun ConsentBody(packs: List<Pack>) {
    Column(
        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        val names = packs.flatMap { p -> LicenceText.group(p.files).filter { it.requiresAcceptance }.map { it.licence } }.distinct()
        Text(
            if (packs.size == 1) {
                t("מודל של צד שלישי, ברישיון %s. הוא רץ על המכשיר שלך, ומסופק כמות שהוא, בלי אחריות מכל סוג.").format(ltr(names.joinToString(", ")))
            } else {
                t("מודלים של צד שלישי, כל אחד ברישיון משלו. הם רצים על המכשיר שלך, ומסופקים כמות שהם, בלי אחריות מכל סוג.")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        for (p in packs) {
            if (packs.size > 1) Text(packName(p.id), style = MaterialTheme.typography.titleSmall)
            // Only the files whose terms need accepting are asked about; the rest of the pack's licences are on its screen.
            for (l in LicenceText.group(p.files).filter { it.requiresAcceptance }) LicenceBlock(l, expanded = false)
            if (p !== packs.last()) HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------------- viewer and list

/** The licence of one model or library, read-only: its notice and the full text, which works offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicenceViewerSheet(title: String, infos: List<LicenceInfo>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding()
                .testTag(Tags.LICENCE_VIEWER),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Text(t("רישיון") + ": " + title, style = AppType.Title)
            for ((i, l) in infos.withIndex()) {
                if (i > 0) HorizontalDivider()
                LicenceBlock(l, expanded = infos.size == 1)
            }
        }
    }
}

/**
 * "Open-source licences": the models, then the libraries and fonts the app is made with, each with
 * its licence; a tap opens the full text (bundled, so it works offline). Reached from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSourceLicencesSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val d = remember { ModelDownloads.get(ctx) }
    val components by produceState<List<LicenceEntry>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching { LicenceCatalog.parseComponents(ctx.assets.open(LicenceCatalog.COMPONENTS).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        }
    }
    val mine = components.filter { it.used.isEmpty() || "android" in it.used }
    val models = LicenceCatalog.models(d.store.registry) + mine.filter { it.kind == "model" }
    var open by remember { mutableStateOf<LicenceEntry?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding()
                .testTag(Tags.LICENCES_SCREEN),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Text(t("רישיונות קוד פתוח"), style = AppType.Title)
            Text(
                t("המודלים, הספריות והגופנים ש-ONIT בנוי מהם. נוסחי הרישיונות המלאים נשמרים באפליקציה, ופתוחים גם בלי חיבור."),
                style = AppType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LicenceSection(t("מודלים"), models) { open = it }
            LicenceSection(t("ספריות"), mine.filter { it.kind == "library" }) { open = it }
            LicenceSection(t("גופנים"), mine.filter { it.kind == "font" }) { open = it }
        }
    }
    open?.let { LicenceViewerSheet(it.info.title, listOf(it.info)) { open = null } }
}

@Composable
private fun LicenceSection(title: String, entries: List<LicenceEntry>, onOpen: (LicenceEntry) -> Unit) {
    if (entries.isEmpty()) return
    Text(title, style = AppType.Heading, modifier = Modifier.padding(top = Space.M))
    for (e in entries) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch)
                .clickable { onOpen(e) }
                .padding(vertical = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.info.title, style = MaterialTheme.typography.titleSmall)
                Text(ltr(e.info.licence), style = AppType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space.S))
        }
        HorizontalDivider()
    }
}
