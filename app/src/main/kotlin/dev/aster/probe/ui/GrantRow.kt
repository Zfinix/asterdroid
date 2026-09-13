package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The row is the control for the thing it reports: tapping it lands on the
 * settings screen that turns it on, so the state and the fix are one target.
 */
@Composable
fun GrantRow(grant: Grant, onOpen: (Grant) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable { onOpen(grant) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (grant.on) Ink.live else Ink.accent)
            Text(text = grant.label, style = MaterialTheme.typography.bodyMedium, color = Ink.text)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (grant.on) "On" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (grant.on) Ink.dim else Ink.accent,
            )
            Chevron()
        }
    }
}
