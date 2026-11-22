package com.elad.faceblur.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

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
