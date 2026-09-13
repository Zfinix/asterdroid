package dev.aster.probe.ui

import dev.aster.probe.Entry

val logFilters = listOf("All", "Calls", "Errors", "No change", "Messages")

/** What the chips and the search leave in: rules stay only when something follows them. */
fun filterLog(entries: List<Entry>, filter: String, query: String): List<Entry> {
    val q = query.trim()
    val kept = entries.filter { e ->
        val byFilter = when (filter) {
            "Calls" -> e is Entry.Call
            "Errors" -> (e is Entry.Call && e.status == Entry.Status.ERROR) || (e is Entry.Event && e.kind == Entry.EventKind.ERROR)
            "No change" -> e is Entry.Call && e.status == Entry.Status.NO_CHANGE
            "Messages" -> e is Entry.Message || e is Entry.Event
            else -> true
        }
        (e is Entry.Turn || byFilter) && (q.isEmpty() || e is Entry.Turn || e.text.contains(q, true))
    }
    return kept.filterIndexed { i, e -> e !is Entry.Turn || kept.getOrNull(i + 1).let { it != null && it !is Entry.Turn } }
}

/** A run of calls shares one card; everything else stands alone. */
sealed class Piece {
    class One(val entry: Entry) : Piece()
    class Calls(val calls: List<Entry.Call>) : Piece()
}

fun groupCalls(entries: List<Entry>): List<Piece> {
    val out = mutableListOf<Piece>()
    var run = mutableListOf<Entry.Call>()
    for (e in entries) {
        if (e is Entry.Call) {
            run += e
        } else {
            if (run.isNotEmpty()) {
                out += Piece.Calls(run)
                run = mutableListOf()
            }
            out += Piece.One(e)
        }
    }
    if (run.isNotEmpty()) out += Piece.Calls(run)
    return out
}
