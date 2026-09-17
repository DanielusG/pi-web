package app.pimobile.data

/**
 * Turns a markdown assistant message into plain text a TTS voice can speak.
 * Code is dropped (reading identifiers aloud is noise); everything else keeps its words.
 */
object TtsText {
    // Precompiled regexes to avoid compiling 19 patterns on each sanitize call.
    private val FENCED_CODE_BLOCK = Regex("(?s)(^|\\n)[ \\t]*(```|~~~).*?(\\n[ \\t]*\\2[ \\t]*(\\n|$)|$)")
    private val BLOCKQUOTE_MARKER = Regex("(?m)^[ \\t]*>[ \\t]?")
    private val HORIZONTAL_RULE = Regex("(?m)^[ \\t]*([-_*][ \\t]*){3,}$")
    private val HEADING_MARKER = Regex("(?m)^[ \\t]*#{1,6}[ \\t]+")
    private val UNORDERED_LIST = Regex("(?m)^[ \\t]*[-*+][ \\t]+")
    private val TABLE_SEPARATOR = Regex("(?m)^[ \\t]*\\|?([ \\t]*:?-+:?[ \\t]*\\|[ \\t]*)+$")
    private val HTML_BREAKS = Regex("(?i)<br\\s*/?>|</?p>")
    private val HTML_TAGS = Regex("</?[a-zA-Z][^>]*>")
    private val IMAGES = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val INLINE_LINKS = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val REFERENCE_LINKS = Regex("\\[([^\\]]*)\\]\\[[^\\]]*\\]")
    private val STRIKETHROUGH = Regex("~~(.*?)~~")
    private val BOLD_ASTERISKS = Regex("""\*\*(.*?)\*\*""")
    private val BOLD_UNDERSCORES = Regex("""(?<=\s|^)__(?!\s)(.*?)(?<=\S)__(?=\s|[.,;:!?]|$)""")
    private val ITALIC_ASTERISKS = Regex("""(?<=\s|^)\*(?!\s)(.*?)(?<=\S)\*(?=\s|[.,;:!?]|$)""")
    private val ITALIC_UNDERSCORES = Regex("""(?<=\s|^)_(?!\s)(.*?)(?<=\S)_(?=\s|[.,;:!?]|$)""")
    private val ESCAPED_MARKDOWN = Regex("""\\([*_`~#\[\]()<>])""")
    private val SPACES = Regex("[ \\t]+")
    private val BLANK_LINES = Regex("\\s*\\n\\s*")

    fun sanitize(markdown: String): String {
        var text = markdown
        // 1. Fenced code blocks are dropped entirely (including unclosed blocks at EOF).
        text = FENCED_CODE_BLOCK.replace(text, " ")
        // 2. Blockquotes: drop leading > markers.
        text = BLOCKQUOTE_MARKER.replace(text, "")
        // 3. Horizontal rules: drop lines of 3+ dashes, asterisks, or underscores.
        text = HORIZONTAL_RULE.replace(text, " ")
        // 4. Headings: drop leading # markers.
        text = HEADING_MARKER.replace(text, "")
        // 5. Unordered list bullets: strip leading -, *, + markers (keep numbered lists like 1. 2.).
        text = UNORDERED_LIST.replace(text, "")
        // 6. Markdown table formatting: drop separator rows and pipes.
        text = TABLE_SEPARATOR.replace(text, " ")
        text = text.replace("|", " ")
        // 7. HTML tags: convert break/paragraph tags to line breaks; strip other tags cleanly.
        text = HTML_BREAKS.replace(text, "\n")
        text = HTML_TAGS.replace(text, "")
        // 8. Images: drop.
        text = IMAGES.replace(text, " ")
        // 9. Links: keep the label.
        text = INLINE_LINKS.replace(text, "$1")
        // Reference-style links [label][id]: keep the label.
        text = REFERENCE_LINKS.replace(text, "$1")
        // 10. Bold, italic, and strikethrough: strip formatting markers while preserving snake_case and math (*).
        text = STRIKETHROUGH.replace(text, "$1")
        text = BOLD_ASTERISKS.replace(text, "$1")
        text = BOLD_UNDERSCORES.replace(text, "$1")
        text = ITALIC_ASTERISKS.replace(text, "$1")
        text = ITALIC_UNDERSCORES.replace(text, "$1")
        // 11. Escaped markdown characters: drop backslash.
        text = ESCAPED_MARKDOWN.replace(text, "$1")
        // 12. Inline code: keep the code text, drop the backticks.
        text = text.replace("`", "")
        // 13. Collapse whitespace and blank lines.
        text = text.replace(SPACES, " ")
        text = text.replace(BLANK_LINES, "\n")
        return text.trim()
    }
}
