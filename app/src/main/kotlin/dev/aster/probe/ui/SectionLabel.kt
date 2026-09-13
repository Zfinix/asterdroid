package dev.aster.probe.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Type.label,
        color = Ink.faint,
        modifier = modifier,
    )
}
