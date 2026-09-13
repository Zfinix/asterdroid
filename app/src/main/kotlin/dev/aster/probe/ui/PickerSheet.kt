package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/** One row of a picker: what it is called, what it is called by, whether it can be used. */
data class Option(val id: String, val title: String, val detail: String? = null, val usable: Boolean = true)

/**
 * A sheet with a search box, which is what a long list needs on a phone: a
 * dropdown of forty providers or four hundred models cannot be scrolled with
 * a thumb, and cannot be searched at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    label: String,
    options: List<Option>,
    chosenId: String?,
    onPick: (Option) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = options.filter { option ->
        query.isBlank() ||
            option.title.contains(query, ignoreCase = true) ||
            option.id.contains(query, ignoreCase = true)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.ground,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = Gutter)
                .navigationBarsPadding(),
        ) {
            SectionLabel(label)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.text),
                cursorBrush = SolidColor(Ink.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
                    .clip(CardShape)
                    .background(Ink.surface)
                    .border(1.dp, Ink.line, CardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { field ->
                    if (query.isEmpty()) {
                        Text(text = "Search", color = Ink.faint, style = MaterialTheme.typography.bodyMedium)
                    }
                    field()
                },
            )
            if (shown.isEmpty()) {
                Text(
                    text = "No matches for “$query”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.faint,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id }) { option ->
                    val chosen = option.id == chosenId
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (chosen) Ink.surface else Ink.ground)
                            .pressable { onPick(option) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                chosen -> Ink.live
                                option.usable -> Ink.text
                                else -> Ink.faint
                            },
                        )
                        if (option.detail != null) {
                            Text(text = option.detail, style = MaterialTheme.typography.labelSmall, color = Ink.faint)
                        }
                    }
                }
            }
        }
    }
}
