package dev.aster.probe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings

/**
 * Doing a thing directly instead of tapping toward it. Launching an app by name
 * or opening the Wi-Fi screen is one intent; getting there through the UI is a
 * dozen reads and taps that can each go wrong. The screen tools are for the
 * long tail with no intent behind it.
 */
object Shortcuts {

    /** Launchable apps, label first because that is what a person names. */
    fun apps(context: Context, filter: String): String {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(main, 0)
            .map { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() to it.activityInfo.packageName }
            .filter { filter.isEmpty() || it.first.contains(filter, true) || it.second.contains(filter, true) }
            .distinctBy { it.second }
            .sortedBy { it.first }
        return "apps=${found.size}\n" + found.joinToString("") { "  ${it.first}  (${it.second})\n" }
    }

    /** Launch by label or package. A label is what someone will actually say. */
    fun open(context: Context, query: String): String {
        if (query.isBlank()) return "error: open needs an app name\n"
        val pm = context.packageManager
        val pkg = resolve(context, query)
            ?: return "error: no app matching \"$query\"; try `apps` to list them\n"
        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: return "error: $label has no launcher entry\n"
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "opening $label ($pkg)\n"
    }

    /** The package for a label or package name, exact match before contains. */
    fun resolve(context: Context, query: String): String? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(main, 0)
        val hit = all.firstOrNull { it.activityInfo.packageName.equals(query, true) }
            ?: all.firstOrNull {
                pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().equals(query, true)
            }
            ?: all.firstOrNull {
                pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().contains(query, true)
            }
        return hit?.activityInfo?.packageName
    }

    /**
     * Straight to the store page. Searching the store through its own UI is a
     * dozen taps that each depend on a layout that changes; a `market:` URI is
     * one. If nothing answers that URI there is no store on the device at all,
     * which is worth saying rather than discovering by tapping.
     */
    fun install(context: Context, query: String): String {
        if (query.isBlank()) return "error: install needs an app name or package\n"
        val arg = query.trim()
        val looksLikePackage = !arg.contains(' ') && arg.count { it == '.' } >= 2
        val uri = when {
            looksLikePackage -> "market://details?id=$arg"
            else -> "market://search?q=${Uri.encode(arg)}"
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "store open on \"$arg\". Read the screen and press Install\n"
        }.getOrElse {
            "error: no app store on this device, so nothing can be installed here. " +
                "This is the device, not the request\n"
        }
    }

    /**
     * Settings screens by name. An app cannot flip Wi-Fi or Bluetooth itself on
     * modern Android, so the honest move is to land on the exact screen and let
     * the caller tap the switch it can now see.
     */
    fun settings(context: Context, which: String): String {
        val action = when (which.lowercase().trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "data", "mobile" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "" -> Settings.ACTION_SETTINGS
            else -> return "error: no settings screen called \"$which\"\n"
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "opened the $which settings screen; read it and tap the control\n"
    }

    /** Dial, message, map, browse: hand off to whatever app owns that scheme. */
    fun intent(context: Context, rest: String): String {
        val parts = rest.trim().split(" ", limit = 2)
        val verb = parts.firstOrNull().orEmpty()
        val arg = parts.getOrNull(1).orEmpty().trim()
        if (arg.isEmpty() && verb != "quicksettings") return "error: $verb needs an argument\n"
        val intent = when (verb) {
            // Dial rather than call: placing a call outright is not something to
            // do from a tool that cannot see who is being rung.
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$arg"))
            "sms" -> {
                val (to, body) = arg.split(" ", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
                    .putExtra("sms_body", body)
            }
            "url", "web" -> Intent(Intent.ACTION_VIEW, Uri.parse(withScheme(arg)))
            "map", "place" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(arg)}"))
            "search" -> Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", arg)
            else -> return "error: unknown shortcut \"$verb\"\n"
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$verb: opened, and the screen now shows what it opened\n"
        }.getOrElse { "error: nothing on this phone handles $verb\n" }
    }

    /**
     * Android will not let an app place an emergency call itself: ACTION_CALL
     * refuses emergency numbers and only ACTION_DIAL may pre-fill one. Opening
     * the dialer is half the job; the service presses the button afterwards,
     * because whoever asked for this is not here to press it.
     */
    fun emergency(context: Context, number: String): String {
        val dialled = number.trim().ifEmpty { "112" }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialled"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "dialer open on $dialled\n"
        }.getOrElse { "error: could not open the dialer for $dialled\n" }
    }

    /**
     * The phone already has an alarm clock and a calendar, and they survive the
     * agent being killed, the battery dying and the app being uninstalled.
     * A reminder held inside the agent has none of those properties.
     */
    fun alarm(context: Context, rest: String): String {
        val parts = rest.trim().split(" ", limit = 2)
        val at = parts.firstOrNull().orEmpty()
        val label = parts.getOrNull(1).orEmpty().ifEmpty { "Aster" }
        val (hour, minute) = parseClock(at) ?: return "error: alarm needs a time like 07:30\n"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // Skip the UI: the point is that it is set, not that a screen opened.
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "alarm set for %02d:%02d (%s) in the phone's clock\n".format(hour, minute, label)
        }.getOrElse { "error: no clock app took the alarm\n" }
    }

    fun timer(context: Context, rest: String): String {
        val parts = rest.trim().split(" ", limit = 2)
        val seconds = parts.firstOrNull()?.let(::parseDuration)
            ?: return "error: timer needs a length like 10m or 90s\n"
        val label = parts.getOrNull(1).orEmpty().ifEmpty { "Aster" }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "timer set for ${seconds}s ($label)\n"
        }.getOrElse { "error: no clock app took the timer\n" }
    }

    /** Opens the calendar on a filled-in event; the person confirms it. */
    fun event(context: Context, rest: String): String {
        val parts = rest.trim().split(" ", limit = 2)
        val at = parts.firstOrNull().orEmpty()
        val title = parts.getOrNull(1).orEmpty().ifEmpty { "Aster" }
        val (hour, minute) = parseClock(at) ?: return "error: event needs a time like 15:00\n"
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "calendar opened on \"$title\" at %02d:%02d; confirm it on screen\n".format(hour, minute)
        }.getOrElse { "error: no calendar app on this phone\n" }
    }

    private fun parseClock(text: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(text.trim()) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    private fun parseDuration(text: String): Int? {
        val m = Regex("^(\\d+)([smh]?)$").find(text.trim().lowercase()) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when (m.groupValues[2]) {
            "h" -> n * 3600
            "m" -> n * 60
            else -> n
        }
    }

    private fun withScheme(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
}
