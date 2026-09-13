package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A strip that fades the ground into nothing, laid over the top of a list so it scrolls out under the controls. */
@Composable
fun TopFade(modifier: Modifier = Modifier, height: Dp = 24.dp, color: Color = Ink.ground) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))),
    )
}
