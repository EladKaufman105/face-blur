package com.elad.faceblur.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.elad.faceblur.prefs.Palette
import com.elad.faceblur.prefs.ThemeMode

/** Accent colors of one palette, for light and dark. */
private class Accent(
    val light: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val dark: Color,
    val darkOn: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
)

private val Accents = mapOf(
    Palette.BLUE to Accent(Color(0xFF3355E0), Color(0xFFDEE4FF), Color(0xFF0D1A5C), Color(0xFF9DB0FF), Color(0xFF0D1A5C), Color(0xFF263C9C), Color(0xFFDEE4FF)),
    Palette.PURPLE to Accent(Color(0xFF7246D8), Color(0xFFEADDFF), Color(0xFF25005A), Color(0xFFD0BCFF), Color(0xFF3B1E7A), Color(0xFF5634A8), Color(0xFFEADDFF)),
    Palette.GREEN to Accent(Color(0xFF1F7A4D), Color(0xFFB6F2CF), Color(0xFF00210F), Color(0xFF8FD8AE), Color(0xFF00391E), Color(0xFF0D5A36), Color(0xFFB6F2CF)),
    Palette.ORANGE to Accent(Color(0xFFC2410C), Color(0xFFFFDBCC), Color(0xFF3A0B00), Color(0xFFFFB59A), Color(0xFF5A1A00), Color(0xFF8A2E06), Color(0xFFFFDBCC)),
    Palette.ROSE to Accent(Color(0xFFC2185B), Color(0xFFFFD9E2), Color(0xFF3E001D), Color(0xFFFFB1C8), Color(0xFF5E1133), Color(0xFF8E2A54), Color(0xFFFFD9E2)),
)

/** Swatch shown in the palette picker. */
fun paletteSwatch(p: Palette): Color = Accents[p]?.light ?: Color(0xFF6750A4)

val dynamicColorAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private fun light(a: Accent) = lightColorScheme(
    primary = a.light,
    onPrimary = Color.White,
    primaryContainer = a.lightContainer,
    onPrimaryContainer = a.lightOnContainer,
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

private fun dark(a: Accent) = darkColorScheme(
    primary = a.dark,
    onPrimary = a.darkOn,
    primaryContainer = a.darkContainer,
    onPrimaryContainer = a.darkOnContainer,
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
private fun scheme(palette: Palette, darkMode: Boolean): ColorScheme {
    if (palette == Palette.DYNAMIC && dynamicColorAvailable) {
        val ctx = LocalContext.current
        return if (darkMode) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    val a = Accents[palette] ?: Accents.getValue(Palette.BLUE)
    return if (darkMode) dark(a) else light(a)
}

@Composable
fun FaceBlurTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: Palette = Palette.BLUE,
    content: @Composable () -> Unit,
) {
    val darkMode = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = scheme(palette, darkMode), content = content)
}

/** The editor is always dark, like video editors: the footage is what should stand out. */
@Composable
fun EditorTheme(palette: Palette = Palette.BLUE, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(palette, true), content = content)
}
