package app.pimobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.data.DiffFile
import app.pimobile.data.DiffKind
import app.pimobile.data.DiffLine
import app.pimobile.data.arr
import app.pimobile.data.int
import app.pimobile.data.str
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Tool-name predicates and header summaries follow pi-web's lib/tool-names.ts
// and lib/tool-display.ts so both clients describe a tool call the same way.

fun isEditToolName(name: String): Boolean =
    name == "edit" || name.startsWith("edit_") || name.endsWith(".edit") || name.endsWith("_edit") ||
        "str_replace" in name || "replace_editor" in name

fun isWriteToolName(name: String): Boolean =
    name == "write" || name.startsWith("write_") || name.endsWith(".write") || name.endsWith("_write")

/** Tools that take a file path the viewer can open. */
fun isFileToolName(name: String): Boolean = name == "read" || isEditToolName(name) || isWriteToolName(name)

/** Web: readToolPath — `file_path ?? path`, non-empty. */
fun toolInputPath(input: JsonObject?): String? =
    (input?.str("file_path") ?: input?.str("path"))?.takeIf { it.isNotEmpty() }

private fun clipText(value: String, max: Int): String = if (value.length > max) value.take(max) + "…" else value

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

fun toolSummaryLine(name: String, input: JsonObject?): String {
    input ?: return ""
    fun s(key: String) = input.str(key)
    return when (name) {
        "bash", "run_command" ->
            clipText((s("command") ?: s("cmd")).orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), 160)
        "read" -> {
            val path = s("path").orEmpty()
            val offset = input.int("offset")
            val limit = input.int("limit")
            when {
                offset != null && limit != null -> "$path (from line $offset, $limit lines)"
                offset != null -> "$path (from line $offset)"
                limit != null -> "$path ($limit lines)"
                else -> path
            }
        }
        "write", "ls" -> s("path").orEmpty()
        "edit" -> {
            val path = s("path").orEmpty()
            input.arr("edits")?.size?.let { "$path · ${plural(it, "edit")}" } ?: path
        }
        "grep", "find" -> {
            val pattern = s("pattern").orEmpty()
            clipText(s("path")?.let { "$pattern in $it" } ?: pattern, 120)
        }
        "web_search" -> clipText(s("query").orEmpty(), 120)
        "web_fetch" -> s("url").orEmpty()
        "batch_web_fetch" -> input.arr("requests")?.size?.let { plural(it, "url") }.orEmpty()
        "Agent", "agent", "Task" -> clipText(s("description").orEmpty(), 120)
        "ask_user_question" -> input.arr("questions")?.size?.let { plural(it, "question") }.orEmpty()
        "steer_subagent", "get_subagent_result" -> s("agent_id").orEmpty()
        else -> {
            val value = listOf("command", "path", "pattern", "query", "url", "description", "agent_id")
                .firstNotNullOfOrNull { s(it) }
                ?: input.values.firstNotNullOfOrNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                }
            clipText(value.orEmpty().lineSequence().firstOrNull().orEmpty(), 140)
        }
    }
}

private const val MAX_DIFF_LINES = 400
private val DiffText = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 19.sp)

/** Unified diff with line numbers and word-level highlights (pi-web's split view, adapted to phone width). */
@Composable
fun DiffView(files: List<DiffFile>, modifier: Modifier = Modifier, maxLines: Int = MAX_DIFF_LINES) {
    val t = Pi.tokens
    val shape = RoundedCornerShape(10.dp)
    val digits = files.maxOfOrNull { file ->
        file.lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) } ?: 0
    }?.toString()?.length?.coerceAtLeast(2) ?: 2
    val gutter = (digits * 7 + 14).dp
    val total = files.sumOf { it.lines.size }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.code)
            .border(1.dp, t.border, shape)
            .padding(vertical = 6.dp),
    ) {
        var shown = 0
        for (file in files) {
            if (files.size > 1 && file.path != null) {
                Text(
                    file.path,
                    style = DiffText.copy(fontWeight = FontWeight.Medium),
                    color = t.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            for (line in file.lines) {
                if (shown >= maxLines) break
                DiffRow(line, gutter)
                shown++
            }
        }
        if (total > maxLines) {
            Text(
                "… ${total - maxLines} more lines",
                style = MaterialTheme.typography.labelSmall,
                color = t.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, gutter: Dp) {
    val t = Pi.tokens
    if (line.kind == DiffKind.Gap) {
        Text(
            "⋯",
            style = DiffText,
            color = t.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(gutter)
                .padding(end = 8.dp),
        )
        return
    }
    val background: Color
    val highlight: Color
    val marker: String
    val markerColor: Color
    when (line.kind) {
        DiffKind.Added -> {
            background = t.success.copy(alpha = 0.10f)
            highlight = t.success.copy(alpha = 0.30f)
            marker = "+"
            markerColor = t.success
        }
        DiffKind.Removed -> {
            background = t.danger.copy(alpha = 0.10f)
            highlight = t.danger.copy(alpha = 0.28f)
            marker = "-"
            markerColor = t.danger
        }
        else -> {
            background = Color.Transparent
            highlight = Color.Transparent
            marker = " "
            markerColor = t.textTertiary
        }
    }
    val text = remember(line, highlight) {
        buildAnnotatedString {
            val segments = line.segments
            if (segments == null) append(line.text)
            else segments.forEach { segment ->
                if (segment.changed) withStyle(SpanStyle(background = highlight)) { append(segment.text) }
                else append(segment.text)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background),
    ) {
        Text(
            (line.newNumber ?: line.oldNumber)?.toString().orEmpty(),
            style = DiffText,
            color = t.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(gutter)
                .padding(end = 8.dp),
        )
        Text(
            marker,
            style = DiffText.copy(fontWeight = FontWeight.SemiBold),
            color = markerColor,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text,
            style = DiffText,
            color = t.text,
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
        )
    }
}
