package dev.aster.probe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime

/** An alarm that drops a wake file the bridge turns into a new turn, so a wait need not hold a turn open. */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val dir = File(context.filesDir, WAKE_DIR).apply { mkdirs() }
        val body = "{\"text\":${quote(text)},\"at\":${System.currentTimeMillis()}}"
        File(dir, "${System.currentTimeMillis()}.json").writeText(body)
        Log.i(TAG, "wake: $text")
    }

    companion object {
        private const val TAG = "ASTERWAKE"
        const val WAKE_DIR = ".local/share/aster/wakeups"
        private const val EXTRA_TEXT = "text"
        private const val MIN_SECS = 5L

        /** `later 2m check the install`: seconds, `Nm`, `Nh`, or `HH:MM` today. */
        fun schedule(context: Context, spec: String): String {
            val (whenPart, text) = spec.trim().split(Regex("\\s+"), limit = 2)
                .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty().trim() }
            if (text.isEmpty()) return "error: later needs a delay and what to do, e.g. `later 2m check the install`\n"
            val delayMs = delay(whenPart) ?: return "error: delay must be like 30s, 2m, 1h or 18:30\n"
            val at = System.currentTimeMillis() + delayMs
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WakeReceiver::class.java).putExtra(EXTRA_TEXT, text)
            val pending = PendingIntent.getBroadcast(
                context,
                at.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // Without the exact-alarm grant the system may hold an alarm
                // for minutes. This process stays up under the foreground
                // service, so a plain timer fires on time while it lives.
                Handler(Looper.getMainLooper()).postDelayed({ context.sendBroadcast(intent) }, delayMs)
            }
            return "receipt: posted (later $whenPart)\nyou will be woken in ${delayMs / 1000}s with: $text\n" +
                "End your turn now; the reminder arrives as a new message.\n"
        }

        private fun delay(spec: String): Long? {
            Regex("(\\d+)([smh]?)").matchEntire(spec)?.let { m ->
                val n = m.groupValues[1].toLong()
                val unit = when (m.groupValues[2]) { "m" -> 60L; "h" -> 3600L; else -> 1L }
                return (n * unit).coerceAtLeast(MIN_SECS) * 1000
            }
            val clock = runCatching { LocalTime.parse(spec) }.getOrNull() ?: return null
            var target = ZonedDateTime.now().with(clock)
            if (!target.isAfter(ZonedDateTime.now())) target = target.plusDays(1)
            return target.toInstant().toEpochMilli() - System.currentTimeMillis()
        }

        private fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
