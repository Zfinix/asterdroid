package dev.aster.probe.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.aster.probe.Entry
import dev.aster.probe.Session
import dev.aster.probe.compact
import dev.aster.probe.duration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
private val day = SimpleDateFormat("d MMM HH:mm", Locale.US)

/** One session in a sheet: its numbers, a search, a filter, and the log turn by turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(session: Session, entries: List<Entry>?, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val shown = remember(query, filter, entries) { filterLog(entries.orEmpty(), filter, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.ground,
        contentColor = Ink.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(start = Gutter, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.label,
                    style = Type.sheetTitle,
                    color = Ink.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .pressable(onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.close, contentDescription = "Close", tint = Ink.dim, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = listOfNotNull(day.format(Date(session.createdAt)), duration(session.lastAt - session.createdAt), session.provider, session.model?.substringAfterLast('/'))
                    .joinToString(" · "),
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Stat(session.turns.toString(), "turns")
                Stat(session.calls.toString(), "calls")
                Stat(compact(session.promptTokens + session.completionTokens), "tokens")
                Stat(session.errors.toString(), "errors", warn = session.errors > 0)
                Stat(session.noChange.toString(), "no change")
                Stat(session.shots.toString(), "shots")
            }
            SearchField(
                query = query,
                hint = "Search this session",
                onQuery = { query = it },
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
            )
            FilterChips(logFilters, filter, { filter = it }, Modifier.padding(start = Gutter, end = Gutter, top = 10.dp))
            when {
                entries == null -> Text(
                    text = "Loading…",
                    style = Type.secondary,
                    color = Ink.faint,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                )
                shown.isEmpty() -> Text(
                    text = if (query.isBlank()) "No entries." else "No line contains “$query”.",
                    style = Type.secondary,
                    color = Ink.faint,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                )
                else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                    LogList(entries = shown, query = query, modifier = Modifier.fillMaxSize())
                    TopFade(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, warn: Boolean = false) {
    Text(
        text = buildAnnotatedString {
            withStyle(Type.secondary.copy(fontWeight = FontWeight.Medium, color = if (warn) Ink.amber else Ink.text).toSpanStyle()) { append(value) }
            append(" $label")
        },
        style = Type.secondary,
        color = Ink.dim,
        maxLines = 1,
    )
}
