package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/** One line to type into, styled as a card, with a hint until something is typed. */
@Composable
fun SearchField(query: String, hint: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = Type.input.copy(color = Ink.text),
        cursorBrush = SolidColor(Ink.accent),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CardShape)
            .background(Ink.surface)
            .border(1.dp, Ink.line, CardShape)
            .padding(horizontal = 14.dp),
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Glyph.search, contentDescription = null, tint = Ink.faint, modifier = Modifier.size(16.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(text = hint, color = Ink.faint, style = Type.input)
                    field()
                }
            }
        },
    )
}
