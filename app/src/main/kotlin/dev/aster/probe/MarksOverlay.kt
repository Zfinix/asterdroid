package dev.aster.probe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws the map's indices over the elements they refer to, so a screenshot
 * carries its own labels. Set-of-Marks: the model names a number instead of
 * guessing a coordinate, which is where pixel grounding falls apart.
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` is the one overlay an accessibility service may
 * add without `SYSTEM_ALERT_WINDOW`, and it never takes touches.
 */
class MarksOverlay(private val service: AccessibilityService) {

    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: MarksView? = null

    val isShowing: Boolean get() = view != null

    fun show(marks: List<Mark>) {
        val v = view ?: MarksView(service).also {
            windows.addView(it, layoutParams())
            view = it
        }
        v.marks = marks
        v.invalidate()
    }

    fun hide() {
        view?.let { windows.removeView(it) }
        view = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    data class Mark(val index: Int, val bounds: Rect, val interactive: Boolean)

    private class MarksView(context: Context) : View(context) {
        var marks: List<Mark> = emptyList()

        private val box = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        private val chip = Paint().apply { isAntiAlias = true }
        private val label = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            marks.forEach { m ->
                val tint = if (m.interactive) INTERACTIVE else READ_ONLY
                box.color = tint
                chip.color = tint
                canvas.drawRect(m.bounds, box)

                val text = m.index.toString()
                val w = label.measureText(text)
                // Keep the chip inside the screen for elements flush to an edge.
                val left = m.bounds.left.toFloat().coerceAtMost(width - w - CHIP_PAD * 2)
                val top = m.bounds.top.toFloat().coerceAtLeast(CHIP_HEIGHT)
                canvas.drawRect(left, top - CHIP_HEIGHT, left + w + CHIP_PAD * 2, top, chip)
                canvas.drawText(text, left + CHIP_PAD, top - CHIP_PAD, label)
            }
        }

        private companion object {
            const val CHIP_HEIGHT = 38f
            const val CHIP_PAD = 8f
            val INTERACTIVE = Color.parseColor("#F79B4D")
            val READ_ONLY = Color.parseColor("#5A5A5A")
        }
    }
}
