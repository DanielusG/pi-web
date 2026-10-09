package app.pimobile.ui.chat

import app.pimobile.data.BOTTOM_KEY
import app.pimobile.data.EARLIER_KEY
import app.pimobile.data.EMPTY_KEY
import app.pimobile.data.LOADING_KEY
import app.pimobile.data.STREAMING_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The anchor is what a chat is restored to; these are the viewport's items as (key, offset). */
class ViewportAnchorTest {

    @Test
    fun theItemUnderTheTopEdgeAnchorsWithThePixelsIntoIt() {
        assertEquals("m1" to 40, viewportAnchor(listOf(EARLIER_KEY to -90, "m1" to -40, "m2" to 10)))
    }

    @Test
    fun anExactlyAlignedItemAnchorsAtZero() {
        assertEquals("m1" to 0, viewportAnchor(listOf("m1" to 0, "m2" to 120)))
    }

    @Test
    fun rowsThatAreNotMessagesAnchorNothing() {
        // Only "m1" is a message, and the edge is still above it: the offset stays negative and
        // the restore clamps it to 0, which is the top of that message.
        assertEquals("m1" to -5, viewportAnchor(listOf(LOADING_KEY to -5, EARLIER_KEY to -1, "m1" to 5)))
    }

    @Test
    fun anEmptyViewportAnchorsNothing() {
        assertNull(viewportAnchor(emptyList()))
        assertNull(viewportAnchor(listOf(EMPTY_KEY to 0, STREAMING_KEY to 30)))
    }

    @Test
    fun bottomSpacerAnchorsNothing() {
        assertNull(viewportAnchor(listOf(BOTTOM_KEY to 0)))
        assertEquals("m1" to 20, viewportAnchor(listOf("m1" to -20, BOTTOM_KEY to 40)))
    }
}
