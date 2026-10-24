package dev.aster.probe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.probe.Lan
import dev.aster.probe.Models
import dev.aster.probe.Relay
import dev.aster.probe.SetupLink

/** One line of what a setup link will change. */
data class Change(val label: String, val value: String)

/** What the sheet shows: the changes, and what is worth a second look before saying yes. */
data class SetupSummary(val changes: List<Change>, val warning: String?, val caution: String?)

/**
 * Everything a setup link writes, laid out before any of it is: any page or
 * QR code can open the link, and one that swaps in somebody else's model
 * address would see every screen the agent reads. A key shows only its last
 * four characters, which is enough to recognise one's own.
 */
fun summarise(setup: SetupLink.Setup, catalog: List<Models.Provider>, ownRelay: String? = null): SetupSummary {
    val changes = mutableListOf<Change>()
    var warning: String? = null
    var caution: String? = null
    setup.brain?.let { brain ->
        val row = SetupLink.known(brain, catalog)
        val host = Lan.host(brain.url) ?: brain.url
        changes += Change("Provider", row?.name ?: "Your server at $host")
        if (row == null) {
            changes += Change("Address", brain.url)
            warning = "This link sends your requests, and every screen the agent reads with them, to $host. " +
                "Droid does not know that provider. Only continue if you made this link yourself."
        }
        changes += Change("Model", brain.model?.let(Models::pretty) ?: "The provider's default")
        changes += Change("Key", brain.key?.let(::lastFour) ?: "None")
        brain.effort?.let { changes += Change("Effort", it) }
    }
    when (val reach = setup.reach) {
        is SetupLink.Reach.Telegram -> {
            changes += Change("Talk", "Telegram bot ${reach.token.substringBefore(':')}")
            changes += Change("Allowed people", reach.users ?: "Nobody yet")
        }
        is SetupLink.Reach.Photon -> {
            changes += Change("Talk", "iMessage or WhatsApp through Photon")
            changes += Change("Photon project", reach.project)
            changes += Change("Photon key", lastFour(reach.key))
            changes += Change("Allowed people", reach.users ?: "Nobody yet")
        }
        SetupLink.Reach.App -> changes += Change("Talk", "In this app")
        null -> Unit
    }
    when (setup.mcp) {
        true -> changes += Change("Other agents (MCP)", "On, with a new key")
        false -> changes += Change("Other agents (MCP)", "Off")
        null -> Unit
    }
    setup.relayUrl?.let { url ->
        val host = Lan.host(url) ?: url
        changes += Change("Relay", host)
        if (Relay.selfHosted(url)) {
            caution = "The relay is $host rather than Aster Relay. Paired computers reach the phone through it."
        }
    }
    setup.claim?.let {
        val through = setup.relayUrl != null || Relay.selfHosted(ownRelay)
        changes += Change("Pair", if (through) "${it.name}, through the relay" else "Not yet: Aster Relay is coming soon")
    }
    return SetupSummary(changes, warning, caution)
}

private fun lastFour(key: String) = if (key.length <= 4) "••••" else "••••" + key.takeLast(4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupConfirmSheet(summary: SetupSummary, onApply: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.ground,
    ) {
        Column(
            Modifier
                .padding(horizontal = Gutter)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Use this setup?", style = Type.sheetTitle, color = Ink.text)
            Text(
                text = "A setup link wants to change these. Nothing is written until you say so.",
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(top = 6.dp),
            )
            GroupCard(Modifier.padding(top = 16.dp)) {
                summary.changes.forEachIndexed { i, change ->
                    ValueRow(change.label) {
                        Text(
                            text = change.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    if (i < summary.changes.lastIndex) RowDivider()
                }
            }
            summary.warning?.let {
                Text(text = it, style = Type.body, color = Ink.red, modifier = Modifier.padding(top = 14.dp))
            }
            summary.caution?.let {
                Text(text = it, style = Type.secondary, color = Ink.amber, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = "Apply and restart Droid", onClick = onApply, modifier = Modifier.fillMaxWidth())
            QuietButton(text = "Cancel", onClick = onCancel)
            Spacer(Modifier.height(12.dp))
        }
    }
}
