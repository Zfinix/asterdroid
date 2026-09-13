package dev.aster.probe.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Almost-black, warm greys, one orange. The dark the rest of Aster uses. */
object Ink {
    val ground = Color(0xFF0A0A0A)
    val surface = Color(0xFF131211)
    val line = Color(0xFF2E2A26)
    val text = Color(0xFFEDEDED)
    val dim = Color(0xFF948B80)
    val faint = Color(0xFF6E675E)
    val accent = Color(0xFFF2764F)
    val onAccent = Color(0xFFFAECE7)
    val live = Color(0xFF3FB950)
    val amber = Color(0xFFEF9F27)
    val red = Color(0xFFE24B4A)
}

/** Tighter tracking as type grows, looser leading as it shrinks. */
private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = Inter,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleSmall = TextStyle(
        fontFamily = Inter,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(
        fontFamily = Mono,
        fontSize = 11.sp,
        lineHeight = 17.sp,
    ),
)

val Gutter = 20.dp

@Composable
fun AsterTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        background = Ink.ground,
        surface = Ink.surface,
        onBackground = Ink.text,
        onSurface = Ink.text,
        primary = Ink.accent,
        onPrimary = Ink.ground,
        outline = Ink.line,
    ),
    typography = typography,
    content = content,
)
