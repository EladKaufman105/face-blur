package com.elad.faceblur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elad.faceblur.audio.VoiceFx
import com.elad.faceblur.audio.SfxLibrary
import com.elad.faceblur.audio.SfxPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.elad.faceblur.edit.EditorController
import com.elad.faceblur.i18n.t

private fun voiceLabel(v: VoiceFx) = when (v) {
    VoiceFx.NONE -> t("רגיל")
    VoiceFx.DEEP -> t("נמוך")
    VoiceFx.HIGH -> t("גבוה")
    VoiceFx.ROBOT -> t("רובוט")
    VoiceFx.DISGUISE -> t("מוסווה")
    VoiceFx.MUTE -> t("ללא קול")
}

/** The sound effects library: tap one to add it at the playhead, the play button to hear it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SfxTab(ctrl: EditorController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var userVersion by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val id = withContext(Dispatchers.IO) { runCatching { SfxLibrary.import(context, uri) }.getOrNull() }
            importing = false
            userVersion++
            if (id == null) ctrl.notice = t("לא הצלחתי לקרוא את הקובץ")
        }
    }
    val sounds = remember(userVersion) { SfxLibrary.all(context) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(272.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            t("הקש על צליל כדי להוסיף אותו במיקום הנוכחי. ▶ כדי לשמוע."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (snd in sounds) {
                Surface(
                    onClick = { ctrl.addSfx(snd.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                        IconButton(onClick = { SfxPlayer.play(context, snd.id) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.PlayArrow, t("השמע"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(t(snd.name), style = MaterialTheme.typography.labelLarge)
                        if (!snd.builtin) {
                            IconButton(onClick = { SfxLibrary.delete(context, snd.id); userVersion++ }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, t("מחק"), Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            Surface(
                onClick = { if (!importing) picker.launch(arrayOf("audio/*")) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (importing) t("מייבא…") else t("ייבוא צליל"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val n = ctrl.model.sfx.size
        if (n > 0) {
            Text(
                if (n == 1) t("צליל אחד בסרטון. הקש עליו בציר הזמן כדי להזיז, לשנות עוצמה או למחוק.")
                else t("%d צלילים בסרטון. הקש על צליל בציר הזמן כדי להזיז, לשנות עוצמה או למחוק.").format(n),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The sound: a voice change for anonymity and words bleeped out (picked from the subtitles).
 * Heard live in the player and applied at export.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoicePanel(ctrl: EditorController) {
    @Suppress("UNUSED_VARIABLE") val v = ctrl.version
    val a = ctrl.audio
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("סאונד"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { SfxPlayer.stop(); ctrl.voicePanel = false }) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("סיום"))
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = ctrl.soundTab == 0, onClick = { ctrl.soundTab = 0 }, label = { Text(t("אפקטים קוליים")) })
                FilterChip(selected = ctrl.soundTab == 1, onClick = { ctrl.soundTab = 1 }, label = { Text(t("קול וצפצופים")) })
            }
            if (ctrl.soundTab == 0) {
                SfxTab(ctrl)
            } else Column(
                Modifier
                    .fillMaxWidth()
                    .height(272.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(t("שינוי קול"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (fx in VoiceFx.entries) {
                        FilterChip(
                            selected = a.voice == fx,
                            onClick = { ctrl.changeAudio(a.copy(voice = fx)) },
                            label = { Text(voiceLabel(fx)) },
                        )
                    }
                }
                Text(
                    t("שינוי קול מקשה לזהות את הדובר, אבל לא מבטיח אנונימיות מלאה."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Caption(t("צפצוף מילים"))
                val hasCues = ctrl.model.cues.isNotEmpty()
                if (!hasCues) {
                    Text(
                        t("כדי לצפצף מילים צריך קודם כתוביות: הן מסמנות מתי כל מילה נאמרת."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { ctrl.voicePanel = false; ctrl.subsSheet = true }) { Text(t("צור כתוביות")) }
                } else {
                    val n = ctrl.bleepedCount
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (n) {
                                0 -> t("אין מילים מצופצפות")
                                1 -> t("מילה אחת מצופצפת")
                                else -> t("%d מילים מצופצפות").format(n)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (n > 0) TextButton(onClick = ctrl::clearBleeps) { Text(t("נקה")) }
                        FilledTonalButton(onClick = {
                            ctrl.voicePanel = false
                            ctrl.bleepMode = true
                            ctrl.listEditor = true
                        }) {
                            Icon(AppIcons.Mute, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("בחר מילים"))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("במקום המילה"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        FilterChip(selected = a.tone, onClick = { ctrl.changeAudio(a.copy(tone = true)) }, label = { Text(t("צפצוף")) })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = !a.tone, onClick = { ctrl.changeAudio(a.copy(tone = false)) }, label = { Text(t("שקט")) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("הסתר אותן גם בכתוביות"), style = MaterialTheme.typography.bodyMedium)
                            Text("****", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = a.maskWords, onCheckedChange = { ctrl.changeAudio(a.copy(maskWords = it)) })
                    }
                }
            }
        }
    }
}
