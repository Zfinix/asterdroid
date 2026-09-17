package dev.aster.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.ArrayDeque

/**
 * The phone saying something happened, instead of the agent asking. A message
 * arriving is a push event; polling the screen for it costs a read per second
 * and still misses anything that lands while another app is in front.
 */
class AsterNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "notifications connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val event = Event(
            at = sbn.postTime,
            pkg = sbn.packageName,
            title = extras.getCharSequence("android.title")?.toString().orEmpty(),
            body = extras.getCharSequence("android.text")?.toString().orEmpty(),
        )
        synchronized(recent) {
            recent.addLast(event)
            while (recent.size > KEEP) recent.removeFirst()
        }
        forward(sbn, event)
    }

    /** A chosen app's notification goes to the chat. An update that says the same thing again does not. */
    private fun forward(sbn: StatusBarNotification, event: Event) {
        val flags = sbn.notification.flags
        if (sbn.packageName == packageName || event.title.isEmpty() && event.body.isEmpty()) return
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.packageName !in Alerts.apps(this)) return
        val said = "${event.title}\n${event.body}"
        synchronized(lastSaid) {
            if (lastSaid[sbn.key] == said) return
            lastSaid[sbn.key] = said
            if (lastSaid.size > KEEP) lastSaid.remove(lastSaid.keys.first())
        }
        val app = Alerts.label(this, sbn.packageName)
        val line = listOf(event.title, event.body).filter { it.isNotEmpty() }.joinToString(": ")
        Alerts.post(this, "🔔 $app · $line")
    }

    data class Event(val at: Long, val pkg: String, val title: String, val body: String) {
        fun render(): String = "%s %s | %s | %s".format(
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at)),
            pkg,
            title,
            body.replace('\n', ' '),
        )
    }

    companion object {
        private const val TAG = "ASTEREYES"
        private const val KEEP = 50
        private val recent = ArrayDeque<Event>()
        private val lastSaid = LinkedHashMap<String, String>()

        /** Newest first, so a bounded read still gets the interesting ones. */
        fun drain(limit: Int = 20): List<Event> = synchronized(recent) {
            recent.reversed().take(limit)
        }
    }
}
