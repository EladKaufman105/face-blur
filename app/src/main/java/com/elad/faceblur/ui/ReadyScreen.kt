package com.elad.faceblur.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.UiState
import com.elad.faceblur.effects.BlurMode
import com.elad.faceblur.i18n.t
import com.elad.faceblur.pipeline.VideoInfo
import kotlin.math.roundToInt

/**
 * A video was picked: the video, one big button, and saving straight away as the other way.
 * The blur's look and wider detection are folded away (the look can change in the editor too);
 * wider detection is the one choice that has to be made before detection runs.
 */
@Composable
internal fun ReadyContent(vm: MainViewModel, s: UiState.Ready, wide: Boolean, onReplace: () -> Unit) {
    var saveSheet by remember { mutableStateOf(false) }
    val actions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            GradientButton(
                t("טשטש פנים"),
                { vm.review = true; vm.start() },
                Modifier.fillMaxWidth().testTag(Tags.BLUR_FACES),
                icon = AppIcons.Face,
            )
            Text(
                t("הפנים יזוהו ויטושטשו, ואז ייפתח העורך לבדיקה ולתיקון לפני השמירה."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { saveSheet = true }, modifier = Modifier.fillMaxWidth().testTag(Tags.BLUR_AND_SAVE)) {
                Text(t("או: טשטש ושמור ישר, בלי עורך"))
            }
        }
    }
    val picker: @Composable () -> Unit = {
        TemplatePickerCard(vm.templates, vm.templateChoice, vm::chooseTemplates, vm.previewBase(), s.info.thumbnail, s.info.aspect)
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.XL)) {
            Column(Modifier.weight(1.3f)) { EnterUp(0) { VideoCard(s.info, onReplace = onReplace) } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.L)) {
                EnterUp(1) { picker() }
                EnterUp(2) { actions() }
                EnterUp(3) { BlurSettings(vm) }
            }
        }
    } else {
        // The template first: it is what the rest of the screen fills in from.
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            EnterUp(0) { picker() }
            EnterUp(1) { VideoCard(s.info, onReplace = onReplace) }
            EnterUp(2) { actions() }
            EnterUp(3) { BlurSettings(vm) }
        }
    }
    if (saveSheet) {
        ExportSheet(
            s.info, vm.exportOptions, vm::changeExport,
            onExport = { saveSheet = false; vm.review = false; vm.start() },
            onDismiss = { saveSheet = false },
            action = t("טשטש ושמור"),
        )
    }
}

/** The blur's look and wider detection, folded under one row that says what's chosen. */
@Composable
private fun BlurSettings(vm: MainViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.STATE), label = "chevron")
    val look = when (vm.mode) {
        BlurMode.BLUR -> t("טשטוש")
        BlurMode.PIXELATE -> t("פיקסלים")
        BlurMode.SOLID -> t("כיסוי")
    }
    val summary = look + " · " + if (vm.sensitive) t("זיהוי מורחב") else t("זיהוי רגיל")
    ClipCard(contentPadding = 0.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = if (open) t("פתוח") else t("מקופל") }
                    .testTag(Tags.BLUR_SETTINGS)
                    .padding(horizontal = Space.L, vertical = Space.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(t("הגדרות טשטוש"), Feature.FACES.tint)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SectionTitleInset),
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                open,
                enter = expandVertically(tween(Motion.PANEL)) + fadeIn(tween(Motion.CONTENT)),
                exit = shrinkVertically(tween(Motion.STATE)) + fadeOut(tween(Motion.CONTENT)),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.L)) {
                        Text(
                            t("אפשר לשנות את המראה גם אחר כך, בעורך."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BlurLookControls(
                            vm.mode, vm.strength, vm.padding,
                            onMode = { vm.mode = it },
                            onStrength = { vm.strength = it },
                            onPadding = { vm.padding = it },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("זיהוי מורחב"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    t("בודק כל פריים ומטשטש גם פנים שהמערכת פחות בטוחה בהן. מפספס פחות, אבל עלול לטשטש גם דברים מיותרים, ואיטי יותר."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(Space.M))
                            Switch(checked = vm.sensitive, onCheckedChange = { vm.sensitive = it })
                        }
                    }
                }
            }
        }
    }
}

/** The picked video: its first frame, name and details, and a way to pick another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoCard(info: VideoInfo, onReplace: (() -> Unit)?) {
    ClipCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
            info.thumbnail?.let { bmp ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .aspectRatio(info.aspect, matchHeightConstraintsFirst = true),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                        InfoChip(ltr("${info.displayWidth}×${info.displayHeight}"))
                        InfoChip(ltr(mmss(info.durationMs)))
                        InfoChip(ltr("${info.fps.roundToInt()} fps"))
                        if (info.isHdr) InfoChip("HDR", Brand.Amber.copy(alpha = 0.22f), Feature.SUBTITLES.tint)
                    }
                }
                if (onReplace != null) TextButton(onClick = onReplace) { Text(t("החלף")) }
            }
        }
    }
}
