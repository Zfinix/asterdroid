package dev.aster.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps the agent alive. Without this the agent is a command that answers once
 * and exits, so nothing can reach it and it reacts to nothing.
 *
 * A foreground service is the only way to hold a long-running process on modern
 * Android, and the notification it requires is also what stops the system
 * killing it.
 */
class AsterAgentService : Service() {

    @Volatile private var running = false
    private val children = mutableListOf<Process>()
    /** The pid of each `sh -c` running a custom agent, whose own children a destroy would miss. */
    private val shells = HashMap<Process, Int>()
    private val battery = BatteryWatch()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Chat.STOP || intent?.action == Chat.ANSWER) {
            // A notification button outliving the service has nothing left to answer.
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (intent.action == Chat.STOP) Chat.stop()
            else Chat.answer(intent.getStringExtra(Chat.EXTRA_ASK).orEmpty(), intent.getStringExtra(Chat.EXTRA_OPTION))
            return START_STICKY
        }
        if (running) return START_STICKY
        running = true
        live = true
        ActivityLog.clear(this)
        since = System.currentTimeMillis()
        connectedAt = 0L
        restarts = 0
        startForeground(NotificationIds.AGENT, notification("Starting"))
        battery.start(this)
        Install.refresh(this)
        val channel = Channel.current(this)
        startAgent(channel)
        if (channel != Channel.NONE) Chat.open(this)
        gated("Access for other agents", "Other agents", Env.MCP_TOKEN, ::runMcp)
        gated("Access for editors", "Editors", Env.ACP_TOKEN, ::runAcp)
        if (Env.value(this, Env.RELAY).equals("on", ignoreCase = true) && Relay.selfHosted(Env.value(this, Env.RELAY_URL))) {
            supervise("The relay", ::runRelay)
        }
        // START_STICKY: if the system reclaims us under pressure, come back.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        live = false
        since = 0L
        connectedAt = 0L
        Chat.close()
        synchronized(children) {
            shells.values.forEach { runCatching { ProcessTree.killBelow(it) } }
            children.forEach { runCatching { it.destroy() } }
        }
        RelayStatus.stopped(null)
        battery.stop(this)
        super.onDestroy()
    }

    /**
     * The chat bridge for the chosen channel, or a note saying why there is
     * none. A channel without its credentials is not started at all: the bridge
     * would exit at once, and the backoff would retry it all day for nothing.
     */
    private fun startAgent(channel: Channel) {
        val missing = channel.missing(this)
        when {
            Env.value(this, Env.AGENT_CMD) != null && channel != Channel.NONE ->
                supervise("The agent") { runAgent(channel) }
            channel == Channel.NONE -> note("No agent runs on this phone. Agents elsewhere can still drive it.")
            missing != null -> note(missing)
            channel.bridge(MODE) == null -> note("Ready. Open the chat to talk to the agent.")
            else -> supervise("The agent") { runAgent(channel) }
        }
    }

    /** A child that only starts with a long enough key, because whoever holds the key holds the phone. */
    private fun gated(what: String, who: String, name: String, run: () -> Int) {
        val token = Env.value(this, name) ?: return
        if (token.length < MIN_TOKEN) {
            note("$who can't connect: the key in $name needs at least $MIN_TOKEN characters. Set a longer one and restart.")
        } else {
            supervise(what, run)
        }
    }

    /** Run a child, and put it back when it falls over. */
    private fun supervise(what: String, run: () -> Int) = thread(name = what) {
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            val started = System.currentTimeMillis()
            val code = run()
            if (!running) break
            val lasted = System.currentTimeMillis() - started
            // A process that survived a while was healthy; only tighten the
            // backoff when it is failing immediately, which means misconfigured.
            backoffMs = if (lasted > HEALTHY_MS) MIN_BACKOFF_MS
            else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            note("$what stopped (code $code). It starts again in ${backoffMs / 1000}s.")
            Thread.sleep(backoffMs)
        }
    }

    private fun runAgent(channel: Channel): Int = runCatching {
        // After an update the client link and the bundled files are stale, and
        // nobody may ever open the app to refresh them.
        Install.refresh(this)
        val custom = Env.value(this, Env.AGENT_CMD)
        val agent = File(applicationInfo.nativeLibraryDir, "libaster.so")
        if (custom == null && !agent.exists()) {
            note("This build has no agent. Install the app again.")
            return@runCatching NO_BINARY
        }
        // The bridge defaults to `manual`, which asks before every action. On a
        // phone there is no terminal to answer, so consent lives in the chat.
        // The shell names itself first, so a stop can find what it forked.
        val command = if (custom != null) listOf(SHELL, "-c", "echo \$\$\n$custom")
        else listOf(agent.absolutePath) + channel.bridge(MODE).orEmpty()
        val process = ProcessBuilder(command)
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply { environment(this@AsterAgentService, environment()) }
            .start()
        track(process)
        val output = process.inputStream.bufferedReader()
        if (custom != null) {
            output.readLine()?.trim()?.toIntOrNull()?.let { pid -> synchronized(children) { shells[process] = pid } }
            note("Your agent is running")
        }
        output.forEachLine { line ->
            Log.i(TAG, line)
            when {
                line.startsWith(CONNECTED) -> {
                    connectedAt = System.currentTimeMillis()
                    note(if (custom != null) "Your agent is connected" else "Connected to ${channel.label}")
                }
                line.contains("error", ignoreCase = true) -> {
                    ActivityLog.agent(this, line)
                    note(line.take(NOTE_CAP))
                }
                else -> ActivityLog.agent(this, line)
            }
        }
        process.waitFor().also {
            untrack(process)
            restarts++
            connectedAt = 0L
        }
    }.getOrElse {
        Log.e(TAG, "agent failed to start: $it")
        note("The agent did not start: $it")
        SPAWN_FAILED
    }

    /** `asterctl mcp --http`, the phone's body for any harness off the phone. */
    private fun runMcp(): Int = runClient(
        "mcp",
        "--http",
        Env.value(this, Env.MCP_PORT) ?: MCP_PORT,
        "--bind",
        Env.value(this, Env.MCP_BIND) ?: LOOPBACK,
    )

    /** `asterctl acp --ws`: the phone's agent for editors that speak ACP, one `aster acp` per socket. */
    private fun runAcp(): Int = runClient(
        "acp",
        "--ws",
        Env.value(this, Env.ACP_PORT) ?: ACP_PORT,
        "--bind",
        Env.value(this, Env.ACP_BIND) ?: LOOPBACK,
    )

    /** `asterctl relay`: dials out to Aster Relay, so no port on the phone has to open. */
    private fun runRelay(): Int {
        RelayStatus.started()
        return runClient("relay") { line -> RelayStatus.line(line)?.let(::note) }
            .also { RelayStatus.stopped("The relay connector stopped.") }
    }

    /** One `libclient.so` child in the shared environment, its output logged and offered to [onLine]. */
    private fun runClient(vararg args: String, onLine: (String) -> Unit = {}): Int = runCatching {
        val client = File(applicationInfo.nativeLibraryDir, "libclient.so")
        val process = ProcessBuilder(listOf(client.absolutePath) + args)
            .directory(filesDir)
            .redirectErrorStream(true)
            .apply { environment(this@AsterAgentService, environment()) }
            .start()
        track(process)
        process.inputStream.bufferedReader().forEachLine {
            Log.i(TAG, it)
            onLine(it)
        }
        process.waitFor().also { untrack(process) }
    }.getOrElse {
        Log.e(TAG, "${args.first()} failed to start: $it")
        SPAWN_FAILED
    }

    private fun track(process: Process) = synchronized(children) {
        children += process
        if (!running) process.destroy()
    }

    private fun untrack(process: Process) = synchronized(children) {
        children -= process
        shells -= process
    }

    /** The persistent notification doubles as the status line. */
    private fun note(text: String) {
        Log.i(TAG, text)
        ActivityLog.state(this, text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NotificationIds.AGENT, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Droid", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows that the agent is running"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Droid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** What the activity's one control reads to know which state it is in. */
        @Volatile var live = false
            private set
        /** When the service came up, and when the bridge last reported connected; 0 when not. */
        @Volatile var since = 0L
            private set
        @Volatile var connectedAt = 0L
            private set
        /** Times the bridge died and was put back in this service life. */
        @Volatile var restarts = 0
            private set

        private const val TAG = "ASTERAGENT"
        private const val CHANNEL = "aster-agent"
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val HEALTHY_MS = 60_000L
        private const val MODE = "yolo"
        private const val SHELL = "/system/bin/sh"
        private const val MCP_PORT = "7071"
        private const val ACP_PORT = "7072"
        /** Nothing off the phone reaches a child unless the address in `.env` says so. */
        private const val LOOPBACK = "127.0.0.1"
        /** Matches asterctl, which refuses a shorter one. */
        private const val MIN_TOKEN = 32
        private const val COMPACT_BUDGET_CHARS = "60000"
        /** Every bridge announces itself with its handle; the phone only needs to know it is up. */
        private const val CONNECTED = "aster remote: connected as"

        private const val NOTE_CAP = 80
        private const val NO_BINARY = -1
        private const val SPAWN_FAILED = -2

        /**
         * The environment every child runs in, so the bridge, the chat, MCP,
         * ACP and the relay all see the same phone: the app's files as home,
         * the linked binaries first on the path, every `.env` value, and the
         * chosen model. The model goes last because it is the picker's choice,
         * and the picker beats the file; the agent's own dotenv load leaves
         * set variables alone, so this holds for it too.
         */
        fun environment(context: Context, env: MutableMap<String, String>) {
            val inherited = env["PATH"]
            env.putAll(Env.inheritable(Env.all(context)))
            env["HOME"] = context.filesDir.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["PATH"] = "${File(context.filesDir, "bin").absolutePath}:$inherited"
            env["ASTER_BIN"] = File(context.applicationInfo.nativeLibraryDir, "libaster.so").absolutePath
            // A screen map is a few thousand chars and goes stale in one
            // step; the desktop budget would resend hours of them.
            env["ASTER_COMPACT_BUDGET"] = COMPACT_BUDGET_CHARS
            Models.apply(context, env)
        }

        fun start(context: Context) {
            val intent = Intent(context, AsterAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AsterAgentService::class.java))
        }
    }
}
