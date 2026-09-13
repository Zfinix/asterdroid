package dev.aster.probe

/** The live activity lines as log entries, so the home feed reads like a session. */
object Feed {
    private val step = Regex("""^(?:\[\d+\]\s*)?→ (\w+)(?::\s*(.*))?$""")
    private val chat = Regex("""^(?:\[\d+\]\s*)?(user|✔ done|queued|✗ error|error): ?(.*)$""")
    private val tag = Regex("""^\[msg \d+[^\]]*\]\s*""")
    private val receipt = Regex("""^receipt: posted \((.*)\)$""")

    fun entries(lines: List<ActivityLog.Entry>): List<Entry> {
        val out = mutableListOf<Entry>()
        var lastCall = -1
        fun settle(status: Entry.Status, summary: String, arg: String? = null) {
            val call = out.getOrNull(lastCall) as? Entry.Call ?: return
            if (call.status != Entry.Status.PENDING) return
            out[lastCall] = Entry.Call(call.at, call.tool, arg ?: call.arg, call.command, null, status, summary, "")
        }
        lines.forEachIndexed { i, line ->
            val text = line.text.trim()
            when (line.kind) {
                ActivityLog.Kind.VERB -> {
                    val verb = text.substringBefore(' ')
                    val rest = text.substringAfter(' ', "")
                    val arg = if (verb == "tap" && rest.toIntOrNull() != null) "element $rest" else rest
                    lastCall = out.size
                    out += Entry.Call(line.at, verb, arg, null, null, Entry.Status.PENDING, "", "")
                }
                ActivityLog.Kind.AGENT -> {
                    val plain = text.replace(Regex("""^\[\d+\]\s*"""), "")
                    val previousWasVerb = lines.getOrNull(i - 1)?.kind == ActivityLog.Kind.VERB
                    when {
                        previousWasVerb -> {
                            val status = when {
                                plain.startsWith("error: ") -> Entry.Status.ERROR
                                plain.contains("+0 -0") -> Entry.Status.NO_CHANGE
                                else -> Entry.Status.OK
                            }
                            val call = out.getOrNull(lastCall) as? Entry.Call
                            val label = receipt.matchEntire(plain)?.groupValues?.get(1)
                                ?.substringBefore(" via ")?.takeIf { it.startsWith("\"") }
                            val arg = if (call != null && label != null && call.arg.startsWith("element")) "${call.arg} · $label" else null
                            settle(status, Sessions.resultSummary(plain, status), arg)
                        }
                        plain.trim() == "✓ ok" -> settle(Entry.Status.OK, "ok")
                        plain.trim().startsWith("✗") -> settle(Entry.Status.ERROR, plain.trim().removePrefix("✗").trim())
                        step.matches(plain) -> {
                            val m = step.matchEntire(plain)!!
                            lastCall = out.size
                            out += Entry.Call(line.at, m.groupValues[1], m.groupValues[2], null, null, Entry.Status.PENDING, "", "")
                        }
                        chat.matches(plain) -> {
                            val m = chat.matchEntire(plain)!!
                            val body = m.groupValues[2].replace(tag, "").trim()
                            when (m.groupValues[1]) {
                                "user" -> {
                                    out += Entry.Turn(line.at, out.count { it is Entry.Turn } + 1)
                                    out += Entry.Message(line.at, body, agent = false)
                                }
                                "✔ done" -> out += Entry.Message(line.at, body, agent = true)
                                "queued" -> out += Entry.Event(line.at, "Queued: $body", Entry.EventKind.NOTE, null)
                                else -> out += Entry.Event(line.at, "The turn failed", Entry.EventKind.ERROR, body)
                            }
                        }
                        plain.contains("error", ignoreCase = true) ->
                            out += Entry.Event(line.at, "Error", Entry.EventKind.ERROR, plain)
                        else -> out += Entry.Event(line.at, plain, Entry.EventKind.NOTE, null)
                    }
                }
                ActivityLog.Kind.STATE -> out += state(line.at, text)
            }
        }
        return out
    }

    /** A service note as an event a person would recognise: started, stopped, or what broke. */
    private fun state(at: Long, text: String): Entry.Event {
        val lower = text.lowercase()
        return when {
            lower.startsWith("the agent did not start") || lower.startsWith("the agent stopped") || lower.startsWith("this build has no agent") ->
                Entry.Event(at, text.substringBefore(": "), Entry.EventKind.ERROR, text.substringAfter(": ", "").ifEmpty { null })
            lower.startsWith("stopped") -> Entry.Event(at, "Stopped", Entry.EventKind.STOP, null)
            lower.startsWith("starting") || lower.startsWith("connected") || lower.startsWith("restarting") ->
                Entry.Event(at, text, Entry.EventKind.START, null)
            else -> Entry.Event(at, text.replaceFirstChar { it.uppercase() }, Entry.EventKind.NOTE, null)
        }
    }
}
