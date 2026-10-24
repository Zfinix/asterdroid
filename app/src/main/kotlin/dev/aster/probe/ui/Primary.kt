package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The one thing a step wants pressed. Greyed rather than hidden while it cannot be, so the way on stays in view. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Ink.accent else Ink.line)
            .pressable { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Type.button, color = if (enabled) Ink.ground else Ink.faint)
    }
}

/** A second way out of a step, said in text so it never competes with the primary one. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.dim) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Type.button, color = color)
    }
}
