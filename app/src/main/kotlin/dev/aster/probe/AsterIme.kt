package dev.aster.probe

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View

/**
 * A keyboard the agent types through. `ACTION_SET_TEXT` is refused by plenty of
 * fields (Compose editors, WebViews, anything with its own input handling), and
 * synthesised key events drop characters. An IME owns the `InputConnection`, so
 * committing text works wherever the cursor is.
 *
 * It draws nothing: the agent is the only thing typing on this device.
 */
class AsterIme : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "ime ready")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // A zero-height view keeps the system happy without covering the screen the
    // agent is trying to read.
    override fun onCreateInputView(): View = View(this)

    // Never show a window: even a zero-height view claims the whole screen as
    // touchable, blocking every tap and hiding the app from the tree. The
    // InputConnection is bound at onStartInput whether or not anything shows.
    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean = false

    companion object {
        private const val TAG = "ASTEREYES"
        private const val FIELD_LIMIT = 10_000

        @Volatile private var instance: AsterIme? = null

        val isReady: Boolean get() = instance != null

        /** Commit text at the cursor. Returns false when no field is focused. */
        fun commit(text: String): Boolean {
            val ic = instance?.currentInputConnection ?: return false
            return ic.commitText(text, 1)
        }

        /** Delete [count] characters before the cursor. */
        fun deleteBefore(count: Int): Boolean {
            val ic = instance?.currentInputConnection ?: return false
            return ic.deleteSurroundingText(count, 0)
        }

        /** One key, down then up, for the few keys that are not text: enter, delete, tab. */
        fun key(code: Int): Boolean {
            val ic = instance?.currentInputConnection ?: return false
            val now = SystemClock.uptimeMillis()
            return ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)) &&
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }

        /** Empty the focused field, whatever sits either side of the cursor. */
        fun clear(): Boolean {
            val ic = instance?.currentInputConnection ?: return false
            val before = ic.getTextBeforeCursor(FIELD_LIMIT, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(FIELD_LIMIT, 0)?.length ?: 0
            return ic.deleteSurroundingText(before, after)
        }

        /** Everything the focused field currently holds, as far as it will say. */
        fun readField(limit: Int = 2000): String? {
            val ic = instance?.currentInputConnection ?: return null
            val before = ic.getTextBeforeCursor(limit, 0) ?: ""
            val after = ic.getTextAfterCursor(limit, 0) ?: ""
            return "$before$after"
        }
    }
}
