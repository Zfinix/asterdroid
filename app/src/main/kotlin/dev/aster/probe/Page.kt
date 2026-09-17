package dev.aster.probe

/** Which screen the activity shows; there are three and no deeper stack. */
sealed interface Page {
    data object Home : Page
    data object History : Page
    data object Settings : Page
}
