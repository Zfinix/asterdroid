package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aster.probe.Relay
import dev.aster.probe.RelayStatus
import kotlinx.coroutines.delay

/**
 * A one-time code for a computer to claim this phone through the relay: typed
 * at the relay's pairing page, or scanned there from the QR. A code alone
 * lets nobody in. The computer that claims it still waits here for a yes, so
 * a code read over a shoulder is worth nothing.
 */
@Composable
fun PairScreen(
    code: Relay.Code?,
    loading: Boolean,
    problem: String?,
    pending: List<RelayStatus.Pending>,
    onNew: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onCopy: (String, String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(code) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val left = code?.let { it.expires - now } ?: 0L
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .padding(horizontal = Gutter),
    ) {
        BackRow(title = "Pair a computer", onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Waiting(pending, onAnswer, Modifier.padding(top = 12.dp))
            when {
                loading -> Text(
                    text = "Asking the relay for a code",
                    style = Type.body,
                    color = Ink.dim,
                    modifier = Modifier.padding(top = 32.dp),
                )
                code == null -> Text(
                    text = problem ?: "No code yet.",
                    style = Type.body,
                    color = if (problem != null) Ink.red else Ink.dim,
                    modifier = Modifier.padding(top = 32.dp),
                )
                else -> {
                    val page = code.url.substringAfter("://").substringBefore('#')
                    Text(
                        text = "On the computer, open $page and type this code, or scan the QR with its camera.",
                        style = Type.body,
                        color = Ink.dim,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        text = code.shown,
                        style = Type.code.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
                        color = if (left > 0) Ink.text else Ink.faint,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        text = if (left > 0) "Works for ${left / 60}:${"%02d".format(left % 60)} more, once." else "This code has run out.",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (left > 0) Ink.dim else Ink.amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (left > 0) QrCode(text = code.url, modifier = Modifier.padding(top = 20.dp))
                    QuietButton(
                        text = "Copy the pairing link",
                        onClick = { onCopy("Pairing link", code.url, false) },
                        color = Ink.accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(Gutter))
        }
        PrimaryButton(
            text = if (code == null) "Get a code" else "New code",
            enabled = !loading,
            onClick = onNew,
            modifier = Modifier.padding(top = 8.dp, bottom = Gutter),
        )
    }
}
