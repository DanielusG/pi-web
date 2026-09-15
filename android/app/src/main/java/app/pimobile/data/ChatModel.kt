package app.pimobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface Block {
    data class Text(val text: String) : Block
    data class Thinking(val text: String, val deferred: Boolean, val blockIndex: Int) : Block
    data class ToolCall(
        val id: String,
        val name: String,
        val input: JsonObject?,
        val rawInput: String = "",
    ) : Block
    data class Image(val mimeType: String) : Block
}

data class ToolResult(
    val text: String,
    val isError: Boolean,
    val imageCount: Int,
    /** Tool-specific payload, e.g. edit's `{diff, patch, firstChangedLine}`. */
    val details: JsonObject? = null,
    val timestamp: Long? = null,
    val entryId: String? = null,
    /** Order in the loaded history, to find the latest result of a message. */
    val position: Int = 0,
)

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val images: List<ImagePayload>,
        val pending: Boolean,
        /** `/skill:<name> [args]` when [text] is a skill expansion, shown collapsed as on the web. */
        val command: String? = null,
        /** Edit from here targets the message itself: the SDK moves the leaf to its parent (none for the first). */
        val entryId: String? = null,
        val timestamp: Long? = null,
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val entryId: String?,
        val blocks: List<Block>,
        val errorMessage: String?,
        val stopReason: String?,
        val streaming: Boolean,
        val timestamp: Long? = null,
    ) : ChatItem

    data class Bash(
        override val key: String,
        val command: String,
        val output: String,
        val exitCode: Int?,
        val cancelled: Boolean,
    ) : ChatItem

    data class Notice(override val key: String, val title: String, val body: String) : ChatItem
}

/** A message as returned by the API plus the identity the list needs. */
data class LoadedMessage(
    val key: String,
    val entryId: String?,
    val json: JsonObject,
    val pending: Boolean = false,
)

object Messages {
    fun contentText(content: JsonElement?): String = when (content) {
        is JsonNull -> ""
        is JsonPrimitive -> content.content
        is JsonArray -> content
            .mapNotNull { (it as? JsonObject)?.takeIf { block -> block.type == "text" }?.str("text") }
            .joinToString("\n")
        else -> ""
    }

    /** User input as typed: a skill expansion reads as its `/skill:` command. */
    fun userText(content: JsonElement?): String = SlashDisplay.display(contentText(content))

    private fun imageCount(content: JsonElement?): Int =
        (content as? JsonArray)?.count { (it as? JsonObject)?.type == "image" } ?: 0

    /** Base64 image blocks, in both the flat pi-ai `{data, mimeType}` and nested `{source}` spellings. */
    fun images(content: JsonElement?): List<ImagePayload> =
        (content as? JsonArray).orEmpty().mapNotNull { element ->
            val block = (element as? JsonObject)?.takeIf { it.type == "image" } ?: return@mapNotNull null
            val source = block.obj("source")?.takeIf { it.type == "base64" }
            val data = source?.str("data") ?: block.str("data") ?: return@mapNotNull null
            ImagePayload(data, source?.str("media_type") ?: block.str("mimeType") ?: "image/png")
        }

    /**
     * Tool calls arrive as `{toolCallId, toolName, input}` from session history
     * but as `{id, name, arguments}` from SSE `message_*` events; accept both.
     */
    fun block(json: JsonObject?, index: Int): Block? = when (json?.type) {
        "text" -> Block.Text(json.str("text").orEmpty())
        "thinking" -> Block.Thinking(json.str("thinking").orEmpty(), json.bool("deferred") == true, index)
        "toolCall" -> Block.ToolCall(
            id = json.str("toolCallId") ?: json.str("id").orEmpty(),
            name = json.str("toolName") ?: json.str("name").orEmpty(),
            input = json.obj("input") ?: json.obj("arguments"),
        )
        "image" -> Block.Image(json.str("mimeType") ?: json.obj("source")?.str("media_type") ?: "image")
        else -> null
    }

    private fun blocks(content: JsonElement?): List<Block> = when (content) {
        is JsonNull -> emptyList()
        is JsonPrimitive -> listOf(Block.Text(content.content))
        is JsonArray -> content.mapIndexedNotNull { index, element -> block(element as? JsonObject, index) }
        else -> emptyList()
    }

    fun toolResults(messages: List<LoadedMessage>): Map<String, ToolResult> = buildMap {
        for ((index, message) in messages.withIndex()) {
            val json = message.json
            if (json.str("role") != "toolResult") continue
            val id = json.str("toolCallId") ?: continue
            put(
                id,
                ToolResult(
                    text = contentText(json["content"]),
                    isError = json.bool("isError") == true,
                    imageCount = imageCount(json["content"]),
                    details = json.obj("details"),
                    timestamp = json.long("timestamp"),
                    entryId = message.entryId,
                    position = index,
                ),
            )
        }
    }

    /** Tool results are not list items: they render inside their tool call card. */
    fun item(message: LoadedMessage): ChatItem? {
        val json = message.json
        return when (json.str("role")) {
            "user" -> contentText(json["content"]).let { text ->
                ChatItem.User(
                    message.key,
                    text,
                    images(json["content"]),
                    message.pending,
                    SlashDisplay.skillExpansionToCommand(text),
                    entryId = message.entryId,
                    timestamp = json.long("timestamp"),
                )
            }
            "assistant" -> ChatItem.Assistant(
                key = message.key,
                entryId = message.entryId,
                blocks = blocks(json["content"]),
                errorMessage = json.str("errorMessage"),
                stopReason = json.str("stopReason"),
                streaming = false,
                timestamp = json.long("timestamp"),
            )
            "bashExecution" -> ChatItem.Bash(
                message.key,
                json.str("command").orEmpty(),
                json.str("output").orEmpty(),
                json.int("exitCode"),
                json.bool("cancelled") == true,
            )
            "custom" -> if (json.bool("display") == false) null else ChatItem.Notice(
                message.key,
                when (val customType = json.str("customType")) {
                    "compaction" -> "Context compacted"
                    null -> "Note"
                    else -> customType
                },
                contentText(json["content"]),
            )
            else -> null
        }
    }
}

/**
 * Rebuilds the in-flight assistant message from `message_start` snapshots and
 * `message_update.assistantMessageEvent` deltas (see lib/streaming-message.ts).
 */
class StreamingAssembler {
    private sealed interface Slot {
        class Text(val text: StringBuilder) : Slot
        class Thinking(val text: StringBuilder) : Slot
        class Tool(val id: String, val name: String, val raw: StringBuilder, val input: JsonObject?) : Slot
        class Other(val block: Block?) : Slot
    }

    private val slots = mutableListOf<Slot?>()

    var active: Boolean = false
        private set

    fun start(message: JsonObject?) {
        slots.clear()
        active = true
        (message?.get("content") as? JsonArray)?.forEachIndexed { index, element ->
            val json = element as? JsonObject
            slots += when (json?.type) {
                "text" -> Slot.Text(StringBuilder(json.str("text").orEmpty()))
                "thinking" -> Slot.Thinking(StringBuilder(json.str("thinking").orEmpty()))
                "toolCall" -> Slot.Tool(
                    json.str("toolCallId") ?: json.str("id").orEmpty(),
                    json.str("toolName") ?: json.str("name").orEmpty(),
                    StringBuilder(),
                    json.obj("input") ?: json.obj("arguments"),
                )
                else -> Slot.Other(Messages.block(json, index))
            }
        }
    }

    fun clear() {
        slots.clear()
        active = false
    }

    /** Returns true when the visible content changed. */
    fun apply(event: JsonObject): Boolean {
        val index = event.int("contentIndex") ?: return false
        if (!active) start(null)
        while (slots.size <= index) slots += null
        val delta = event.str("delta").orEmpty()
        when (event.type) {
            "text_start" -> if (slots[index] !is Slot.Text) slots[index] = Slot.Text(StringBuilder())
            "text_delta" -> textSlot(index).text.append(delta)
            "text_end" -> event.str("content")?.let { slots[index] = Slot.Text(StringBuilder(it)) }
            "thinking_start" -> if (slots[index] !is Slot.Thinking) slots[index] = Slot.Thinking(StringBuilder())
            "thinking_delta" -> thinkingSlot(index).text.append(delta)
            "thinking_end" -> event.str("content")?.let { slots[index] = Slot.Thinking(StringBuilder(it)) }
            "toolcall_start" -> slots[index] = Slot.Tool(
                event.str("id").orEmpty(),
                event.str("toolName").orEmpty(),
                StringBuilder(),
                null,
            )
            "toolcall_delta" -> (slots[index] as? Slot.Tool)?.raw?.append(delta)
            "toolcall_end" -> event.obj("toolCall")?.let { call ->
                slots[index] = Slot.Tool(
                    call.str("id").orEmpty(),
                    call.str("name").orEmpty(),
                    StringBuilder(),
                    call.obj("arguments"),
                )
            }
            else -> return false
        }
        return true
    }

    private fun textSlot(index: Int): Slot.Text =
        slots[index] as? Slot.Text ?: Slot.Text(StringBuilder()).also { slots[index] = it }

    private fun thinkingSlot(index: Int): Slot.Thinking =
        slots[index] as? Slot.Thinking ?: Slot.Thinking(StringBuilder()).also { slots[index] = it }

    fun blocks(): List<Block> = slots.mapIndexedNotNull { index, slot ->
        when (slot) {
            is Slot.Text -> Block.Text(slot.text.toString())
            is Slot.Thinking -> Block.Thinking(slot.text.toString(), deferred = false, blockIndex = index)
            is Slot.Tool -> Block.ToolCall(slot.id, slot.name, slot.input, slot.raw.toString())
            is Slot.Other -> slot.block
            null -> null
        }
    }
}
