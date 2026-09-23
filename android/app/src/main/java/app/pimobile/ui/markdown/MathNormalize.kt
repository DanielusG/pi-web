package app.pimobile.ui.markdown

/*
 * Port of pi-web's normalizeDisplayMath (lib/markdown.ts), kept line-for-line so the two stay in
 * sync: models emit display math in shapes the Markdown math parsers do not recognise
 * (`\[ … \]`, `$$` glued to the formula, formulas nested in list items).
 */

private const val DD = "\$\$"

private val LINE_BREAK = Regex("\r?\n")
private val CODE_FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
private val RAW_CODE_OPEN = Regex("<(code|pre|script|style)\\b", RegexOption.IGNORE_CASE)
private val INDENTED_CODE = Regex("^(?: {4}|\t)")
private val ESCAPED_INLINE_CODE = Regex("""(?<![\\`])`((?:[^`\n]|\\`)+?)(?<![\\`])`(?!`)""")
private val BACKTICK_RUN = Regex("`+")
private val BRACKET_DISPLAY_ONE_LINE = Regex("""^( {0,3})\\\[[ \t]*(.+?)[ \t]*\\\][ \t]*$""")
private val LOOSE_BRACKET_DISPLAY_ONE_LINE = Regex("""^( {0,3})\[[ \t]*(.+?)[ \t]*\][ \t]*$""")
private val BRACKET_DISPLAY_START = Regex("""^( {0,3})\\\[[ \t]*$""")
private val BRACKET_DISPLAY_CLOSE = Regex("""^ {0,3}\\\][ \t]*$""")
private val DISPLAY_MATH_ONE_LINE = Regex("""^([ \t]{0,3})\$\$(.+)\$\$[ \t]*$""")
private val DISPLAY_MATH_MULTI_LINE = Regex("""^([ \t]{0,3})\$\$(.+)$""")
private val DISPLAY_MATH_BARE_OPEN = Regex("""^([ \t]{0,3})\$\$\s*$""")
private val DISPLAY_MATH_TOP_LEVEL_FENCE = Regex("""^ {0,3}\$\$\s*$""")
private val DISPLAY_MATH_FENCE = Regex("""^\$\$\s*$""")
private val DISPLAY_MATH_GLUED_CLOSE = Regex("""^(.+?)\$\$\s*$""")
private val DISPLAY_MATH_OPENING_LINE = Regex("""^ {0,3}\$\$(?:\S|[ \t]+\S)""")
private val DISPLAY_MATH_BLOCK_BOUNDARIES = listOf(
    CODE_FENCE,
    Regex("""^[ \t]*(?:[-+*]|\d{1,9}[.)])(?:[ \t]+|$)"""),
    Regex("""^ {0,3}#{1,6}(?:[ \t]+|$)"""),
    Regex("^ {0,3}>"),
    RAW_CODE_OPEN,
)
private val INLINE_LATEX_SKIP = listOf(
    Regex("""^\s{0,3}\[[^\]]+\]:"""),
    Regex("""\]\s*\("""),
    Regex("""<(?:!--|/?[A-Za-z][^>]*>)"""),
    Regex("""\b(?:https?|file|mailto):""", RegexOption.IGNORE_CASE),
    Regex("""\b[A-Za-z]:\\"""),
)
private val INLINE_LATEX = Regex("""(?<!\\)\\\(([^`\r\n$]+?)(?<!\\)\\\)""")
private val LATEX_COMMAND = Regex("""\\[A-Za-z]+""")
private val NOT_MATH = Regex("""\b(?:https?|file|mailto):|\b[A-Za-z]:\\|^\\\\""", RegexOption.IGNORE_CASE)

private fun rawCodeClose(tag: String) = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)

private fun rewriteEscapedInlineCodeBackticks(line: String): String =
    ESCAPED_INLINE_CODE.replace(line) { match ->
        val content = match.groupValues[1]
        val code = content.replace("\\`", "`")
        if (code == content) return@replace match.value
        val marker = "`".repeat((BACKTICK_RUN.findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1)
        "$marker$code$marker"
    }

fun normalizeDisplayMath(markdown: String): String {
    val lineBreak = if ("\r\n" in markdown) "\r\n" else "\n"
    val lines = markdown.split(LINE_BREAK)
    val normalized = ArrayList<String>(lines.size)
    var fenceMarker = ' '
    var fenceSize = 0 // 0 = outside a code fence
    var inlineCodeMarkerSize = 0
    var rawCodeTag: String? = null
    val unmatchedDisplayMathUntil = HashMap<String, Int>()

    var index = -1
    while (++index < lines.size) {
        var line = lines[index]

        val openRawCodeTag = rawCodeTag
        if (openRawCodeTag != null) {
            normalized += line
            if (rawCodeClose(openRawCodeTag).containsMatchIn(line)) rawCodeTag = null
            continue
        }

        val fenceMatch = CODE_FENCE.find(line)
        if (fenceMatch != null) {
            val run = fenceMatch.groupValues[1]
            if (fenceSize == 0) {
                fenceMarker = run[0]
                fenceSize = run.length
            } else if (run[0] == fenceMarker && run.length >= fenceSize) {
                fenceSize = 0
            }
            inlineCodeMarkerSize = 0
            normalized += line
            continue
        }

        if (fenceSize != 0) {
            normalized += line
            continue
        }

        val rawCodeOpen = RAW_CODE_OPEN.find(line)
        if (rawCodeOpen != null) {
            val tag = rawCodeOpen.groupValues[1].lowercase()
            val remainder = line.substring(rawCodeOpen.range.last + 1)
            if (!rawCodeClose(tag).containsMatchIn(remainder)) rawCodeTag = tag
            inlineCodeMarkerSize = 0
            normalized += line
            continue
        }

        if (INDENTED_CODE.containsMatchIn(line) || line.isBlank()) {
            inlineCodeMarkerSize = 0
            normalized += line
            continue
        }

        if (inlineCodeMarkerSize == 0) line = rewriteEscapedInlineCodeBackticks(line)

        if (inlineCodeMarkerSize != 0 || '`' in line) {
            inlineCodeMarkerSize = updateInlineCodeMarker(line, inlineCodeMarkerSize)
            normalized += line
            continue
        }

        val bracketDisplayOneLine = BRACKET_DISPLAY_ONE_LINE.find(line)
        if (bracketDisplayOneLine != null) {
            val indent = bracketDisplayOneLine.groupValues[1]
            val math = bracketDisplayOneLine.groupValues[2].trim()
            if (math.isNotEmpty()) {
                // Keep the content line indented together with the `$$` fence: at column 0 inside a
                // list item it would be a lazy continuation line and break the fence pair.
                normalized += listOf("$indent$DD", "$indent$math", "$indent$DD")
                continue
            }
        }

        val looseBracketDisplayOneLine = LOOSE_BRACKET_DISPLAY_ONE_LINE.find(line)
        if (looseBracketDisplayOneLine != null) {
            val indent = looseBracketDisplayOneLine.groupValues[1]
            val math = looseBracketDisplayOneLine.groupValues[2].trim()
            if (isLikelyMathExpression(math)) {
                normalized += listOf("$indent$DD", "$indent$math", "$indent$DD")
                continue
            }
        }

        val bracketDisplayStart = BRACKET_DISPLAY_START.find(line)
        if (bracketDisplayStart != null) {
            val closingIndex = findBracketDisplayClose(lines, index + 1)
            if (closingIndex != -1) {
                val indent = bracketDisplayStart.groupValues[1]
                normalized += "$indent$DD"
                for (j in index + 1 until closingIndex) normalized += indentDisplayMathContent(lines[j], indent)
                normalized += "$indent$DD"
                index = closingIndex
                continue
            }
        }

        val displayMathMatch = DISPLAY_MATH_ONE_LINE.find(line)
        if (displayMathMatch != null) {
            val indent = displayMathMatch.groupValues[1]
            val math = displayMathMatch.groupValues[2].trim()
            if (math.isNotEmpty()) {
                normalized += listOf("$indent$DD", "$indent$math", "$indent$DD")
                continue
            }
        }

        // Multi-line block with the opening `$$` glued to the first formula line and/or the closing
        // one glued to the last (`$$x = 1` + `y = 2$$`): otherwise it swallows the following text.
        val displayMathMultiLine = DISPLAY_MATH_MULTI_LINE.find(line)
        if (displayMathMultiLine != null) {
            val indent = displayMathMultiLine.groupValues[1]
            val firstLine = displayMathMultiLine.groupValues[2].trimEnd()
            // `$$x$$ and text` stays untouched and is rendered as inline math.
            if (firstLine.isNotEmpty() && DD !in firstLine) {
                val closing = findDisplayMathClose(lines, index + 1, indent, unmatchedDisplayMathUntil)
                if (closing != null) {
                    normalized += listOf("$indent$DD", "$indent$firstLine")
                    for (j in index + 1 until closing.index) normalized += indentDisplayMathContent(lines[j], indent)
                    if (closing.content.isNotEmpty()) normalized += "$indent${closing.content}"
                    normalized += "$indent$DD"
                    index = closing.index
                    continue
                }
            }
        }

        // Bare `$$` opener: move a glued closing `$$` to its own line and re-indent lazy content
        // lines inside list items. A column-0 block with a detached closing `$$` is left untouched.
        val displayMathBareOpen = DISPLAY_MATH_BARE_OPEN.find(line)
        if (displayMathBareOpen != null) {
            val indent = displayMathBareOpen.groupValues[1]
            val closing = findDisplayMathClose(lines, index + 1, indent, unmatchedDisplayMathUntil)
            if (closing != null && (closing.glued || indent != "")) {
                normalized += "$indent$DD"
                for (j in index + 1 until closing.index) normalized += indentDisplayMathContent(lines[j], indent)
                if (closing.content.isNotEmpty()) normalized += "$indent${closing.content}"
                normalized += "$indent$DD"
                index = closing.index
                continue
            }
        }

        normalized += normalizeInlineLatexMath(line)
    }

    return normalized.joinToString(lineBreak)
}

private class DisplayMathClose(val index: Int, val content: String, val glued: Boolean)

private fun findDisplayMathClose(
    lines: List<String>,
    startIndex: Int,
    indent: String,
    unmatchedUntil: MutableMap<String, Int>,
): DisplayMathClose? {
    val knownUnmatchedUntil = unmatchedUntil[indent]
    if (knownUnmatchedUntil != null && startIndex < knownUnmatchedUntil) return null

    for (index in startIndex until lines.size) {
        val line = lines[index]
        if (isDisplayMathFence(line, indent)) return DisplayMathClose(index, "", glued = false)

        // A new Markdown block cannot belong to the preceding formula; in particular a later
        // sibling list item must not provide the closing `$$`.
        if (isDisplayMathBlockBoundary(line) || DISPLAY_MATH_OPENING_LINE.containsMatchIn(line)) {
            unmatchedUntil[indent] = index
            return null
        }

        val content = getDisplayMathGluedCloseContent(line, indent)
        if (content != null) return DisplayMathClose(index, content, glued = true)
    }

    // Cache the unmatched range so several unmatched openers keep the search linear.
    unmatchedUntil[indent] = lines.size
    return null
}

private fun isDisplayMathFence(line: String, indent: String): Boolean =
    if (indent.isEmpty()) DISPLAY_MATH_TOP_LEVEL_FENCE.containsMatchIn(line)
    else line.startsWith(indent) && DISPLAY_MATH_FENCE.containsMatchIn(line.substring(indent.length))

private fun getDisplayMathGluedCloseContent(line: String, indent: String): String? {
    if (!line.startsWith(indent)) return null
    val match = DISPLAY_MATH_GLUED_CLOSE.find(line.substring(indent.length)) ?: return null
    val content = match.groupValues[1].trimEnd()
    return content.takeIf { it.isNotEmpty() && DD !in it }
}

private fun isDisplayMathBlockBoundary(line: String): Boolean =
    DISPLAY_MATH_BLOCK_BOUNDARIES.any { it.containsMatchIn(line) }

private fun indentDisplayMathContent(line: String, indent: String): String {
    if (indent.isEmpty() || line.isEmpty() || line.startsWith("\t")) return line
    val leadingSpaces = line.length - line.trimStart(' ').length
    if (leadingSpaces >= indent.length) return line
    return indent.substring(leadingSpaces) + line
}

private fun findBracketDisplayClose(lines: List<String>, startIndex: Int): Int {
    for (index in startIndex until lines.size) {
        val line = lines[index]
        if (BRACKET_DISPLAY_CLOSE.containsMatchIn(line)) return index
        // Do not pair delimiters across another Markdown block boundary.
        if (CODE_FENCE.containsMatchIn(line) || BRACKET_DISPLAY_START.containsMatchIn(line) ||
            RAW_CODE_OPEN.containsMatchIn(line)
        ) {
            return -1
        }
    }
    return -1
}

private fun updateInlineCodeMarker(line: String, initialMarkerSize: Int): Int {
    var markerSize = initialMarkerSize
    var cursor = 0
    while (cursor < line.length) {
        if (line[cursor] != '`') {
            cursor++
            continue
        }
        var end = cursor + 1
        while (end < line.length && line[end] == '`') end++
        val runSize = end - cursor
        if (markerSize == 0) markerSize = runSize
        else if (runSize == markerSize) markerSize = 0
        cursor = end
    }
    return markerSize
}

private fun normalizeInlineLatexMath(line: String): String {
    if (INLINE_LATEX_SKIP.any { it.containsMatchIn(line) }) return line
    return INLINE_LATEX.replace(line) { match ->
        val math = match.groupValues[1]
        if (math.isNotBlank()) "\$$math\$" else match.value
    }
}

private fun isLikelyMathExpression(value: String): Boolean =
    LATEX_COMMAND.containsMatchIn(value) && !NOT_MATH.containsMatchIn(value)

/**
 * Turns every `$$` display block into a ```math fence (Android only, runs after
 * [normalizeDisplayMath]). The JetBrains parser reads `$$` math inside paragraphs, so a formula
 * line starting with `-`, `#` or `>` would split the block; fenced code content is never parsed.
 */
fun displayMathToFences(markdown: String): String {
    val lineBreak = if ("\r\n" in markdown) "\r\n" else "\n"
    val lines = markdown.split(LINE_BREAK)
    val result = ArrayList<String>(lines.size)
    var fenceMarker = ' '
    var fenceSize = 0

    var index = -1
    while (++index < lines.size) {
        val line = lines[index]
        val fenceMatch = CODE_FENCE.find(line)
        if (fenceMatch != null) {
            val run = fenceMatch.groupValues[1]
            if (fenceSize == 0) {
                fenceMarker = run[0]
                fenceSize = run.length
            } else if (run[0] == fenceMarker && run.length >= fenceSize) {
                fenceSize = 0
            }
            result += line
            continue
        }
        val open = if (fenceSize == 0) DISPLAY_MATH_BARE_OPEN.find(line) else null
        val indent = open?.groupValues?.get(1)
        val closingIndex = if (indent != null) findMathFenceClose(lines, index + 1, indent) else -1
        if (indent == null || closingIndex == -1) {
            result += line
            continue
        }
        result += "$indent```math"
        for (j in index + 1 until closingIndex) result += lines[j]
        result += "$indent```"
        index = closingIndex
    }

    return result.joinToString(lineBreak)
}

private fun findMathFenceClose(lines: List<String>, startIndex: Int, indent: String): Int {
    for (index in startIndex until lines.size) {
        val line = lines[index]
        if (isDisplayMathFence(line, indent)) return index
        // A code fence would end the math fence early; a line left of the indent leaves the list item.
        if (CODE_FENCE.containsMatchIn(line)) return -1
        if (indent.isNotEmpty() && line.isNotBlank() && !line.startsWith(indent)) return -1
    }
    return -1
}

/** Source ready for the Markdown parser: normalized delimiters, display math as ```math fences. */
fun prepareMathMarkdown(markdown: String): String = displayMathToFences(normalizeDisplayMath(markdown))
