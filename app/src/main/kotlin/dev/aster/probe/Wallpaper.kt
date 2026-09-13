package dev.aster.probe

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Set the wallpaper from an image on disk. Going through the picker means a
 * crop dialog someone has to confirm, which is not available on a phone nobody
 * is holding, so this scales and crops the image itself.
 */
object Wallpaper {

    fun set(context: Context, path: String): String {
        if (path.isBlank()) return "error: wallpaper needs an image path\n"
        val file = File(path)
        if (!file.exists()) return "error: no file at $path\n"
        val manager = WallpaperManager.getInstance(context)
        // Not the launcher's desiredMinimum: that is a double-width landscape
        // canvas for parallax, and cropping a portrait photo to it keeps a
        // band across the middle instead of the picture.
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val source = decode(file, width, height)
            ?: return "error: $path is not an image Android can read\n"
        return runCatching {
            manager.setBitmap(fill(source, width, height))
            "wallpaper set from ${file.name} (${source.width}x${source.height} " +
                "cropped to ${width}x$height)\n"
        }.getOrElse { "error: could not set the wallpaper: $it\n" }
    }

    /**
     * Wallpapers are far larger than the screen needs, and decoding one at full
     * size is what makes this run out of memory. Sample it down on the way in.
     */
    private fun decode(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** Cover the target and centre the overflow, the way a launcher would. */
    private fun fill(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaled = RectF(
            0f,
            0f,
            (source.width * scale).roundToInt().toFloat(),
            (source.height * scale).roundToInt().toFloat(),
        )
        scaled.offset((width - scaled.width()) / 2f, (height - scaled.height()) / 2f)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            scaled,
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }
}
