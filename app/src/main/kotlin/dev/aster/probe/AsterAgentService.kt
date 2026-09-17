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
    private var child: Process? = null
    private val battery = BatteryWatch()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        live = true
        ActivityLog.clear(this)
        since = System.currentTimeMillis()
        connectedAt = 0L
        restarts = 0
        startForeground(NOTIFICATION_ID, notification("Starting"))
        battery.start(this)
        supervise()
        // START_STICKY: if the system reclaims us under pressure, come back.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        live = false
        since = 0L
        connectedAt = 0L
        runCatching { child?.destroy() }
        battery.stop(this)
        super.onDestroy()
    }

    /** Run the bridge, and put it back when it falls over. */
    private fun supervise() = thread(name = "aster-agent") {
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            val started = System.currentTimeMillis()
            val code = runAgent()
            if (!running) break
            val lasted = System.currentTimeMillis() - started
            restarts++
            connectedAt = 0L
            // A process that survived a while was healthy; only tighten the
            // backoff when it is failing immediately, which means misconfigured.
            backoffMs = if (lasted > HEALTHY_MS) MIN_BACKOFF_MS
            else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            note("The agent stopped (code $code). It starts again in ${backoffMs / 1000}s.")
            Thread.sleep(backoffMs)
        }
    }

    private fun runAgent(): Int = runCatching {
        val home = filesDir
        // After an update the client link and the bundled files are stale, and
        // nobody may ever open the app to refresh them.
        val bin = Install.binDir(this)
        Install.skill(this)
        Install.instructions(this)
        Install.env(this)
        val agent = File(applicationInfo.nativeLibraryDir, "libaster.so")
        if (!agent.exists()) {
            note("This build has no agent. Install the app again.")
            return@runCatching NO_BINARY
        }
        // The bridge defaults to `manual`, which asks before every action. On a
        // phone there is no terminal to answer, so consent lives in the chat.
        val process = ProcessBuilder(agent.absolutePath, "remote", "telegram", "--mode", MODE)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = cacheDir.absolutePath
                environment()["PATH"] = "${bin.absolutePath}:${environment()["PATH"]}"
                // A screen map is a few thousand chars and goes stale in one
                // step; the desktop budget would resend hours of them.
                environment()["ASTER_COMPACT_BUDGET"] = COMPACT_BUDGET_CHARS
                Models.apply(this@AsterAgentService, environment())
            }
            .start()
        child = process
        process.inputStream.bufferedReader().forEachLine { line ->
            Log.i(TAG, line)
            when {
                line.startsWith(CONNECTED) -> {
                    connectedAt = System.currentTimeMillis()
                    note("Connected to Telegram")
                }
                line.contains("error", ignoreCase = true) -> {
                    ActivityLog.agent(this, line)
                    note(line.take(NOTE_CAP))
                }
                else -> ActivityLog.agent(this, line)
            }
        }
        process.waitFor()
    }.getOrElse {
        Log.e(TAG, "agent failed to start: $it")
        note("The agent did not start: $it")
        SPAWN_FAILED
    }

    /** The persistent notification doubles as the status line. */
    private fun note(text: String) {
        Log.i(TAG, text)
        ActivityLog.state(this, text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Aster", NotificationManager.IMPORTANCE_LOW).apply {
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
            .setContentTitle("Aster")
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
        private const val NOTIFICATION_ID = 1
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val HEALTHY_MS = 60_000L
        private const val MODE = "yolo"
        private const val COMPACT_BUDGET_CHARS = "60000"
        /** The bridge announces itself with the bot handle; the phone only needs to know it is up. */
        private const val CONNECTED = "aster remote: connected as"

        private const val NOTE_CAP = 80
        private const val NO_BINARY = -1
        private const val SPAWN_FAILED = -2

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
