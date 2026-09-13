package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** `Turn 3 ———— 18:58:12`: where one request ends and the next begins. */
@Composable
fun TurnRule(label: String, time: String, first: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = Type.label, color = Ink.faint)
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 0.5.dp, color = Ink.line)
        Text(text = time, style = Type.label, color = Ink.faint)
    }
}
