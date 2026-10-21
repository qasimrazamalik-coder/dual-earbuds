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

/** Raw brand colours. UI code should prefer [AppTheme.colors] / MaterialTheme so Light mode works. */
object Palette {
    val Navy = Color(0xFF0B1220)
    val NavyRaised = Color(0xFF111B2E)
    val NavyHigh = Color(0xFF17233B)
    val NavyHighest = Color(0xFF1E2C49)
    val Line = Color(0xFF26355A)
    val Teal = Color(0xFF14E0C2)
    val TealDeep = Color(0xFF0A8F7C)
    val TealInk = Color(0xFF02201B)
    val Sky = Color(0xFF7FA8FF)
    val Amber = Color(0xFFFFC857)
    val Coral = Color(0xFFFF6B7A)
    val Text = Color(0xFFE7EEF8)
    val TextDim = Color(0xFF93A3C0)

    // Light mode
    val Mist = Color(0xFFF3F6FA)
    val Paper = Color(0xFFFFFFFF)
    val Cloud = Color(0xFFE7EDF5)
    val CloudHigh = Color(0xFFDCE4EF)
    val Ink = Color(0xFF0B1220)
    val InkDim = Color(0xFF52627C)
    val TealText = Color(0xFF00796A)
    val AmberText = Color(0xFF9A6200)
    val CoralText = Color(0xFFC62E45)
}

/** Colours Material 3 has no slot for, resolved per theme. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    /** The accent as a fill (buttons, rings, meters). */
    val accent: Color,
    /** The accent as text/icons on the background. */
    val accentText: Color,
    val onAccent: Color,
    val warning: Color,
    val danger: Color,
    val textDim: Color,
    val track: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val heroGlow: Color
)

private val DarkAppColors = AppColors(
    isDark = true,
    accent = Palette.Teal,
    accentText = Palette.Teal,
    onAccent = Palette.TealInk,
    warning = Palette.Amber,
    danger = Palette.Coral,
    textDim = Palette.TextDim,
    track = Palette.NavyHigh,
    heroTop = Color(0xFF13233A),
    heroBottom = Palette.NavyRaised,
    heroGlow = Palette.Teal.copy(alpha = 0.16f)
)

private val LightAppColors = AppColors(
    isDark = false,
    accent = Color(0xFF0FBFA5),
    accentText = Palette.TealText,
    onAccent = Color(0xFF00201B),
    warning = Palette.AmberText,
    danger = Palette.CoralText,
    textDim = Palette.InkDim,
    track = Palette.Cloud,
    heroTop = Color(0xFFE3FBF6),
    heroBottom = Palette.Paper,
    heroGlow = Color(0xFF0FBFA5).copy(alpha = 0.14f)
)

private val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private val DarkColors = darkColorScheme(
    primary = Palette.Teal,
    onPrimary = Palette.TealInk,
    primaryContainer = Color(0xFF0D3B37),
    onPrimaryContainer = Palette.Teal,
    secondary = Palette.Sky,
    onSecondary = Color(0xFF0A1A3D),
    secondaryContainer = Color(0xFF1B2D57),
    onSecondaryContainer = Color(0xFFD6E2FF),
    tertiary = Palette.Amber,
    onTertiary = Color(0xFF2A1E00),
    background = Palette.Navy,
    onBackground = Palette.Text,
    surface = Palette.NavyRaised,
    onSurface = Palette.Text,
    surfaceVariant = Palette.NavyHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLowest = Palette.Navy,
    surfaceContainerLow = Palette.NavyRaised,
    surfaceContainer = Palette.NavyRaised,
    surfaceContainerHigh = Palette.NavyHigh,
    surfaceContainerHighest = Palette.NavyHighest,
    outline = Palette.Line,
    outlineVariant = Color(0xFF1C2945),
    error = Palette.Coral,
    onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF3B1420),
    onErrorContainer = Color(0xFFFFD9DD)
)

private val LightColors = lightColorScheme(
    primary = Palette.TealText,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF5EE),
    onPrimaryContainer = Color(0xFF00453C),
    secondary = Color(0xFF3557B7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5FF),
    onSecondaryContainer = Color(0xFF0E2464),
    tertiary = Palette.AmberText,
    onTertiary = Color.White,
    background = Palette.Mist,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Cloud,
    onSurfaceVariant = Palette.InkDim,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.Paper,
    surfaceContainerHigh = Palette.Cloud,
    surfaceContainerHighest = Palette.CloudHigh,
    outline = Color(0xFFC3CEDC),
    outlineVariant = Color(0xFFDDE4EE),
    error = Palette.CoralText,
    onError = Color.White,
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410009)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

/** Spacing scale used across screens (4-pt grid). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val gutter = 16.dp
}

/** Dark = deep navy with an electric teal accent (the original look). Light = mist + deep teal. */
@Composable
fun DualEarbudsTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
