package dev.aster.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Tells the chat when the phone runs low, once per level per discharge. */
class BatteryWatch : BroadcastReceiver() {
    private val warned = mutableSetOf<Int>()

    fun start(context: Context) {
        context.registerReceiver(this, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun stop(context: Context) {
        runCatching { context.unregisterReceiver(this) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!Alerts.batteryOn(context)) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val levels = Alerts.levels(context)
        val due = due(percent, plugged, levels, warned) ?: return
        val advice = if (due == levels.last() && levels.size > 1) "Plug it in soon or I will go offline."
        else "Plug it in when you can."
        Alerts.post(context, "🔋 Battery at $percent%. $advice")
    }

    companion object {
        /** Past the top level by this much, or on the charger, the warnings are owed again. */
        private const val REARM_MARGIN = 5

        /** The level to warn about now, if any. Starting below several warns once, not once each. */
        fun due(percent: Int, plugged: Boolean, levels: List<Int>, warned: MutableSet<Int>): Int? {
            if (plugged || percent > (levels.maxOrNull() ?: 0) + REARM_MARGIN) {
                warned.clear()
                return null
            }
            val crossed = levels.filter { percent <= it }
            if (crossed.all { it in warned }) return null
            warned += crossed
            return crossed.min()
        }
    }
}
