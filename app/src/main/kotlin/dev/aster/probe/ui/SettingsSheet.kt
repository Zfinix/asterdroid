package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** One line of the `.env`, as a row the phone can fill in without a computer. */
data class EnvEntry(
    val name: String,
    val label: String,
    val value: String,
    /** Shown as dots in the list: a token read over someone's shoulder is spent. */
    val secret: Boolean,
    /** What is missing while it is unset. */
    val need: String,
)

/**
 * The keys the agent runs on, editable here because the phone is often the only
 * device in reach. Tapping a row swaps this sheet for its editor rather than
 * opening a second one on top of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(entries: List<EnvEntry>, onSet: (String, String) -> Unit, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    val chosen = entries.firstOrNull { it.name == editing }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        contentColor = Ink.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = Gutter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(start = Gutter, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = chosen?.label ?: "Settings", style = Type.sheetTitle, color = Ink.text)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pressable { if (chosen == null) onDismiss() else editing = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (chosen == null) Glyph.close else Glyph.back,
                        contentDescription = if (chosen == null) "Close" else "Back",
                        tint = Ink.dim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (chosen == null) {
                Keys(entries, onEdit = { editing = it.name })
            } else {
                Editor(
                    entry = chosen,
                    onSave = { value ->
                        editing = null
                        onSet(chosen.name, value)
                    },
                )
            }
        }
    }
}

@Composable
private fun Keys(entries: List<EnvEntry>, onEdit: (EnvEntry) -> Unit) {
    Column(Modifier.padding(horizontal = Gutter)) {
        SectionLabel("KEYS", Modifier.padding(top = 4.dp, bottom = 8.dp))
        GroupCard {
            entries.forEachIndexed { i, entry ->
                SettingRow(
                    label = entry.label,
                    value = shown(entry),
                    onClick = { onEdit(entry) },
                )
                if (i < entries.lastIndex) RowDivider()
            }
        }
        entries.filter { it.value.isEmpty() }.forEach { entry ->
            Text(
                text = entry.need,
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            text = "These are written to the agent's .env on this phone. Changing one restarts the agent, " +
                "because the keys are read when it starts.",
            style = Type.secondary,
            color = Ink.faint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Editor(entry: EnvEntry, onSave: (String) -> Unit) {
    var draft by remember(entry.name) { mutableStateOf(entry.value) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(entry.name) { focus.requestFocus() }
    Column(Modifier.padding(horizontal = Gutter)) {
        Text(text = entry.name, style = Type.code, color = Ink.faint)
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.replace("\n", "") },
            singleLine = true,
            textStyle = Type.code.copy(color = Ink.text),
            cursorBrush = SolidColor(Ink.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(CardShape)
                .background(Ink.ground)
                .border(1.dp, Ink.line, CardShape)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .focusRequester(focus),
            decorationBox = { field ->
                if (draft.isEmpty()) Text(text = "Paste it here", color = Ink.faint, style = Type.code)
                field()
            },
        )
        Text(
            text = entry.need,
            style = Type.secondary,
            color = Ink.dim,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(16.dp))
        Action(text = "Save", onClick = { onSave(draft) })
        if (entry.value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pressable { onSave("") },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Remove", style = Type.button, color = Ink.red)
            }
        }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Ink.accent)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Type.button, color = Ink.ground)
    }
}

private fun shown(entry: EnvEntry): String = when {
    entry.value.isEmpty() -> "Not set"
    !entry.secret -> entry.value
    entry.value.length <= 4 -> "••••"
    else -> "••••" + entry.value.takeLast(4)
}
