package app.pimobile.ui.markdown

/*
 * Formula rewrites for the LaTeX renderer (huarangmeng/latex 1.5.4), which draws two things unlike
 * TeX (android/docs/latex-rendering-2026-09-26.md):
 * - TeX ignores spaces in math mode; the library draws each run as a 0.25em space and drops its
 *   operator spacing next to it, so `\gamma \, \mathbf R` got a wide gap.
 * - `-` is drawn with the hyphen glyph.
 */

/** Arguments copied unchanged: text mode (spaces and hyphens are text), dimensions, names. */
private val VERBATIM_ARGUMENT = setOf(
    "text", "textrm", "textbf", "textit", "textsf", "texttt", "textnormal", "textup", "textmd",
    "textsl", "textsc", "mbox", "hbox", "emph", "operatorname", "tag", "label", "ref", "eqref",
    "href", "url", "ce", "pu", "hspace", "vspace", "mspace", "raisebox", "rule", "cline",
)

/** Commands followed by a dimension without braces, where `-` is a sign: `\kern-3mu`. */
private val BARE_DIMENSION = setOf("kern", "mkern", "hskip", "mskip")

/** U+2212 with the binary operator class, so the library still spaces it as a minus. */
private const val MINUS = "\\mathbin{−}"

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/** What a space in the source separates, which decides whether it is kept. */
private enum class Gap {
    /** Nothing: math mode ignores it. */
    NONE,
    /** A control word (or its bare dimension) from a following letter: `\le t`, `\kern3mu x`. */
    WORD,
    /** `\\` from a following `[`, which would read as its optional argument. */
    ROW,
}

/**
 * [latex] with its math-mode spaces dropped and its minus signs drawn as U+2212. A space stays where
 * it ends a control word before a letter (`\le t`, `\mathbf x`).
 */
fun normalizeLatex(latex: String): String {
    val out = StringBuilder(latex.length + 16)
    var depth = 0
    var verbatimDepth = -1 // brace depth of the argument being copied unchanged, -1 outside one
    var verbatimNext = false // the next `{` opens such an argument
    var gap = Gap.NONE // what a space at the current position would separate
    var i = 0
    while (i < latex.length) {
        val c = latex[i]
        when {
            c == '\\' && i + 1 < latex.length -> {
                var end = i + 1
                if (latex[end].isAsciiLetter()) {
                    while (end < latex.length && latex[end].isAsciiLetter()) end++
                    val name = latex.substring(i + 1, end)
                    out.append(latex, i, end)
                    gap = Gap.WORD
                    if (verbatimDepth < 0) {
                        verbatimNext = name in VERBATIM_ARGUMENT
                        if (name in BARE_DIMENSION) {
                            val dimensionEnd = bareDimensionEnd(latex, end)
                            latex.substring(end, dimensionEnd).filterTo(out) { !it.isWhitespace() }
                            end = dimensionEnd
                        }
                    }
                } else {
                    end++
                    out.append(latex, i, end)
                    gap = Gap.NONE
                    if (latex[i + 1] == '\\') {
                        gap = Gap.ROW
                        val close = if (end < latex.length && latex[end] == '[') latex.indexOf(']', end) else -1
                        if (verbatimDepth < 0 && close != -1) {
                            out.append(latex, end, close + 1)
                            end = close + 1
                            gap = Gap.NONE
                        }
                    }
                }
                i = end
            }
            c == '%' -> {
                // A comment runs to the end of the line, which must stay to end it.
                val lineEnd = latex.indexOf('\n', i).let { if (it == -1) latex.length else it + 1 }
                out.append(latex, i, lineEnd)
                gap = Gap.NONE
                i = lineEnd
            }
            c.isWhitespace() -> {
                var end = i + 1
                while (end < latex.length && latex[end].isWhitespace()) end++
                val next = latex.getOrNull(end)
                val keep = verbatimDepth >= 0 || when (gap) {
                    Gap.NONE -> false
                    Gap.WORD -> next != null && next.isAsciiLetter()
                    Gap.ROW -> next == '['
                }
                if (keep) out.append(' ')
                // A space does not end the wait for an argument: `\text {a}`.
                i = end
            }
            else -> {
                when {
                    c == '{' -> {
                        depth++
                        if (verbatimNext && verbatimDepth < 0) verbatimDepth = depth
                        out.append(c)
                    }
                    c == '}' -> {
                        if (depth == verbatimDepth) verbatimDepth = -1
                        depth--
                        out.append(c)
                    }
                    (c == '-' || c == '−') && verbatimDepth < 0 -> {
                        // A script takes one token: `e^-x` becomes `e^{−}x`, as in TeX.
                        val scripted = out.isNotEmpty() && (out.last() == '^' || out.last() == '_')
                        if (scripted) out.append('{').append(MINUS).append('}') else out.append(MINUS)
                    }
                    else -> out.append(c)
                }
                // `\hspace*{…}`: the star belongs to the command.
                if (c != '*') verbatimNext = false
                gap = Gap.NONE
                i++
            }
        }
    }
    return out.toString()
}

/** End of the dimension written right after `\kern` and the like (`-3mu`, ` 2.5 pt`), or [start]. */
private fun bareDimensionEnd(latex: String, start: Int): Int {
    var i = start
    fun skipSpaces() {
        while (i < latex.length && latex[i].isWhitespace()) i++
    }
    skipSpaces()
    if (i < latex.length && (latex[i] == '-' || latex[i] == '+')) i++
    skipSpaces()
    val digits = i
    while (i < latex.length && (latex[i].isDigit() || latex[i] == '.')) i++
    if (i == digits) return start
    skipSpaces()
    // TeX units are two letters: `mu`, `pt`, `em`.
    if (i + 1 < latex.length && latex[i].isAsciiLetter() && latex[i + 1].isAsciiLetter()) return i + 2
    return start
}

/**
 * Source for an inline formula: `$…$` makes the library lay it out in text style (small operators,
 * limits beside Σ), which neither [com.hrm.latex.renderer.Latex] nor its measurer can otherwise ask for.
 */
fun inlineLatex(latex: String): String = "$" + normalizeLatex(latex) + "$"
