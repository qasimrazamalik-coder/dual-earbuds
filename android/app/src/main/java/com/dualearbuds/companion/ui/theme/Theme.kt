package com.dualearbuds.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-0.5).sp),
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

/** Dual Earbuds is dark by design: deep navy with an electric teal accent. */
@Composable
fun DualEarbudsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
