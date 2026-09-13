package dev.aster.probe.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aster.probe.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_medium, FontWeight.SemiBold),
)

val Mono = FontFamily(Font(R.font.jetbrainsmono, FontWeight.Normal))

private val tight = PlatformTextStyle(includeFontPadding = false)

private fun inter(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    platformStyle = tight,
)

/** Every size the screens use, so a change here moves all of them together. */
object Type {
    val title = inter(20, 24, FontWeight.Medium, -0.3f)
    val sheetTitle = inter(18, 22, FontWeight.Medium, -0.2f)
    val metric = inter(20, 24, FontWeight.Medium, -0.3f)
    val label = inter(12, 16, FontWeight.Medium, 0.2f)
    val body = inter(15, 22)
    val secondary = inter(13, 18)
    val input = inter(14, 20)
    val proseHeading = inter(15, 22, FontWeight.Medium)
    val prose = inter(14, 22)
    val button = inter(16, 20, FontWeight.Medium, -0.1f)
    val code = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 22.sp, platformStyle = tight)
}
