package dev.aster.probe.ui

/** The three switches the phone will only let a person flip. */
enum class GrantId { SCREEN, KEYBOARD, NOTIFICATIONS }

/** One permission the phone can only grant by hand. */
data class Grant(
    val id: GrantId,
    val label: String,
    val on: Boolean,
    /** What the agent cannot do while this is off. */
    val need: String,
)
