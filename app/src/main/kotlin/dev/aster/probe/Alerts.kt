package dev.aster.probe

import android.content.Context
import java.io.File

/**
 * What the phone tells the chat on its own: battery warnings and the
 * notifications of chosen apps. A notice rides the reminder folder with
 * `kind: notice`, so the bridge posts it without starting a turn.
 */
object Alerts {
    private const val PREFS = "alerts"
    private const val APPS = "apps"
    private const val BATTERY = "battery"
    private const val LEVELS = "levels"
    val DEFAULT_LEVELS = listOf(20, 10)

    fun post(context: Context, text: String) {
        val dir = File(context.filesDir, WakeReceiver.WAKE_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val body = "{\"kind\":\"notice\",\"text\":${WakeReceiver.quote(text)},\"at\":$now}"
        File(dir, "$now-${System.nanoTime()}.json").writeText(body)
    }

    fun apps(context: Context): Set<String> = prefs(context).getStringSet(APPS, emptySet()).orEmpty()

    fun batteryOn(context: Context): Boolean = prefs(context).getBoolean(BATTERY, true)

    /** Highest first, so the first warning of a discharge is the gentle one. */
    fun levels(context: Context): List<Int> = prefs(context).getString(LEVELS, null)
        ?.let(::parseLevels) ?: DEFAULT_LEVELS

    /**
     * `alerts` shows the settings; `alerts battery on|off|<levels>`,
     * `alerts add <app>` and `alerts remove <app>` change them.
     */
    fun command(context: Context, spec: String): String {
        val parts = spec.trim().split(Regex("\\s+"), limit = 2)
        val sub = parts.firstOrNull().orEmpty()
        val arg = parts.getOrNull(1).orEmpty().trim()
        return when (sub) {
            "" -> status(context)
            "battery" -> battery(context, arg)
            "add" -> app(context, arg, add = true)
            "remove" -> app(context, arg, add = false)
            else -> "error: alerts takes battery on|off|<levels>, add <app> or remove <app>\n"
        }
    }

    private fun status(context: Context): String {
        val apps = apps(context).map { label(context, it) to it }.sortedBy { it.first }
        val battery = if (batteryOn(context)) "on" else "off"
        return "battery=$battery levels=${levels(context).joinToString(",")}\n" +
            "apps=${apps.size}\n" + apps.joinToString("") { "  ${it.first}  (${it.second})\n" }
    }

    private fun battery(context: Context, arg: String): String {
        val edit = prefs(context).edit()
        when (arg) {
            "on" -> edit.putBoolean(BATTERY, true)
            "off" -> edit.putBoolean(BATTERY, false)
            else -> {
                val levels = parseLevels(arg)
                    ?: return "error: battery takes on, off, or levels between 1 and 99 like 20,10\n"
                edit.putBoolean(BATTERY, true).putString(LEVELS, levels.joinToString(","))
            }
        }
        edit.apply()
        return status(context)
    }

    private fun app(context: Context, query: String, add: Boolean): String {
        if (query.isEmpty()) return "error: name an app, e.g. `alerts add whatsapp`\n"
        val apps = apps(context).toMutableSet()
        val pkg = apps.firstOrNull { it.equals(query, true) || label(context, it).equals(query, true) }
            ?: Shortcuts.resolve(context, query)
            ?: return "error: no app matching \"$query\"; try `apps` to list them\n"
        if (add) apps.add(pkg) else apps.remove(pkg)
        prefs(context).edit().putStringSet(APPS, apps).apply()
        return status(context)
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun parseLevels(raw: String): List<Int>? {
        val levels = raw.split(',', ' ').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return null }
        if (levels.isEmpty() || levels.any { it !in 1..99 }) return null
        return levels.distinct().sortedDescending()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
