package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** A row of pills, one pressed: the filter on a list. */
@Composable
fun FilterChips(options: List<String>, chosen: String, onChoose: (String) -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val on = option == chosen
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(pill)
                    .background(if (on) Ink.text else Ink.ground)
                    .border(0.5.dp, if (on) Ink.text else Ink.line, pill)
                    .pressable { onChoose(option) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = option, style = Type.label, color = if (on) Ink.ground else Ink.dim)
            }
        }
    }
}
