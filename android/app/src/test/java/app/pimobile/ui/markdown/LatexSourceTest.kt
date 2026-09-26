package app.pimobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexSourceTest {

    private val minus = "\\mathbin{−}"

    // spaces

    @Test
    fun dropsSpacesAroundSpacingCommands() {
        assertEquals("\\gamma\\,\\mathbf R_t", normalizeLatex("\\gamma \\, \\mathbf R_t"))
    }

    @Test
    fun keepsTheSpaceThatEndsAControlWordBeforeALetter() {
        assertEquals("\\sum_{j\\le t}\\mathbf x", normalizeLatex("\\sum_{j \\le t} \\mathbf x"))
    }

    @Test
    fun dropsTheSpaceAfterAControlWordBeforeAnythingElse() {
        assertEquals("\\alpha2+\\frac{a}{b}", normalizeLatex("\\alpha 2 + \\frac {a} {b}"))
    }

    @Test
    fun collapsesLineBreaksOfMultiLineFormulas() {
        assertEquals(
            "\\begin{aligned}a&=b\\\\c&=d\\end{aligned}",
            normalizeLatex("\\begin{aligned}\n  a &= b \\\\\n  c &= d\n\\end{aligned}"),
        )
    }

    @Test
    fun keepsTextArgumentsAsTheyAre() {
        assertEquals("x\\text{ for all t-1 }y", normalizeLatex("x \\text{ for all  t-1 } y"))
        assertEquals("\\text{a {b} c}", normalizeLatex("\\text{a {b} c}"))
        assertEquals("\\text{a b}", normalizeLatex("\\text {a b}"))
    }

    @Test
    fun keepsChemistryAsItIs() {
        assertEquals("\\ce{A + B -> C}", normalizeLatex("\\ce{A + B -> C}"))
    }

    @Test
    fun keepsAControlSpace() {
        assertEquals("a\\ b", normalizeLatex("a \\ b"))
    }

    @Test
    fun keepsEscapedBracesOutOfTheDepthCount() {
        assertEquals("\\text{\\}a b}c", normalizeLatex("\\text{\\}a b} c"))
    }

    @Test
    fun keepsTheLineEndingAComment() {
        assertEquals("a% note\n+b", normalizeLatex("a % note\n+ b"))
    }

    // minus

    @Test
    fun drawsBinaryAndUnaryMinusAsU2212() {
        assertEquals("\\mathbf v_t${minus}\\mathbf S_{t${minus}1}", normalizeLatex("\\mathbf v_t - \\mathbf S_{t-1}"))
        assertEquals("\\left(${minus}\\frac12\\right)", normalizeLatex("\\left( -\\frac12 \\right)"))
    }

    @Test
    fun turnsAUnicodeMinusIntoTheOperator() {
        assertEquals("1${minus}2", normalizeLatex("1 − 2"))
    }

    @Test
    fun bracesAMinusThatIsAWholeScript() {
        assertEquals("e^{${minus}}x", normalizeLatex("e^-x"))
        assertEquals("e^{${minus}x}", normalizeLatex("e^{-x}"))
    }

    @Test
    fun keepsTheSignOfDimensions() {
        assertEquals("a\\hspace{-1em}b", normalizeLatex("a \\hspace{-1em} b"))
        assertEquals("a\\hspace*{-1em}b", normalizeLatex("a\\hspace*{-1em}b"))
        assertEquals("a\\kern-3mu b", normalizeLatex("a \\kern -3 mu b"))
        assertEquals("a\\mkern-2mu${minus}b", normalizeLatex("a\\mkern-2mu - b"))
        assertEquals("a\\\\[-2pt]b", normalizeLatex("a \\\\[-2pt] b"))
    }

    @Test
    fun keepsTheSpaceBetweenARowBreakAndABracket() {
        assertEquals("a\\\\ [0,1]", normalizeLatex("a \\\\ [0,1]"))
    }

    @Test
    fun keepsHyphensInOperatorNames() {
        assertEquals("\\operatorname{arg-max}_x", normalizeLatex("\\operatorname{arg-max}_x"))
    }

    // inline

    @Test
    fun wrapsInlineFormulasInTextStyle() {
        assertEquals("\$\\sum_jx_j\$", inlineLatex("\\sum_j x_j"))
    }
}
