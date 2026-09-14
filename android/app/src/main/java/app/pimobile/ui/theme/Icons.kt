package app.pimobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Thin 24px line icons (2px round strokes); tinted by Icon like any vector. */
object PiIcons {
    val ArrowUp by lazy { lineIcon("ArrowUp", "M12 19V5", "M5 12l7-7 7 7") }
    val ArrowLeft by lazy { lineIcon("ArrowLeft", "M19 12H5", "M12 19l-7-7 7-7") }
    val ChevronDown by lazy { lineIcon("ChevronDown", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { lineIcon("ChevronUp", "M18 15l-6-6-6 6") }
    val Plus by lazy { lineIcon("Plus", "M12 5v14", "M5 12h14") }
    val Check by lazy { lineIcon("Check", "M20 6L9 17l-5-5") }
    val Alert by lazy {
        lineIcon("Alert", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18", "M12 8v4.5", "M12 16h.01")
    }
    val Copy by lazy {
        lineIcon(
            "Copy",
            "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z",
            "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1",
        )
    }
    val Refresh by lazy { lineIcon("Refresh", "M21 12a9 9 0 1 1-3-6.7", "M21 4v5h-5") }
    val Sliders by lazy {
        lineIcon(
            "Sliders",
            "M4 7h9", "M19 7h1", "M16 4a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
            "M4 17h1", "M11 17h9", "M8 14a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
        )
    }
    val Folder by lazy {
        lineIcon("Folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    }
}

private fun lineIcon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (path in paths) {
        builder.addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
