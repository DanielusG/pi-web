package app.pimobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pimobile.data.Block
import app.pimobile.data.ChatItem
import app.pimobile.data.Patch
import app.pimobile.data.ToolResult
import app.pimobile.data.arr
import app.pimobile.data.str
import app.pimobile.ui.markdown.CodeBlock
import app.pimobile.ui.markdown.Markdown
import app.pimobile.ui.theme.GeistMono
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import app.pimobile.ui.theme.ShimmerText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private val PrettyJson = Json { prettyPrint = true }
private const val MAX_OUTPUT_CHARS = 8_000
private val CardShape = RoundedCornerShape(12.dp)

private fun clip(text: String): String =
    if (text.length <= MAX_OUTPUT_CHARS) text
    else text.take(MAX_OUTPUT_CHARS) + "\n… ${text.length - MAX_OUTPUT_CHARS} more characters"

@Composable
fun UserBubble(item: ChatItem.User) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier
                .alpha(if (item.pending) 0.55f else 1f)
                .clip(RoundedCornerShape(22.dp))
                .background(t.muted)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, color = t.text)
            }
            if (item.imageCount > 0) {
                Text(
                    if (item.imageCount == 1) "1 image" else "${item.imageCount} images",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textTertiary,
                )
            }
        }
    }
}

@Composable
fun AssistantMessage(
    item: ChatItem.Assistant,
    toolResults: Map<String, ToolResult>,
    liveTools: Map<String, LiveTool>,
    running: Boolean,
    fullThinking: Map<String, String>,
    onLoadThinking: (entryId: String, blockIndex: Int) -> Unit,
    editPreviews: Map<String, EditPreview>,
    onPreview: (Block.ToolCall) -> Unit,
) {
    val t = Pi.tokens
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item.blocks.forEach { block ->
            when (block) {
                is Block.Text -> if (block.text.isNotBlank()) SelectionContainer { Markdown(block.text) }
                is Block.Thinking -> if (block.text.isNotBlank()) {
                    val key = item.entryId?.let { "$it:${block.blockIndex}" }
                    val full = key?.let { fullThinking[it] }
                    ThinkingBlock(
                        text = full ?: block.text,
                        needsLoad = block.deferred && full == null && item.entryId != null,
                        streaming = item.streaming,
                        onLoad = { item.entryId?.let { onLoadThinking(it, block.blockIndex) } },
                    )
                }
                is Block.ToolCall -> ToolCard(
                    call = block,
                    result = toolResults[block.id],
                    live = liveTools[block.id],
                    pending = running && toolResults[block.id] == null,
                    startedAt = item.timestamp,
                    preview = editPreviews[block.id],
                    onPreview = onPreview,
                )
                is Block.Image -> Text(
                    "[${block.mimeType}]",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.textTertiary,
                )
            }
        }
        if (item.stopReason == "aborted") {
            Text("Stopped", style = MaterialTheme.typography.labelMedium, color = t.textTertiary)
        }
        val error = item.errorMessage
        if (item.stopReason == "error" && !error.isNullOrBlank()) ErrorNote(error)
    }
}

@Composable
private fun ErrorNote(message: String) {
    val t = Pi.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(t.danger.copy(alpha = 0.06f))
            .border(1.dp, t.danger.copy(alpha = 0.3f), CardShape)
            .padding(12.dp),
    ) {
        Icon(PiIcons.Alert, null, tint = t.danger, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = t.text)
    }
}

@Composable
private fun ThinkingBlock(text: String, needsLoad: Boolean, streaming: Boolean, onLoad: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val t = Pi.tokens
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    expanded = !expanded
                    if (expanded && needsLoad) onLoad()
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) ShimmerText("Thinking")
            else Text("Thought process", style = MaterialTheme.typography.labelLarge, color = t.textTertiary)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) PiIcons.ChevronUp else PiIcons.ChevronDown,
                contentDescription = null,
                tint = t.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded || streaming) {
            val shown = if (expanded) text.trim() else text.lines().filter { it.isNotBlank() }.takeLast(2).joinToString("\n")
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    .padding(top = 6.dp),
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(t.borderStrong),
                )
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

private val MonoSmall = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 18.sp)
private val MonoOutput = TextStyle(fontFamily = GeistMono, fontSize = 12.5.sp, lineHeight = 19.sp)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
        color = Pi.tokens.textTertiary,
    )
}

@Composable
private fun ToolCard(
    call: Block.ToolCall,
    result: ToolResult?,
    live: LiveTool?,
    pending: Boolean,
    startedAt: Long?,
    preview: EditPreview?,
    onPreview: (Block.ToolCall) -> Unit,
) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val t = Pi.tokens
    val isError = result?.isError == true
    val generatingInput = call.input == null && call.rawInput.isNotEmpty()
    val isEdit = isEditToolName(call.name)
    // Finished edits carry the SDK's unified patch; pending ones ask the server for a preview.
    val patchText = result?.takeIf { !it.isError }?.details?.let { it.str("patch") ?: it.str("diff") }
    val resultDiff = remember(patchText) { patchText?.let(Patch::parse)?.takeIf { it.isNotEmpty() } }
    val canPreview = isEdit && result == null && !generatingInput && call.input?.arr("edits")?.isNotEmpty() == true
    val resultTime = result?.timestamp
    val seconds = if (resultTime != null && startedAt != null) ((resultTime - startedAt) / 1000.0).roundToInt() else 0

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(1.dp, if (isError) t.danger.copy(alpha = 0.35f) else t.border, CardShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    pending || generatingInput -> CircularProgressIndicator(
                        Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = t.textSecondary,
                        trackColor = Color.Transparent,
                    )
                    isError -> Icon(PiIcons.Alert, null, tint = t.danger, modifier = Modifier.size(15.dp))
                    // No result and nothing running (e.g. an interrupted run): no status, like pi-web.
                    result != null -> Icon(PiIcons.Check, null, tint = t.textTertiary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(call.name.ifBlank { "tool" }, style = MonoSmall.copy(fontWeight = FontWeight.Medium), color = t.text)
            Spacer(Modifier.width(8.dp))
            Text(
                if (generatingInput) "Generating input…" else toolSummaryLine(call.name, call.input),
                style = MonoSmall,
                color = t.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            resultDiff?.let { files ->
                Spacer(Modifier.width(8.dp))
                Text("+${files.sumOf { it.added }}", style = MonoSmall.copy(fontSize = 12.sp), color = t.success)
                Spacer(Modifier.width(4.dp))
                Text("-${files.sumOf { it.removed }}", style = MonoSmall.copy(fontSize = 12.sp), color = t.danger)
            }
            if (seconds > 0) {
                Spacer(Modifier.width(8.dp))
                Text("${seconds}s", style = MaterialTheme.typography.labelSmall, color = t.textTertiary)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) PiIcons.ChevronUp else PiIcons.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        val liveOutput = live?.output?.takeIf { it.isNotBlank() && result == null }
        if (liveOutput != null && !expanded) {
            HorizontalDivider(color = t.border)
            OutputArea(liveOutput.trimEnd().lines().takeLast(8).joinToString("\n"))
        }
        AnimatedVisibility(expanded) {
            Column {
                HorizontalDivider(color = t.border)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (canPreview) {
                        LaunchedEffect(call.id) { onPreview(call) }
                        SectionLabel("Proposed changes")
                        when (preview) {
                            is EditPreview.Ready -> {
                                val files = remember(preview.patch) { preview.patch?.let(Patch::parse).orEmpty() }
                                if (files.isEmpty()) {
                                    Text("No changes", style = MaterialTheme.typography.bodySmall, color = t.textTertiary)
                                } else {
                                    DiffView(files)
                                }
                            }
                            is EditPreview.Failed -> Text(
                                preview.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = t.danger,
                            )
                            else -> ShimmerText("Loading preview", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    // A finished or previewable edit is fully described by its diff.
                    if (!(isEdit && (result != null || canPreview))) {
                        SectionLabel("Input")
                        ToolInput(call)
                    }
                    when {
                        resultDiff != null -> {
                            SectionLabel("Changes")
                            DiffView(resultDiff)
                        }
                        result != null -> ResultBody(result)
                        liveOutput != null -> {
                            SectionLabel("Output")
                            CodeBlock("", clip(liveOutput.trimEnd()), header = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBody(result: ToolResult) {
    val t = Pi.tokens
    SectionLabel(if (result.isError) "Error" else "Output")
    val text = result.text.trimEnd()
    when {
        text.isEmpty() || text == "(no output)" -> Text(
            "No output",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = t.textTertiary,
        )
        result.isError -> {
            val shape = RoundedCornerShape(10.dp)
            Text(
                clip(text),
                style = MonoOutput,
                color = t.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(t.danger.copy(alpha = 0.05f))
                    .border(1.dp, t.danger.copy(alpha = 0.25f), shape)
                    .padding(12.dp),
            )
        }
        else -> CodeBlock("", clip(text), header = false)
    }
}

@Composable
private fun OutputArea(text: String) {
    val t = Pi.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .background(t.code)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MonoOutput, color = t.textSecondary, softWrap = false)
    }
}

@Composable
private fun ToolInput(call: Block.ToolCall) {
    val t = Pi.tokens
    val input: JsonObject? = call.input
    if (input == null) {
        if (call.rawInput.isNotBlank()) CodeBlock("", clip(call.rawInput), header = false)
        return
    }
    input.forEach { (key, value) ->
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: PrettyJson.encodeToString(JsonElement.serializer(), value)
        if (text.length <= 60 && '\n' !in text) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(key, style = MaterialTheme.typography.labelMedium, color = t.textTertiary)
                Spacer(Modifier.width(10.dp))
                Text(text, style = MonoSmall, color = t.text)
            }
        } else {
            Text(key, style = MaterialTheme.typography.labelMedium, color = t.textTertiary)
            CodeBlock("", clip(text), header = false)
        }
    }
}

@Composable
fun BashCard(item: ChatItem.Bash) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val t = Pi.tokens
    val failed = item.cancelled || (item.exitCode != null && item.exitCode != 0)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(1.dp, t.border, CardShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = MonoSmall, color = t.textTertiary)
            Spacer(Modifier.width(10.dp))
            Text(
                item.command,
                style = MonoSmall,
                color = t.text,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    item.cancelled -> "cancelled"
                    item.exitCode != null -> "exit ${item.exitCode}"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) t.danger else t.textTertiary,
            )
        }
        if (expanded && item.output.isNotBlank()) {
            HorizontalDivider(color = t.border)
            OutputArea(clip(item.output.trimEnd()))
        }
    }
}

@Composable
fun NoticeRow(item: ChatItem.Notice) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val t = Pi.tokens
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = t.border)
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                color = t.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            HorizontalDivider(Modifier.weight(1f), color = t.border)
        }
        if (expanded && item.body.isNotBlank()) {
            Markdown(item.body, Modifier.padding(top = 10.dp))
        }
    }
}
