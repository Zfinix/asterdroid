package dev.aster.probe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A card with one quiet line, for a list with nothing in it yet. */
@Composable
fun EmptyCard(text: String, modifier: Modifier = Modifier) {
    GroupCard(modifier) {
        Text(
            text = text,
            color = Ink.faint,
            style = Type.secondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
        )
    }
}
