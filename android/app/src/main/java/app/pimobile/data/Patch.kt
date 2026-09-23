package app.pimobile.data

enum class DiffKind { Context, Added, Removed, Gap }

data class Segment(val text: String, val changed: Boolean)

data class DiffLine(
    val kind: DiffKind,
    val oldNumber: Int?,
    val newNumber: Int?,
    val text: String,
    /** Word-level split for a removed/added pair; null renders the line plain. */
    val segments: List<Segment>? = null,
)

data class DiffFile(val path: String?, val lines: List<DiffLine>) {
    val added: Int get() = lines.count { it.kind == DiffKind.Added }
    val removed: Int get() = lines.count { it.kind == DiffKind.Removed }
}

/**
 * Unified patch parser, mirroring pi-web's lib/patch.ts: `---`/`+++` are file
 * headers only between hunks, removed/added runs are paired line by line for
 * word highlights. Hunk boundaries become [DiffKind.Gap] rows.
 */
object Patch {
    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    fun parse(patch: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var path: String? = null
        var lines = mutableListOf<DiffLine>()
        val removed = mutableListOf<DiffLine>()
        val added = mutableListOf<DiffLine>()
        var oldNumber = 0
        var newNumber = 0
        var oldLeft = 0
        var newLeft = 0

        fun flushChanges() {
            for (i in 0 until minOf(removed.size, added.size)) {
                WordDiff.segments(removed[i].text, added[i].text)?.let { (left, right) ->
                    removed[i] = removed[i].copy(segments = left)
                    added[i] = added[i].copy(segments = right)
                }
            }
            lines.addAll(removed)
            lines.addAll(added)
            removed.clear()
            added.clear()
        }

        fun flushFile() {
            flushChanges()
            if (lines.any { it.kind != DiffKind.Gap }) files += DiffFile(path, lines)
            lines = mutableListOf()
        }

        for (raw in patch.lineSequence()) {
            val inHunk = oldLeft > 0 || newLeft > 0
            if (!inHunk) {
                when {
                    raw.startsWith("--- ") -> {
                        flushFile()
                        path = raw.removePrefix("--- ").substringBefore('\t').trim()
                    }
                    raw.startsWith("+++ ") -> {
                        path = raw.removePrefix("+++ ").substringBefore('\t').trim().ifEmpty { path }
                    }
                    else -> HUNK.find(raw)?.let { hunk ->
                        flushChanges()
                        if (lines.isNotEmpty()) lines += DiffLine(DiffKind.Gap, null, null, "")
                        oldNumber = hunk.groupValues[1].toInt()
                        oldLeft = hunk.groupValues[2].ifEmpty { "1" }.toInt()
                        newNumber = hunk.groupValues[3].toInt()
                        newLeft = hunk.groupValues[4].ifEmpty { "1" }.toInt()
                    }
                }
                continue
            }
            when {
                raw.startsWith("+") -> {
                    added += DiffLine(DiffKind.Added, null, newNumber++, raw.substring(1))
                    newLeft--
                }
                raw.startsWith("-") -> {
                    removed += DiffLine(DiffKind.Removed, oldNumber++, null, raw.substring(1))
                    oldLeft--
                }
                raw.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> {
                    flushChanges()
                    lines += DiffLine(DiffKind.Context, oldNumber++, newNumber++, raw.removePrefix(" "))
                    oldLeft--
                    newLeft--
                }
            }
        }
        flushFile()
        return files
    }
}

/**
 * Word-level diff of two lines (LCS over word/space/punctuation tokens), with
 * pi-web's bail-outs: long lines, identical lines, or mostly-rewritten lines
 * render without inline highlights.
 */
object WordDiff {
    private val TOKEN = Regex("\\s+|\\w+|[^\\w\\s]")
    private const val MAX_LINE = 500

    fun segments(old: String, new: String): Pair<List<Segment>, List<Segment>>? {
        if (old.isEmpty() || new.isEmpty() || old == new || old.length > MAX_LINE || new.length > MAX_LINE) return null
        val a = TOKEN.findAll(old).map { it.value }.toList()
        val b = TOKEN.findAll(new).map { it.value }.toList()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val left = mutableListOf<Segment>()
        val right = mutableListOf<Segment>()
        fun MutableList<Segment>.add(text: String, changed: Boolean) {
            if (isNotEmpty() && last().changed == changed) this[lastIndex] = Segment(last().text + text, changed)
            else add(Segment(text, changed))
        }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { left.add(a[i++], false); right.add(b[j++], false) }
                lcs[i + 1][j] >= lcs[i][j + 1] -> left.add(a[i++], true)
                else -> right.add(b[j++], true)
            }
        }
        while (i < a.size) left.add(a[i++], true)
        while (j < b.size) right.add(b[j++], true)

        if (left.size <= 1 && right.size <= 1) return null
        val changedLeft = left.filter { it.changed }.sumOf { it.text.length }.toDouble() / old.length
        val changedRight = right.filter { it.changed }.sumOf { it.text.length }.toDouble() / new.length
        if ((changedLeft + changedRight) / 2 > 0.5) return null
        return left to right
    }
}
