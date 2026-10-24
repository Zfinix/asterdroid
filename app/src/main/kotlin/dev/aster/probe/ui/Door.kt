package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One of a few ways forward, as a card with what it costs under its name.
 * The choices on a setup step are few and different in kind, so each gets
 * room to say what it means rather than a one-word row.
 */
@Composable
fun Door(title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    GroupCard(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = title, style = Type.proseHeading, color = Ink.text)
                    if (badge != null) Text(text = badge, style = Type.label, color = Ink.accent)
                }
                Text(
                    text = detail,
                    style = Type.secondary,
                    color = Ink.dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Chevron()
        }
    }
}
