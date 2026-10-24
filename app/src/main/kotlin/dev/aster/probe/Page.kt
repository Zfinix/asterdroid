package dev.aster.probe

/**
 * Which screen the activity shows. There is no deeper stack: each page knows
 * the one it came from, and the activity holds that where it matters.
 */
sealed interface Page {
    data object Home : Page
    data object Chat : Page
    data object History : Page
    data object Settings : Page
    /** The first run, until it is finished, or again from settings. */
    data object Onboarding : Page
    /** MCP, ACP and the relay: letting computers in. */
    data object Connect : Page
    data object Pair : Page
    data object Clients : Page
    data object Scan : Page
}
