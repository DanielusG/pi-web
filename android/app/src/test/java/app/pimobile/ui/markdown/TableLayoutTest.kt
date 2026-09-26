package app.pimobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TableLayoutTest {

    private fun widths(available: Float, vararg columns: Pair<Float, Float>) =
        tableWidths(columns.map { (natural, min) -> ColumnExtent(natural, min) }, available)

    private fun assertWidths(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.01f) }
    }

    @Test
    fun stretchesATableThatFitsOnOneLine() {
        assertWidths(listOf(100f, 300f), widths(400f, 50f to 50f, 150f to 60f))
    }

    @Test
    fun wrapsTextToFitGivingTheRestInProportionToWhatEachColumnWants() {
        // Minimums 100 + 100 leave 200, shared 100:300 by the extra width each column wants.
        assertWidths(listOf(150f, 250f), widths(400f, 200f to 100f, 400f to 100f))
    }

    @Test
    fun aColumnThatCannotWrapKeepsItsWidth() {
        // A formula column: its minimum is its natural width.
        assertWidths(listOf(250f, 150f), widths(400f, 250f to 250f, 400f to 100f))
    }

    @Test
    fun scrollsATableWhoseMinimumsDoNotFit() {
        assertWidths(listOf(300f, 200f), widths(400f, 500f to 300f, 600f to 200f))
    }
}
