package dev.aster.probe.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * One control with two states rather than two buttons, so the phone never
 * offers to start something already running.
 */
@Composable
fun AgentButton(running: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val fill by animateColorAsState(
        targetValue = if (running) Ink.surface else Ink.accent,
        label = "fill",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(fill)
            .then(if (running) Modifier.border(1.dp, Ink.line, shape) else Modifier)
            .pressable(onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) "Stop the agent" else "Start the agent",
            style = Type.button,
            color = if (running) Ink.text else Ink.ground,
        )
    }
}
