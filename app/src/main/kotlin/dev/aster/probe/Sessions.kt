package dev.aster.probe

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import org.json.JSONObject

/** One recorded conversation, with what it cost, read from the agent's transcript. */
data class Session(
    val id: String,
    val file: File,
    val createdAt: Long,
    val lastAt: Long,
    val title: String?,
    val model: String?,
    val provider: String?,
    val turns: Int,
    val rounds: Int,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val errors: Int,
    val noChange: Int,
    val shots: Int,
) {
    val label: String get() = title ?: firstRequest ?: id
    var firstRequest: String? = null
}

/** One thing in a session's log: a turn boundary, a message, a tool call, or a title. */
sealed class Entry(val at: Long, val text: String) {
    class Turn(at: Long, val number: Int) : Entry(at, "Turn $number")
    class Message(at: Long, text: String, val agent: Boolean) : Entry(at, text)
    class Title(at: Long, text: String) : Entry(at, text)
    class Event(at: Long, text: String, val kind: EventKind, val detail: String?) : Entry(at, text)
    class Call(
        at: Long,
        val tool: String,
        val arg: String,
        val command: String?,
        val durationMs: Long?,
        val status: Status,
        val summary: String,
        val output: String,
    ) : Entry(at, "$tool $arg $command $summary $output")

    enum class Status { OK, NO_CHANGE, ERROR, PENDING }
    enum class EventKind { START, STOP, NOTE, ERROR }
}

/** A skill on the phone; `learned` when the learning loop wrote it and keeps a ledger. */
data class LearnedSkill(
    val name: String,
    val description: String,
    val file: File,
    val learned: Boolean,
    val always: Boolean,
    val runs: Int,
    val bestRounds: Int?,
    val bestCalls: Int?,
)

/** One block of the agent's memory: a name, a line about it, and the file to read. */
data class Note(val name: String, val description: String, val file: File)

/** The transcripts the agent writes under its data home, read off the main thread. */
object Sessions {
    private const val TAG = "ASTERSESSIONS"
    private const val HOME = ".local/share/aster"
    private const val CLIP = 200

    fun dir(context: Context): File = File(context.filesDir, "$HOME/sessions")

    fun skillsDir(context: Context): File = File(context.filesDir, "$HOME/skills")

    fun memoryDir(context: Context): File = File(context.filesDir, "$HOME/memory")

    /** Every session, newest first. Reads each file once for its stats. */
    fun list(context: Context): List<Session> {
        val files = dir(context).walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
        return files.mapNotNull { summarize(it) }.sortedByDescending { it.createdAt }
    }

    private fun summarize(file: File): Session? {
        var id: String? = null
        var createdAt = 0L
        var lastAt = 0L
        var title: String? = null
        var model: String? = null
        var provider: String? = null
        var firstRequest: String? = null
        var turns = 0
        var rounds = 0
        var calls = 0
        var prompt = 0L
        var completion = 0L
        var errors = 0
        var noChange = 0
        var shots = 0
        forEachObject(file) { obj ->
            when (obj.optString("type")) {
                "session" -> {
                    id = obj.optString("id").ifEmpty { null }
                    createdAt = millis(obj.optString("created_at"))
                    model = obj.optString("model").ifEmpty { null }
                    provider = providerName(obj.optString("base_url"))
                    title = obj.optString("title").ifEmpty { null }
                }
                "title" -> title = obj.optString("title").ifEmpty { title }
                "message" -> {
                    lastAt = maxOf(lastAt, millis(obj.optString("ts")))
                    val content = obj.optString("content")
                    when (obj.optString("role")) {
                        "user" -> if (!obj.has("tool_call_id")) {
                            turns++
                            if (firstRequest == null) firstRequest = request(content).take(CLIP)
                        }
                        "assistant" -> {
                            val toolCalls = obj.optJSONArray("tool_calls")
                            if (toolCalls != null && toolCalls.length() > 0) {
                                rounds++
                                calls += toolCalls.length()
                                for (i in 0 until toolCalls.length()) {
                                    val args = toolCalls.getJSONObject(i)
                                        .optJSONObject("function")?.optString("arguments").orEmpty()
                                    if (args.contains("asterctl") && args.contains("\"shot\"")) shots++
                                }
                            }
                            obj.optJSONObject("usage")?.let {
                                prompt += it.optLong("prompt_tokens")
                                completion += it.optLong("completion_tokens")
                            }
                        }
                        "tool" -> {
                            if (content.trimStart().startsWith("error: ")) errors++
                            if (content.contains("changed: +0 -0")) noChange++
                        }
                    }
                }
            }
        }
        val sessionId = id ?: return null
        return Session(
            id = sessionId,
            file = file,
            createdAt = createdAt,
            lastAt = if (lastAt == 0L) createdAt else lastAt,
            title = title,
            model = model,
            provider = provider,
            turns = turns,
            rounds = rounds,
            calls = calls,
            promptTokens = prompt,
            completionTokens = completion,
            errors = errors,
            noChange = noChange,
            shots = shots,
        ).also { it.firstRequest = firstRequest }
    }

    /** The session as a log to read and search, calls joined to their results. */
    fun entries(file: File): List<Entry> {
        val out = mutableListOf<Entry>()
        val pending = LinkedHashMap<String, Int>()
        var turn = 0
        forEachObject(file) { obj ->
            val at = millis(obj.optString("ts"))
            when (obj.optString("type")) {
                "title" -> out += Entry.Title(at, obj.optString("title"))
                "message" -> {
                    val content = obj.optString("content")
                    when (obj.optString("role")) {
                        "user" -> if (!obj.has("tool_call_id")) {
                            turn++
                            out += Entry.Turn(at, turn)
                            out += Entry.Message(at, request(content), agent = false)
                        }
                        "assistant" -> {
                            if (content.isNotBlank()) out += Entry.Message(at, content.trim(), agent = true)
                            val toolCalls = obj.optJSONArray("tool_calls") ?: return@forEachObject
                            for (i in 0 until toolCalls.length()) {
                                val call = toolCalls.getJSONObject(i)
                                val fn = call.optJSONObject("function") ?: continue
                                val (tool, arg, command) = describe(fn.optString("name"), fn.optString("arguments"))
                                pending[call.optString("id")] = out.size
                                out += Entry.Call(at, tool, arg, command, null, Entry.Status.PENDING, "", "")
                            }
                        }
                        "tool" -> {
                            val index = pending.remove(obj.optString("tool_call_id")) ?: return@forEachObject
                            val call = out[index] as? Entry.Call ?: return@forEachObject
                            val status = when {
                                content.trimStart().startsWith("error: ") -> Entry.Status.ERROR
                                content.contains("changed: +0 -0") -> Entry.Status.NO_CHANGE
                                else -> Entry.Status.OK
                            }
                            out[index] = Entry.Call(
                                call.at, call.tool, call.arg, call.command,
                                (at - call.at).takeIf { it >= 0 }, status, resultSummary(content, status), cleanOutput(content),
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    /** A call as a person would name it: `tap` and `element 3`, not run_command and JSON. */
    private fun describe(name: String, arguments: String): Triple<String, String, String?> {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return Triple(name, arguments.take(CLIP), null)
        if (name != "run_command") {
            val arg = listOf("path", "query", "pattern", "name", "note", "text")
                .firstNotNullOfOrNull { args.optString(it).ifEmpty { null } }
            return Triple(name, arg?.take(CLIP).orEmpty(), null)
        }
        val command = args.optString("command")
        val list = args.optJSONArray("args")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        return when {
            command == "asterctl" && list.isNotEmpty() -> {
                val verb = list[0]
                val rest = list.drop(1).joinToString(" ")
                val arg = if (verb == "tap" && rest.toIntOrNull() != null) "element $rest" else rest
                Triple(verb, arg, null)
            }
            command == "sh" && list.size >= 2 && list[0] == "-c" -> Triple("run_command", "sh", list[1])
            command == "aster" && list.firstOrNull() == "python" -> Triple("python", list.drop(1).take(1).joinToString(), list.drop(2).joinToString(" ").ifEmpty { null })
            else -> Triple(name, command, list.joinToString(" ").ifEmpty { null })
        }
    }

    fun resultSummary(content: String, status: Entry.Status): String {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !plumbing(it) }
        val changed = lines.firstOrNull { it.startsWith("changed: ") }
        return when {
            status == Entry.Status.ERROR -> lines.firstOrNull { it.startsWith("error: ") }?.removePrefix("error: ").orEmpty()
            status == Entry.Status.NO_CHANGE -> "No change on screen (+0 −0)"
            changed != null -> {
                val words = changed.removePrefix("changed: ").split(' ')
                val pkg = words.firstOrNull { it.startsWith("pkg=") }?.removePrefix("pkg=")
                listOfNotNull(words.getOrNull(0)?.let { "changed $it ${words.getOrNull(1).orEmpty().replace('-', '−')}" }, pkg).joinToString(" · ")
            }
            lines.firstOrNull()?.startsWith("pkg=") == true -> {
                val words = lines[0].split(' ')
                val n = words.firstNotNullOfOrNull { w -> w.substringAfter("elements=", "").ifEmpty { null } ?: w.substringAfter("matches=", "").ifEmpty { null } }
                listOfNotNull(n?.let { "$it elements" }, words[0].removePrefix("pkg=")).joinToString(" · ")
            }
            else -> lines.take(4).joinToString(" · ").take(CLIP)
        }
    }

    private fun cleanOutput(content: String): String =
        content.lines().filter { !plumbing(it.trim()) }.joinToString("\n").trim()

    private fun plumbing(line: String): Boolean =
        line == "stdout:" || line == "stderr:" || line.startsWith("exit code:") ||
            (line.startsWith("(") && line.endsWith("ms)")) || line.startsWith("receipt: posted")

    /** Every skill the agent can load, learned ones first, newest first within each. */
    fun skills(context: Context): List<LearnedSkill> {
        val roots = listOf(File(context.filesDir, ".aster/skills"), skillsDir(context))
        val dirs = roots.flatMap { it.listFiles { f -> f.isDirectory }.orEmpty().toList() }
        return dirs.mapNotNull { dir ->
            val file = File(dir, "SKILL.md")
            if (!file.exists()) return@mapNotNull null
            val ledger = File(dir, "runs.jsonl")
            var runs = 0
            var bestRounds: Int? = null
            var bestCalls: Int? = null
            forEachObject(ledger) { obj ->
                runs++
                val score = obj.optJSONObject("score") ?: return@forEachObject
                val rounds = score.optInt("rounds")
                val calls = score.optInt("calls")
                val better = bestRounds == null || rounds < bestRounds!! ||
                    (rounds == bestRounds && calls < (bestCalls ?: Int.MAX_VALUE))
                if (better) {
                    bestRounds = rounds
                    bestCalls = calls
                }
            }
            val front = frontmatter(file)
            val always = frontValue(front, "always")?.let { it == "true" || it == "yes" } ?: false
            LearnedSkill(
                name = frontValue(front, "name") ?: dir.name,
                description = blurb(frontValue(front, "description").orEmpty()),
                file = file,
                learned = ledger.exists(),
                always = always,
                runs = runs,
                bestRounds = bestRounds,
                bestCalls = bestCalls,
            )
        }.distinctBy { it.name }
            .sortedWith(compareByDescending<LearnedSkill> { it.learned }.thenByDescending { it.file.lastModified() })
    }

    /** The memory blocks, newest first; the project file and the journal are not blocks. */
    fun notes(context: Context): List<Note> {
        val files = memoryDir(context).listFiles { f -> f.isFile && f.extension == "md" && f.name != "ASTER.md" }
        return files.orEmpty().map { file ->
            val front = frontmatter(file)
            Note(
                name = frontValue(front, "name") ?: file.nameWithoutExtension,
                description = frontValue(front, "description").orEmpty(),
                file = file,
            )
        }.sortedByDescending { it.file.lastModified() }
    }

    /** A file as lines to read on screen, frontmatter dropped, tolerant of a missing file. */
    fun read(file: File): List<String> {
        val raw = runCatching { file.readText() }.getOrElse { return listOf("Could not read ${file.name}: $it") }
        val body = if (raw.startsWith("---\n")) raw.substringAfter("\n---\n", raw) else raw
        return body.trimEnd().lines()
    }

    private fun frontmatter(file: File): String =
        runCatching { file.readText() }.getOrDefault("").removePrefix("---\n").substringBefore("\n---\n")

    /** The trigger line as a person reads it: no "Use when the user asks to", first letter up. */
    private fun blurb(description: String): String {
        val plain = description
            .removePrefix("Use when the user asks to ")
            .removePrefix("Use when the user asks ")
            .removePrefix("Use when ")
            .trim()
        return plain.replaceFirstChar { it.uppercase() }
    }

    /** The host of the endpoint as a name a person knows: `openrouter.ai` is OpenRouter. */
    private fun providerName(baseUrl: String): String? {
        val host = baseUrl.substringAfter("://").substringBefore('/').ifEmpty { return null }
        return when {
            host.contains("openrouter") -> "OpenRouter"
            host.contains("fireworks") -> "Fireworks AI"
            host.contains("baseten") -> "Baseten"
            host.contains("deepseek") -> "DeepSeek"
            host.contains("anthropic") -> "Anthropic"
            host.contains("openai") -> "OpenAI"
            host.contains("z.ai") || host.contains("bigmodel") -> "Z.ai"
            else -> host.removePrefix("api.").removePrefix("www.")
        }
    }

    private fun frontValue(front: String, key: String): String? =
        front.lines().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.ifEmpty { null }

    /** The file is appended to while it is read, so a torn last line is expected, not fatal. */
    private fun forEachObject(file: File, each: (JSONObject) -> Unit) {
        runCatching {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    each(obj)
                }
            }
        }.onFailure { Log.w(TAG, "reading ${file.name}: $it") }
    }

    private fun millis(ts: String): Long =
        runCatching { Instant.parse(ts).toEpochMilli() }.getOrDefault(0L)

    /** The request as the person wrote it, without the chat prefix and the `[msg N]` tag. */
    private fun request(content: String): String {
        val fromTag = content.lastIndexOf("[msg ").let { if (it >= 0) content.substring(it) else content }
        val body = if (fromTag.startsWith("[msg ")) fromTag.substringAfter('\n', "") else fromTag
        return body.trim()
    }

}

/** `just now`, `4m`, `3h`, `2d`: how long ago, for a feed that is still live. */
fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val secs = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        secs < 45 -> "just now"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}

/** `2h 14m`, `38s`: how long something has been going. */
fun duration(ms: Long): String {
    val secs = ms / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** `158K`, `2.1M`, `2M`: a token count that fits a tile. */
fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1f".format(n / 1_000_000.0).removeSuffix(".0") + "M"
    n >= 1_000 -> "%.1f".format(n / 1_000.0).removeSuffix(".0") + "K"
    else -> n.toString()
}
