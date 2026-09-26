package app.pimobile.ui.markdown

/** How wide a table column wants to be, padding included, in px. */
class ColumnExtent(
    /** Its widest cell on one line. */
    val natural: Float,
    /** Its widest piece that cannot wrap: a word or a formula. */
    val min: Float,
)

/**
 * Column widths like a browser's automatic table layout. A table that fits on one line fills
 * [available], each column in proportion to its content. Otherwise text wraps: each column keeps its
 * minimum and gets a share of the space left in proportion to how much more it would take. A table
 * whose minimums do not fit gets them and scrolls.
 */
fun tableWidths(columns: List<ColumnExtent>, available: Float): List<Float> {
    val natural = columns.sumOf { it.natural.toDouble() }.toFloat()
    val min = columns.sumOf { it.min.toDouble() }.toFloat()
    return when {
        natural <= available -> columns.map { it.natural * available / natural }
        min < available -> columns.map { it.min + (available - min) * (it.natural - it.min) / (natural - min) }
        else -> columns.map { it.min }
    }
}
