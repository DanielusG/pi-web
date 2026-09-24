package app.pimobile.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Own draw layer per closed segment: its drawing is recorded once and then reused. */
private val SegmentLayer = Modifier.graphicsLayer()

/**
 * [Markdown] for a message that is still streaming, at a cost proportional to the new text
 * instead of the whole message. The text is cut at safe block boundaries ([StreamingSplit]):
 * the segments before the last cut never change again, so each is parsed once, then skipped
 * by recomposition and drawn from its cached layer. Only the tail after the last cut is parsed
 * and laid out on every update.
 *
 * The finished message is rendered whole by [Markdown], so the final result is unchanged; only
 * while streaming may constructs spanning a cut (e.g. reference-style link definitions) show
 * differently.
 */
@Composable
fun StreamingMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    baseDir: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    val split = remember { StreamingSplit() }
    val segments = split.update(text)
    val tail = text.substring(split.closedEnd)
    // A stable callback, or every closed segment would recompose with each update.
    val currentOpenFile by rememberUpdatedState(onOpenFile)
    val openFile: ((String) -> Unit)? = remember(onOpenFile == null) {
        if (onOpenFile == null) null else { path -> currentOpenFile?.invoke(path) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        segments.forEachIndexed { index, segment ->
            key(index) { Markdown(segment, SegmentLayer, baseDir = baseDir, onOpenFile = openFile) }
        }
        if (tail.isNotBlank()) Markdown(tail, baseDir = baseDir, onOpenFile = openFile)
    }
}

/**
 * Incremental cutter for append-only Markdown text. A cut goes after a blank line that is
 * outside code fences, display math and raw code blocks, and only when the next line starts a
 * new top-level block: not indented (list item continuation, indented code) and not a list
 * marker (a loose list stays whole). Each update scans only the text added since the last one.
 */
internal class StreamingSplit {
    private val _segments = ArrayList<String>()
    private var text = ""
    /** End of the last closed segment: the tail starts here. */
    var closedEnd = 0
        private set
    /** Start of the first line not scanned yet. */
    private var scanPos = 0
    /** Position after the last blank line seen outside any block, waiting for the next line. */
    private var pendingCut = -1

    private var fenceChar = ' '
    private var fenceSize = 0 // 0 = outside a code fence
    private var rawCodeTag: String? = null
    private var bracketMath = false
    private var dollarMath = false

    private val inBlock get() = fenceSize > 0 || rawCodeTag != null || bracketMath || dollarMath

    /** The closed segments of [newText]; the same list instance while nothing new closes. */
    fun update(newText: String): List<String> {
        if (newText.length < scanPos || !newText.regionMatches(0, text, 0, scanPos)) reset()
        text = newText
        while (true) {
            val newline = newText.indexOf('\n', scanPos)
            if (newline < 0) break
            val line = newText.substring(scanPos, newline)
            scanPos = newline + 1
            if (pendingCut >= 0) {
                if (line.isBlank()) {
                    pendingCut = scanPos
                    continue
                }
                if (startsTopLevelBlock(line, complete = true) == true) cut(pendingCut)
                pendingCut = -1
            }
            scanLine(line)
            if (line.isBlank() && !inBlock) pendingCut = scanPos
        }
        // The line still streaming may already tell whether the pending blank line is a cut.
        if (pendingCut >= 0) {
            val partial = newText.substring(scanPos)
            if (partial.isNotBlank()) {
                when (startsTopLevelBlock(partial, complete = false)) {
                    true -> {
                        cut(pendingCut)
                        pendingCut = -1
                    }
                    false -> pendingCut = -1
                    null -> Unit
                }
            }
        }
        return _segments
    }

    private fun cut(at: Int) {
        if (at <= closedEnd) return
        _segments += text.substring(closedEnd, at)
        closedEnd = at
    }

    private fun reset() {
        _segments.clear()
        closedEnd = 0
        scanPos = 0
        pendingCut = -1
        fenceChar = ' '
        fenceSize = 0
        rawCodeTag = null
        bracketMath = false
        dollarMath = false
    }

    private fun scanLine(line: String) {
        if (fenceSize > 0) {
            val run = FENCE.find(line)?.groupValues?.get(1)
            if (run != null && run[0] == fenceChar && run.length >= fenceSize && line.substringAfter(run).isBlank()) {
                fenceSize = 0
            }
            return
        }
        rawCodeTag?.let { tag ->
            if (Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(line)) rawCodeTag = null
            return
        }
        if (bracketMath) {
            if (BRACKET_MATH_CLOSE.matches(line)) bracketMath = false
            return
        }
        if (dollarMath) {
            if (countDollarPairs(line) % 2 == 1) dollarMath = false
            return
        }
        FENCE.find(line)?.let { match ->
            val run = match.groupValues[1]
            fenceChar = run[0]
            fenceSize = run.length
            return
        }
        RAW_CODE_OPEN.find(line)?.let { match ->
            val tag = match.groupValues[1]
            val rest = line.substring(match.range.last + 1)
            if (!Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(rest)) rawCodeTag = tag
            return
        }
        if (BRACKET_MATH_OPEN.matches(line)) {
            bracketMath = true
            return
        }
        if (countDollarPairs(line) % 2 == 1) dollarMath = true
    }

    private fun countDollarPairs(line: String): Int {
        var count = 0
        var i = line.indexOf("$$")
        while (i >= 0) {
            count++
            i = line.indexOf("$$", i + 2)
        }
        return count
    }

    companion object {
        private val FENCE = Regex("^\\s*(`{3,}|~{3,})")
        private val RAW_CODE_OPEN = Regex("<(code|pre|script|style)\\b", RegexOption.IGNORE_CASE)
        private val BRACKET_MATH_OPEN = Regex("""^\s*\\\[\s*$""")
        private val BRACKET_MATH_CLOSE = Regex("""^\s*\\\]\s*$""")

        /**
         * Whether a line after a blank line starts a new top-level block; null while a line
         * that is still streaming is too short to tell (e.g. "1" may become "1. item").
         */
        fun startsTopLevelBlock(line: String, complete: Boolean): Boolean? {
            val first = line.firstOrNull() ?: return if (complete) true else null
            if (first == ' ' || first == '\t') return false
            if (first == '-' || first == '*' || first == '+') {
                val next = line.getOrNull(1) ?: return if (complete) false else null
                return !(next == ' ' || next == '\t')
            }
            if (first.isDigit()) {
                val end = line.indexOfFirst { !it.isDigit() }
                if (end < 0) return if (complete) true else null
                if (end > 9 || (line[end] != '.' && line[end] != ')')) return true
                val next = line.getOrNull(end + 1) ?: return if (complete) false else null
                return !(next == ' ' || next == '\t')
            }
            return true
        }
    }
}
