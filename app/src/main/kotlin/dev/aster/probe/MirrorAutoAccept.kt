package dev.aster.probe

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

/**
 * Answers the system's screen-capture dialog with the accessibility service's
 * own thumb, so a /mirror sent from the couch does not wait on a person who is
 * not holding the phone. The dialog is systemui's; the service already taps
 * for the agent, so the same press accepts the projection.
 */
object MirrorAutoAccept {

    private const val TAG = "aster-mirror"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val POLL_MS = 250L

    /** Matches the consent wait in Mirror; a later answer is no answer at all. */
    private const val BUDGET_MS = 30_000L

    /** Long enough for the spinner's list to come up and for a pick to land. */
    private const val SETTLE_MS = 300L

    /**
     * The dialog is addressed by view id rather than by what it says: the
     * button's own label is "Share screen" here, "Record screen" or "Cast
     * screen" elsewhere, and "Next" whenever one app is the chosen mode.
     */
    private const val DIALOG = "com.android.systemui:id/screen_share_permission_dialog"
    private const val MODE = "com.android.systemui:id/screen_share_mode_options"
    private const val OPTION = "android:id/text1"
    private const val ACCEPT = "android:id/button1"

    /** What the whole-screen choice calls itself, shared by share, record and cast. */
    private const val WHOLE_SCREEN = "entire screen"

    @Volatile private var watching = false

    /** Watch for the dialog until it is answered or the consent wait expires. */
    fun arm(service: AccessibilityService) {
        if (watching) return
        watching = true
        thread(name = "aster-mirror-consent") {
            try {
                watch(service)
            } finally {
                watching = false
            }
        }
    }

    /**
     * The stream this was armed for raises the dialog a moment later, so the
     * watch waits for the consent to begin rather than reading `consentPending`
     * once, finding it still false, and going home before the dialog is up.
     */
    private fun watch(service: AccessibilityService) {
        val deadline = System.currentTimeMillis() + BUDGET_MS
        var raised = false
        while (System.currentTimeMillis() < deadline) {
            if (Mirror.consentPending) {
                raised = true
                if (answer(service)) return
            } else if (raised) {
                return
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** One attempt at the dialog; false while it is not up or not yet settled. */
    private fun answer(service: AccessibilityService): Boolean {
        if (dialog(service) == null) return false
        if (!wholeScreen(service)) return false
        // Refetched: the nodes from before the spinner are stale by now.
        val accept = dialog(service)?.let { byId(it, ACCEPT) } ?: return false
        val pressed = accept.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "capture dialog auto-accepted: $pressed")
        return pressed
    }

    /**
     * Since Android 14 the dialog chooses between one app and the whole screen,
     * and it opens on one app, whose button leads to an app picker rather than
     * to a grant. The mirror wants the display, so the spinner is set first.
     */
    private fun wholeScreen(service: AccessibilityService): Boolean {
        val spinner = dialog(service)?.let { byId(it, MODE) } ?: return true
        if (says(spinner).contains(WHOLE_SCREEN, ignoreCase = true)) return true
        if (!spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        Thread.sleep(SETTLE_MS)
        // The choices are a window of their own, not part of the dialog.
        val option = roots(service).firstNotNullOfOrNull { option(it) }
        if (option == null) {
            Log.w(TAG, "the capture dialog offers no whole screen; leaving it to a person")
            return false
        }
        if (!option.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        Thread.sleep(SETTLE_MS)
        return true
    }

    /** The screen-capture dialog's root, when systemui has it up. */
    private fun dialog(service: AccessibilityService): AccessibilityNodeInfo? =
        roots(service).firstNotNullOfOrNull { byId(it, DIALOG) }

    /** The whole-screen row of the open spinner, if that is what is showing. */
    private fun option(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(OPTION)
            .firstOrNull { it.text?.toString()?.contains(WHOLE_SCREEN, ignoreCase = true) == true }
            ?.let(::clickable)

    private fun roots(service: AccessibilityService): List<AccessibilityNodeInfo> =
        service.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == SYSTEM_UI }

    private fun byId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        node.findAccessibilityNodeInfosByViewId(id).firstOrNull()

    /** A row's label is a child of the row, and only the row takes the press. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var up: AccessibilityNodeInfo? = node
        while (up != null && !up.isClickable) up = up.parent
        return up
    }

    /** Everything a node and its children say; a spinner's label is a child's. */
    private fun says(node: AccessibilityNodeInfo): String {
        val own = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val kids = (0 until node.childCount).mapNotNull { node.getChild(it) }
        return kids.joinToString(" ", prefix = "$own ") { says(it) }
    }
}
