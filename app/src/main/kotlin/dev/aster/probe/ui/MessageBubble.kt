package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** What was said, by the person or by the agent, on a soft card. */
@Composable
fun MessageBubble(text: AnnotatedString, agent: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (agent) Glyph.agent else Glyph.user,
            contentDescription = null,
            tint = if (agent) Ink.accent else Ink.faint,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Text(text = text, style = Type.prose, color = Ink.text)
    }
}
