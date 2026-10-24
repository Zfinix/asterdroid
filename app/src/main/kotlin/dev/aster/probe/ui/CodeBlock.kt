package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A command or config to paste into a computer. It scrolls sideways rather
 * than wrapping, because a wrapped shell line reads as two commands, and the
 * copy target sits above it so a thumb never lands in the middle of the text.
 */
@Composable
fun CodeBlock(text: String, onCopy: (() -> Unit)?, modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.surface)
            .border(1.dp, Ink.line, CardShape),
    ) {
        if (label != null || onCopy != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label.orEmpty(), style = Type.label, color = Ink.faint)
                if (onCopy != null) {
                    Text(
                        text = "Copy",
                        style = Type.label,
                        color = Ink.accent,
                        modifier = Modifier
                            .pressable(onCopy)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text(
            text = text,
            style = Type.code,
            color = Ink.text,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = if (label != null || onCopy != null) 0.dp else 12.dp, bottom = 12.dp),
        )
    }
}
