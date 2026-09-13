package dev.aster.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A skill or memory file to read: its name, a line about it, and the lines once read. */
data class SkillFile(val name: String, val meta: String, val lines: List<String>?)

/** The file as the agent reads it, in a sheet, with a search that highlights its hits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillSheet(skill: SkillFile, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val blocks = remember(skill.lines) { parseBlocks(skill.lines.orEmpty()) }
    val visible = remember(query, blocks) {
        val q = query.trim()
        if (q.isEmpty()) blocks else blocks.filter { it.text.contains(q, true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
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
                    .padding(start = Gutter, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skill.name,
                    style = Type.sheetTitle,
                    color = Ink.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pressable(onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.close, contentDescription = "Close", tint = Ink.dim, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = when (skill.lines) {
                    null -> skill.meta
                    else -> "${skill.meta} · ${skill.lines.size} lines"
                },
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(horizontal = Gutter),
            )
            Spacer(Modifier.height(12.dp))
            SearchField(
                query = query,
                hint = "Search this file",
                onQuery = { query = it },
                modifier = Modifier.padding(horizontal = Gutter),
            )
            when {
                skill.lines == null -> Text(
                    text = "Loading…",
                    style = Type.secondary,
                    color = Ink.faint,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                )
                visible.isEmpty() -> Text(
                    text = if (query.isBlank()) "This file is empty." else "No line contains “$query”.",
                    style = Type.secondary,
                    color = Ink.faint,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                )
                else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible) { block -> BlockView(block, query) }
                    }
                    TopFade(Modifier.align(Alignment.TopCenter), color = Ink.surface)
                }
            }
        }
    }
}
