package dev.aster.probe.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.aster.probe.R

/** Mark and wordmark at one height, so the star's top and bottom meet the A's cap and baseline. */
@Composable
fun Lockup(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.aster_mark),
            contentDescription = null,
            modifier = Modifier.size(LockupHeight),
        )
        Image(
            painter = painterResource(R.drawable.aster_wordmark),
            contentDescription = "Aster",
            colorFilter = ColorFilter.tint(Ink.text),
            modifier = Modifier.height(LockupHeight),
        )
    }
}

private val LockupHeight = 30.dp
