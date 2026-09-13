package dev.aster.probe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A number with its name under it, sized to sit three across. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, live: Boolean = false) {
    Column(modifier = modifier.padding(14.dp)) {
        Text(
            text = value,
            style = Type.metric,
            color = if (live) Ink.live else Ink.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = label, style = Type.label, color = Ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
