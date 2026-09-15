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
    val Image by lazy {
        lineIcon(
            "Image",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M8.5 7a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3",
            "M21 15l-5-5L5 21",
        )
    }
    val Close by lazy { lineIcon("Close", "M18 6L6 18", "M6 6l12 12") }
    val Warning by lazy {
        lineIcon("Warning", "M10.3 2.9L1.8 17a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 2.9a2 2 0 0 0-3.4 0z", "M12 9v4", "M12 17h.01")
    }
    val File by lazy { lineIcon("File", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    val FileText by lazy {
        lineIcon("FileText", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6", "M9 17h6")
    }
    val AtSign by lazy {
        lineIcon("AtSign", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8", "M16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.5 7.1")
    }
    val Search by lazy { lineIcon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14", "M20 20l-4-4") }
    val More by lazy {
        lineIcon(
            "More",
            "M12 4.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2",
            "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2",
            "M12 17.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2",
        )
    }
    val Share by lazy { lineIcon("Share", "M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7", "M16 6l-4-4-4 4", "M12 2v13") }
    val ExternalLink by lazy {
        lineIcon("ExternalLink", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6", "M15 3h6v6", "M10 14L21 3")
    }
    val ChevronRight by lazy { lineIcon("ChevronRight", "M9 18l6-6-6-6") }
    val WrapText by lazy { lineIcon("WrapText", "M3 6h18", "M3 12h15a3 3 0 1 1 0 6h-4", "M16 16l-2 2 2 2", "M3 18h7") }
    val Music by lazy {
        lineIcon("Music", "M9 18V5l12-2v13", "M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6", "M18 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6")
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
