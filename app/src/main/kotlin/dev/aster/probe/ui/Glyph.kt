package dev.aster.probe.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The mockups' icons, path for path: 24-unit box, 2-unit round stroke, no fill. Tint sets the colour. */
object Glyph {
    val close = stroked("close", "M18 6 6 18M6 6l12 12")
    val chevron = stroked("chevron", "m9 6 6 6-6 6")
    val stop = stroked("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z")
    val play = stroked("play", "M7 4v16l13-8z")
    val back = stroked("back", "m15 18-6-6 6-6")
    val search = stroked("search", "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z", "m20 20-3.5-3.5")
    val user = stroked("user", "M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z", "M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1")
    val agent = stroked("agent", "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4")
    val check = stroked("check", "m5 12 5 5L20 7")
    val settings = stroked(
        "settings",
        "M20 7h-9",
        "M14 17H5",
        "M17 14a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z",
        "M7 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z",
    )
    val caret = stroked("caret", "m6 9 6 6 6-6")
    val dash = stroked("dash", "M5 12h14")
    val error = stroked("error", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M12 8v4M12 16h.01")
    val trophy = stroked(
        "trophy",
        "M6 9H4.5a2.5 2.5 0 0 1 0-5H6",
        "M18 9h1.5a2.5 2.5 0 0 0 0-5H18",
        "M4 22h16",
        "M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22",
        "M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22",
        "M18 2H6v7a6 6 0 0 0 12 0V2Z",
    )

    private fun stroked(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
