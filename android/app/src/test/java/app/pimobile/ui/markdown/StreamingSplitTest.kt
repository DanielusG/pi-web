package app.pimobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSplitTest {

    private fun split(text: String): Pair<List<String>, String> {
        val split = StreamingSplit()
        val segments = split.update(text).toList()
        return segments to text.substring(split.closedEnd)
    }

    /** Feeds [text] a few characters at a time, like a stream of token deltas. */
    private fun streamed(text: String, step: Int = 3): Pair<List<String>, String> {
        val split = StreamingSplit()
        var end = 0
        while (end < text.length) {
            end = minOf(text.length, end + step)
            split.update(text.substring(0, end))
        }
        return split.update(text).toList() to text.substring(split.closedEnd)
    }

    @Test
    fun cutsBetweenParagraphs() {
        val (segments, tail) = split("First paragraph.\n\nSecond paragraph.\n\nThird, still stream")
        assertEquals(listOf("First paragraph.\n\n", "Second paragraph.\n\n"), segments)
        assertEquals("Third, still stream", tail)
    }

    @Test
    fun waitsForTheNextLineBeforeCutting() {
        assertEquals(emptyList<String>(), split("First paragraph.\n\n").first)
        // "1" may still become an ordered list item continuing a loose list.
        assertEquals(emptyList<String>(), split("- item\n\n1").first)
        assertEquals(listOf("Para\n\n"), split("Para\n\nNext").first)
    }

    @Test
    fun neverCutsInsideCodeFences() {
        val text = "Intro\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nAfter the code\n\nTail"
        val (segments, tail) = split(text)
        assertEquals(listOf("Intro\n\n", "```kotlin\nval a = 1\n\nval b = 2\n```\n\n", "After the code\n\n"), segments)
        assertEquals("Tail", tail)
    }

    @Test
    fun neverCutsInsideDisplayMath() {
        val dollars = "\$\$\na\n\nb\n\$\$\n\nText"
        assertEquals(listOf("\$\$\na\n\nb\n\$\$\n\n"), split(dollars).first)
        val brackets = "\\[\na\n\nb\n\\]\n\nText"
        assertEquals(listOf("\\[\na\n\nb\n\\]\n\n"), split(brackets).first)
    }

    @Test
    fun neverCutsInsideRawCodeBlocks() {
        val text = "<pre>\na\n\nb\n</pre>\n\nText"
        assertEquals(listOf("<pre>\na\n\nb\n</pre>\n\n"), split(text).first)
    }

    @Test
    fun keepsLooseListsAndContinuationsTogether() {
        val loose = "- one\n\n- two\n\n1. three\n\n    indented code\n\n  continuation\n\nAfter"
        val (segments, tail) = split(loose)
        assertEquals(listOf(loose.removeSuffix("After")), segments)
        assertEquals("After", tail)
    }

    @Test
    fun cutsBeforeHorizontalRulesHeadingsAndEmphasis() {
        val text = "A\n\n---\n\n# Title\n\n*emphasis*\n\n1.5 is a number\n\nEnd"
        assertEquals(
            listOf("A\n\n", "---\n\n", "# Title\n\n", "*emphasis*\n\n", "1.5 is a number\n\n"),
            split(text).first,
        )
    }

    @Test
    fun streamingInSmallStepsGivesTheSameSegments() {
        val docs = listOf(
            "First paragraph.\n\nSecond paragraph.\n\nThird",
            "Intro\n\n```\ncode\n\nmore\n```\n\n- a\n\n- b\n\n12. twelve\n\nDone\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nEnd",
            "\$\$\nx\n\ny\n\$\$\n\n\\[\nz\n\\]\n\n<pre>\n\n</pre>\n\nlast",
        )
        for (doc in docs) {
            for (step in listOf(1, 2, 5, 17)) {
                assertEquals("step $step of: $doc", split(doc), streamed(doc, step))
            }
        }
    }

    @Test
    fun segmentsAndTailRebuildTheText() {
        val doc = "a\n\nb\n\n```\nc\n\n```\n\n- d\n\n- e\n\nf"
        val (segments, tail) = streamed(doc, 4)
        assertEquals(doc, segments.joinToString("") + tail)
    }

    @Test
    fun returnsTheSameListWhileNothingCloses() {
        val split = StreamingSplit()
        val first = split.update("A\n\nB")
        assertSame(first, split.update("A\n\nBBB"))
        assertEquals(listOf("A\n\n"), split.update("A\n\nBBB"))
    }

    @Test
    fun restartsWhenTheTextIsNotAnExtension() {
        val split = StreamingSplit()
        split.update("A\n\nB\n\nC")
        val segments = split.update("X\n\nY")
        assertEquals(listOf("X\n\n"), segments)
        assertEquals(3, split.closedEnd)
        assertTrue(split.update("").isEmpty())
    }
}
