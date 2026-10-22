package com.dualearbuds.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design system "instrument" (docs/design-system.md): the same tokens as the Windows app —
 * Bg, Surface, Surface2, Line, Text, Muted, Accent, Good, Warn, Bad. Colour means state; the accent is only for
 * live state, the primary action, the selected segment, focus and meters.
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    /** The accent as a fill (primary button, toggles when on, meters, live dot). */
    val accent: Color,
    /** The accent as text on Surface/Surface2 (≥ 4.5:1). */
    val accentText: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color
) {
    // Names the screens used before the redesign.
    val textDim: Color get() = muted
    val warning: Color get() = warn
    val danger: Color get() = bad
    val track: Color get() = line
}

private val DarkAppColors = AppColors(
    isDark = true,
    bg = Color(0xFF0B1220),
    surface = Color(0xFF121B2E),
    surface2 = Color(0xFF18233A),
    line = Color(0xFF22304A),
    text = Color(0xFFE6EDF7),
    muted = Color(0xFF8FA3BF),
    accent = Color(0xFF14E0C2),
    accentText = Color(0xFF14E0C2),
    onAccent = Color(0xFF04201B),
    good = Color(0xFF34D399),
    warn = Color(0xFFF5B84B),
    bad = Color(0xFFFF6B6B)
)

private val LightAppColors = AppColors(
    isDark = false,
    bg = Color(0xFFF3F6FB),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEEF3FA),
    line = Color(0xFFDCE4EF),
    text = Color(0xFF0F172A),
    muted = Color(0xFF5B6B82),
    accent = Color(0xFF0E9F95),
    accentText = Color(0xFF0B7A72),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF047857),
    warn = Color(0xFF8A5A12),
    bad = Color(0xFFC81E1E)
)

private val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private fun scheme(c: AppColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.surface2, onPrimaryContainer = c.accentText,
        secondary = c.muted, onSecondary = c.bg,
        secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        tertiary = c.warn, onTertiary = c.bg,
        background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
        outline = c.line, outlineVariant = c.line,
        error = c.bad, onError = c.bg, errorContainer = c.surface2, onErrorContainer = c.bad
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.surface2, onPrimaryContainer = c.accentText,
        secondary = c.muted, onSecondary = c.surface,
        secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        tertiary = c.warn, onTertiary = c.surface,
        background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
        outline = c.line, outlineVariant = c.line,
        error = c.bad, onError = c.surface, errorContainer = c.surface2, onErrorContainer = c.bad
    )
}

private val Sans = FontFamily.SansSerif

private fun sans(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp)

/**
 * Type scale (design-system.md "Type"): state headline 20/semibold, page name 15/semibold, body 14,
 * section label 12/semibold, hint 12. No display sizes: the big slots map to the state headline.
 * labelSmall is the mono readout style (values in ms, dB, kHz, %, IP, PIN).
 */
private val AppTypography = Typography(
    displayLarge = sans(20, FontWeight.SemiBold, 26),
    displayMedium = sans(20, FontWeight.SemiBold, 26),
    displaySmall = sans(20, FontWeight.SemiBold, 26),
    headlineLarge = sans(20, FontWeight.SemiBold, 26),
    headlineMedium = sans(20, FontWeight.SemiBold, 26),
    headlineSmall = sans(20, FontWeight.SemiBold, 26),
    titleLarge = sans(15, FontWeight.SemiBold, 20),
    titleMedium = sans(14, FontWeight.Medium, 20),
    titleSmall = sans(14, FontWeight.Medium, 20),
    bodyLarge = sans(14, FontWeight.Normal, 20),
    bodyMedium = sans(14, FontWeight.Normal, 20),
    bodySmall = sans(12, FontWeight.Normal, 16),
    labelLarge = sans(14, FontWeight.Medium, 20),
    labelMedium = sans(12, FontWeight.SemiBold, 16),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.2.sp, fontFeatureSettings = "tnum"
    )
)

/** Small 6 dp (buttons, segments, fields), medium 8 dp (panels). Nothing rounder. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

/** Mono, tabular values. */
object Mono {
    val value: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall

    /** Larger mono figure for the one live value a view is about (13 sp). */
    val large: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
}

/** Spacing scale (4-pt grid). Sections sit [section] apart; a label is [sm] above its panel. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val gutter = 16.dp
    val section = 24.dp
}

@Composable
fun DualEarbudsTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = scheme(colors),
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
