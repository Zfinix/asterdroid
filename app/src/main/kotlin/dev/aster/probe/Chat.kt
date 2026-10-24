package dev.aster.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chat inside the app, as one conversation with an `aster acp` child.
 *
 * It lives in the process rather than the activity because the agent's first
 * act is usually to open another app, which pauses this one; the service opens
 * it and closes it, so the turn survives the screen it was typed on. Everything
 * the screen reads is snapshot state, changed only on the main thread, so the
 * chat redraws itself without the broadcast the activity feed uses per line.
 */
object Chat {

    sealed interface Item {
        val key: String
    }

    data class Said(override val key: String, val text: String, val agent: Boolean) : Item

    data class Thought(override val key: String, val text: String) : Item

    /** A tool call as the log already draws it, so a chat turn and a session read the same. */
    data class Tool(override val key: String, val call: Entry.Call) : Item

    data class Plan(override val key: String, val steps: List<Step>) : Item

    /** An approval or a question. [chosen] is the answer's name once there is one. */
    data class Ask(
        override val key: String,
        val title: String,
        val body: String,
        val options: List<Option>,
        val question: Boolean,
        val chosen: String?,
    ) : Item

    data class Notice(override val key: String, val text: String, val error: Boolean) : Item

    data class Option(val id: String, val name: String, val kind: String)

    data class Step(val text: String, val status: String)

    val items = mutableStateListOf<Item>()

    /** The service is up and the chat can take a message. */
    var open by mutableStateOf(false)
        private set

    /** A turn is running; the composer steers it rather than starting another. */
    var busy by mutableStateOf(false)
        private set

    /** The child is being started or a saved conversation loaded back. */
    var starting by mutableStateOf(false)
        private set

    var title by mutableStateOf<String?>(null)
        private set

    /** Whether someone is looking at the chat, which is when a notification would only repeat it. */
    @Volatile var visible = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aster-chat") }
    /** The application context, never an activity, so holding it here leaks nothing. */
    private var context: Context? = null
    @Volatile private var client: AcpClient? = null
    @Volatile private var session: String? = null
    /**
     * Set from [close] until the next [open]. A job queued on the worker before
     * the service stopped still runs after it, and must not start a child that
     * nothing would ever stop; [lock] keeps that check and the child's
     * publishing in one step against [close].
     */
    @Volatile private var closed = true
    private val lock = Any()
    private val asks = HashMap<String, Any>()
    /**
     * This turn's tool calls by the agent's id, to the item that draws each and
     * when it began. Providers reuse ids across turns (`call_0` every time), so
     * an id only names a call within the turn it came from.
     */
    private val tools = HashMap<String, Pair<String, Long>>()
    private var serial = 0L
    @Volatile private var lastProgress = 0L

    /** From the service as it starts. The child itself waits for the first use. */
    fun open(service: Context) {
        context = service.applicationContext
        closed = false
        main.post { open = true }
    }

    /** From the service as it stops: the child goes with it, and so does any turn. */
    fun close() {
        synchronized(lock) {
            closed = true
            client?.close()
            client = null
            session = null
        }
        main.post {
            open = false
            busy = false
            starting = false
            settleAsks()
        }
        context?.let { cancelNotes(it) }
    }

    /**
     * The activity saying whether the chat is on screen. Leaving it with a
     * question still open moves the question to a notification, since the
     * screen that held its buttons is gone.
     */
    fun seen(shown: Boolean) {
        val was = visible
        visible = shown
        if (!was || shown) return
        val ctx = context ?: return
        val open = synchronized(asks) { asks.keys.toSet() }
        items.lastOrNull { it is Ask && it.key in open }?.let { askNote(ctx, it as Ask) }
    }

    /** Opening the chat starts the agent and loads the last conversation, so it is there to read. */
    fun warm() {
        if (!open || client?.alive == true) return
        worker.execute { runCatching { ensure() }.onFailure { failed(it) } }
    }

    fun send(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        if (!open) {
            add(Notice(key(), "Start the agent to chat.", error = false))
            return
        }
        if (busy) {
            steer(said)
            return
        }
        busy = true
        worker.execute { turn(said) }
    }

    /** Ask the agent to stop; the turn then ends as cancelled, which is an ordinary end. */
    fun stop() {
        val id = session ?: return
        client?.notify("session/cancel", JSONObject().put("sessionId", id))
        main.post { settleAsks() }
    }

    /** The person's answer to an approval or a question; null declines it. */
    fun answer(key: String, optionId: String?) {
        main.post {
            val at = items.indexOfFirst { it.key == key }
            val ask = items.getOrNull(at) as? Ask ?: return@post
            if (ask.chosen != null) return@post
            val id = synchronized(asks) { asks.remove(key) } ?: return@post
            val picked = ask.options.firstOrNull { it.id == optionId }
            items[at] = ask.copy(chosen = picked?.name ?: "Declined")
            val outcome = if (picked == null) JSONObject().put("outcome", "cancelled")
            else JSONObject().put("outcome", "selected").put("optionId", picked.id)
            client?.respond(id, JSONObject().put("outcome", outcome))
            context?.let { notes(it).cancel(NotificationIds.CHAT_ASK) }
        }
    }

    /** Forget the conversation; the next message starts a new one. */
    fun fresh() {
        if (busy) return
        session = null
        context?.let { prefs(it).edit().remove(SESSION).apply() }
        items.clear()
        title = null
    }

    private fun turn(text: String) {
        runCatching {
            val (agent, id) = ensure()
            main.post {
                tools.clear()
                items.add(Said(key(), text, agent = false))
            }
            val prompt = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            val done = agent.call("session/prompt", JSONObject().put("sessionId", id).put("prompt", prompt))
            if (done.optString("stopReason") == "cancelled") add(Notice(key(), "Stopped.", error = false))
        }.onFailure { failed(it) }
        main.post {
            busy = false
            settleAsks()
        }
        context?.let { finished(it) }
    }

    /**
     * A message typed mid-turn joins the turn in flight instead of queueing
     * behind it. The agent echoes it back as the person's words, which is what
     * draws it, so nothing is added here unless the turn had already ended.
     */
    private fun steer(text: String) {
        val id = session
        val agent = client
        if (id == null || agent == null) {
            unsent(text)
            return
        }
        thread(name = "aster-steer") {
            val prompt = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            val params = JSONObject().put("sessionId", id).put("prompt", prompt)
                .put("_meta", JSONObject().put("steerOnly", true))
            val steered = runCatching {
                agent.call("session/prompt", params).optJSONObject("_meta")?.optBoolean("steered") == true
            }.getOrDefault(false)
            if (!steered) main.post { if (busy) unsent(text) else send(text) }
        }
    }

    private fun unsent(text: String) {
        val shown = if (text.length > 60) text.take(59) + "…" else text
        add(Notice(key(), "Not sent: \"$shown\". Send it again once this turn ends.", error = true))
    }

    /**
     * The running child and its session: started, initialised, and the saved
     * conversation loaded back, or a new one. Loading replays the earlier turns
     * as updates, so the list is emptied first and filled again by the replay.
     */
    private fun ensure(): Pair<AcpClient, String> {
        val ctx = context?.takeUnless { closed } ?: throw AcpClient.Failure("Start the agent to chat.", 0)
        val running = client?.takeIf { it.alive }
        val known = session
        if (running != null && known != null) return running to known
        main.post { starting = true }
        try {
            val agent = running ?: spawn(ctx)
            val base = JSONObject().put("cwd", ctx.filesDir.absolutePath).put("mcpServers", JSONArray())
            val loaded = prefs(ctx).getString(SESSION, null)?.let { wanted ->
                main.post {
                    items.clear()
                    title = null
                }
                runCatching { agent.call("session/load", JSONObject(base.toString()).put("sessionId", wanted)) }
                    .onFailure { Log.i(TAG, "could not load the last chat: $it") }
                    .map { it.optString("sessionId").ifEmpty { wanted } }
                    .getOrNull()
            }
            val id = loaded ?: agent.call("session/new", base).optString("sessionId").also {
                prefs(ctx).edit().putString(SESSION, it).apply()
            }
            session = id
            return agent to id
        } finally {
            main.post { starting = false }
        }
    }

    private fun spawn(ctx: Context): AcpClient {
        val binary = File(ctx.applicationInfo.nativeLibraryDir, "libaster.so")
        if (!binary.exists()) throw AcpClient.Failure("This build has no agent. Install the app again.", 0)
        val process = ProcessBuilder(binary.absolutePath, "acp", "--permission-mode", MODE)
            .directory(ctx.filesDir)
            .apply { AsterAgentService.environment(ctx, environment()) }
            .start()
        val agent = AcpClient(process, Listener)
        synchronized(lock) {
            if (closed) {
                agent.close()
                throw AcpClient.Failure("Start the agent to chat.", 0)
            }
            client = agent
        }
        val info = JSONObject().put("name", "droid").put("title", "Droid").put("version", version(ctx))
        val caps = JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))
        agent.call(
            "initialize",
            JSONObject().put("protocolVersion", 1).put("clientCapabilities", caps).put("clientInfo", info),
        )
        return agent
    }

    private object Listener : AcpClient.Listener {
        override fun onUpdate(params: JSONObject) {
            val update = params.optJSONObject("update") ?: return
            main.post { apply(update) }
            context?.let { progress(it, update) }
        }

        override fun onPermission(id: Any, params: JSONObject) {
            val call = params.optJSONObject("toolCall") ?: JSONObject()
            val options = params.optJSONArray("options").objects().map {
                Option(it.optString("optionId"), it.optString("name"), it.optString("kind"))
            }
            val ask = Ask(
                key = key(),
                title = call.optString("title").ifEmpty { "The agent wants to go ahead" },
                body = text(call.opt("content")),
                options = options,
                question = call.optString("kind") == "think",
                chosen = null,
            )
            synchronized(asks) { asks[ask.key] = id }
            main.post { items.add(ask) }
            context?.let { if (!visible) askNote(it, ask) }
        }

        /** A turn in flight hears of it from its own call failing, so only the session goes here. */
        override fun onClosed(why: String) {
            Log.i(TAG, "agent closed: $why")
            session = null
            main.post { settleAsks() }
        }
    }

    /** One `session/update`, folded into the conversation. Runs on the main thread. */
    private fun apply(update: JSONObject) {
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> append(text(update.opt("content")), agent = true)
            "user_message_chunk" -> append(text(update.opt("content")), agent = false)
            "agent_thought_chunk" -> {
                val chunk = text(update.opt("content"))
                val last = items.lastOrNull()
                if (last is Thought) items[items.lastIndex] = last.copy(text = last.text + chunk)
                else if (chunk.isNotBlank()) items.add(Thought(key(), chunk))
            }
            "tool_call" -> {
                val id = update.optString("toolCallId")
                val input = update.opt("rawInput")
                val name = update.optString("name").ifEmpty { update.optString("kind").ifEmpty { "tool" } }
                val command = (input as? JSONObject)?.optString("command")?.ifEmpty { null }
                // A command says what it does better than the title made from it.
                val arg = if (command != null) "" else update.optString("title").takeIf { it != name }.orEmpty().ifEmpty { compact(input) }
                val now = System.currentTimeMillis()
                val call = Entry.Call(now, name, arg, command, null, Entry.Status.PENDING, "", "")
                val known = tools[id]?.first
                val at = items.indexOfFirst { it.key == known }
                if (at >= 0) {
                    items[at] = Tool(items[at].key, call)
                } else {
                    val tool = Tool(key(), call)
                    tools[id] = tool.key to now
                    items.add(tool)
                }
            }
            "tool_call_update" -> {
                val id = update.optString("toolCallId")
                val at = items.indexOfFirst { it.key == tools[id]?.first }
                val tool = items.getOrNull(at) as? Tool ?: return
                val status = when (update.optString("status")) {
                    "completed" -> Entry.Status.OK
                    "failed" -> Entry.Status.ERROR
                    else -> return
                }
                val output = text(update.opt("content")).ifEmpty { compact(update.opt("rawOutput")) }
                val old = tool.call
                val lasted = tools[id]?.let { System.currentTimeMillis() - it.second }
                val summary = Sessions.resultSummary(output, status).ifEmpty { if (status == Entry.Status.OK) "ok" else "failed" }
                items[at] = Tool(tool.key, Entry.Call(old.at, old.tool, old.arg, old.command, lasted, status, summary, output.trim()))
            }
            "plan" -> {
                val steps = update.optJSONArray("entries").objects().map {
                    Step(it.optString("content"), it.optString("status"))
                }
                val at = items.indexOfLast { it is Plan }
                if (at >= 0 && at == items.lastIndex) items[at] = Plan(items[at].key, steps)
                else items.add(Plan(key(), steps))
            }
            "session_info_update" -> update.optString("title").ifEmpty { null }?.let { title = it }
        }
    }

    /** Chunks of one speaker run together into one bubble until someone else speaks or a tool runs. */
    private fun append(chunk: String, agent: Boolean) {
        if (chunk.isEmpty()) return
        val last = items.lastOrNull()
        if (last is Said && last.agent == agent) items[items.lastIndex] = last.copy(text = last.text + chunk)
        else items.add(Said(key(), chunk.trimStart(), agent))
    }

    private fun add(item: Item) = main.post { items.add(item) }

    /**
     * Unanswered asks die with the turn. The agent still waits on each one, and
     * ACP has the client answer them as cancelled; buttons left live would
     * answer nothing.
     */
    private fun settleAsks() {
        val stale = synchronized(asks) { asks.toMap().also { asks.clear() } }
        val cancelled = JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))
        stale.forEach { (key, id) ->
            client?.takeIf { it.alive }?.respond(id, cancelled)
            val at = items.indexOfFirst { it.key == key }
            (items.getOrNull(at) as? Ask)?.let { items[at] = it.copy(chosen = "No answer") }
        }
        if (stale.isNotEmpty()) context?.let { notes(it).cancel(NotificationIds.CHAT_ASK) }
    }

    private fun failed(error: Throwable) {
        Log.w(TAG, "chat failed: $error")
        val text = when {
            error is AcpClient.Failure && error.code == AcpClient.NO_CREDENTIALS ->
                "The agent has no key for its model. Add the provider's key in settings, then try again."
            else -> error.message ?: "The agent did not answer."
        }
        add(Notice(key(), text, error = true))
    }

    private fun key(): String = synchronized(this) { "chat-${serial++}" }

    private fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** The text in a content block, a list of them, or a tool call's content wrapper. */
    private fun text(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).joinToString("") { text(value.opt(it)) }
        is JSONObject -> value.optString("text").ifEmpty { text(value.opt("content")) }
        else -> ""
    }

    /** A notification shows markdown as typed, so the marks come off first. */
    private fun plain(text: String): String = text
        .replace(Regex("""\*\*|`+"""), "")
        .replace(Regex("""(?m)^#+\s*"""), "")
        .trim()

    private fun compact(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> value.toString()
    }.take(COMPACT_CAP)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * While the app is behind another one the chat cannot be seen, so the turn
     * shows in a quiet ongoing notification instead. At most one change a
     * second: a streamed reply would otherwise post on every few words.
     */
    private fun progress(ctx: Context, update: JSONObject) {
        if (visible || !busy) return
        val now = System.currentTimeMillis()
        if (now - lastProgress < PROGRESS_MS) return
        lastProgress = now
        val doing = when (update.optString("sessionUpdate")) {
            "tool_call" -> update.optString("title").ifEmpty { update.optString("name") }
                .ifEmpty { "Working" }
            "agent_message_chunk" -> "Writing a reply"
            "agent_thought_chunk" -> "Thinking"
            else -> return
        }
        channels(ctx)
        val stop = Notification.Action.Builder(null, "Stop", serviceIntent(ctx, STOP, 0) {}).build()
        val note = Notification.Builder(ctx, PROGRESS_CHANNEL)
            .setContentTitle("Droid is working")
            .setContentText(doing.take(NOTE_CAP))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(stop)
            .build()
        notes(ctx).notify(NotificationIds.CHAT_PROGRESS, note)
    }

    /** The reply once the turn ends, for someone who is looking at a different app by then. */
    private fun finished(ctx: Context) = main.post {
        val notes = notes(ctx)
        notes.cancel(NotificationIds.CHAT_PROGRESS)
        // Stopped with the service, or read as it was written: nothing to tell.
        if (visible || !open) return@post
        val last = items.lastOrNull()
        val text = when (last) {
            is Said -> if (last.agent) plain(last.text) else return@post
            is Notice -> last.text
            else -> "Finished."
        }
        channels(ctx)
        val note = Notification.Builder(ctx, REPLY_CHANNEL)
            .setContentTitle(title ?: "Droid")
            .setContentText(text.take(NOTE_CAP))
            .setStyle(Notification.BigTextStyle().bigText(text.take(REPLY_CAP)))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openIntent(ctx))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        notes.notify(NotificationIds.CHAT_REPLY, note)
    }

    /**
     * An approval that waits on a screen nobody has open waits forever, so it
     * goes to a notification with its answers as buttons. Answering from the
     * lock screen asks for the unlock first: these approve calls and payments.
     * Before Android 12 a button cannot ask for that, so there are none and a
     * tap opens the chat, which a locked phone only shows once unlocked.
     */
    private fun askNote(ctx: Context, ask: Ask) {
        channels(ctx)
        val builder = Notification.Builder(ctx, REPLY_CHANNEL)
            .setContentTitle(if (ask.question) "Droid has a question" else "Droid needs your yes")
            .setContentText(ask.title.take(NOTE_CAP))
            .setStyle(Notification.BigTextStyle().bigText(listOf(ask.title, ask.body).filter { it.isNotBlank() }.joinToString("\n").take(REPLY_CAP)))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openIntent(ctx))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ask.options.take(MAX_ACTIONS).forEachIndexed { i, option ->
                val pending = serviceIntent(ctx, ANSWER, i + 1) {
                    putExtra(EXTRA_ASK, ask.key)
                    putExtra(EXTRA_OPTION, option.id)
                }
                val action = Notification.Action.Builder(null, option.name, pending)
                    .setAuthenticationRequired(true)
                builder.addAction(action.build())
            }
        }
        notes(ctx).notify(NotificationIds.CHAT_ASK, builder.build())
    }

    private fun cancelNotes(ctx: Context) {
        val notes = notes(ctx)
        notes.cancel(NotificationIds.CHAT_PROGRESS)
        notes.cancel(NotificationIds.CHAT_ASK)
    }

    private fun channels(ctx: Context) {
        val notes = notes(ctx)
        notes.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Chat progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What the agent is doing while the chat is out of sight"
            },
        )
        notes.createNotificationChannel(
            NotificationChannel(REPLY_CHANNEL, "Chat replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The agent's replies and questions from the chat"
            },
        )
    }

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java)
            .setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(ctx: Context, action: String, code: Int, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getService(
            ctx,
            code,
            Intent(ctx, AsterAgentService::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notes(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /** The activity opens on the chat when started with this action. */
    const val OPEN = "dev.aster.probe.OPEN_CHAT"
    /** Service actions behind the notification buttons. */
    const val STOP = "dev.aster.probe.CHAT_STOP"
    const val ANSWER = "dev.aster.probe.CHAT_ANSWER"
    const val EXTRA_ASK = "ask"
    const val EXTRA_OPTION = "option"

    /** Pauses on the risky actions and asks, which is what the approval buttons are for. */
    private const val MODE = "auto"
    private const val PREFS = "chat"
    private const val SESSION = "session"
    private const val PROGRESS_CHANNEL = "aster-chat-progress"
    private const val REPLY_CHANNEL = "aster-chat"
    private const val PROGRESS_MS = 1_000L
    private const val NOTE_CAP = 120
    private const val REPLY_CAP = 2_000
    private const val COMPACT_CAP = 400
    private const val MAX_ACTIONS = 3
    private const val TAG = "ASTERCHAT"
}
