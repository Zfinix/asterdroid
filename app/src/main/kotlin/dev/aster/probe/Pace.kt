package dev.aster.probe

import android.content.Context
import kotlin.math.ceil

/**
 * How long this phone, and each app on it, takes to answer a touch. A fixed
 * wait has to be sized for the slowest phone running the slowest app, so on
 * everything else it is dead time on every step. These figures are learned
 * from the actions themselves and kept across restarts.
 *
 * An app's own history is used once it has enough samples, the whole phone's
 * before that, and the old fixed waits before either, so a fresh install is
 * never faster than it can back up.
 */
class Pace(context: Context) {

    private val prefs = context.getSharedPreferences("pace", Context.MODE_PRIVATE)
    private val series = HashMap<String, ArrayDeque<Int>>()

    /** How long to wait for the first sign a touch landed before calling it no change. */
    @Synchronized fun landWindow(pkg: String): Int =
        percentile(LAND, pkg, LAND_PERCENTILE)
            ?.let { (it * 3 / 2 + LAND_MARGIN_MS).coerceIn(LAND_MIN_MS, LAND_MAX_MS) }
            ?: LAND_MAX_MS

    /**
     * How long to wait for the screen to go quiet once a touch landed. An app
     * that is never quiet (a feed that autoplays, a ticking game) gets a short
     * budget, because waiting longer for a calm that never comes buys nothing.
     */
    @Synchronized fun quietBudget(pkg: String): Int {
        val busy = pick(BUSY, pkg)?.takeLast(BUSY_WINDOW)
        if (busy != null && busy.count { it == 1 } * 2 >= busy.size) return BUSY_BUDGET_MS
        return percentile(SETTLE, pkg, SETTLE_PERCENTILE)
            ?.let { (it * 3 / 2 + SETTLE_MARGIN_MS).coerceIn(SETTLE_MIN_MS, SETTLE_MAX_MS) }
            ?: SETTLE_MAX_MS
    }

    @Synchronized fun landed(pkg: String, ms: Int) = add(LAND, pkg, ms)

    @Synchronized fun settled(pkg: String, ms: Int, quiet: Boolean) {
        add(SETTLE, pkg, ms)
        add(BUSY, pkg, if (quiet) 0 else 1)
    }

    /** The system's minimum spacing between screenshots, learned from its refusals. */
    var shotGap: Int
        @Synchronized get() = prefs.getInt(SHOT_GAP, 0)
        @Synchronized set(value) {
            prefs.edit().putInt(SHOT_GAP, value).apply()
        }

    @Synchronized fun describe(pkg: String): String {
        val here = load(key(LAND, pkg)).size
        val phone = load(key(LAND, PHONE)).size
        val source = when {
            here >= MIN_SAMPLES -> "this app's own $here touches"
            phone >= MIN_SAMPLES -> "the phone's $phone touches ($here here so far)"
            else -> "the fixed defaults, until $MIN_SAMPLES touches have been timed"
        }
        return "pace for $pkg, from $source\n" +
            "  wait for a touch to land: up to ${landWindow(pkg)}ms\n" +
            "  wait for the screen to go quiet: up to ${quietBudget(pkg)}ms\n" +
            "  screenshot spacing: ${shotGap}ms\n"
    }

    @Synchronized fun reset() {
        series.clear()
        prefs.edit().clear().apply()
    }

    /** The app's own samples once there are enough, else the phone's. */
    private fun pick(kind: String, pkg: String): List<Int>? =
        load(key(kind, pkg)).takeIf { it.size >= MIN_SAMPLES }
            ?: load(key(kind, PHONE)).takeIf { it.size >= MIN_SAMPLES }

    private fun percentile(kind: String, pkg: String, p: Double): Int? {
        val sorted = pick(kind, pkg)?.sorted() ?: return null
        return sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun add(kind: String, pkg: String, value: Int) {
        val edit = prefs.edit()
        for (k in listOf(key(kind, pkg), key(kind, PHONE)).distinct()) {
            val s = load(k)
            s.addLast(value)
            while (s.size > KEEP) s.removeFirst()
            edit.putString(k, s.joinToString(","))
        }
        edit.apply()
    }

    private fun load(k: String): ArrayDeque<Int> = series.getOrPut(k) {
        ArrayDeque(prefs.getString(k, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty())
    }

    private fun key(kind: String, pkg: String) = "$kind:${pkg.ifEmpty { PHONE }}"

    private companion object {
        const val PHONE = "*"
        const val LAND = "land"
        const val SETTLE = "settle"
        const val BUSY = "busy"
        const val SHOT_GAP = "shot_gap"
        const val KEEP = 32
        const val MIN_SAMPLES = 6
        const val LAND_PERCENTILE = 0.95
        const val LAND_MARGIN_MS = 200
        const val LAND_MIN_MS = 350

        /** The old fixed wait. Learning only ever shortens it. */
        const val LAND_MAX_MS = 1500
        const val SETTLE_PERCENTILE = 0.9
        const val SETTLE_MARGIN_MS = 100
        const val SETTLE_MIN_MS = 250
        const val SETTLE_MAX_MS = 600
        const val BUSY_WINDOW = 12
        const val BUSY_BUDGET_MS = 250
    }
}
