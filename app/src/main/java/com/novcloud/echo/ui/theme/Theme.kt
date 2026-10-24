package com.novcloud.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF4F6D)
val AccentDeep = Color(0xFFE0284A)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1820),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFB9B9C6),
    background = Color(0xFF0A0A0D),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF0A0A0D),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF9A9AA6),
    surfaceContainerLowest = Color(0xFF0D0D10),
    surfaceContainerLow = Color(0xFF131317),
    surfaceContainer = Color(0xFF17171C),
    surfaceContainerHigh = Color(0xFF1E1E24),
    surfaceContainerHighest = Color(0xFF26262D),
    outline = Color(0xFF34343C),
    outlineVariant = Color(0xFF24242A),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDE3),
    onPrimaryContainer = Color(0xFF3E0011),
    secondary = Color(0xFF5E5E6B),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFEDEDF1),
    onSurfaceVariant = Color(0xFF6C6C78),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F5),
    surfaceContainer = Color(0xFFEDEDF1),
    surfaceContainerHigh = Color(0xFFE6E6EB),
    surfaceContainerHighest = Color(0xFFDDDDE3),
    outline = Color(0xFFCFCFD6),
    outlineVariant = Color(0xFFE2E2E8),
)

private val base = Typography()
private val EchoType = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun EchoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = EchoType, content = content)
    }
}

fun seededGradient(seed: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFFFF5F6D), Color(0xFFFFC371)),
        listOf(Color(0xFF7F5AF0), Color(0xFF2CB1BC)),
        listOf(Color(0xFF00C6FB), Color(0xFF005BEA)),
        listOf(Color(0xFFF7797D), Color(0xFFC471ED)),
        listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
        listOf(Color(0xFFFC466B), Color(0xFF3F5EFB)),
        listOf(Color(0xFFF2994A), Color(0xFFEB5757)),
        listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
        listOf(Color(0xFF43C6AC), Color(0xFF191654)),
        listOf(Color(0xFFDA4453), Color(0xFF89216B)),
    )
    return palettes[(seed.hashCode() and 0x7fffffff) % palettes.size]
}
