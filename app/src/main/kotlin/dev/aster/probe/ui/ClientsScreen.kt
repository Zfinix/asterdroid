package dev.aster.probe.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.Relay

/**
 * Every computer the relay lets in, and the way to shut each out. Revoking
 * closes its connection at once, so this is also the screen for a laptop
 * that was lost or lent.
 */
@Composable
fun ClientsScreen(
    clients: List<Relay.Client>?,
    loading: Boolean,
    problem: String?,
    onRevoke: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .padding(horizontal = Gutter),
    ) {
        BackRow(title = "Paired computers", onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                loading && clients == null -> Text(
                    text = "Asking the relay",
                    style = Type.body,
                    color = Ink.dim,
                    modifier = Modifier.padding(top = 20.dp),
                )
                problem != null -> Text(text = problem, style = Type.body, color = Ink.red, modifier = Modifier.padding(top = 20.dp))
                clients.isNullOrEmpty() -> EmptyCard(
                    text = "No computer is paired. Pair one from Connect a computer.",
                    modifier = Modifier.padding(top = 12.dp),
                )
                else -> {
                    GroupCard(Modifier.padding(top = 12.dp)) {
                        clients.forEachIndexed { i, client ->
                            ClientRow(client, onRevoke)
                            if (i < clients.lastIndex) RowDivider()
                        }
                    }
                    QuietButton(
                        text = "Revoke every computer",
                        onClick = { onRevoke("all") },
                        color = Ink.red,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(Gutter))
        }
        QuietButton(text = if (loading) "Refreshing" else "Refresh", onClick = onRefresh, modifier = Modifier.padding(bottom = Gutter))
    }
}

@Composable
private fun ClientRow(client: Relay.Client, onRevoke: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = client.name, style = MaterialTheme.typography.bodyMedium, color = Ink.text)
            Text(
                text = listOfNotNull(
                    KINDS[client.kind] ?: client.kind.takeIf { it.isNotEmpty() },
                    seen(client.lastSeen)?.let { "used $it" } ?: seen(client.created)?.let { "paired $it" },
                ).joinToString(", ").replaceFirstChar { it.uppercase() },
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "Revoke",
            style = Type.label,
            color = Ink.red,
            modifier = Modifier
                .pressable { onRevoke(client.id) }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

/**
 * The relay may count in seconds or milliseconds; either reads as "3 hours
 * ago". The last minute, and a stamp a little ahead of the phone's clock, are
 * both "just now".
 */
private fun seen(stamp: Long): String? {
    if (stamp <= 0L) return null
    val ms = if (stamp < 100_000_000_000L) stamp * 1000 else stamp
    if (System.currentTimeMillis() - ms < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        .lowercase()
}

private val KINDS = mapOf(
    "mcp-http" to "an agent over MCP",
    "mcp-shim" to "an agent over MCP",
    "acp-shim" to "an editor over ACP",
    "browser" to "a browser",
)
