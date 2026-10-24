package dev.aster.probe

/**
 * Every notification id the app posts under, in one place. Two posters sharing
 * an id replace each other's notification, and one of them may be a
 * foreground service's, which a cancel can then no longer take down.
 */
object NotificationIds {
    const val AGENT = 1
    const val MIRROR = 2
    const val CHAT_PROGRESS = 3
    const val CHAT_REPLY = 4
    const val CHAT_ASK = 5

    val all = listOf(AGENT, MIRROR, CHAT_PROGRESS, CHAT_REPLY, CHAT_ASK)
}
