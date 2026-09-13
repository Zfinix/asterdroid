package dev.aster.probe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.ActivityLog
import dev.aster.probe.Feed

/** Everything both halves of the app did, newest at the bottom, drawn and filtered like a session log. */
@Composable
fun ActivityFeed(entries: List<ActivityLog.Entry>, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    var filter by remember { mutableStateOf("All") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val log = remember(entries, filter) { filterLog(Feed.entries(entries), filter, "") }
    val count = groupCalls(log).size
    val atBottom = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        ?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true

    // Follow the tail, but let go the moment someone scrolls back to read.
    LaunchedEffect(count) {
        if (atBottom && count > 0) list.animateScrollToItem(count - 1)
    }

    Column(modifier) {
        FilterChips(logFilters, filter, { filter = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                entries.isEmpty() -> EmptyState(
                    icon = Glyph.agent,
                    title = "No activity yet",
                    detail = "Start the agent. Its actions show here.",
                )
                log.isEmpty() -> EmptyState(
                    icon = Glyph.search,
                    title = "No ${filter.lowercase()} yet",
                    detail = "This run has no ${filter.lowercase()}.",
                )
            }
            LogList(
                entries = log,
                query = "",
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 0.dp, bottom = 8.dp),
                now = maxOf(now, entries.lastOrNull()?.at ?: 0L),
            )
        }
    }
}
