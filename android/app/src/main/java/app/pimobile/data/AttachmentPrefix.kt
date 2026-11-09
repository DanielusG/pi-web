package app.pimobile.data

/** A document attached to a prompt: its saved copy on the server and its name on the device. */
data class AttachmentRef(val path: String, val name: String)

/**
 * The text put before a prompt to hand the model the documents the user attached
 * (server: lib/attachment-prefix.ts, which must stay in step). The files are saved
 * by POST /api/sessions/[id]/attachments; the prompt only names them:
 *
 * ```
 * Attachments from the user (you should probably read them; Pi saved them in /…/<id>/):
 * - /…/<id>/a1b2c3_notes.txt (original name: notes.txt)
 *
 * ---
 *
 * <what the user typed, absent when they typed nothing>
 * ```
 */
object AttachmentPrefix {
    data class Split(val dir: String, val attachments: List<AttachmentRef>, val body: String)

    private const val HEADER_START = "Attachments from the user (you should probably read them; Pi saved them in "
    private const val HEADER_END = "):"
    private const val LINE_START = "- "
    private const val ORIGINAL_NAME = " (original name: "
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]+")

    fun format(dir: String, attachments: List<AttachmentRef>): String =
        (listOf("$HEADER_START$dir$HEADER_END") +
            attachments.map { "$LINE_START${it.path}$ORIGINAL_NAME${it.name.replace(CONTROL, " ")})" })
            .joinToString("\n")

    /** [body] after the attachment list, or the list alone when the user typed nothing. */
    fun wrap(dir: String, attachments: List<AttachmentRef>, body: String): String = when {
        attachments.isEmpty() -> body
        body.isEmpty() -> format(dir, attachments)
        else -> format(dir, attachments) + "\n\n---\n\n" + body
    }

    /** [text] as a one-line list shows it: the body, or the attached names for files alone (server: attachmentDisplayText). */
    fun display(text: String): String {
        val split = split(text) ?: return text
        return split.body.ifEmpty { split.attachments.joinToString(", ") { it.name } }
    }

    /** The prefix and the body apart, or null when [text] does not start with a complete prefix. */
    fun split(text: String): Split? {
        if (!text.startsWith(HEADER_START)) return null
        val lines = text.split("\n")
        val header = lines[0]
        if (!header.endsWith(HEADER_END)) return null
        val dir = header.substring(HEADER_START.length, header.length - HEADER_END.length)
        if (dir.isEmpty()) return null

        val attachments = mutableListOf<AttachmentRef>()
        var index = 1
        while (index < lines.size && lines[index].startsWith(LINE_START)) {
            val line = lines[index]
            val at = line.indexOf(ORIGINAL_NAME)
            if (at <= LINE_START.length || !line.endsWith(")")) return null
            attachments += AttachmentRef(
                path = line.substring(LINE_START.length, at),
                name = line.substring(at + ORIGINAL_NAME.length, line.length - 1),
            )
            index++
        }
        if (attachments.isEmpty()) return null

        if (index == lines.size) return Split(dir, attachments, "")
        if (lines[index] != "" || lines.getOrNull(index + 1) != "---" || lines.getOrNull(index + 2) != "") return null
        return Split(dir, attachments, lines.drop(index + 3).joinToString("\n"))
    }
}
