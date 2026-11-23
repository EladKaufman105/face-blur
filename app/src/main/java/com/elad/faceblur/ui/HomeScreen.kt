package com.elad.faceblur.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elad.faceblur.MainViewModel
import com.elad.faceblur.QuickTool
import com.elad.faceblur.batch.BatchMath
import com.elad.faceblur.batch.BatchState
import com.elad.faceblur.i18n.t

/**
 * The home screen: a new video (blur faces) first, the other tools, then the saved projects.
 * On a tablet, starting something new on one side and the projects on the other.
 */
@Composable
internal fun HomeContent(vm: MainViewModel, wide: Boolean, onPick: () -> Unit, onTool: (QuickTool) -> Unit, onBatch: () -> Unit) {
    val projects: @Composable () -> Unit = {
        ProjectsSection(vm.projects, vm::openProject, vm::renameProject, vm::deleteProject)
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.XL)) {
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(Space.XL)) {
                BatchState.current?.let { b -> BatchBanner(b, vm::showBatch) }
                EnterUp(0) { HeroCard(onPick) }
                if (!vm.batchRunning) EnterUp(1) { BatchCard(onBatch) }
                EnterUp(1) { ToolsSection(onTool) }
                EnterUp(3) { ShareHint() }
            }
            Column(Modifier.weight(1f)) { EnterUp(2) { projects() } }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Space.XL)) {
            BatchState.current?.let { b -> BatchBanner(b, vm::showBatch) }
            EnterUp(0) { HeroCard(onPick) }
            if (!vm.batchRunning) EnterUp(1) { BatchCard(onBatch) }
            EnterUp(1) { ToolsSection(onTool) }
            EnterUp(2) { projects() }
            EnterUp(3) { ShareHint() }
        }
    }
}

/** The logo, the name, the promise (nothing leaves the phone) and the app's settings. */
@Composable
internal fun Header(onSettings: () -> Unit) {
    // Left to right in every language (the logo on the left), in both builds. The beta build adds
    // a BETA tag on the logo and "Beta" after the name.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { HeaderRow(isBetaBuild, onSettings) }
}

@Composable
private fun HeaderRow(beta: Boolean, onSettings: () -> Unit) {
    Row(Modifier.padding(top = Space.S, bottom = Space.XS), verticalAlignment = Alignment.CenterVertically) {
        if (beta) {
            // Fixed to the logo's size: a large system font grows the tag, not the row.
            Box(Modifier.size(44.dp)) {
                LogoMark(44.dp)
                BetaBadge(Modifier.align(Alignment.BottomEnd).wrapContentSize(Alignment.BottomEnd, unbounded = true).offset(x = 6.dp, y = 4.dp))
            }
        } else {
            LogoMark(44.dp)
        }
        Spacer(Modifier.width(Space.M))
        Column(Modifier.weight(1f)) {
            if (beta) {
                val title = buildAnnotatedString {
                    append("ONIT")
                    withStyle(SpanStyle(fontSize = 0.5.em, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)) { append(" Beta") }
                }
                Text(title, style = AppType.Display, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            } else {
                Text("ONIT", style = AppType.Display, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.XS))
                // One line: a narrow screen gets it a little smaller rather than broken.
                androidx.compose.foundation.text.BasicText(
                    t("הכל מעובד על המכשיר. שום דבר לא עולה לרשת."),
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                )
            }
        }
        IconButton(onClick = onSettings, modifier = Modifier.testTag(Tags.SETTINGS)) { Icon(Icons.Filled.Settings, t("הגדרות")) }
    }
}

/** The main tool: a night-blue card with a miniature timeline of blurs. */
@Composable
private fun HeroCard(onPick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth().pressScale(source),
        shape = MaterialTheme.shapes.extraLarge,
        color = Brand.Ink,
        interactionSource = source,
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF33237A), Brand.Night, Brand.Ink)))
                .padding(Space.XL),
        ) {
            // Lanes of blurs, as in the editor: found automatically (coral), marked by hand (peach).
            MiniTimeline(
                Modifier.fillMaxWidth().height(56.dp),
                lanes = listOf(
                    Brand.Coral to listOf(0.03f..0.34f, 0.42f..0.74f, 0.80f..0.97f),
                    Color(0xFFFF8A65) to listOf(0.12f..0.30f, 0.52f..0.88f),
                    Brand.Coral.copy(alpha = 0.55f) to listOf(0.06f..0.22f, 0.62f..0.79f),
                ),
            )
            Spacer(Modifier.height(Space.XL))
            Text(t("טשטוש פנים בסרטון"), style = AppType.Display, color = Color.White)
            Spacer(Modifier.height(Space.S))
            Text(
                t("הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה."),
                style = AppType.Body,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(Space.XL))
            GradientButton(t("בחר סרטון"), onPick, Modifier.fillMaxWidth().testTag(Tags.PICK_VIDEO), icon = Icons.Filled.Add)
        }
    }
}

/** Several videos at once, with one template (a batch). */
@Composable
private fun BatchCard(onClick: () -> Unit) {
    ClipCard(onClick = onClick, accent = Feature.FACES.color, modifier = Modifier.testTag(Tags.BATCH)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureTile(AppIcons.Folder, Feature.FACES.tint, 48.dp)
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f)) {
                Text(t("כמה סרטונים"), style = MaterialTheme.typography.titleSmall)
                Text(
                    t("עד %d סרטונים בבת אחת, עם תבנית אחת. ממשיך גם כשהמסך כבוי.").format(BatchMath.MAX_VIDEOS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Quick ways into the editor without face detection, one per tool. */
@Composable
private fun ToolsSection(onTool: (QuickTool) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        SectionHeader(t("כלים נוספים"), t("פותח את העורך ישר, בלי זיהוי פנים"), MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            ToolTile(Modifier.weight(1f), AppIcons.Subtitles, t("כתוביות"), Feature.SUBTITLES) { onTool(QuickTool.SUBTITLES) }
            ToolTile(Modifier.weight(1f), AppIcons.Person, t("הסרת רקע"), Feature.BACKGROUND) { onTool(QuickTool.BACKGROUND) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            ToolTile(Modifier.weight(1f), AppIcons.Voice, t("קול ואפקטים"), Feature.SOUND) { onTool(QuickTool.SOUND) }
            ToolTile(Modifier.weight(1f), AppIcons.Music, t("המרה ל-MP3"), Feature.SOUND) { onTool(QuickTool.MP3) }
        }
    }
}

@Composable
private fun ToolTile(modifier: Modifier, icon: ImageVector, label: String, feature: Feature, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(source).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        interactionSource = source,
    ) {
        Column(
            Modifier.padding(vertical = Space.L, horizontal = Space.S),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FeatureTile(icon, feature.tint, 48.dp)
            Spacer(Modifier.height(Space.M))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ShareHint() {
    Text(
        t("אפשר גם לשתף סרטון מהגלריה ישירות לאפליקציה"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** A section's title (with its color's bar) and a line under it, lined up with the title's text. */
@Composable
internal fun SectionHeader(title: String, note: String?, color: Color, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionTitle(title, color)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SectionTitleInset),
                )
            }
        }
        trailing()
    }
}
