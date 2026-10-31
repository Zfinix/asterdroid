package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.probe.Chat

/** Consecutive tool calls share one card, as they do in a session log; everything else stands alone. */
private sealed interface Shown {
    data class One(val item: Chat.Item) : Shown
    data class Tools(val tools: List<Chat.Tool>) : Shown
}

private fun group(items: List<Chat.Item>): List<Shown> {
    val out = mutableListOf<Shown>()
    for (item in items) {
        val last = out.lastOrNull()
        if (item is Chat.Tool && last is Shown.Tools) out[out.lastIndex] = Shown.Tools(last.tools + item)
        else out += if (item is Chat.Tool) Shown.Tools(listOf(item)) else Shown.One(item)
    }
    return out
}

/**
 * Talking to the agent without leaving the phone or signing up for anything.
 * Replies stream in as they are written, each action shows as a call card,
 * and what the agent wants a yes for waits here as buttons.
 */
@Composable
fun ChatScreen(
    items: List<Chat.Item>,
    title: String?,
    running: Boolean,
    open: Boolean,
    busy: Boolean,
    starting: Boolean,
    off: String?,
    keyboardBlocked: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String, String?) -> Unit,
    onFresh: () -> Unit,
    onStart: () -> Unit,
    onKeyboard: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val shown = group(items)
    val tail = (items.lastOrNull() as? Chat.Said)?.text?.length ?: 0
    val atBottom = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        ?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true

    // Follow the reply as it streams, but let go once someone scrolls back to read.
    LaunchedEffect(shown.size, tail) {
        if (atBottom && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex, Int.MAX_VALUE / 2)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Gutter, end = Gutter, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackRow(title = title ?: "Chat", onBack = onBack, modifier = Modifier.weight(1f))
            if (items.isNotEmpty() && !busy) {
                Text(
                    text = "New chat",
                    style = Type.label,
                    color = Ink.dim,
                    modifier = Modifier
                        .pressable(onFresh)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                off != null -> EmptyState(title = "The chat is off", detail = off)
                !running -> EmptyState(
                    title = "The agent is not running",
                    detail = "Start it to chat. It keeps running when you leave the app.",
                )
                items.isEmpty() && !starting -> EmptyState(
                    title = "Ask Droid to do something",
                    detail = "It uses this phone the way you would: it opens apps, reads the screen, taps " +
                        "and types. It asks you before anything risky.",
                )
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown.size) { i ->
                    when (val row = shown[i]) {
                        is Shown.Tools -> ToolCard(row.tools)
                        is Shown.One -> ChatItem(row.item, onAnswer)
                    }
                }
                if (starting || busy && items.lastOrNull().let { it is Chat.Said && !it.agent }) {
                    item {
                        Text(
                            text = if (starting) "Starting the agent" else "Thinking",
                            style = Type.label,
                            color = Ink.faint,
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (keyboardBlocked) {
            Text(
                text = "The Droid keyboard is chosen, and it has no keys. Tap here to type with your own keyboard.",
                style = Type.secondary,
                color = Ink.amber,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onKeyboard)
                    .padding(horizontal = Gutter, vertical = 8.dp),
            )
        }
        if (!running && off == null) {
            AgentButton(
                running = false,
                onToggle = onStart,
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp, bottom = Gutter),
            )
        } else if (off == null) {
            Composer(
                draft = draft,
                onDraft = { draft = it },
                enabled = open,
                busy = busy,
                onSend = {
                    onSend(draft)
                    draft = ""
                },
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun ChatItem(item: Chat.Item, onAnswer: (String, String?) -> Unit) {
    when (item) {
        is Chat.Said -> MessageBubble(markdown(item.text), item.agent)
        is Chat.Thought -> Thought(item.text)
        is Chat.Plan -> PlanCard(item.steps)
        is Chat.Ask -> AskCard(item, onAnswer)
        is Chat.Notice -> EventRow(
            icon = if (item.error) Glyph.error else Glyph.dash,
            text = item.text,
            time = "",
            error = item.error,
            detail = null,
        )
        is Chat.Tool -> ToolCard(listOf(item))
    }
}

@Composable
private fun ToolCard(tools: List<Chat.Tool>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(0.5.dp, Ink.line, CardShape),
    ) {
        tools.forEachIndexed { i, tool ->
            CallCard(tool.call, query = "")
            if (i < tools.lastIndex) RowDivider()
        }
    }
}

/** The agent thinking aloud: two quiet lines, the rest a tap away. */
@Composable
private fun Thought(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        text = text.trim(),
        style = Type.secondary,
        color = Ink.faint,
        maxLines = if (open) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .pressable { open = !open }
            .padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlanCard(steps: List<Chat.Step>) {
    GroupCard {
        steps.forEachIndexed { i, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (step.status) {
                    "completed" -> Icon(Glyph.check, contentDescription = null, tint = Ink.live, modifier = Modifier.size(14.dp))
                    "in_progress" -> Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { Dot(Ink.accent) }
                    else -> Icon(Glyph.dash, contentDescription = null, tint = Ink.faint, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = step.text,
                    style = Type.secondary,
                    color = if (step.status == "completed") Ink.dim else Ink.text,
                )
            }
            if (i < steps.lastIndex) RowDivider()
        }
    }
}

/**
 * What the agent will not do without a yes, with its answers as buttons. A
 * reject option is drawn plainly, so the button that spends or sends is the
 * one that stands out and is never pressed by mistake for the other.
 */
@Composable
private fun AskCard(ask: Chat.Ask, onAnswer: (String, String?) -> Unit) {
    GroupCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(text = ask.title, style = Type.body, color = Ink.text)
            if (ask.body.isNotBlank()) {
                Text(
                    text = ask.body.trim(),
                    style = Type.code,
                    color = Ink.dim,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (ask.chosen != null) {
                Text(
                    text = ask.chosen,
                    style = Type.label,
                    color = Ink.faint,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ask.options.forEach { option ->
                        Choice(
                            text = option.name,
                            strong = !option.kind.startsWith("reject"),
                            onClick = { onAnswer(ask.key, option.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(text: String, strong: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(if (strong) Ink.accent else Ink.ground)
            .then(if (strong) Modifier else Modifier.border(1.dp, Ink.line, shape))
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Type.button, color = if (strong) Ink.ground else Ink.text, maxLines = 1)
    }
}

/**
 * The message field and the one button beside it. While a turn runs and the
 * field is empty the button stops the agent; with words in it, it sends them
 * into the turn already running.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val stopping = busy && draft.isBlank()
    val ready = stopping || enabled && draft.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = Type.input.copy(color = Ink.text),
            cursorBrush = SolidColor(Ink.accent),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Ink.surface)
                .border(1.dp, Ink.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            decorationBox = { field ->
                if (draft.isEmpty()) {
                    Text(
                        text = if (busy) "Add to what it is doing" else "Message Droid",
                        style = Type.input,
                        color = Ink.faint,
                    )
                }
                field()
            },
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (ready) Ink.accent else Ink.line)
                .pressable {
                    when {
                        stopping -> onStop()
                        ready -> onSend()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (stopping) Glyph.stop else Glyph.play,
                contentDescription = if (stopping) "Stop" else "Send",
                tint = if (ready) Ink.ground else Ink.faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
