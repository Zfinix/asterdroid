package dev.aster.probe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A page's first line: the way back on the left, the page's name after it. */
@Composable
fun BackRow(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .pressable(onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.back, contentDescription = "Back", tint = Ink.dim, modifier = Modifier.size(18.dp))
        }
        Text(
            text = title,
            style = Type.title,
            color = Ink.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
