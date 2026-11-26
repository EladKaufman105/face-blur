package com.elad.faceblur.ui

import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t
import com.elad.faceblur.models.FailReason
import com.elad.faceblur.models.Installed
import com.elad.faceblur.models.Job
import com.elad.faceblur.models.ModelDownloads
import com.elad.faceblur.models.Net
import com.elad.faceblur.models.NetDecision
import com.elad.faceblur.models.NetworkRule
import com.elad.faceblur.models.PackId
import com.elad.faceblur.models.PackStatus
import com.elad.faceblur.models.TranslationPacks
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------- words

/** A pack's name (also in the download notification). */
fun packName(id: PackId): String = when (id) {
    PackId.FACES -> t("טשטוש פנים")
    PackId.PEOPLE -> t("הסרת רקע")
    PackId.SPEAKERS -> t("זיהוי דוברים")
    PackId.SPEECH_HE -> t("כתוביות בעברית")
    PackId.SPEECH_ANY -> t("כתוביות בכל שפה")
}

/** What the pack is (the model in it). */
private fun packDesc(id: PackId): String = when (id) {
    PackId.FACES -> t("זיהוי פנים: RetinaFace ו-YuNet, גם על ה-GPU")
    PackId.PEOPLE -> t("מוצא את האנשים בכל פריים")
    PackId.SPEAKERS -> t("WeSpeaker, מזהה מי מדבר")
    PackId.SPEECH_HE -> t("Whisper של ivrit.ai, כותב גם דיבור באנגלית")
    PackId.SPEECH_ANY -> t("Whisper רב-לשוני")
}

/** What the pack makes possible. */
private fun packEnables(id: PackId): String = when (id) {
    PackId.FACES -> t("מאפשר: טשטוש פנים, אצוות ותבניות עם טשטוש")
    PackId.PEOPLE -> t("מאפשר: להשאיר את האנשים ולהחליף את הרקע")
    PackId.SPEAKERS -> t("מאפשר: צבע לכל דובר")
    PackId.SPEECH_HE -> t("מאפשר: תמלול על המכשיר")
    PackId.SPEECH_ANY -> t("מאפשר: תמלול בשפות נוספות")
}

private fun packIcon(id: PackId): ImageVector = when (id) {
    PackId.FACES -> AppIcons.Face
    PackId.PEOPLE -> AppIcons.Person
    PackId.SPEAKERS -> AppIcons.Voice
    PackId.SPEECH_HE, PackId.SPEECH_ANY -> AppIcons.Subtitles
}

private fun needTitle(id: PackId): String = when (id) {
    PackId.FACES -> t("כדי לטשטש פנים צריך את מודל זיהוי הפנים")
    PackId.PEOPLE -> t("כדי להסיר רקע צריך את מודל האנשים")
    PackId.SPEAKERS -> t("כדי למצוא דוברים צריך את מודל הקולות")
    PackId.SPEECH_HE, PackId.SPEECH_ANY -> t("כדי ליצור כתוביות צריך את מודל הדיבור")
}

private fun needBody(id: PackId): String = when (id) {
    PackId.FACES -> t("הורדה חד-פעמית. בלי המודל ONIT לא מטשטש פנים, ולא מנסה בשיטה חלשה יותר.")
    PackId.PEOPLE -> t("הורדה חד-פעמית. המודל מוצא את האנשים בכל פריים.")
    PackId.SPEAKERS -> t("הורדה חד-פעמית. כל דובר יקבל צבע משלו.")
    PackId.SPEECH_HE, PackId.SPEECH_ANY -> t("הורדה חד-פעמית, גדולה. מומלץ ב-Wi-Fi.")
}

private fun failText(r: FailReason): String = when (r) {
    FailReason.NO_SPACE -> t("אין מספיק מקום פנוי במכשיר.")
    FailReason.NETWORK -> t("החיבור נקטע. אפשר לנסות שוב, וההורדה תמשיך מאיפה שעצרה.")
    FailReason.CHECKSUM -> t("הקובץ שהורד לא עבר את הבדיקה, ולכן לא נשמר.")
    FailReason.SERVER -> t("השרת לא מסר את הקובץ. נסה שוב מאוחר יותר.")
}

/** A download size: "19.2 MB", "574 MB", "1.15 GB" (left to right in Hebrew too). */
internal fun modelSize(bytes: Long, approx: Boolean = false): String {
    val mb = bytes / 1e6
    val s = when {
        mb >= 1000 -> String.format(Locale.US, "%.2f GB", mb / 1000)
        mb >= 100 -> String.format(Locale.US, "%d MB", mb.roundToInt())
        else -> String.format(Locale.US, "%.1f MB", mb).replace(".0 MB", " MB")
    }
    return ltr((if (approx) "≈" else "") + s)
}

/** The packs in the order the screens list them. */
private val ORDER = listOf(PackId.FACES, PackId.PEOPLE, PackId.SPEAKERS, PackId.SPEECH_HE, PackId.SPEECH_ANY)

@Composable
private fun rememberDownloads(): ModelDownloads {
    val ctx = LocalContext.current
    return remember { ModelDownloads.get(ctx) }
}

/** Asks for packs from a tap; tells the user when it waits for a connection. */
private fun requestPacks(ctx: android.content.Context, d: ModelDownloads, ids: List<PackId>) {
    if (d.request(ids) == NetDecision.WAIT_NETWORK) {
        Toast.makeText(ctx, t("אין חיבור. ההורדה תתחיל כשתהיה רשת."), Toast.LENGTH_LONG).show()
    }
}

/** Translation's pack waits for Wi-Fi by the user's rule (ML Kit does the waiting). */
private fun translationNeedsWifi(d: ModelDownloads): Boolean {
    val rule = d.prefs.rule
    return rule.wifiOnly || (d.net == Net.METERED && TranslationPacks.APPROX_BYTES > rule.askOverBytes)
}

// ---------------------------------------------------------------------- status chip

@Composable
internal fun ModelChip(s: PackStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (val j = s.job) {
        is Job.Queued -> t("בתור") to colors.secondaryContainer
        is Job.Downloading -> t("מוריד %d%%").format((j.fraction * 100).toInt()) to colors.secondaryContainer
        is Job.Verifying -> t("בודק…") to colors.secondaryContainer
        is Job.Waiting -> (if (j.wifi) t("ממתין ל-Wi-Fi") else t("ממתין לרשת")) to colors.tertiaryContainer
        is Job.Failed -> t("ההורדה נכשלה") to colors.errorContainer
        null -> when (s.installed) {
            Installed.READY -> t("במכשיר") to colors.primaryContainer
            Installed.UPDATE_AVAILABLE -> t("עדכון זמין") to colors.tertiaryContainer
            Installed.NEEDS_UPDATE -> t("צריך עדכון") to colors.errorContainer
            Installed.PENDING -> t("עוד לא זמין") to colors.surfaceContainerHighest
            Installed.MISSING -> t("לא הורד") to colors.surfaceContainerHighest
        }
    }
    StatusPill(text, color, ready = s.job == null && s.installed == Installed.READY)
}

@Composable
private fun StatusPill(text: String, color: Color, ready: Boolean = false) {
    Surface(shape = RoundedCornerShape(50), color = color) {
        Row(Modifier.padding(horizontal = Space.S, vertical = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp))
                Spacer(Modifier.width(Space.XS))
            }
            Text(text, style = AppType.Caption, maxLines = 1)
        }
    }
}

/** A bar under a pack that downloads (determinate) or is checked (indeterminate). */
@Composable
private fun JobBar(s: PackStatus) {
    when (val j = s.job) {
        is Job.Downloading -> LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
        is Job.Verifying, is Job.Queued -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> Unit
    }
}

// ---------------------------------------------------------------------- "download to use" card

/**
 * A feature's "download to use" card for model pack [id]: what is missing and one button to get
 * it, then the download's progress, waiting, failure or an update. Shows nothing when the pack is
 * installed and current (or, with [showReady], a short "ready" line).
 */
@Composable
internal fun ModelCard(id: PackId, modifier: Modifier = Modifier, showReady: Boolean = false) {
    val ctx = LocalContext.current
    val d = rememberDownloads()
    val s = d.store.statuses[id] ?: return
    val p = s.pack
    val j = s.job
    if (j == null && s.installed == Installed.READY) {
        if (showReady) {
            Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.S))
                Text(t("המודל מוכן: נבדק ונשמר במכשיר."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    // An installed older version still works: a small offer, not a block.
    if (j == null && s.installed == Installed.UPDATE_AVAILABLE) {
        Row(modifier.fillMaxWidth().testTag(Tags.modelCard(id)), verticalAlignment = Alignment.CenterVertically) {
            Text(t("עדכון זמין למודל"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { requestPacks(ctx, d, listOf(id)) }) { Text(t("עדכן") + " · " + modelSize(p.bytes)) }
        }
        return
    }
    val offline = d.net == Net.NONE
    Card(
        modifier = modifier.fillMaxWidth().testTag(Tags.modelCard(id)).semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            val (icon, title) = when {
                j is Job.Waiting -> AppIcons.Wifi to (if (j.wifi) t("ממתין ל-Wi-Fi") else t("ממתין לחיבור"))
                s.installed == Installed.NEEDS_UPDATE && j == null -> Icons.Filled.Refresh to t("המודל במכשיר ישן מדי לגרסה הזו של ONIT")
                else -> (if (j == null) packIcon(id) else AppIcons.Download) to needTitle(id)
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, Modifier.size(22.dp))
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    val body = when {
                        j is Job.Waiting -> if (j.wifi) t("ההורדה תתחיל כשתתחבר ל-Wi-Fi.") else t("אין חיבור לאינטרנט. ההורדה תתחיל כשתהיה רשת.")
                        j is Job.Failed -> failText(j.reason)
                        j != null -> null
                        s.installed == Installed.PENDING -> t("המודל הזה עוד לא זמין להורדה.")
                        else -> needBody(id)
                    }
                    if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (j) {
                is Job.Queued, is Job.Downloading, is Job.Verifying -> {
                    JobBar(s)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (j) {
                                is Job.Downloading -> t("%s מתוך %s").format(modelSize(j.done), modelSize(j.total))
                                is Job.Verifying -> t("בודק את הקובץ (SHA-256)…")
                                else -> t("בתור")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (j is Job.Downloading) Text(ltr("${(j.fraction * 100).toInt()}%"), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { d.cancel(id) }) { Text(t("ביטול")) }
                }
                is Job.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    if (j.wifi) Button(onClick = { d.downloadNow(id) }) { Text(t("הורד עכשיו")) }
                    TextButton(onClick = { d.cancel(id) }) { Text(t("ביטול")) }
                }
                else -> if (s.installed != Installed.PENDING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                        Button(
                            onClick = { requestPacks(ctx, d, listOf(id)) },
                            enabled = !offline,
                            modifier = Modifier.testTag(Tags.modelDownload(id)),
                        ) {
                            Icon(AppIcons.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.S))
                            Text(
                                when {
                                    j is Job.Failed -> t("נסה שוב")
                                    s.installed == Installed.NEEDS_UPDATE -> t("עדכן") + " · " + modelSize(p.bytes)
                                    else -> t("הורד את המודל · %s").format(modelSize(p.bytes))
                                },
                            )
                        }
                        if (offline) Text(t("אין חיבור לאינטרנט"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- dialogs

/**
 * What every screen shares: "Download on mobile data?" when a tap asks for more than the user's
 * limit, and a short note when a model is ready. Put once at the top of the app's screens.
 */
@Composable
internal fun ModelQuestionHost() {
    val ctx = LocalContext.current
    val d = rememberDownloads()
    d.question?.let { q ->
        AlertDialog(
            onDismissRequest = d::dismissQuestion,
            icon = { Icon(AppIcons.Wifi, null) },
            title = { Text(t("להוריד בחבילת הגלישה?")) },
            text = { Text(t("ההורדה היא %s. ברשת סלולרית זה עלול לעלות כסף.").format(modelSize(q.bytes))) },
            confirmButton = { TextButton(onClick = { d.answer(true) }) { Text(t("הורד עכשיו")) } },
            dismissButton = { TextButton(onClick = { d.answer(false) }) { Text(t("חכה ל-Wi-Fi")) } },
        )
    }
    val ready = d.justReady
    LaunchedEffect(ready) {
        if (ready != null) {
            Toast.makeText(ctx, t("המודל מוכן: %s").format(packName(ready)), Toast.LENGTH_SHORT).show()
            d.justReady = null
        }
    }
}

/** The user's rule for mobile data: Wi-Fi only, or ask above a size they pick. */
@Composable
internal fun NetworkRuleControl(rule: NetworkRule, onChange: (NetworkRule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Caption(t("הורדות ברשת סלולרית"))
        Segments(listOf(true to t("רק Wi-Fi"), false to t("גם בסלולרי")), rule.wifiOnly) { onChange(rule.copy(wifiOnly = it)) }
        Text(
            when {
                rule.wifiOnly -> t("כל הורדה מחכה ל-Wi-Fi.")
                rule.askOverMb == 0 -> t("בסלולרי: שואל לפני כל הורדה.")
                else -> t("בסלולרי: מוריד עד %s בלי לשאול, ושואל לפני קובץ גדול יותר.").format(ltr("${rule.askOverMb} MB"))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!rule.wifiOnly) {
            Chips(NetworkRule.CHOICES_MB.map { it to ltr("$it MB") }, rule.askOverMb) { onChange(rule.copy(askOverMb = it)) }
        }
    }
}

// ---------------------------------------------------------------------- first launch

private enum class SheetView { INTRO, CHOOSE, PROGRESS }

/** A line of the sheet's lists: the ML Kit translation pack, or a model pack. */
private const val TRANSLATE = "translate"

/**
 * The first-launch sheet ("ONIT's models"): download all, or choose packs from a checklist, with
 * the mobile-data rule; then their progress. Closing it in any way marks it seen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelsIntroSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val d = rememberDownloads()
    LaunchedEffect(Unit) { TranslationPacks.refresh() }
    var view by rememberSaveable { mutableStateOf(SheetView.INTRO) }
    val statuses = d.store.statuses
    val available = ORDER.filter { statuses[it]?.installed != Installed.PENDING }
    fun ready(id: PackId) = statuses[id]?.installed == Installed.READY
    val translateReady = TranslationPacks.state == TranslationPacks.State.READY
    var chosen by remember {
        mutableStateOf(available.filter { d.store.registry[it].recommended && !ready(it) }.map { it.key }.toSet() + (if (translateReady) emptySet() else setOf(TRANSLATE)))
    }
    val close = {
        d.prefs.markIntroSeen()
        onDismiss()
    }
    fun start(keys: Set<String>) {
        val ids = available.filter { it.key in keys && !ready(it) }
        if (TRANSLATE in keys && !translateReady) TranslationPacks.download(translationNeedsWifi(d))
        requestPacks(ctx, d, ids)
        d.prefs.markIntroSeen()
        view = SheetView.PROGRESS
    }
    val missingAll = available.filter { !ready(it) }
    val missingBytes = missingAll.sumOf { statuses[it]?.pack?.bytes ?: 0L } + (if (translateReady) 0L else TranslationPacks.APPROX_BYTES)
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding()
                .testTag(Tags.MODELS_SHEET),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            when (view) {
                SheetView.INTRO -> {
                    FeatureTile(AppIcons.CloudDownload, MaterialTheme.colorScheme.primary, 56.dp)
                    Text(t("המודלים של ONIT"), style = AppType.Title)
                    Text(
                        t("הכל רץ על הטלפון שלך. כדי שהאפליקציה תישאר קטנה, המודלים יורדים בנפרד, פעם אחת, מהמאגר של ONIT ב-GitHub."),
                        style = AppType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                        for (id in available) {
                            MiniRow(packIcon(id), packName(id), ready(id), modelSize(statuses[id]?.pack?.bytes ?: 0L))
                        }
                        MiniRow(AppIcons.Translate, t("תרגום עברית ↔ אנגלית"), translateReady, modelSize(TranslationPacks.APPROX_BYTES, approx = true))
                    }
                    NetworkRuleControl(d.prefs.rule, d::updateRule)
                    Button(
                        onClick = { start(available.map { it.key }.toSet() + TRANSLATE) },
                        enabled = missingBytes > 0,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(Tags.MODELS_DOWNLOAD_ALL),
                    ) {
                        Icon(AppIcons.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.S))
                        Text(t("הורד הכל · %s").format(modelSize(missingBytes)))
                    }
                    FilledTonalButton(onClick = { view = SheetView.CHOOSE }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag(Tags.MODELS_CHOOSE)) {
                        Text(t("בחר מודלים"))
                    }
                    TextButton(onClick = close, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(Tags.MODELS_LATER)) { Text(t("אחר כך")) }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AppIcons.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Space.S))
                        Text(
                            t("אפשר להוריד כל מודל גם מתוך הכלי שלו, או בהגדרות › מודלים."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SheetView.CHOOSE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { view = SheetView.INTRO }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("חזרה")) }
                        Text(t("בחר מה להוריד"), style = AppType.Title)
                    }
                    Text(t("מומלצים מסומנים. את המודלים הגדולים כדאי להוריד ב-Wi-Fi."), style = AppType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (id in available) {
                        val st = statuses[id] ?: continue
                        val on = ready(id) || id.key in chosen
                        CheckRow(
                            packIcon(id), packName(id), packDesc(id),
                            (if (ready(id)) t("כבר במכשיר") else modelSize(st.pack.bytes)) + " · " + packEnables(id),
                            recommended = st.pack.recommended && !ready(id),
                            checked = on, enabled = !ready(id),
                        ) { chosen = if (id.key in chosen) chosen - id.key else chosen + id.key }
                    }
                    CheckRow(
                        AppIcons.Translate, t("תרגום עברית ↔ אנגלית"), t("Google ML Kit, על המכשיר"),
                        (if (translateReady) t("כבר במכשיר") else modelSize(TranslationPacks.APPROX_BYTES, approx = true)) + " · " + t("מאפשר: כתוביות בשתי השפות"),
                        recommended = !translateReady, checked = translateReady || TRANSLATE in chosen, enabled = !translateReady,
                    ) { chosen = if (TRANSLATE in chosen) chosen - TRANSLATE else chosen + TRANSLATE }
                    val selIds = available.filter { it.key in chosen && !ready(it) }
                    val selCount = selIds.size + (if (TRANSLATE in chosen && !translateReady) 1 else 0)
                    val selBytes = selIds.sumOf { statuses[it]?.pack?.bytes ?: 0L } + (if (TRANSLATE in chosen && !translateReady) TranslationPacks.APPROX_BYTES else 0L)
                    NetworkRuleControl(d.prefs.rule, d::updateRule)
                    Button(onClick = { start(chosen) }, enabled = selCount > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(AppIcons.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.S))
                        Text(t("הורד %d · %s").format(selCount, modelSize(selBytes)))
                    }
                    TextButton(onClick = close, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t("אחר כך")) }
                }
                SheetView.PROGRESS -> {
                    Text(t("מוריד מודלים"), style = AppType.Title)
                    Text(t("אפשר לסגור. ההורדה ממשיכה ברקע, עם התקדמות בהתראה."), style = AppType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val shown = available.filter { statuses[it]?.job != null || it.key in chosen }
                    for (id in shown) {
                        val st = statuses[id] ?: continue
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(packIcon(id), null, Modifier.size(22.dp))
                            Spacer(Modifier.width(Space.M))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                                Text(packName(id), style = MaterialTheme.typography.titleSmall)
                                Text(modelSize(st.pack.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                JobBar(st)
                            }
                            Spacer(Modifier.width(Space.S))
                            Column(horizontalAlignment = Alignment.End) {
                                ModelChip(st)
                                if (st.busy || st.waiting) TextButton(onClick = { d.cancel(id) }) { Text(t("ביטול")) }
                            }
                        }
                    }
                    val anyBusy = shown.any { statuses[it]?.let { s -> s.busy || s.waiting } == true }
                    if (anyBusy) {
                        FilledTonalButton(onClick = close, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(t("סגור")) }
                    } else {
                        Button(onClick = close, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(t("סיום")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniRow(icon: ImageVector, name: String, ready: Boolean, size: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.M))
        Text(name, style = AppType.Body, modifier = Modifier.weight(1f))
        if (ready) StatusPill(t("כבר במכשיר"), MaterialTheme.colorScheme.primaryContainer, ready = true)
        else Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckRow(
    icon: ImageVector,
    name: String,
    desc: String,
    meta: String,
    recommended: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = Space.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.M))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                if (recommended) {
                    Spacer(Modifier.width(Space.S))
                    StatusPill(t("מומלץ"), MaterialTheme.colorScheme.secondaryContainer)
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(meta, style = AppType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

// ---------------------------------------------------------------------- Settings › Models

/** Settings › Models: what is on the device, the mobile-data rule, and each pack to download, update or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelsSettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val d = rememberDownloads()
    LaunchedEffect(Unit) { TranslationPacks.refresh() }
    val statuses = d.store.statuses
    var deleting by remember { mutableStateOf<PackId?>(null) }
    var deletingTranslation by remember { mutableStateOf(false) }
    val available = ORDER.filter { statuses[it] != null }
    val used = available.filter { statuses[it]?.usable == true }.sumOf { statuses[it]?.pack?.bytes ?: 0L }
    val all = available.filter { statuses[it]?.installed != Installed.PENDING }.sumOf { statuses[it]?.pack?.bytes ?: 0L }
    val missing = available.filter { statuses[it]?.downloadable == true }
    val free = remember(statuses) { runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(0L) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding()
                .testTag(Tags.MODELS_SCREEN),
            verticalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Text(t("מודלים"), style = AppType.Title)
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Row {
                    Text(t("בשימוש: %s").format(modelSize(used)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(t("פנוי במכשיר: %s").format(modelSize(free)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { if (all > 0) (used.toFloat() / all).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(
                    onClick = { requestPacks(ctx, d, missing) },
                    enabled = missing.isNotEmpty() && d.net != Net.NONE,
                ) {
                    Icon(AppIcons.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.S))
                    Text(t("הורד את החסרים · %s").format(modelSize(missing.sumOf { statuses[it]?.pack?.bytes ?: 0L })))
                }
                if (d.net == Net.NONE) Text(t("אין חיבור לאינטרנט"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            NetworkRuleControl(d.prefs.rule, d::updateRule)
            HorizontalDivider()
            for (id in available) {
                val s = statuses[id] ?: continue
                PackRow(s, offline = d.net == Net.NONE,
                    onDownload = { requestPacks(ctx, d, listOf(id)) },
                    onCancel = { d.cancel(id) },
                    onNow = { d.downloadNow(id) },
                    onDelete = { deleting = id },
                )
                HorizontalDivider()
            }
            TranslationRow(d, onDelete = { deletingTranslation = true })
            Text(
                t("כל מודל נבדק (גודל ו-SHA-256) לפני שהוא נשמר, ומודל זיהוי הפנים גם לפני כל שימוש. מודל שנמחק יבקש הורדה מחדש בכלי שלו."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    deleting?.let { id ->
        val s = statuses[id]
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("למחוק את \"%s\"?").format(packName(id))) },
            text = { Text(t("יתפנו %s. כדי להשתמש בכלי שוב צריך להוריד את המודל מחדש.").format(modelSize(s?.pack?.bytes ?: 0L))) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (d.store.delete(id)) {
                        Toast.makeText(ctx, t("נמחק: %s").format(packName(id)), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, t("המודל בשימוש עכשיו. אפשר למחוק אותו כשהעבודה תסתיים."), Toast.LENGTH_LONG).show()
                    }
                }) { Text(t("מחק"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("ביטול")) } },
        )
    }
    if (deletingTranslation) {
        AlertDialog(
            onDismissRequest = { deletingTranslation = false },
            title = { Text(t("למחוק את \"%s\"?").format(t("תרגום עברית ↔ אנגלית"))) },
            text = { Text(t("יתפנו %s. כדי להשתמש בכלי שוב צריך להוריד את המודל מחדש.").format(modelSize(TranslationPacks.APPROX_BYTES, approx = true))) },
            confirmButton = {
                TextButton(onClick = { deletingTranslation = false; TranslationPacks.delete() }) { Text(t("מחק"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTranslation = false }) { Text(t("ביטול")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackRow(s: PackStatus, offline: Boolean, onDownload: () -> Unit, onCancel: () -> Unit, onNow: () -> Unit, onDelete: () -> Unit) {
    val id = s.pack.id
    Row(verticalAlignment = Alignment.Top) {
        FeatureTile(packIcon(id), MaterialTheme.colorScheme.primary, 40.dp)
        Spacer(Modifier.width(Space.M))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(packName(id), style = MaterialTheme.typography.titleSmall)
            Text(packEnables(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                modelSize(s.pack.bytes) + (if (s.usable) " · " + t("גרסה %d").format(s.pack.version) else ""),
                style = AppType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JobBar(s)
            (s.job as? Job.Failed)?.let { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(Space.XS))
                    Text(failText(f.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                when {
                    s.busy || s.waiting -> {
                        TextButton(onClick = onCancel) { Text(t("ביטול")) }
                        if ((s.job as? Job.Waiting)?.wifi == true) FilledTonalButton(onClick = onNow) { Text(t("הורד עכשיו")) }
                    }
                    s.installed == Installed.READY -> OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.XS))
                        Text(t("מחק"))
                    }
                    s.installed == Installed.UPDATE_AVAILABLE || s.installed == Installed.NEEDS_UPDATE -> {
                        FilledTonalButton(onClick = onDownload, enabled = !offline) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.XS))
                            Text(t("עדכן") + " · " + modelSize(s.pack.bytes))
                        }
                        TextButton(onClick = onDelete) { Text(t("מחק"), color = MaterialTheme.colorScheme.error) }
                    }
                    s.installed == Installed.PENDING -> Text(t("המודל הזה עוד לא זמין להורדה."), style = MaterialTheme.typography.bodySmall)
                    else -> FilledTonalButton(onClick = onDownload, enabled = !offline, modifier = Modifier.testTag(Tags.modelDownload(id))) {
                        Icon(AppIcons.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.XS))
                        Text(if (s.job is Job.Failed) t("נסה שוב") else t("הורד"))
                    }
                }
            }
        }
        Spacer(Modifier.width(Space.S))
        ModelChip(s)
    }
}

/** Translation's pack, kept by ML Kit: listed with the models. */
@Composable
private fun TranslationRow(d: ModelDownloads, onDelete: () -> Unit) {
    val st = TranslationPacks.state
    Row(verticalAlignment = Alignment.Top) {
        FeatureTile(AppIcons.Translate, MaterialTheme.colorScheme.primary, 40.dp)
        Spacer(Modifier.width(Space.M))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(t("תרגום עברית ↔ אנגלית"), style = MaterialTheme.typography.titleSmall)
            Text(t("מאפשר: כתוביות בשתי השפות"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(modelSize(TranslationPacks.APPROX_BYTES, approx = true) + " · " + t("מנוהל על ידי ML Kit"), style = AppType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st == TranslationPacks.State.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (st) {
                TranslationPacks.State.READY -> OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.XS))
                    Text(t("מחק"))
                }
                TranslationPacks.State.MISSING, TranslationPacks.State.FAILED, TranslationPacks.State.UNKNOWN ->
                    FilledTonalButton(onClick = { TranslationPacks.download(translationNeedsWifi(d)) }, enabled = d.net != Net.NONE) {
                        Icon(AppIcons.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.XS))
                        Text(t("הורד"))
                    }
                else -> Unit
            }
        }
        Spacer(Modifier.width(Space.S))
        val colors = MaterialTheme.colorScheme
        when (st) {
            TranslationPacks.State.READY -> StatusPill(t("במכשיר"), colors.primaryContainer, ready = true)
            TranslationPacks.State.DOWNLOADING -> StatusPill(t("מוריד…"), colors.secondaryContainer)
            TranslationPacks.State.WAITING_WIFI -> StatusPill(t("ממתין ל-Wi-Fi"), colors.tertiaryContainer)
            TranslationPacks.State.FAILED -> StatusPill(t("ההורדה נכשלה"), colors.errorContainer)
            else -> StatusPill(t("לא הורד"), colors.surfaceContainerHighest)
        }
    }
}
