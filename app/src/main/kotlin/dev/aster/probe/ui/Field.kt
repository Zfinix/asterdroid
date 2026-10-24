package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * One line of input for keys, addresses and ids: monospace, no autocorrect,
 * nothing capitalised, and no line breaks, since each value becomes one
 * `.env` line. A secret is dotted out, because a key read over someone's
 * shoulder is spent.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    uri: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "").replace("\r", "")) },
        singleLine = true,
        textStyle = Type.code.copy(color = Ink.text),
        cursorBrush = SolidColor(Ink.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = when {
                secret -> KeyboardType.Password
                uri -> KeyboardType.Uri
                else -> KeyboardType.Ascii
            },
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
