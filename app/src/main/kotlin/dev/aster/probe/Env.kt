package dev.aster.probe

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The `.env` the agent reads, as something the phone itself can edit. Keys used
 * to arrive only as a file pushed over adb, which is no help to a phone with no
 * computer near it, and this is the file the bridge needs a token in before
 * anyone can reach the phone at all.
 *
 * A write keeps every other line, comments included, so a file that was pushed
 * and then edited here does not lose what this screen knows nothing about.
 */
object Env {

    const val TELEGRAM_TOKEN = "ASTER_TELEGRAM_TOKEN"
    const val REMOTE_USERS = "ASTER_REMOTE_USERS"

    fun file(context: Context): File = File(context.filesDir, ".env")

    /** Every name that carries a value, which is what holding a key means here. */
    fun all(context: Context): Map<String, String> =
        parse(lines(context)).filterValues { it.isNotBlank() }

    fun value(context: Context, name: String): String? = all(context)[name]

    /** Set one name, or drop its line when the value is blank. */
    fun set(context: Context, name: String, value: String) {
        val wanted = value.trim()
        val kept = lines(context).filterNot { nameOf(it) == name }
        write(context, if (wanted.isEmpty()) kept else kept + "$name=$wanted")
    }

    /**
     * Fold a pushed file into the one on disk rather than over it. Overwriting
     * was fine while adb was the only way in; now the phone holds keys a push
     * may know nothing about.
     */
    fun merge(context: Context, dropped: File) {
        val incoming = parse(runCatching { dropped.readLines() }.getOrDefault(emptyList()))
        if (incoming.isEmpty()) return
        val kept = lines(context).filterNot { nameOf(it) in incoming.keys }
        write(context, kept + incoming.map { (name, value) -> "$name=$value" })
    }

    private fun lines(context: Context): List<String> =
        runCatching { file(context).readLines() }.getOrDefault(emptyList())

    private fun parse(lines: List<String>): Map<String, String> = lines
        .mapNotNull { line -> nameOf(line)?.let { it to line.substringAfter('=').trim() } }
        .toMap()

    /** The name a line sets, or null for a blank line, a comment, or noise. */
    private fun nameOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) return null
        return trimmed.substringBefore('=').trim().ifEmpty { null }
    }

    /** Through a temporary file, because a torn write here loses every key on the phone. */
    private fun write(context: Context, lines: List<String>) {
        val target = file(context)
        val temp = File(target.parentFile, ".env.writing")
        runCatching {
            temp.writeText(lines.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" })
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write .env: $it") }
    }

    private const val TAG = "ASTERENV"
}
