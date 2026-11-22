package com.elad.faceblur.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.elad.faceblur.edit.EditorPanel
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elad.faceblur.i18n.t

/** A tablet (or an unfolded phone): at least 600 dp on its short side. Phones stay upright. */
fun isTablet(c: Configuration) = c.smallestScreenWidthDp >= 600

/**
 * Wide enough for panes side by side: a tablet on its side, or a big one either way.
 * Phones always keep the one-column layout (they're held upright; the app doesn't turn on them).
 */
@Composable
fun isWide(): Boolean {
    val c = LocalConfiguration.current
    if (!isTablet(c)) return false
    return c.screenWidthDp >= 840 || (c.screenWidthDp >= 600 && c.screenWidthDp > c.screenHeightDp)
}

/**
 * Inside the tablet's side panel: a panel there is one of the editor's tabs (always one open,
 * no "done") and its body takes the panel's full height.
 */
val LocalSidePanel = staticCompositionLocalOf { false }

/** Hides the tablet's side panel (the video gets the room); a tool brings it back. */
val LocalPanelHide = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * A panel's scrolling body: [phone] tall on a phone (where it takes the timeline's place under
 * the video), the rest of the height in the tablet's side panel. [upTo]: at most that tall.
 */
@Composable
fun panelBody(phone: Dp, upTo: Boolean = false): Modifier = when {
    LocalSidePanel.current -> Modifier.fillMaxHeight()
    upTo -> Modifier.heightIn(max = phone)
    else -> Modifier.height(phone)
}

/**
 * The tools of the open panel's group, one tab each (subtitles: make, list, look). A panel's
 * title row shows them in place of its title, so moving between a group's tools is one tap.
 */
class PanelTabs(
    val tabs: List<Pair<EditorPanel, String>>,
    val current: EditorPanel,
    val color: Color,
    val onSelect: (EditorPanel) -> Unit,
)

val LocalPanelTabs = staticCompositionLocalOf<PanelTabs?> { null }

/**
 * A panel's title row: the title in its feature's color (or its group's tabs), [actions], and
 * "done" (on a phone) or "close" (beside the video).
 */
@Composable
fun PanelHeader(title: String, color: Color, onDone: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val side = LocalSidePanel.current
    val tabs = LocalPanelTabs.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(start = if (tabs != null && tabs.tabs.size > 1) Space.S else Space.L, end = if (side) Space.M else Space.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tabs != null && tabs.tabs.size > 1) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((p, label) in tabs.tabs) PanelTab(label, p == tabs.current, tabs.color, Modifier.testTag(Tags.tab(p))) { tabs.onSelect(p) }
            }
        } else {
            SectionTitle(title, color, Modifier.weight(1f))
        }
        actions()
        val hide = LocalPanelHide.current
        if (side && hide != null) {
            IconButton(onClick = hide, modifier = Modifier.testTag(Tags.PANEL_CLOSE)) { Icon(Icons.Filled.Close, t("סגור את הפאנל")) }
        }
        if (!side) {
            TextButton(onClick = onDone, modifier = Modifier.testTag(Tags.PANEL_DONE)) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Space.XS))
                Text(t("סיום"))
            }
        }
    }
}

/** A tab of a panel's group: its tool's name, filled in the group's color when it's the one open. */
@Composable
private fun PanelTab(label: String, on: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (on) color.copy(alpha = 0.2f) else Color.Transparent,
        modifier = modifier.semantics { selected = on },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (on) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Space.M, vertical = Space.S),
        )
    }
}
