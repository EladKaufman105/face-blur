package com.elad.faceblur.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Wide enough for panes side by side: a tablet, an unfolded phone, or a phone on its side.
 * (A phone held upright keeps the one-column layout.)
 */
@Composable
fun isWide(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp >= 840 || (c.screenWidthDp >= 600 && c.screenWidthDp > c.screenHeightDp)
}
