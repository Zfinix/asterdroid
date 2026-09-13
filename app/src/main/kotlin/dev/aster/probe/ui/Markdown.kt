package dev.aster.probe.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val fence = Regex("```[a-zA-Z]*\n?")

/** The markdown a chat reply carries, as styled text: bold, inline code, bullets, headings. */
fun markdown(text: String, query: String = ""): AnnotatedString = buildAnnotatedString {
    val lines = text.replace(fence, "").lines()
    lines.forEachIndexed { i, raw ->
        var line = raw
        var bold = false
        when {
            line.startsWith("#") -> {
                line = line.trimStart('#').trim()
                bold = true
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = line.length - line.trimStart().length
                line = " ".repeat(indent) + "•  " + line.trimStart().drop(2)
            }
            Regex("""^\s*\d+\.\s""").containsMatchIn(line) -> Unit
        }
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { spans(line, query) } else spans(line, query)
        if (i < lines.lastIndex) append("\n")
    }
}

/** `**bold**` and `` `code` `` inside one line, with search hits marked. */
private fun AnnotatedString.Builder.spans(line: String, query: String) {
    var rest = line
    while (rest.isNotEmpty()) {
        val bold = rest.indexOf("**")
        val code = rest.indexOf('`')
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(highlight(rest, query))
            return
        }
        append(highlight(rest.substring(0, next), query))
        rest = rest.substring(next)
        if (rest.startsWith("**")) {
            val end = rest.indexOf("**", 2)
            if (end < 0) {
                append(highlight(rest, query))
                return
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Ink.text)) { append(highlight(rest.substring(2, end), query)) }
            rest = rest.substring(end + 2)
        } else {
            val end = rest.indexOf('`', 1)
            if (end < 0) {
                append(highlight(rest, query))
                return
            }
            withStyle(Type.code.toSpanStyle().copy(color = Ink.text)) { append(highlight(rest.substring(1, end), query)) }
            rest = rest.substring(end + 1)
        }
    }
}
