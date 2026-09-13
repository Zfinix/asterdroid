package dev.aster.probe.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** The text with every hit of the search marked in the accent. */
fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) {
        append(text)
        return@buildAnnotatedString
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
