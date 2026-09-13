package dev.aster.probe

import android.content.Context
import android.content.Intent

/**
 * What the agent is doing, in one place. Both halves write here: the
 * accessibility service records every verb it serves, and the agent service
 * records the process output. The activity renders it live.
 *
 * Without this the only way to see inside a run is `adb logcat`, which is not
 * available to someone holding the phone.
 */
object ActivityLog {

    const val CHANGED = "dev.aster.probe.LOG_CHANGED"
    private const val KEEP = 300

    enum class Kind { VERB, AGENT, STATE }

    data class Entry(val at: Long, val kind: Kind, val text: String)

    private val entries = ArrayDeque<Entry>()

    @Volatile var state: String = "idle"
        private set

    fun verb(context: Context?, line: String) = add(context, Kind.VERB, line)

    fun agent(context: Context?, line: String) = add(context, Kind.AGENT, line)

    fun state(context: Context?, line: String) {
        state = line
        add(context, Kind.STATE, line)
    }

    /** A new run starts with an empty feed, so what shows is what this agent did. */
    fun clear(context: Context?) {
        synchronized(entries) { entries.clear() }
        context?.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }

    fun recent(limit: Int = KEEP): List<Entry> = synchronized(entries) {
        entries.toList().takeLast(limit)
    }

    private fun add(context: Context?, kind: Kind, text: String) {
        if (text.isBlank()) return
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), kind, text.trim()))
            while (entries.size > KEEP) entries.removeFirst()
        }
        // The activity may not be running; a broadcast nobody hears is free.
        context?.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }
}
