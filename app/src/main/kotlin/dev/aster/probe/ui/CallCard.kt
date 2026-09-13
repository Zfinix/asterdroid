package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aster.probe.Entry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

/** One tool call: what ran, how long, and what came back, tap to open the full output. */
@Composable
fun CallCard(call: Entry.Call, query: String, time: String = clock.format(Date(call.at))) {
    var open by remember { mutableStateOf(false) }
    val expandable = call.command != null || call.output.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressable { if (expandable) open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = call.tool,
                style = Type.code,
                color = Ink.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Ink.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = call.arg,
                style = Type.label,
                color = Ink.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = time + (call.durationMs?.let { " · ${"%.1f".format(it / 1000.0)}s" } ?: ""),
                style = Type.label,
                color = Ink.faint,
            )
        }
        call.command?.let {
            Text(
                text = highlight(it, query),
                style = Type.code,
                color = Ink.dim,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (call.status != Entry.Status.PENDING) {
            val (mark, tint) = when (call.status) {
                Entry.Status.OK -> Glyph.check to Ink.live
                Entry.Status.NO_CHANGE -> Glyph.dash to Ink.amber
                Entry.Status.ERROR -> Glyph.error to Ink.red
                Entry.Status.PENDING -> Glyph.dash to Ink.faint
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mark, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Text(
                    text = highlight(call.summary, query),
                    style = if (call.status == Entry.Status.NO_CHANGE) Type.label else Type.code,
                    color = if (call.status == Entry.Status.ERROR) Ink.red else Ink.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (expandable) {
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Glyph.caret,
                        contentDescription = null,
                        tint = Ink.faint,
                        modifier = Modifier.size(14.dp).rotate(if (open) 180f else 0f),
                    )
                }
            }
        }
        if (open && call.output.isNotEmpty()) {
            Text(
                text = highlight(call.output, query),
                style = Type.code,
                color = Ink.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
