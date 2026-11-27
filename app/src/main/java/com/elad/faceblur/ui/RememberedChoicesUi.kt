package com.elad.faceblur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.i18n.t
import com.elad.faceblur.prefs.AppPrefs

/** Settings > Remembered choices: how many are kept, as the row's second line. */
internal fun rememberedChoicesSummary(prefs: AppPrefs): String {
    @Suppress("UNUSED_VARIABLE") val v = prefs.choicesVersion
    val n = prefs.choices.remembered().size
    return if (n > 0) t("%d בחירות שמורות").format(n) else t("שואלים בכל פעם")
}

/**
 * Every key that has a remembered value, with the value, "Clear" (back to asking each time) and "Clear all". An empty state when
 * nothing is remembered. Labels come from the registry (shared/prefs/choices.json), in the app's language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RememberedChoicesSheet(prefs: AppPrefs, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = prefs.choicesVersion
    val en = prefs.lang == Lang.EN
    val list = prefs.choices.remembered()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.XL)
                .padding(bottom = Space.XL)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Text(t("בחירות שנשמרו"), style = AppType.Title)
            if (list.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Text(t("אין בחירות שמורות"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t("כשתסמנו \"זכור את הבחירה שלי\" בחלון, הבחירה תופיע כאן, ואפשר יהיה לנקות אותה."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    t("בחירות שנשמרו כדי שלא נשאל שוב. ניקוי מחזיר את השאלה."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (e in list) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (en) e.key.en else e.key.he, style = MaterialTheme.typography.titleSmall)
                            Text(if (en) e.value.en else e.value.he, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalButton(onClick = { prefs.choices.clear(e.key.id) }, modifier = Modifier.testTag(Tags.REMEMBERED_CLEAR)) { Text(t("נקה")) }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { prefs.choices.clearAll() }, modifier = Modifier.testTag(Tags.REMEMBERED_CLEAR_ALL)) { Text(t("נקה הכל")) }
            }
        }
    }
}
