package dev.aster.probe

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel reasoning for screens the accessibility tree cannot see. Everything
 * here is a pure function of a screenshot: it finds coloured blobs so a canvas
 * control can be pointed at by its real centre, groups the pixels an action
 * changed so a blind screen still reports what moved, and reads the cue and
 * aim line so a shot can be servoed instead of guessed.
 *
 * Work happens on a downscaled copy (the eye does not need 3040px to find a
 * ball) and every coordinate is scaled back to the real screen before it
 * leaves, so callers always speak in device pixels.
 */
object Vision {

    const val WORK_WIDTH = 900
    private const val BALL_CORE_MIN = 3
    private const val BALL_CORE_MAX = 400

    data class Blob(val cx: Int, val cy: Int, val area: Int, val bounds: Rect)

    data class Aim(val ballX: Int, val ballY: Int, val tipX: Int, val tipY: Int) {
        val angle: Double get() = atan2((tipY - ballY).toDouble(), (tipX - ballX).toDouble())
    }

    /** A downscaled snapshot with the pixels read out once, so several passes share one copy. */
    class Frame(source: Bitmap, val roi: Rect?) {
        val scale = source.width.toFloat() / WORK_WIDTH
        val w = WORK_WIDTH
        val h = (source.height / scale).toInt().coerceAtLeast(1)
        val px = IntArray(w * h)

        init {
            Bitmap.createScaledBitmap(source, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        }

        fun inRoi(i: Int): Boolean {
            val r = roi ?: return true
            val fx = (i % w) * scale
            val fy = (i / w) * scale
            return fx >= r.left && fx < r.right && fy >= r.top && fy < r.bottom
        }

        fun up(v: Int) = (v * scale).toInt()
    }

    /** Blobs of pixels a mask accepts, largest first, each above a minimum area. */
    fun blobs(frame: Frame, minAreaFrac: Double, mask: (Int, Int, Int) -> Boolean): List<Blob> {
        val hit = BooleanArray(frame.px.size)
        for (i in frame.px.indices) {
            if (!frame.inRoi(i)) continue
            val c = frame.px[i]
            if (mask((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) hit[i] = true
        }
        return componentize(hit, frame.w, frame.h, max(3, (frame.w * frame.h * minAreaFrac).toInt())) { x, y, a, r ->
            Blob(frame.up(x), frame.up(y), a, Rect(frame.up(r.left), frame.up(r.top), frame.up(r.right), frame.up(r.bottom)))
        }
    }

    /** The rectangles that changed between two thumbnails, in full-screen pixels, largest first. */
    fun changedRegions(before: Bitmap, after: Bitmap, fullW: Int, fullH: Int, delta: Int): List<Rect> {
        if (before.width != after.width || before.height != after.height) return emptyList()
        val w = before.width; val h = before.height
        val a = IntArray(w * h).also { before.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { after.getPixels(it, 0, w, 0, 0, w, h) }
        val sx = fullW.toFloat() / w; val sy = fullH.toFloat() / h
        val hit = BooleanArray(w * h)
        for (i in a.indices) {
            val dr = kotlin.math.abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            val dg = kotlin.math.abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            val db = kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF))
            if (dr + dg + db > delta) hit[i] = true
        }
        return componentize(hit, w, h, 3) { _, _, _, r ->
            Rect((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
        }
    }

    /**
     * The cue ball and where its aim line points. The ball is found by eroding
     * the white mask, which erases the thin aim line and the hollow ghost ring
     * and leaves the ball's solid core, so its centre is the ball's centre and
     * not a point dragged along the line. The tip is the bright pixels farthest
     * from the ball, which is the ring at the far end of the guideline.
     */
    fun aim(frame: Frame): Aim? {
        val w = frame.w; val h = frame.h; val px = frame.px
        val white = BooleanArray(px.size)
        for (i in px.indices) {
            if (!frame.inRoi(i)) continue
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            // Neutral brights only: the ball, the line and the ring are grey-white,
            // a hand hint or a gold ball is warm, which the red-blue gap catches.
            if (r > 185 && g > 185 && b > 185 && max(r, max(g, b)) - min(r, min(g, b)) < 45 && r - b < 35) white[i] = true
        }
        val e = max(2, w / 450)
        val core = erode(white, w, h, e)
        // A ball's core is small and fills its box; a hand hint or a caption
        // is bigger and ragged. Take the roundest core of ball size.
        val ball = componentize(core, w, h, 4) { x, y, a, r ->
            Blob(x, y, a, Rect(r.left, r.top, r.right, r.bottom))
        }.filter { it.area in BALL_CORE_MIN..BALL_CORE_MAX }
            .maxByOrNull { it.area.toDouble() / (max(1, it.bounds.width() + 1) * max(1, it.bounds.height() + 1)) }
            ?: return null
        val bx = ball.cx; val by = ball.cy
        val ballR = hypot(ball.bounds.width() / 2.0, ball.bounds.height() / 2.0).coerceAtLeast(2.0)
        val far = ArrayList<Pair<Double, Int>>()
        for (i in white.indices) {
            if (!white[i]) continue
            val d = hypot((i % w) - bx.toDouble(), (i / w) - by.toDouble())
            if (d > ballR * 1.8) far.add(d to i)
        }
        if (far.isEmpty()) return null
        far.sortByDescending { it.first }
        val top = far.take(max(6, far.size / 20))
        val tx = top.sumOf { it.second % w } / top.size
        val ty = top.sumOf { it.second / w } / top.size
        return Aim(frame.up(bx), frame.up(by), frame.up(tx), frame.up(ty))
    }

    fun colorMask(name: String): ((Int, Int, Int) -> Boolean)? = when (name.lowercase()) {
        "white", "bright" -> { r, g, b -> r > 195 && g > 195 && b > 195 }
        "black", "dark" -> { r, g, b -> r < 55 && g < 55 && b < 55 }
        "red" -> { r, g, b -> r > 140 && g < 90 && b < 90 }
        "green" -> { r, g, b -> g > 130 && r < 120 && b < 120 }
        "blue" -> { r, g, b -> b > 130 && r < 110 && g < 150 }
        "yellow" -> { r, g, b -> r > 170 && g > 150 && b < 110 }
        "orange" -> { r, g, b -> r > 200 && g in 100..190 && b < 90 }
        else -> null
    }

    private fun erode(hit: BooleanArray, w: Int, h: Int, e: Int): BooleanArray {
        val out = BooleanArray(hit.size)
        for (i in hit.indices) {
            if (!hit[i]) continue
            val x = i % w; val y = i / w
            if (x < e || y < e || x >= w - e || y >= h - e) continue
            if (hit[i - e] && hit[i + e] && hit[i - e * w] && hit[i + e * w]) out[i] = true
        }
        return out
    }

    private fun <T> componentize(
        hit: BooleanArray,
        w: Int,
        h: Int,
        minArea: Int,
        make: (cx: Int, cy: Int, area: Int, bounds: Rect) -> T,
    ): List<T> {
        val seen = BooleanArray(hit.size)
        val stack = ArrayDeque<Int>()
        val out = ArrayList<Pair<Int, T>>()
        for (start in hit.indices) {
            if (!hit[start] || seen[start]) continue
            stack.addLast(start); seen[start] = true
            var area = 0; var sx = 0L; var sy = 0L
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                val x = p % w; val y = p / w
                area++; sx += x; sy += y
                minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
                if (x > 0 && hit[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack.addLast(p - 1) }
                if (x < w - 1 && hit[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack.addLast(p + 1) }
                if (y > 0 && hit[p - w] && !seen[p - w]) { seen[p - w] = true; stack.addLast(p - w) }
                if (y < h - 1 && hit[p + w] && !seen[p + w]) { seen[p + w] = true; stack.addLast(p + w) }
            }
            if (area >= minArea) {
                out.add(area to make((sx / area).toInt(), (sy / area).toInt(), area, Rect(minX, minY, maxX, maxY)))
            }
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }
}
