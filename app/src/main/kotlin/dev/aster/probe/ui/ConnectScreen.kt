package dev.aster.probe.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.RelayStatus

/** How the phone is reachable right now, as the screen needs it. */
data class Reachable(
    val mcpToken: String?,
    val acpToken: String?,
    val mcpPort: String,
    val acpPort: String,
    /** Listening on the Wi-Fi address rather than only on the phone itself. */
    val wifi: Boolean,
    /** The phone's Wi-Fi address, or null off Wi-Fi. */
    val address: String?,
    val relay: Boolean,
    /** ASTER_RELAY_URL names a relay of the person's own; Aster Relay itself is coming soon. */
    val selfHosted: Boolean,
    val running: Boolean,
)

/**
 * Letting other agents and editors in: MCP for agents that drive the phone,
 * ACP for editors that talk to the agent on it, on this network, over
 * Tailscale, or through a relay from anywhere. Each has its own key, made here, and the
 * snippet to paste into each client is written out with it, so nobody
 * assembles a URL and a header by hand.
 */
@Composable
fun ConnectScreen(
    reach: Reachable,
    pending: List<RelayStatus.Pending>,
    onMcp: (Boolean) -> Unit,
    onAcp: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onRelay: (Boolean) -> Unit,
    onPair: () -> Unit,
    onClients: () -> Unit,
    onScan: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onCopy: (String, String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .padding(horizontal = Gutter),
    ) {
        BackRow(title = "Connect a computer", onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Waiting(pending, onAnswer, Modifier.padding(top = 12.dp))
            SectionLabel("NEARBY", Modifier.padding(top = 12.dp, bottom = 8.dp))
            GroupCard {
                ToggleRow(
                    label = "Agents (MCP)",
                    detail = "Claude Code, Codex, Cursor and other agents use this phone's screen.",
                    on = reach.mcpToken != null,
                    onChange = onMcp,
                )
                RowDivider()
                ToggleRow(
                    label = "Editors (ACP)",
                    detail = "Zed and other editors chat with the agent on this phone.",
                    on = reach.acpToken != null,
                    onChange = onAcp,
                )
                RowDivider()
                ToggleRow(
                    label = "Over Wi-Fi",
                    detail = if (reach.wifi) {
                        "Any computer on this Wi-Fi that has the key can connect."
                    } else {
                        "Off: only over a USB cable, through adb."
                    },
                    on = reach.wifi,
                    onChange = onWifi,
                    enabled = reach.address != null || reach.wifi,
                )
            }
            Text(
                text = when {
                    reach.wifi -> "The key travels unencrypted on the Wi-Fi, and whoever holds it controls the " +
                        "phone. Use this on networks you trust, or over Tailscale."
                    else -> "Whoever holds a key controls the phone: calls, messages and money. Changing these " +
                        "restarts Droid."
                },
                style = Type.secondary,
                color = if (reach.wifi) Ink.amber else Ink.faint,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (reach.mcpToken != null || reach.acpToken != null) Snippets(reach, onCopy)
            SectionLabel("FROM ANYWHERE", Modifier.padding(top = 28.dp, bottom = 8.dp))
            GroupCard {
                ToggleRow(
                    label = "Reach from anywhere",
                    detail = if (reach.selfHosted) {
                        "Through your relay. Nothing on the phone opens a port, and you let in each computer here."
                    } else {
                        "Coming soon. Until then, connect over USB, Wi-Fi or Tailscale above."
                    },
                    on = reach.relay,
                    onChange = onRelay,
                    enabled = reach.selfHosted,
                )
                if (reach.relay) {
                    RowDivider()
                    ValueRow("Status") { RelayLine(reach.running) }
                    RowDivider()
                    SettingRow(label = "Pair a computer", value = "Show a code", onClick = onPair)
                    RowDivider()
                    SettingRow(label = "Paired computers", value = "", onClick = onClients)
                    RowDivider()
                    SettingRow(label = "Scan a computer's code", value = "", onClick = onScan)
                }
            }
            Text(
                text = if (reach.selfHosted) {
                    "Traffic to and from the relay is encrypted in transit."
                } else {
                    "Aster Relay is coming soon. A relay you host yourself works now: put its address in " +
                        "ASTER_RELAY_URL in settings."
                },
                style = Type.secondary,
                color = Ink.faint,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(Gutter))
        }
    }
}

@Composable
private fun RelayLine(running: Boolean) {
    val (color, text) = when {
        !running -> Ink.faint to "Starts with Droid"
        RelayStatus.online -> Ink.live to "Online"
        RelayStatus.running -> Ink.amber to (RelayStatus.reason?.let { "Offline: $it" } ?: "Connecting")
        else -> Ink.amber to "Not running"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Text(text = text, style = Type.secondary, color = Ink.text, maxLines = 1)
    }
}

/** Clients asking the relay to be let in, each with its own yes and no. */
@Composable
fun Waiting(pending: List<RelayStatus.Pending>, onAnswer: (String, Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (pending.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pending.forEach { ask ->
            GroupCard {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(text = "${ask.name} wants to reach this phone", style = Type.body, color = Ink.text)
                    Text(
                        text = "Let it in only if you are pairing it right now. It will be able to use the phone " +
                            "as the agent does.",
                        style = Type.secondary,
                        color = Ink.dim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(text = "Let it in", onClick = { onAnswer(ask.id, true) }, modifier = Modifier.weight(1f))
                        QuietButton(text = "Refuse", onClick = { onAnswer(ask.id, false) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private enum class Client(val label: String) { CLAUDE("Claude Code"), CODEX("Codex"), CURSOR("Cursor"), ZED("Zed") }

/**
 * What to paste into each client. The key shows only its last four here,
 * while Copy carries the whole thing, so the snippet can sit on screen
 * without spelling out the secret.
 */
@Composable
private fun Snippets(reach: Reachable, onCopy: (String, String, Boolean) -> Unit) {
    var client by remember { mutableStateOf(Client.CLAUDE) }
    val host = if (reach.wifi) reach.address ?: LOOPBACK else LOOPBACK
    val mcpUrl = "http://$host:${reach.mcpPort}/mcp"
    val acpUrl = "ws://$host:${reach.acpPort}/acp"
    SectionLabel("PASTE INTO YOUR COMPUTER", Modifier.padding(top = 24.dp, bottom = 8.dp))
    FilterChips(
        options = Client.entries.map { it.label },
        chosen = client.label,
        onChoose = { picked -> client = Client.entries.first { it.label == picked } },
    )
    if (!reach.wifi) {
        val forwards = listOfNotNull(
            reach.mcpToken?.let { "adb forward tcp:${reach.mcpPort} tcp:${reach.mcpPort}" },
            reach.acpToken?.let { "adb forward tcp:${reach.acpPort} tcp:${reach.acpPort}" },
        ).joinToString("\n")
        CodeBlock(
            text = forwards,
            label = "FIRST, WITH THE PHONE ON USB",
            onCopy = { onCopy("adb forward", forwards, false) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    reach.mcpToken?.let { token ->
        val shown = mcp(client, mcpUrl, masked(token))
        CodeBlock(
            text = shown,
            label = "AGENTS (MCP)",
            onCopy = { onCopy("${client.label} MCP", mcp(client, mcpUrl, token), true) },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    reach.acpToken?.let { token ->
        val shown = acp(client, acpUrl, masked(token))
        CodeBlock(
            text = shown,
            label = "EDITORS (ACP)",
            onCopy = { onCopy("${client.label} ACP", acp(client, acpUrl, token), true) },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        reach.mcpToken?.let { token ->
            QuietButton(text = "Copy MCP key", onClick = { onCopy("MCP key", token, true) }, modifier = Modifier.weight(1f), color = Ink.accent)
        }
        reach.acpToken?.let { token ->
            QuietButton(text = "Copy ACP key", onClick = { onCopy("ACP key", token, true) }, modifier = Modifier.weight(1f), color = Ink.accent)
        }
    }
}

private fun masked(token: String) = "••••" + token.takeLast(4)

private fun mcp(client: Client, url: String, token: String): String = when (client) {
    Client.CLAUDE -> "claude mcp add --transport http phone $url \\\n  --header \"Authorization: Bearer $token\""
    Client.CODEX -> "export DROID_MCP_TOKEN=$token\ncodex mcp add phone --url $url \\\n  --bearer-token-env-var DROID_MCP_TOKEN"
    Client.CURSOR -> "// ~/.cursor/mcp.json\n{\n  \"mcpServers\": {\n    \"phone\": {\n      \"url\": \"$url\",\n" +
        "      \"headers\": { \"Authorization\": \"Bearer $token\" }\n    }\n  }\n}"
    Client.ZED -> "// Zed settings.json\n{\n  \"context_servers\": {\n    \"phone\": {\n      \"url\": \"$url\",\n" +
        "      \"headers\": { \"Authorization\": \"Bearer $token\" }\n    }\n  }\n}"
}

/**
 * Editors start an ACP agent as a local command, and none of them dials a
 * WebSocket yet, so the snippet runs websocat as the command, carrying stdio
 * to the phone one JSON-RPC line per message. Zed is the editor with a custom
 * agent setting; the others get the address and header to use.
 */
private fun acp(client: Client, url: String, token: String): String = when (client) {
    Client.ZED -> "// brew install websocat, then in Zed settings.json\n{\n  \"agent_servers\": {\n    \"Droid\": {\n" +
        "      \"type\": \"custom\",\n      \"command\": \"websocat\",\n" +
        "      \"args\": [\"-H\", \"Authorization: Bearer $token\", \"$url\"]\n    }\n  }\n}"
    else -> "# Any ACP client: a WebSocket, one JSON-RPC message per text frame\n$url\nAuthorization: Bearer $token"
}

private const val LOOPBACK = "127.0.0.1"
