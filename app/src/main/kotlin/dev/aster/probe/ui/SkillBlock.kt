package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One paragraph of a skill or memory file, typed by what it is. */
sealed class Block(val text: String) {
    class Heading(text: String) : Block(text)
    class Callout(text: String) : Block(text)
    class Step(val number: Int, text: String) : Block(text)
    class Body(text: String) : Block(text)
}

private val stepRegex = Regex("""^(\d+)\.\s+(.*)$""")
private val recordRegex = Regex("""^Best so far: (.+?) \(session (\S+), (\d{4})-(\d{2})-(\d{2})\)\. Beat it\.$""")
private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The harness's record line, shortened for a phone: `01M28…D7G5G, 11 Sep` instead of the full id and date. */
private fun record(text: String): String {
    val m = recordRegex.matchEntire(text) ?: return text
    val (score, id, _, month, day) = m.destructured
    val short = if (id.length > 12) "${id.take(5)}…${id.takeLast(5)}" else id
    val mon = months.getOrElse(month.toInt() - 1) { month }
    return "$score. Session $short, ${day.toInt()} $mon. Beat it."
}

fun parseBlocks(lines: List<String>): List<Block> {
    val out = mutableListOf<Block>()
    var first = true
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val step = stepRegex.matchEntire(line)
        out += when {
            line.startsWith("#") -> Block.Heading(line.trimStart('#').trim())
            first && !line.startsWith(">") -> Block.Heading(line)
            line.startsWith(">") -> Block.Callout(record(line.removePrefix(">").trim()))
            step != null -> Block.Step(step.groupValues[1].toInt(), step.groupValues[2])
            else -> Block.Body(line)
        }
        first = false
    }
    return out
}

@Composable
fun BlockView(block: Block, query: String) {
    when (block) {
        is Block.Heading -> Text(
            text = inlineCode(block.text, query),
            style = Type.proseHeading,
            color = Ink.text,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        is Block.Callout -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Glyph.trophy, contentDescription = null, tint = Ink.accent, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(text = inlineCode(block.text, query), style = Type.secondary, color = Ink.text)
        }
        is Block.Step -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "${block.number}.", style = Type.prose, color = Ink.faint)
            Text(text = inlineCode(block.text, query), style = Type.prose, color = Ink.dim)
        }
        is Block.Body -> Text(text = inlineCode(block.text, query), style = Type.prose, color = Ink.dim)
    }
}

private fun inlineCode(text: String, query: String): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { i, part ->
        if (i % 2 == 1) {
            withStyle(Type.code.toSpanStyle().copy(color = Ink.text)) {
                highlight(part, query)
            }
        } else {
            highlight(part, query)
        }
    }
}

private fun AnnotatedString.Builder.highlight(text: String, query: String) {
    val q = query.trim()
    if (q.isEmpty()) {
        append(text)
        return
    }
    var idx = 0
    while (idx < text.length) {
        val hit = text.indexOf(q, idx, ignoreCase = true)
        if (hit < 0) {
            append(text.substring(idx))
            break
        }
        append(text.substring(idx, hit))
        withStyle(SpanStyle(background = Ink.accent.copy(alpha = 0.35f))) {
            append(text.substring(hit, hit + q.length))
        }
        idx = hit + q.length
    }
}
