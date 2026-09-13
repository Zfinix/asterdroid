package dev.aster.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * A lettered grid over the screen, so a canvas with no elements can still be
 * pointed at by name: `F7` is a cell, and a person reading a gridded shot can
 * say "swipe F7 to J3". Geometry follows only from the screen size, so the
 * same name means the same pixel for the shot and for the tap after it.
 */
class Grid(val width: Int, val height: Int, cellPx: Int? = null) {

    val cell: Int = (cellPx ?: (minOf(width, height) / CELLS_ACROSS.toFloat()).roundToInt())
        .coerceIn(MIN_CELL, minOf(width, height))
    val cols: Int = (width + cell - 1) / cell
    val rows: Int = (height + cell - 1) / cell

    fun legend(): String =
        "grid: ${cell}px cells, cols A-${column(cols - 1)}, rows 1-$rows; " +
            "tap/press/swipe take a cell like F7 (its centre)\n"

    fun centre(spec: String): PointF? {
        val m = CELL.matchEntire(spec.trim()) ?: return null
        val col = m.groupValues[1].uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
        val row = m.groupValues[2].toInt() - 1
        if (col !in 0 until cols || row !in 0 until rows) return null
        return PointF((col + 0.5f) * cell, (row + 0.5f) * cell)
    }

    fun draw(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val shadow = Paint().apply { color = Color.argb(110, 0, 0, 0); strokeWidth = 3f }
        val line = Paint().apply { color = Color.argb(200, 255, 255, 255); strokeWidth = 1f }
        val chip = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        val text = Paint().apply {
            color = LABEL
            textSize = cell * LABEL_SCALE
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = cell * PAD_SCALE
        val w = width.toFloat()
        val h = height.toFloat()
        for (c in 0..cols) {
            val x = (c * cell).toFloat()
            canvas.drawLine(x, 0f, x, h, shadow)
            canvas.drawLine(x, 0f, x, h, line)
        }
        for (r in 0..rows) {
            val y = (r * cell).toFloat()
            canvas.drawLine(0f, y, w, y, shadow)
            canvas.drawLine(0f, y, w, y, line)
        }
        for (c in 0 until cols) for (r in 0 until rows) {
            val label = "${column(c)}${r + 1}"
            val x = c * cell + pad
            val top = r * cell + pad
            val tw = text.measureText(label)
            val th = text.textSize
            canvas.drawRect(x - pad / 2, top, x + tw + pad / 2, top + th + pad, chip)
            canvas.drawText(label, x, top + th, text)
        }
        return out
    }

    private fun column(index: Int): String =
        if (index < 26) ('A' + index).toString() else column(index / 26 - 1) + ('A' + index % 26)

    /**
     * A crop with finer lines labelled in screen pixels, for aiming at
     * something smaller than a cell: the labels are the numbers a tap takes.
     */
    fun zoom(source: Bitmap, crop: android.graphics.Rect): Pair<Bitmap, String> {
        val scale = if (minOf(crop.width(), crop.height()) < ZOOM_UPSCALE_BELOW) 2 else 1
        val cut = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        val out = Bitmap.createScaledBitmap(cut, cut.width * scale, cut.height * scale, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val step = FINE_STEPS.firstOrNull { maxOf(crop.width(), crop.height()) / it <= FINE_LINES && it * scale >= MIN_LINE_GAP }
            ?: FINE_STEPS.last()
        val shadow = Paint().apply { color = Color.argb(110, 0, 0, 0); strokeWidth = 3f }
        val line = Paint().apply { color = Color.argb(200, 255, 255, 255); strokeWidth = 1f }
        val chip = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        val text = Paint().apply {
            color = LABEL
            textSize = ZOOM_LABEL_PX
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = text.textSize * 0.2f
        var x = (crop.left / step + 1) * step
        while (x < crop.right) {
            val lx = (x - crop.left).toFloat() * scale
            canvas.drawLine(lx, 0f, lx, out.height.toFloat(), shadow)
            canvas.drawLine(lx, 0f, lx, out.height.toFloat(), line)
            label(canvas, x.toString(), lx + pad, pad, chip, text, pad)
            x += step
        }
        var y = (crop.top / step + 1) * step
        while (y < crop.bottom) {
            val ly = (y - crop.top).toFloat() * scale
            canvas.drawLine(0f, ly, out.width.toFloat(), ly, shadow)
            canvas.drawLine(0f, ly, out.width.toFloat(), ly, line)
            label(canvas, y.toString(), pad, ly + pad, chip, text, pad)
            y += step
        }
        val legend = "grid: crop ${crop.left},${crop.top}-${crop.right},${crop.bottom}" +
            (if (scale > 1) " shown at ${scale}x" else "") + ", lines every ${step}px " +
            "labelled with screen x (top) and y (left); tap/press/drag take those pixels as x,y\n"
        return out to legend
    }

    private fun label(canvas: Canvas, s: String, x: Float, top: Float, chip: Paint, text: Paint, pad: Float) {
        val tw = text.measureText(s)
        val th = text.textSize
        canvas.drawRect(x - pad / 2, top, x + tw + pad / 2, top + th + pad, chip)
        canvas.drawText(s, x, top + th, text)
    }

    companion object {
        const val CELLS_ACROSS = 10
        private const val LABEL_SCALE = 0.2f
        private const val PAD_SCALE = 0.05f
        private val LABEL = Color.rgb(255, 220, 120)
        private val CELL = Regex("([A-Za-z]{1,2})(\\d{1,3})")
        private const val MIN_CELL = 24
        private val FINE_STEPS = listOf(10, 20, 25, 50, 100, 200, 500)
        private const val FINE_LINES = 12
        private const val MIN_LINE_GAP = 72
        private const val ZOOM_LABEL_PX = 26f
        private const val ZOOM_UPSCALE_BELOW = 600

        fun isCell(spec: String): Boolean = CELL.matches(spec.trim())
    }
}
