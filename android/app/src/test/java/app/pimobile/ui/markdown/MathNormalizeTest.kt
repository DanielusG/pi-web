package app.pimobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The normalizeDisplayMath cases mirror pi-web's lib/markdown.test.mjs. */
class MathNormalizeTest {

    // single-line $$…$$

    @Test
    fun splitsSingleLineBlockIntoThreeLines() {
        assertEquals("\$\$\na + b\n\$\$", normalizeDisplayMath("\$\$a + b\$\$"))
    }

    @Test
    fun preservesIndentOfSurroundingListItem() {
        assertEquals(
            "- item:\n  \$\$\n  a + b\n  \$\$\n- next",
            normalizeDisplayMath("- item:\n  \$\$a + b\$\$\n- next"),
        )
    }

    // multi-line blocks with glued delimiters

    @Test
    fun movesGluedOpeningDelimiterToItsOwnLine() {
        val input = "\$\$\n\\frac{a}{b} = c\n<d\$\$\n\nafter"
        assertEquals("\$\$\n\\frac{a}{b} = c\n<d\n\$\$\n\nafter", normalizeDisplayMath(input))
    }

    @Test
    fun movesGluedClosingDelimiterToItsOwnLine() {
        val input = "\$\$\nx = y\nz = w\$\$\n\nafter"
        assertEquals("\$\$\nx = y\nz = w\n\$\$\n\nafter", normalizeDisplayMath(input))
    }

    // blocks nested in GFM list items

    @Test
    fun reindentsLazyContentLinesOfIndentedBareFenceBlock() {
        val input = "- item:\n  \$\$\nx = y\n  \$\$\n- next"
        assertEquals("- item:\n  \$\$\n  x = y\n  \$\$\n- next", normalizeDisplayMath(input))
    }

    @Test
    fun reindentsPartiallyIndentedContentLines() {
        val input = "- item:\n  \$\$\n x = y\n  \$\$\n- next"
        assertEquals("- item:\n  \$\$\n  x = y\n  \$\$\n- next", normalizeDisplayMath(input))
    }

    @Test
    fun doesNotUseSiblingListItemFormulaAsClosingFence() {
        val input = "- first\n  \$\$x = y\n- second\n  \$\$z = w\$\$\n- third"
        assertEquals(
            "- first\n  \$\$x = y\n- second\n  \$\$\n  z = w\n  \$\$\n- third",
            normalizeDisplayMath(input),
        )
    }

    @Test
    fun doesNotScanBareFencePastSiblingListItem() {
        val input = "- first\n  \$\$\nx = y\n- second\n  \$\$z = w\$\$\n- third"
        assertEquals(
            "- first\n  \$\$\nx = y\n- second\n  \$\$\n  z = w\n  \$\$\n- third",
            normalizeDisplayMath(input),
        )
    }

    // blocks that must stay untouched

    @Test
    fun leavesTopLevelBlockWithDetachedDelimitersUntouched() {
        val input = "\$\$\n\\frac{a}{b}\n\$\$\n\nend"
        assertEquals(input, normalizeDisplayMath(input))
    }

    @Test
    fun leavesContentInsideFencedCodeBlocksUntouched() {
        val input = "```\n\$\$ not math \$\$\n\$\$\n```\n\nreal \$\$x = 1\$\$ end"
        val normalized = normalizeDisplayMath(input)
        assertTrue(normalized.contains("```\n\$\$ not math \$\$\n\$\$\n```"))
        // every `$$` is preserved: 3 inside the fence + 2 in inline math
        assertEquals(5, Regex("""\$\$""").findAll(normalized).count())
    }

    @Test
    fun leavesInlineMathAndPlainProseUntouched() {
        val input = "text \$x = 1\$ and \$\$a + b\$\$ more"
        assertEquals(input, normalizeDisplayMath(input))
    }

    @Test
    fun doesNotTreatGluedOpenerWithMidLineDelimiterAsBlock() {
        val input = "\$\$x\$\$ and text"
        assertEquals(input, normalizeDisplayMath(input))
    }

    // \[ … \] blocks

    @Test
    fun normalizesSingleLineBrackets() {
        assertEquals("\$\$\na + b\n\$\$", normalizeDisplayMath("\\[a + b\\]"))
    }

    @Test
    fun keepsBracketContentIndentedInListItem() {
        assertEquals(
            "- item:\n  \$\$\n  a + b\n  \$\$\n- next",
            normalizeDisplayMath("- item:\n  \\[a + b\\]\n- next"),
        )
    }

    @Test
    fun normalizesMultiLineBracketsWithoutDoubleIndenting() {
        assertEquals(
            "- item:\n  \$\$\n  x = y\n  \$\$\n- next",
            normalizeDisplayMath("- item:\n  \\[\n  x = y\n  \\]\n- next"),
        )
    }

    // loose [ … ] formula blocks

    @Test
    fun normalizesModelEmittedBracketOnlyFormulaLines() {
        assertEquals(
            "\$\$\nC(x) = \\frac{2}{T(T-1)} \\sum_{i<j} S(\\hat{y}^{(i)}, \\hat{y}^{(j)})\n\$\$",
            normalizeDisplayMath("[ C(x) = \\frac{2}{T(T-1)} \\sum_{i<j} S(\\hat{y}^{(i)}, \\hat{y}^{(j)}) ]"),
        )
    }

    @Test
    fun leavesAmbiguousBracketOnlyMarkdownUntouched() {
        for (input in listOf(
            "[普通说明文字]",
            "[See note (important)]",
            "[status=ready]",
            "[yes/no]",
            "[API_v2]\n\n[API_v2]: https://example.com/docs",
            "[C:\\Users\\alex]",
            "[\\\\server\\share]",
            "[https://example.com/\\alpha]",
        )) {
            assertEquals(input, normalizeDisplayMath(input))
        }
    }

    // \( … \) inline math

    @Test
    fun convertsParenthesisInlineMathToDollars() {
        assertEquals("area \$\\pi r^2\$ here", normalizeDisplayMath("area \\(\\pi r^2\\) here"))
    }

    // Android: display math as ```math fences

    @Test
    fun turnsDisplayBlockIntoMathFence() {
        assertEquals("```math\na + b\n```", prepareMathMarkdown("\$\$\na + b\n\$\$"))
    }

    @Test
    fun keepsMathFenceIndentedInListItem() {
        assertEquals(
            "- item:\n  ```math\n  a + b\n  ```\n- next",
            prepareMathMarkdown("- item:\n  \$\$a + b\$\$\n- next"),
        )
    }

    @Test
    fun keepsFormulaLinesThatLookLikeMarkdownInsideTheFence() {
        assertEquals(
            "```math\n\\begin{aligned}\n- a &= b \\\\\n# c &= d\n\\end{aligned}\n```",
            prepareMathMarkdown("\$\$\n\\begin{aligned}\n- a &= b \\\\\n# c &= d\n\\end{aligned}\n\$\$"),
        )
    }

    @Test
    fun leavesDollarBlocksInsideCodeFencesAlone() {
        val input = "```\n\$\$\nx\n\$\$\n```"
        assertEquals(input, prepareMathMarkdown(input))
    }

    @Test
    fun leavesUnclosedBlockWhileStreaming() {
        val input = "\$\$\nx = 1"
        assertEquals(input, prepareMathMarkdown(input))
    }

    @Test
    fun leavesInlineMathForTheParser() {
        val input = "text \$x\$ and \$\$a\$\$ more"
        assertEquals(input, prepareMathMarkdown(input))
    }
}
