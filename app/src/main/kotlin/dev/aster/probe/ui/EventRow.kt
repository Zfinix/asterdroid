package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Something that happened to the agent itself: started, stopped, failed. A failure opens to its detail. */
@Composable
fun EventRow(icon: ImageVector, text: String, time: String, error: Boolean, detail: String?) {
    var open by remember { mutableStateOf(false) }
    val tint = if (error) Ink.red else Ink.faint
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (detail != null) Modifier.pressable { open = !open } else Modifier)
            .padding(horizontal = 2.dp, vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(text = text, style = Type.label, color = tint, modifier = Modifier.weight(1f))
            Text(text = time, style = Type.label, color = tint)
        }
        if (open && detail != null) {
            Text(
                text = detail,
                style = Type.code,
                color = Ink.dim,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
    }
}
