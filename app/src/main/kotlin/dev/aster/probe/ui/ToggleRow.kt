package dev.aster.probe.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A setting that is either on or off, with what it does under its name. The
 * whole row is the target, as with [GrantRow], so the words and the switch
 * are one press.
 */
@Composable
fun ToggleRow(label: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable { if (enabled) onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Ink.text else Ink.faint)
            if (detail != null) {
                Text(
                    text = detail,
                    style = Type.secondary,
                    color = Ink.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(on)
    }
}

@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Ink.accent else Ink.line, label = "track")
    val knob by animateDpAsState(if (on) 18.dp else 2.dp, label = "knob")
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knob.roundToPx(), 0) }
                .padding(vertical = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) Ink.ground else Ink.dim)
                .align(Alignment.CenterStart),
        )
    }
}
