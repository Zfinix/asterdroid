package dev.aster.probe.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.aster.probe.Entry
import dev.aster.probe.ago
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

/** The log as the mockup draws it: turn rules, bubbles, and grouped call cards. */
@Composable
fun LogList(
    entries: List<Entry>,
    query: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(start = Gutter, end = Gutter, top = 16.dp, bottom = 32.dp),
    now: Long? = null,
) {
    val groups = groupCalls(entries)
    val stamp: (Long) -> String = { at -> if (now == null) clock.format(Date(at)) else ago(at, now) }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups.size) { i ->
            when (val g = groups[i]) {
                is Piece.One -> when (val e = g.entry) {
                    is Entry.Turn -> TurnRule(e.text, stamp(e.at), first = i == 0)
                    is Entry.Message -> MessageBubble(markdown(e.text, query), e.agent)
                    is Entry.Title -> TurnRule("Titled “${e.text}”", stamp(e.at), first = i == 0)
                    is Entry.Event -> EventRow(
                        icon = when (e.kind) {
                            Entry.EventKind.START -> Glyph.play
                            Entry.EventKind.STOP -> Glyph.stop
                            Entry.EventKind.ERROR -> Glyph.error
                            Entry.EventKind.NOTE -> Glyph.dash
                        },
                        text = e.text,
                        time = stamp(e.at),
                        error = e.kind == Entry.EventKind.ERROR,
                        detail = e.detail,
                    )
                    is Entry.Call -> Unit
                }
                is Piece.Calls -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .border(0.5.dp, Ink.line, CardShape),
                ) {
                    g.calls.forEachIndexed { j, call ->
                        CallCard(call, query, stamp(call.at))
                        if (j < g.calls.lastIndex) RowDivider()
                    }
                }
            }
        }
    }
}
