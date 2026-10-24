package dev.aster.probe

import android.content.Context

/**
 * Where people talk to the agent. Telegram used to be the only way in, so a
 * phone without a bot token had an agent nobody could reach and a bridge that
 * fell over every few minutes. The in-app chat needs no account at all, which
 * is why it is the default once no token is set.
 */
enum class Channel(val id: String, val label: String) {
    /** The chat inside Droid, and no bridge to anywhere else. */
    APP("app", "the Droid app"),
    TELEGRAM("telegram", "Telegram"),
    /** iMessage, WhatsApp and Telegram through one Photon project. */
    PHOTON("photon", "Photon"),
    /** No agent on the phone; only other agents drive it, over MCP, ACP or the relay. */
    NONE("none", "nothing");

    /** The bridge command after the agent binary, or null when this channel runs none. */
    fun bridge(mode: String): List<String>? = when (this) {
        TELEGRAM -> listOf("remote", "telegram", "--mode", mode)
        PHOTON -> listOf("remote", "spectrum", "--mode", mode)
        APP, NONE -> null
    }

    /**
     * What this channel cannot start without, as a sentence for the status
     * line, or null when it has everything. A bridge started without its
     * credentials exits at once, and the supervisor would put it back every
     * few minutes for as long as the phone is on.
     */
    fun missing(context: Context): String? {
        val values = Env.all(context)
        return when (this) {
            TELEGRAM -> if (Env.TELEGRAM_TOKEN in values) null
            else "Telegram has no bot token. Add ${Env.TELEGRAM_TOKEN} in settings, or use the chat in the app."
            PHOTON -> listOf(Env.PHOTON_KEY, Env.PHOTON_PROJECT).filterNot { it in values }
                .takeIf { it.isNotEmpty() }
                ?.let { "Photon needs ${it.joinToString(" and ")}. Add them in settings, or use the chat in the app." }
            APP, NONE -> null
        }
    }

    companion object {
        /** The one in `.env`, or Telegram when a bot token is there, or the app. */
        fun current(context: Context): Channel {
            val values = Env.all(context)
            val named = values[Env.CHANNEL]?.trim()?.lowercase()
            return entries.firstOrNull { it.id == named }
                ?: if (Env.TELEGRAM_TOKEN in values) TELEGRAM else APP
        }
    }
}
