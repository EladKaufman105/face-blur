package com.elad.faceblur.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elad.faceblur.prefs.Palette
import com.elad.faceblur.prefs.ThemeMode

/**
 * ONIT's look. The logo is a play button cut in two (video, edited), in glass: coral and violet
 * on a light tile, coral and amber on a dark one (with the theme; the app icon too). The clip colors (coral, amber, cyan) mark what each tool works on (see [Feature]),
 * shapes are rounded like clips, and the type is Allo Hebrew.
 */
object Brand {
    val Coral = Color(0xFFFF4D6D)
    val Amber = Color(0xFFFFC53D)
    val Cyan = Color(0xFF3DDCFF)
    val Violet = Color(0xFF8B6CFF)
    val Ink = Color(0xFF0B0D1C)
    val InkRaised = Color(0xFF14172C)
    val Night = Color(0xFF2B1E66)
}

/**
 * What a tool works on, and its color everywhere (cards, tools, timeline lanes). [deep] is the
 * same color dark enough to read on light backgrounds.
 */
enum class Feature(val color: Color, val deep: Color) {
    FACES(Brand.Coral, Color(0xFFD62650)),
    SUBTITLES(Brand.Amber, Color(0xFFB07400)),
    SOUND(Brand.Cyan, Color(0xFF0086A6)),
    BACKGROUND(Brand.Violet, Color(0xFF6346E0)),
}

/** True for the light color scheme. */
val ColorScheme.isLight get() = background.luminance() > 0.5f

/** The feature's color for text and icons on the current background. */
val Feature.tint: Color
    @Composable get() = if (MaterialTheme.colorScheme.isLight) deep else color

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
    Palette.ONIT to Accent(Color(0xFFE2345A), Color(0xFFFFDEE5), Color(0xFF3F0013), Color(0xFFFF5C7A), Color(0xFF2A0711), Color(0xFF4A1A2C), Color(0xFFFFD9E1)),
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
    secondary = Color(0xFF4B3FB0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E6FA),
    onSecondaryContainer = Color(0xFF1C1745),
    tertiary = Color(0xFF00809E),
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF13152B),
    surface = Color(0xFFF7F6FB),
    onSurface = Color(0xFF13152B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0EFF7),
    surfaceContainerHigh = Color(0xFFE8E7F2),
    surfaceContainerHighest = Color(0xFFDEDDEB),
    onSurfaceVariant = Color(0xFF5A5E7A),
    outline = Color(0xFFC6C5D6),
    outlineVariant = Color(0xFFE3E2EE),
    error = Color(0xFFC62828),
)

private fun dark(a: Accent) = darkColorScheme(
    primary = a.dark,
    onPrimary = a.darkOn,
    primaryContainer = a.darkContainer,
    onPrimaryContainer = a.darkOnContainer,
    secondary = Color(0xFFB7A8FF),
    onSecondary = Color(0xFF1C1250),
    secondaryContainer = Color(0xFF262A50),
    onSecondaryContainer = Color(0xFFE2E4FF),
    tertiary = Brand.Cyan,
    background = Brand.Ink,
    onBackground = Color(0xFFEEF0FF),
    surface = Brand.Ink,
    onSurface = Color(0xFFEEF0FF),
    surfaceContainerLowest = Color(0xFF080A16),
    surfaceContainerLow = Color(0xFF12152A),
    surfaceContainer = Color(0xFF171A33),
    surfaceContainerHigh = Color(0xFF1F2340),
    surfaceContainerHighest = Color(0xFF282D4F),
    onSurfaceVariant = Color(0xFFA5AACB),
    outline = Color(0xFF3A4068),
    outlineVariant = Color(0xFF262A47),
    error = Color(0xFFFF8A80),
)

@Composable
private fun scheme(palette: Palette, darkMode: Boolean): ColorScheme {
    if (palette == Palette.DYNAMIC && dynamicColorAvailable) {
        val ctx = LocalContext.current
        return if (darkMode) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    val a = Accents[palette] ?: Accents.getValue(Palette.ONIT)
    return if (darkMode) dark(a) else light(a)
}

/**
 * Spacing: every gap and padding is one of these (a 4 dp grid). Screens don't pick their own
 * numbers; a step that looks off is fixed here, for all of them.
 */
object Space {
    val XS: Dp = 4.dp
    val S: Dp = 8.dp
    val M: Dp = 12.dp
    val L: Dp = 16.dp
    val XL: Dp = 24.dp
    val XXL: Dp = 32.dp
    val XXXL: Dp = 48.dp
}

/** The smallest thing a finger should have to hit, whatever is drawn (a timeline handle, a dot). */
val MinTouch: Dp = 48.dp

/** Corner radii: controls (fields, chips' cousins), cards, panels and sheets. Pills are 50%. */
object Radius {
    val Control: Dp = 8.dp
    val Card: Dp = 12.dp
    val Panel: Dp = 16.dp
}

/**
 * Motion, in ms: swapping content (a fade only), changing state (a toggle, a selection) and
 * opening a panel. Nothing slides the layout around, with one deliberate exception: the editor's
 * safe-zone strip grows open and shrinks closed (the owner's choice; see SafeZoneStrip).
 */
object Motion {
    const val CONTENT = 120
    const val STATE = 200
    const val PANEL = 280
}

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Control),
    small = RoundedCornerShape(Radius.Control),
    medium = RoundedCornerShape(Radius.Card),
    large = RoundedCornerShape(Radius.Card),
    extraLarge = RoundedCornerShape(Radius.Panel),
)

/**
 * Allo Hebrew (Allo by Josh Puckett with a Hebrew set built to its rules; OFL; variable) for all
 * text, using its axes:
 * - optical size (opsz 9..32) follows each style's text size, as the font intends: small text
 *   gets the open spacing of its small-size design, headings the tighter display one;
 * - roundness (ROND): headings and titles fully round, in step with the app's pill buttons and
 *   rounded timeline bars; running text at the font's default, crisper at small sizes;
 * - weight per style, as before.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun typography(): Typography {
    val assets = LocalContext.current.assets
    return remember(assets) {
        val families = HashMap<Pair<Int, Int>, FontFamily>()
        fun allo(opsz: Int, round: Int) = families.getOrPut(opsz to round) {
            runCatching {
                FontFamily(
                    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black).map {
                        Font(
                            "fonts/allo_hebrew.ttf", assets, weight = it,
                            variationSettings = FontVariation.Settings(
                                FontVariation.weight(it.weight),
                                FontVariation.Setting("opsz", opsz.toFloat()),
                                FontVariation.Setting("ROND", round.toFloat()),
                            ),
                        )
                    },
                )
            }.getOrDefault(FontFamily.Default)
        }
        val base = Typography()
        fun TextStyle.r(weight: FontWeight? = null, spacing: Float? = null, round: Int = 50) = copy(
            fontFamily = allo(fontSize.value.toInt().coerceIn(9, 32), round),
            fontWeight = weight ?: fontWeight,
            letterSpacing = spacing?.sp ?: letterSpacing,
        )
        Typography(
            displayLarge = base.displayLarge.r(FontWeight.Black, -1f, 100),
            displayMedium = base.displayMedium.r(FontWeight.Black, -0.5f, 100),
            displaySmall = base.displaySmall.r(FontWeight.ExtraBold, -0.5f, 100),
            headlineLarge = base.headlineLarge.r(FontWeight.ExtraBold, -0.5f, 100),
            // The six steps of the scale (see [AppType]); the other slots are rarely used.
            headlineMedium = base.headlineMedium.r(FontWeight.SemiBold, -0.25f, 100),
            headlineSmall = base.headlineSmall.r(FontWeight.Bold, round = 100),
            titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp).r(FontWeight.SemiBold, 0f, 100),
            titleMedium = base.titleMedium.r(FontWeight.SemiBold, 0f),
            titleSmall = base.titleSmall.r(FontWeight.SemiBold, 0f),
            bodyLarge = base.bodyLarge.r(spacing = 0f),
            bodyMedium = base.bodyMedium.r(spacing = 0f),
            bodySmall = base.bodySmall.r(spacing = 0f),
            labelLarge = base.labelLarge.r(FontWeight.SemiBold, 0f),
            labelMedium = base.labelMedium.r(FontWeight.Medium, 0f),
            labelSmall = base.labelSmall.r(FontWeight.Normal, 0f),
        )
    }
}

/**
 * The type scale: six steps, each a slot of the Material typography above (Allo Hebrew,
 * its optical size following the text size).
 */
object AppType {
    /** 28/36, semibold: the home screen's title, big numbers. */
    val Display: TextStyle @Composable get() = MaterialTheme.typography.headlineMedium
    /** 20/28, semibold: a screen's or a sheet's title. */
    val Title: TextStyle @Composable get() = MaterialTheme.typography.titleLarge
    /** 16/24, semibold: a panel's title, a section. */
    val Heading: TextStyle @Composable get() = MaterialTheme.typography.titleMedium
    /** 14/20: running text. */
    val Body: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium
    /** 12/16, medium: buttons, tool names, chips. */
    val Label: TextStyle @Composable get() = MaterialTheme.typography.labelMedium
    /** 11/16: notes and values. */
    val Caption: TextStyle @Composable get() = MaterialTheme.typography.labelSmall
}

/**
 * Digits of one width (Allo's tabular figures): times, percents and sizes keep still while they
 * change, during playback and export.
 */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun FaceBlurTheme(
    mode: ThemeMode = ThemeMode.DARK,
    palette: Palette = Palette.ONIT,
    content: @Composable () -> Unit,
) {
    val darkMode = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = scheme(palette, darkMode), typography = typography(), shapes = Shapes, content = content)
}

/** The editor is always dark, like video editors: the footage is what should stand out. */
@Composable
fun EditorTheme(palette: Palette = Palette.ONIT, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(palette, true), typography = typography(), shapes = Shapes, content = content)
}
