package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A dot and a word for whether the agent is up, and nothing more. */
@Composable
fun AgentStatus(running: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (running) Ink.live else Ink.faint)
        Text(
            text = if (running) "Running" else "Not running",
            style = MaterialTheme.typography.labelMedium,
            color = if (running) Ink.text else Ink.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
