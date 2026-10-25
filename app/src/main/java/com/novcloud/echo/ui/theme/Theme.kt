package com.novcloud.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.R

val Vermilion = Color(0xFFFF5A28)
val VermilionDeep = Color(0xFFE2471A)
val Cobalt = Color(0xFF3D5BFF)
val Ink = Color(0xFF11100E)
val Paper = Color(0xFFF1ECE2)
val Sand = Color(0xFFC9B99A)

val Accent = Vermilion

@Immutable
data class EchoPalette(
    val signal: Color,
    val onSignal: Color,
    val second: Color,
    val ink: Color,
    val paper: Color,
    val rule: Color,
    val muted: Color,
    val faint: Color,
    val card: Color,
    val cardHigh: Color,
)

private val DarkPalette = EchoPalette(
    signal = Vermilion, onSignal = Ink, second = Cobalt, ink = Ink, paper = Paper,
    rule = Color(0xFF2E2C27), muted = Color(0xFFA49F94), faint = Color(0xFF6E6A61),
    card = Color(0xFF1A1916), cardHigh = Color(0xFF23211D),
)

private val LightPalette = EchoPalette(
    signal = VermilionDeep, onSignal = Paper, second = Color(0xFF2C47E0), ink = Ink, paper = Paper,
    rule = Color(0xFFD6CEBE), muted = Color(0xFF6B655A), faint = Color(0xFF9C9588),
    card = Color(0xFFE9E3D7), cardHigh = Color(0xFFE0D9CB),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

private val Dark = darkColorScheme(
    primary = Vermilion,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A1A0F),
    onPrimaryContainer = Color(0xFFFFD8C9),
    secondary = Sand,
    tertiary = Cobalt,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Color(0xFF1F1D1A),
    onSurfaceVariant = Color(0xFFA49F94),
    surfaceContainerLowest = Color(0xFF0C0B0A),
    surfaceContainerLow = Color(0xFF161512),
    surfaceContainer = Color(0xFF1A1916),
    surfaceContainerHigh = Color(0xFF23211D),
    surfaceContainerHighest = Color(0xFF2C2A25),
    outline = Color(0xFF3B3833),
    outlineVariant = Color(0xFF2E2C27),
    error = Color(0xFFFF6B5B),
)

private val Light = lightColorScheme(
    primary = VermilionDeep,
    onPrimary = Paper,
    primaryContainer = Color(0xFFFFDCCD),
    onPrimaryContainer = Color(0xFF3A1206),
    secondary = Color(0xFF7D6E52),
    tertiary = Color(0xFF2C47E0),
    background = Color(0xFFF2EDE3),
    onBackground = Color(0xFF15130F),
    surface = Color(0xFFF2EDE3),
    onSurface = Color(0xFF15130F),
    surfaceVariant = Color(0xFFE9E3D7),
    onSurfaceVariant = Color(0xFF6B655A),
    surfaceContainerLowest = Color(0xFFFAF7F1),
    surfaceContainerLow = Color(0xFFEEE8DD),
    surfaceContainer = Color(0xFFE9E3D7),
    surfaceContainerHigh = Color(0xFFE0D9CB),
    surfaceContainerHighest = Color(0xFFD7CFBF),
    outline = Color(0xFFC4BBA9),
    outlineVariant = Color(0xFFD6CEBE),
)

val Anton = FontFamily(Font(R.font.anton))

val Grotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object EchoType {
    fun poster(size: TextUnit) = TextStyle(fontFamily = Anton, fontSize = size, lineHeight = size * 1.02f, letterSpacing = 0.2.sp, lineHeightStyle = tight)
    fun heavy(size: TextUnit) = TextStyle(fontWeight = FontWeight.Black, fontSize = size, lineHeight = size * 1.12f, letterSpacing = (-0.02).em(size), lineHeightStyle = tight)
    val label = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp)
    val labelBold = label.copy(fontWeight = FontWeight.Bold)
    val numeral = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp)
    fun numeral(size: TextUnit) = numeral.copy(fontSize = size, lineHeight = size * 1.1f, letterSpacing = (-0.03).em(size))
}

private fun Double.em(size: TextUnit): TextUnit = (size.value * this).sp

fun isCjk(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code).let { s ->
    s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
} }

fun displayStyle(text: String, size: TextUnit): TextStyle =
    if (isCjk(text)) EchoType.heavy(size * 0.86f) else EchoType.poster(size)

private val EchoTypography = Typography(
    displayLarge = EchoType.poster(64.sp),
    displayMedium = EchoType.poster(48.sp),
    displaySmall = EchoType.poster(36.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val EchoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

object EchoShape {
    val cover = RoundedCornerShape(4.dp)
    val card = RoundedCornerShape(6.dp)
    val key = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(4.dp)
}

val LocalDarkTheme = staticCompositionLocalOf { true }
val LocalMotion = staticCompositionLocalOf { true }

@Composable
fun EchoTheme(dark: Boolean = isSystemInDarkTheme(), motion: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalPalette provides if (dark) DarkPalette else LightPalette,
        LocalMotion provides motion,
    ) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = EchoTypography, shapes = EchoShapes, content = content)
    }
}

val palette: EchoPalette @Composable get() = LocalPalette.current

private val seeds = listOf(
    Color(0xFFFF5A28), Color(0xFF3D5BFF), Color(0xFFC9B99A), Color(0xFF2E8B6E),
    Color(0xFFE8B931), Color(0xFF8A4FFF), Color(0xFFD9475E), Color(0xFF3FA7C9),
)

fun seededColor(seed: String): Color = seeds[(seed.hashCode() and 0x7fffffff) % seeds.size]

fun seededGradient(seed: String): List<Color> {
    val c = seededColor(seed)
    return listOf(c, androidx.compose.ui.graphics.lerp(c, Ink, 0.55f))
}

object EchoMotion {
    const val Quick = 160
    const val Standard = 260
    const val Medium = 300
    const val Emphasized = 420
}
