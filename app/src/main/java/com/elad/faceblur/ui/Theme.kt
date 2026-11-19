package com.elad.faceblur.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3355E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE4FF),
    onPrimaryContainer = Color(0xFF0D1A5C),
    secondaryContainer = Color(0xFFE3E7F4),
    onSecondaryContainer = Color(0xFF1B2133),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF15171D),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF15171D),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    onSurfaceVariant = Color(0xFF5B6070),
    outline = Color(0xFFC7CBD8),
    outlineVariant = Color(0xFFE1E4EE),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB0FF),
    onPrimary = Color(0xFF0D1A5C),
    primaryContainer = Color(0xFF263C9C),
    onPrimaryContainer = Color(0xFFDEE4FF),
    secondaryContainer = Color(0xFF2A3042),
    onSecondaryContainer = Color(0xFFE0E4F2),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE6E8EF),
    surfaceContainerLow = Color(0xFF181B22),
    surfaceContainer = Color(0xFF1D2028),
    surfaceContainerHigh = Color(0xFF252935),
    onSurfaceVariant = Color(0xFFA3A8B8),
    outline = Color(0xFF454B5C),
    outlineVariant = Color(0xFF2E3341),
    error = Color(0xFFFF8A80),
)

@Composable
fun FaceBlurTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The editor is always dark, like video editors: the footage is what should stand out. */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}
