package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Where the page is: the list, one name, or the file itself. */
private sealed interface View {
    data object List : View
    data class One(val name: String) : View
    data object New : View
    data object Whole : View
}

/**
 * The keys the agent runs on, editable here because the phone is often the only
 * device in reach. A page rather than a sheet: the file has a row per name, and
 * a sheet that tall is a list read through a letterbox.
 */
@Composable
fun SettingsScreen(
    entries: List<EnvEntry>,
    raw: String,
    onSet: (String, String) -> Unit,
    onRaw: (String) -> Unit,
    onBack: () -> Unit,
) {
    var view by remember { mutableStateOf<View>(View.List) }
    val one = (view as? View.One)?.let { at -> entries.firstOrNull { it.name == at.name } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = Gutter),
    ) {
        BackRow(
            title = when {
                view is View.Whole -> ".env"
                view is View.New -> "New value"
                one != null -> one.label
                else -> "Settings"
            },
            onBack = { if (view is View.List) onBack() else view = View.List },
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                view is View.Whole -> Whole(
                    raw = raw,
                    onSave = { text ->
                        view = View.List
                        onRaw(text)
                    },
                )
                view is View.New -> NewValue(
                    taken = entries.map { it.name },
                    onSave = { name, value ->
                        view = View.List
                        onSet(name, value)
                    },
                )
                one != null -> Editor(
                    entry = one,
                    onSave = { value ->
                        view = View.List
                        onSet(one.name, value)
                    },
                )
                else -> Keys(
                    entries = entries,
                    onEdit = { view = View.One(it.name) },
                    onAdd = { view = View.New },
                    onWhole = { view = View.Whole },
                )
            }
            Spacer(Modifier.height(Gutter))
        }
    }
}

@Composable
private fun Keys(
    entries: List<EnvEntry>,
    onEdit: (EnvEntry) -> Unit,
    onAdd: () -> Unit,
    onWhole: () -> Unit,
) {
    SectionLabel("KEYS", Modifier.padding(top = 12.dp, bottom = 8.dp))
    GroupCard {
        entries.forEachIndexed { i, entry ->
            SettingRow(
                label = entry.label,
                value = shown(entry),
                onClick = { onEdit(entry) },
            )
            RowDivider()
        }
        AddRow(onAdd)
    }
    entries.filter { it.value.isEmpty() }.forEach { entry ->
        Text(
            text = entry.need,
            style = Type.secondary,
            color = Ink.dim,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    SectionLabel("FILE", Modifier.padding(top = 24.dp, bottom = 8.dp))
    GroupCard {
        SettingRow(label = "Edit .env", value = "Text", onClick = onWhole)
    }
    Text(
        text = "These are written to the agent's .env on this phone. Changing one restarts the agent, " +
            "because the keys are read when it starts. Edit .env opens the whole file, for changing " +
            "several at once.",
        style = Type.secondary,
        color = Ink.faint,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** The way to a name this screen does not already list, without opening the file. */
@Composable
private fun AddRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "+", style = Type.button, color = Ink.accent)
        Text(text = "Add a value", style = Type.body, color = Ink.accent)
    }
}

/**
 * A blank row: the name on top, the value under it. Both are needed before this
 * can be saved, and a name already in the file would quietly overwrite it, so
 * neither case leaves the button live.
 */
@Composable
private fun NewValue(taken: List<String>, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val clean = name.trim()
    val clash = clean.isNotEmpty() && taken.any { it.equals(clean, ignoreCase = true) }
    SectionLabel("NAME", Modifier.padding(top = 12.dp, bottom = 8.dp))
    CodeField(
        value = name,
        onValueChange = { typed -> name = typed.filterNot { it == '=' || it.isWhitespace() } },
        hint = "ANTHROPIC_API_KEY",
        modifier = Modifier.focusRequester(focus),
    )
    SectionLabel("VALUE", Modifier.padding(top = 20.dp, bottom = 8.dp))
    CodeField(
        value = value,
        onValueChange = { value = it.replace("\n", "") },
        hint = "Paste it here",
        onDone = { if (clean.isNotEmpty() && !clash) onSave(clean, value) },
    )
    Text(
        text = if (clash) "$clean is already on this screen. Open its row to change it." else
            "The name as the agent reads it, and what it is set to. It lands in the .env on this phone.",
        style = Type.secondary,
        color = if (clash) Ink.red else Ink.dim,
        modifier = Modifier.padding(top = 10.dp),
    )
    Spacer(Modifier.height(16.dp))
    Action(
        text = "Save",
        enabled = clean.isNotEmpty() && value.isNotBlank() && !clash,
        onClick = { onSave(clean, value) },
    )
}

/** The one input these screens use: monospace, no autocorrect, nothing capitalised. */
@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Type.code.copy(color = Ink.text),
        cursorBrush = SolidColor(Ink.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.surface)
            .border(1.dp, Ink.line, CardShape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        decorationBox = { field ->
            if (value.isEmpty()) Text(text = hint, color = Ink.faint, style = Type.code)
            field()
        },
    )
}

@Composable
private fun Editor(entry: EnvEntry, onSave: (String) -> Unit) {
    var draft by remember(entry.name) { mutableStateOf(entry.value) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(entry.name) { focus.requestFocus() }
    Text(text = entry.name, style = Type.code, color = Ink.faint, modifier = Modifier.padding(top = 12.dp))
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
            .background(Ink.surface)
            .border(1.dp, Ink.line, CardShape)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .focusRequester(focus),
        decorationBox = { field ->
            if (draft.isEmpty()) Text(text = "Paste it here", color = Ink.faint, style = Type.code)
            field()
        },
    )
    if (entry.need.isNotEmpty()) {
        Text(
            text = entry.need,
            style = Type.secondary,
            color = Ink.dim,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
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

/**
 * Names in the accent, values in plain text, comments dimmed. A file of long
 * base64 values wraps into a wall otherwise, with nothing to say where one line
 * ends and the next begins. Only colour changes, so offsets still map straight
 * through and the cursor lands where it is put.
 */
private val EnvSyntax = VisualTransformation { text ->
    val painted = buildAnnotatedString {
        text.text.split("\n").forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val at = line.indexOf('=')
            when {
                line.trimStart().startsWith("#") ->
                    withStyle(SpanStyle(color = Ink.faint)) { append(line) }
                at > 0 -> {
                    withStyle(SpanStyle(color = Ink.accent)) { append(line.substring(0, at)) }
                    withStyle(SpanStyle(color = Ink.faint)) { append("=") }
                    withStyle(SpanStyle(color = Ink.text)) { append(line.substring(at + 1)) }
                }
                else -> withStyle(SpanStyle(color = Ink.text)) { append(line) }
            }
        }
    }
    TransformedText(painted, OffsetMapping.Identity)
}

/**
 * The whole file, for changing several names at once or repairing one by hand.
 * It saves every line as typed, comments and blanks included, so what is written
 * here is what the agent reads.
 */
@Composable
private fun Whole(raw: String, onSave: (String) -> Unit) {
    var draft by remember(raw) { mutableStateOf(raw) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        textStyle = Type.code.copy(color = Ink.text, lineHeight = 26.sp),
        cursorBrush = SolidColor(Ink.accent),
        visualTransformation = EnvSyntax,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(top = 12.dp)
            .clip(CardShape)
            .background(Ink.surface)
            .border(1.dp, Ink.line, CardShape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        decorationBox = { field ->
            if (draft.isEmpty()) {
                Text(text = "NAME=value, one per line", color = Ink.faint, style = Type.code)
            }
            field()
        },
    )
    Text(
        text = "One NAME=value per line. Lines starting with # are left alone.",
        style = Type.secondary,
        color = Ink.dim,
        modifier = Modifier.padding(top = 10.dp),
    )
    Spacer(Modifier.height(16.dp))
    Action(text = "Save", onClick = { onSave(draft) })
}

@Composable
private fun Action(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Ink.accent else Ink.line)
            .pressable { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Type.button, color = if (enabled) Ink.ground else Ink.faint)
    }
}

private fun shown(entry: EnvEntry): String = when {
    entry.value.isEmpty() -> "Not set"
    !entry.secret -> entry.value
    entry.value.length <= 4 -> "••••"
    else -> "••••" + entry.value.takeLast(4)
}
