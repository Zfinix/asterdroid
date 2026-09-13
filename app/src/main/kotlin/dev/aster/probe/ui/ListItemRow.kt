package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Three lines and a chevron: the shape every list on the history pages shares. */
@Composable
fun ListItemRow(title: String, line2: String, line3: String, onClick: () -> Unit, accent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Type.body, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line2.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(text = line2, style = Type.secondary, color = Ink.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = line3,
                style = Type.label,
                color = if (accent) Ink.accent else Ink.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron()
    }
}
