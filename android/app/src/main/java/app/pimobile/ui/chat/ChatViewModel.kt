package app.pimobile.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pimobile.data.ApiException
import app.pimobile.data.AttachedImage
import app.pimobile.data.Block
import app.pimobile.data.ChatItem
import app.pimobile.data.ImageAttachments
import app.pimobile.data.ImagePayload
import app.pimobile.data.LoadedMessage
import app.pimobile.data.Messages
import app.pimobile.data.PiApi
import app.pimobile.data.SlashDisplay
import app.pimobile.data.StreamingAssembler
import app.pimobile.data.ToolResult
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.bool
import app.pimobile.data.double
import app.pimobile.data.int
import app.pimobile.data.long
import app.pimobile.data.obj
import app.pimobile.data.str
import app.pimobile.data.strings
import app.pimobile.data.type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException

enum class LinkState { Idle, Connecting, Live, Reconnecting }

data class ModelRef(val provider: String, val modelId: String) {
    val key: String get() = "$provider:$modelId"
}

data class ModelOption(
    val provider: String,
    val id: String,
    val name: String,
    /** Input modalities from /api/models, e.g. ["text", "image"]; null when unknown. */
    val input: List<String>? = null,
) {
    val key: String get() = "$provider:$id"
}

data class LiveTool(val id: String, val name: String, val output: String)

/** Server-computed diff for an edit that has no result yet (POST /api/edit-preview). */
sealed interface EditPreview {
    data object Loading : EditPreview
    data class Ready(val patch: String?) : EditPreview
    data class Failed(val message: String) : EditPreview
}

data class ExtensionDialog(
    val id: String,
    val method: String,
    val title: String,
    val message: String?,
    val options: List<String>,
    val prefill: String?,
    val placeholder: String?,
    val expiresAt: Long? = null,
)

data class SessionStats(
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val totalTokens: Long,
    val cost: Double,
    val activeMs: Long,
) {
    /** Cache reads over all input-class tokens, as pi-web computes it. */
    val cacheHitRate: Double?
        get() {
            val denominator = cacheRead + cacheWrite + input
            return if (cacheRead + cacheWrite > 0 && denominator > 0) cacheRead * 100.0 / denominator else null
        }
}

data class ChatUiState(
    val sessionId: String?,
    val cwd: String,
    val title: String = "",
    val loading: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val toolResults: Map<String, ToolResult> = emptyMap(),
    val streaming: ChatItem.Assistant? = null,
    val liveTools: Map<String, LiveTool> = emptyMap(),
    val running: Boolean = false,
    val status: String? = null,
    val link: LinkState = LinkState.Idle,
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    val model: ModelRef? = null,
    val thinkingLevel: String? = null,
    val models: List<ModelOption> = emptyList(),
    val thinkingLevels: Map<String, List<String>> = emptyMap(),
    val contextPercent: Double? = null,
    val contextTokens: Long? = null,
    val contextWindow: Long? = null,
    val stats: SessionStats? = null,
    val fullThinking: Map<String, String> = emptyMap(),
    val editPreviews: Map<String, EditPreview> = emptyMap(),
    val queued: List<String> = emptyList(),
    val dialog: ExtensionDialog? = null,
    val error: String? = null,
    val notice: String? = null,
    val restoredDraft: String? = null,
    val attachments: List<AttachedImage> = emptyList(),
    /** Picked images still being read and compressed. */
    val pendingImages: Int = 0,
    /** Extension, prompt and skill commands from `get_commands`; built-ins are local. */
    val slashCommands: List<SlashCommand> = emptyList(),
    val slashCommandsLoading: Boolean = false,
    /** Skill name -> disable-model-invocation, from /api/skills. */
    val skillDormancy: Map<String, Boolean> = emptyMap(),
    /** A built-in slash command is in flight; the composer is disabled meanwhile. */
    val commandPending: Boolean = false,
) {
    /** Web: modelSupportsImageInput — unknown modality info never warns. */
    val modelSupportsImages: Boolean
        get() {
            val ref = model ?: return true
            val input = models.firstOrNull { it.provider == ref.provider && it.id == ref.modelId }?.input ?: return true
            return "image" in input
        }
}

private val DEFAULT_TOOLS = listOf("read", "bash", "edit", "write")
private const val STATE_POLL_MS = 15_000L
private const val IDLE_CLOSE_MS = 30_000L
private const val LEASE_RENEW_MS = 30_000L

/**
 * One chat session. Mirrors the web client's rules (hooks/useAgentSession.ts):
 * SSE is opened before a prompt and kept for a grace window afterwards; a run
 * ends on `prompt_done`/`agent_settled`, never on the first `agent_end`; state
 * polling and a refetch after every reconnect cover events lost while offline.
 */
class ChatViewModel(
    private val api: PiApi,
    sessionId: String?,
    cwd: String,
    /** Lets the app start its background run watcher (completion notifications). */
    private val onRunActive: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(sessionId = sessionId, cwd = cwd, loading = sessionId != null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var messages: List<LoadedMessage> = emptyList()
    private var oldestEntryId: String? = null
    private val sessionMutex = Mutex()
    private var localCounter = 0

    private val assembler = StreamingAssembler()
    private var streamDirty = false

    private var eventsJob: Job? = null
    private var streamJob: Job? = null
    private var idleCloseJob: Job? = null
    private var pollJob: Job? = null
    private val connected = MutableStateFlow(false)
    private var everConnected = false
    private var reconnectAttempt = 0

    private var promptPending = false
    private var sdkActive = false
    private var lastSendAt = 0L
    private var foregroundSeen = false

    init {
        viewModelScope.launch { streamFlushLoop() }
        viewModelScope.launch { loadModels() }
        if (sessionId != null) {
            viewModelScope.launch {
                refreshSession(sessionId)
                reconcile(sessionId)
            }
        }
    }

    // region public actions

    fun send(text: String) {
        val message = text.trim()
        val images = _state.value.attachments
        if (message.isEmpty() && images.isEmpty()) return
        _state.update { it.copy(attachments = emptyList()) }
        viewModelScope.launch {
            val steering = _state.value.running
            val optimistic = LoadedMessage(
                key = "pending-${localCounter++}",
                entryId = null,
                json = buildJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", message)
                    } else {
                        putJsonArray("content") {
                            if (message.isNotEmpty()) addJsonObject { put("type", "text"); put("text", message) }
                            images.forEach { addJsonObject { putImage(it.payload) } }
                        }
                    }
                },
                pending = true,
            )
            if (!steering) {
                messages = messages + optimistic
                publishMessages()
                if (_state.value.title.isBlank() && message.isNotEmpty()) {
                    _state.update { it.copy(title = message.lineSequence().first().take(80)) }
                }
                promptPending = true
                setRunning(if (images.isEmpty() && message.startsWith("/")) "Running command…" else "Starting…")
            }
            lastSendAt = SystemClock.elapsedRealtime()
            var sessionId = _state.value.sessionId
            try {
                if (sessionId == null) sessionId = ensureSession()
                ensureEvents(sessionId)
                withTimeoutOrNull(60_000) { connected.first { it } }
                    ?: throw IOException("Could not open the event stream")
                api.command(sessionId, buildJsonObject {
                    put("type", "prompt")
                    put("message", message)
                    if (images.isNotEmpty()) {
                        putJsonArray("images") { images.forEach { addJsonObject { putImage(it.payload) } } }
                    }
                    if (steering) put("streamingBehavior", "steer")
                })
                if (steering) _state.update { it.copy(notice = "Steering message sent") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val rejected = (e as? ApiException)?.code == "prompt_rejected" ||
                    sessionId == null || !connected.value
                if (rejected) {
                    if (!steering) {
                        messages = messages - optimistic
                        publishMessages()
                        promptPending = false
                        if (!sdkActive) markIdle()
                    }
                    _state.update {
                        it.copy(
                            error = e.message ?: "Message not sent",
                            restoredDraft = message.ifEmpty { null },
                            attachments = (images + it.attachments).take(ImageAttachments.MAX_IMAGES),
                        )
                    }
                } else {
                    // Ambiguous failure: the prompt may be running. Ask the server.
                    _state.update { it.copy(error = "Connection problem: ${e.message}. Checking…") }
                    reconcile(sessionId)
                }
            }
        }
    }

    /** Reads picked images off the main thread; extras past the per-message limit are dropped, as on the web. */
    fun addImages(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _state.value
        val room = (ImageAttachments.MAX_IMAGES - current.attachments.size - current.pendingImages).coerceAtLeast(0)
        val accepted = uris.take(room)
        if (accepted.size < uris.size) {
            _state.update { it.copy(notice = "A message can include at most ${ImageAttachments.MAX_IMAGES} images") }
        }
        if (accepted.isEmpty()) return
        _state.update { it.copy(pendingImages = it.pendingImages + accepted.size) }
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            for (uri in accepted) {
                val id = localCounter++.toLong()
                val image = try {
                    withContext(Dispatchers.IO) { ImageAttachments.load(resolver, uri, id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += e.message ?: "Could not read the image"
                    null
                }
                _state.update { state ->
                    val added = image?.takeIf { state.attachments.size < ImageAttachments.MAX_IMAGES }
                    state.copy(
                        attachments = if (added != null) state.attachments + added else state.attachments,
                        pendingImages = (state.pendingImages - 1).coerceAtLeast(0),
                    )
                }
            }
            failures.distinct().firstOrNull()?.let { message -> _state.update { it.copy(error = message) } }
        }
    }

    fun removeImage(id: Long) = _state.update { state ->
        state.copy(attachments = state.attachments.filterNot { it.id == id })
    }

    fun abort() {
        val id = _state.value.sessionId ?: return
        _state.update { it.copy(status = "Stopping…") }
        viewModelScope.launch {
            runCatchingApi { api.command(id, buildJsonObject { put("type", "abort") }) }
        }
    }

    fun refresh() {
        val id = _state.value.sessionId ?: return
        viewModelScope.launch {
            refreshSession(id)
            reconcile(id)
        }
    }

    fun onForeground() {
        if (!foregroundSeen) {
            foregroundSeen = true
            return
        }
        refresh()
    }

    fun loadEarlier() {
        val id = _state.value.sessionId ?: return
        val before = oldestEntryId ?: return
        if (_state.value.loadingEarlier) return
        _state.update { it.copy(loadingEarlier = true) }
        viewModelScope.launch {
            runCatchingApi {
                val body = api.get(
                    "/api/sessions/${PiApi.encode(id)}/context?before=${PiApi.encode(before)}&deferThinking=1&deferMedia=1",
                ).asObj()
                val context = body?.obj("context")
                if (context != null) {
                    messages = parseContext(context) + messages
                    oldestEntryId = context.str("oldestEntryId") ?: oldestEntryId
                    _state.update { it.copy(hasMore = context.bool("hasMore") == true) }
                    publishMessages()
                }
            }
            _state.update { it.copy(loadingEarlier = false) }
        }
    }

    fun loadFullThinking(entryId: String, blockIndex: Int) {
        val id = _state.value.sessionId ?: return
        val key = "$entryId:$blockIndex"
        if (key in _state.value.fullThinking) return
        viewModelScope.launch {
            runCatchingApi {
                val body = api.get(
                    "/api/sessions/${PiApi.encode(id)}/entries/${PiApi.encode(entryId)}/thinking?blockIndex=$blockIndex",
                ).asObj()
                body?.str("thinking")?.let { text ->
                    _state.update { it.copy(fullThinking = it.fullThinking + (key to text)) }
                }
            }
        }
    }

    fun loadEditPreview(call: Block.ToolCall) {
        val existing = _state.value.editPreviews[call.id]
        if (existing is EditPreview.Loading || existing is EditPreview.Ready) return
        val input = call.input ?: return
        _state.update { it.copy(editPreviews = it.editPreviews + (call.id to EditPreview.Loading)) }
        viewModelScope.launch {
            val preview = try {
                val body = api.post("/api/edit-preview", buildJsonObject {
                    put("toolName", call.name)
                    put("cwd", _state.value.cwd)
                    put("input", input)
                }).asObj()
                EditPreview.Ready(body?.str("patch")?.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not cached as final: expanding again retries, like pi-web.
                EditPreview.Failed(e.message ?: "Preview unavailable")
            }
            _state.update { it.copy(editPreviews = it.editPreviews + (call.id to preview)) }
        }
    }

    fun selectModel(option: ModelOption) {
        val id = _state.value.sessionId
        _state.update { it.copy(model = ModelRef(option.provider, option.id)) }
        if (id == null) return
        viewModelScope.launch {
            runCatchingApi {
                api.command(id, buildJsonObject {
                    put("type", "set_model")
                    put("provider", option.provider)
                    put("modelId", option.id)
                })
            }
            refreshSession(id)
        }
    }

    fun selectThinking(level: String) {
        val id = _state.value.sessionId
        _state.update { it.copy(thinkingLevel = level) }
        if (id == null) return
        viewModelScope.launch {
            runCatchingApi {
                api.command(id, buildJsonObject {
                    put("type", "set_thinking_level")
                    put("level", level)
                })
            }
        }
    }

    fun respondDialog(value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val dialog = _state.value.dialog ?: return
        val id = _state.value.sessionId ?: return
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            runCatchingApi {
                api.command(id, buildJsonObject {
                    put("type", "extension_ui_response")
                    put("id", dialog.id)
                    when {
                        cancelled -> put("cancelled", true)
                        confirmed != null -> put("confirmed", confirmed)
                        else -> put("value", value.orEmpty())
                    }
                })
            }
        }
    }

    /** Web: loadSlashCommands. Commands come from the runtime, so a new chat gets an idle one (ensure_session). */
    fun loadSlashCommands() {
        _state.update { it.copy(slashCommandsLoading = true) }
        viewModelScope.launch {
            val commands = try {
                val id = ensureSession()
                api.command(id, buildJsonObject { put("type", "get_commands") })
                    .asObj()?.arr("commands").orEmpty()
                    .mapNotNull(::slashCommandOf)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList() // as on the web: the palette falls back to the built-ins
            }
            _state.update { it.copy(slashCommands = commands, slashCommandsLoading = false) }
        }
    }

    /** Refetched each time the palette opens, so skill toggles show up; failures leave skills unannotated. */
    fun loadSkillDormancy() {
        val cwd = _state.value.cwd
        if (cwd.isBlank()) return
        _state.update { it.copy(skillDormancy = emptyMap()) }
        viewModelScope.launch {
            val dormancy = try {
                api.get("/api/skills?cwd=${PiApi.encode(cwd)}").asObj()?.arr("skills").orEmpty()
                    .mapNotNull { element ->
                        val skill = element as? JsonObject ?: return@mapNotNull null
                        val name = skill.str("name") ?: return@mapNotNull null
                        name to (skill.bool("disableModelInvocation") == true)
                    }
                    .toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            _state.update { it.copy(skillDormancy = dormancy) }
        }
    }

    /**
     * Web: handleBuiltinSlashCommand. Returns false when [text] is not a built-in
     * this client runs, so the caller sends it as a prompt (extension, prompt and
     * skill commands run on the server). [onSettled] reports whether it succeeded.
     */
    fun runBuiltinCommand(text: String, onSettled: (succeeded: Boolean) -> Unit): Boolean {
        val current = _state.value
        val command = builtinSlashCommand(text) ?: return false
        // Images make it a prompt; while running, only streaming-safe built-ins run here.
        if (current.attachments.isNotEmpty() || (current.running && !command.availableWhileStreaming)) return false
        if (current.commandPending) return true
        val args = text.trim().substringAfter("/${command.name}").trim()
        _state.update { it.copy(commandPending = true) }
        viewModelScope.launch {
            var message: String? = null
            var failure: String? = null
            try {
                val id = ensureSession()
                message = when (command.name) {
                    "name" -> nameSession(id, args)
                    else -> throw IllegalStateException("/${command.name} is not supported")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            _state.update { it.copy(commandPending = false, error = failure ?: it.error, notice = message ?: it.notice) }
            onSettled(failure == null)
        }
        return true
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }
    fun consumeRestoredDraft() = _state.update { it.copy(restoredDraft = null) }

    // endregion

    // region slash commands

    private suspend fun nameSession(id: String, name: String): String {
        if (name.isEmpty()) throw IllegalArgumentException("Usage: /name <name>")
        api.command(id, buildJsonObject {
            put("type", "set_session_name")
            put("name", name)
        })
        refreshSession(id)
        return "Session renamed to $name"
    }

    private fun slashCommandOf(element: JsonElement): SlashCommand? {
        val json = element as? JsonObject ?: return null
        val name = json.str("name") ?: return null
        val source = when (json.str("source")) {
            "extension" -> SlashSource.Extension
            "prompt" -> SlashSource.Prompt
            "skill" -> SlashSource.Skill
            else -> return null
        }
        return SlashCommand(name, json.str("description").orEmpty(), source)
    }

    // endregion

    // region loading

    /** The palette and a send can both need a runtime for a new chat: create it once. */
    private suspend fun ensureSession(): String = sessionMutex.withLock {
        _state.value.sessionId ?: createSession()
    }

    private suspend fun createSession(): String {
        val current = _state.value
        val body = api.post("/api/agent/new", buildJsonObject {
            put("cwd", current.cwd)
            put("type", "ensure_session")
            putJsonArray("toolNames") { DEFAULT_TOOLS.forEach { add(it) } }
            current.model?.let {
                put("provider", it.provider)
                put("modelId", it.modelId)
            }
            current.thinkingLevel?.let { put("thinkingLevel", it) }
        }).asObj()
        val id = body?.str("sessionId") ?: throw IOException("Server did not return a session id")
        _state.update { state ->
            state.copy(
                sessionId = id,
                model = body.obj("model")?.let { modelRef(it, "modelId") } ?: state.model,
                thinkingLevel = body.str("thinkingLevel") ?: state.thinkingLevel,
            )
        }
        return id
    }

    private suspend fun refreshSession(id: String) {
        try {
            val body = api.get("/api/sessions/${PiApi.encode(id)}?deferThinking=1&deferMedia=1").asObj() ?: return
            val context = body.obj("context")
            val info = body.obj("info")
            val fresh = context?.let(::parseContext).orEmpty()
            // Keep optimistic bubbles the server has not recorded yet.
            val lastServerUser = fresh.lastOrNull { it.json.str("role") == "user" }
                ?.let { Messages.userText(it.json["content"]) }
            val stillPending = messages.filter {
                it.pending && promptPending && Messages.userText(it.json["content"]) != lastServerUser
            }
            messages = fresh + stillPending
            oldestEntryId = context?.str("oldestEntryId")
            _state.update { state ->
                state.copy(
                    loading = false,
                    hasMore = context?.bool("hasMore") == true,
                    cwd = info?.str("cwd") ?: state.cwd,
                    title = info?.let { titleOf(it) } ?: state.title,
                    model = context?.obj("model")?.let { modelRef(it, "modelId") } ?: state.model,
                    thinkingLevel = context?.str("thinkingLevel") ?: state.thinkingLevel,
                    stats = parseStats(body) ?: state.stats,
                )
            }
            publishMessages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    private fun parseStats(body: JsonObject): SessionStats? {
        val stats = body.obj("stats") ?: return null
        val tokens = stats.obj("tokens")
        return SessionStats(
            userMessages = stats.int("userMessages") ?: 0,
            assistantMessages = stats.int("assistantMessages") ?: 0,
            toolCalls = stats.int("toolCalls") ?: 0,
            input = tokens?.long("input") ?: 0,
            output = tokens?.long("output") ?: 0,
            cacheRead = tokens?.long("cacheRead") ?: 0,
            cacheWrite = tokens?.long("cacheWrite") ?: 0,
            totalTokens = tokens?.long("total") ?: 0,
            cost = stats.double("cost") ?: 0.0,
            activeMs = body.long("totalActiveMs") ?: 0,
        )
    }

    private fun parseContext(context: JsonObject): List<LoadedMessage> {
        val entryIds = context.arr("entryIds").strings()
        return context.arr("messages").orEmpty().mapIndexedNotNull { index, element ->
            val json = element as? JsonObject ?: return@mapIndexedNotNull null
            val entryId = entryIds.getOrNull(index)
            LoadedMessage(entryId ?: "m-${localCounter++}", entryId, json)
        }
    }

    private suspend fun loadModels() {
        val cwd = _state.value.cwd
        val path = if (cwd.isBlank()) "/api/models" else "/api/models?cwd=${PiApi.encode(cwd)}"
        runCatchingApi {
            val body = api.get(path).asObj() ?: return@runCatchingApi
            val options = body.arr("modelList").orEmpty().mapNotNull { element ->
                val json = element as? JsonObject ?: return@mapNotNull null
                val provider = json.str("provider") ?: return@mapNotNull null
                val id = json.str("id") ?: return@mapNotNull null
                ModelOption(provider, id, json.str("name") ?: id, json.arr("input")?.strings())
            }
            val levels = body.obj("thinkingLevels")?.mapValues { (_, value) ->
                (value as? kotlinx.serialization.json.JsonArray).strings()
            }.orEmpty()
            val default = body.obj("defaultModel")?.let { modelRef(it, "modelId") }
            _state.update { it.copy(models = options, thinkingLevels = levels, model = it.model ?: default) }
        }
    }

    // endregion

    // region run state

    /** GET /api/agent/[id]: authoritative "is anything running" check. */
    private suspend fun reconcile(id: String) {
        val body = try {
            api.get("/api/agent/${PiApi.encode(id)}").asObj() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val state = body.obj("state")
        if (state != null) {
            _state.update { current ->
                current.copy(
                    contextPercent = state.obj("contextUsage")?.double("percent") ?: current.contextPercent,
                    contextTokens = state.obj("contextUsage")?.long("tokens") ?: current.contextTokens,
                    contextWindow = state.obj("contextUsage")?.long("contextWindow") ?: current.contextWindow,
                    thinkingLevel = state.str("thinkingLevel") ?: current.thinkingLevel,
                    model = state.obj("model")?.let { modelRef(it, "id") } ?: current.model,
                    queued = state.obj("queuedMessages")?.let {
                        it.arr("steering").strings() + it.arr("followUp").strings()
                    } ?: current.queued,
                )
            }
        }
        val active = body.bool("running") == true && state != null &&
            (state.bool("isStreaming") == true || state.bool("isPromptRunning") == true ||
                state.bool("isCompacting") == true || state.bool("isBashRunning") == true)
        when {
            active -> {
                if (!_state.value.running) setRunning("Working…")
                ensureEvents(id)
            }
            _state.value.running && !recentlySent() -> settle(id)
        }
    }

    private fun recentlySent() = SystemClock.elapsedRealtime() - lastSendAt < 5_000

    private fun setRunning(status: String?) {
        idleCloseJob?.cancel()
        onRunActive()
        _state.update { it.copy(running = true, status = status) }
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    delay(STATE_POLL_MS)
                    val id = _state.value.sessionId ?: continue
                    if (!_state.value.running) break
                    reconcile(id)
                }
            }
        }
    }

    private fun markIdle() {
        _state.update { it.copy(running = false, status = null, liveTools = emptyMap()) }
        pollJob?.cancel()
        scheduleIdleClose()
    }

    /** The run is over: drop transient state and reload the authoritative history. */
    private fun settle(id: String) {
        promptPending = false
        sdkActive = false
        assembler.clear()
        streamDirty = false
        _state.update { it.copy(streaming = null) }
        markIdle()
        viewModelScope.launch { refreshSession(id) }
    }

    private fun setStatus(status: String?) = _state.update { if (it.running) it.copy(status = status) else it }

    // endregion

    // region events

    private fun ensureEvents(id: String) {
        idleCloseJob?.cancel()
        if (eventsJob?.isActive == true) return
        eventsJob = viewModelScope.launch { eventLoop(id) }
    }

    private fun scheduleIdleClose() {
        idleCloseJob?.cancel()
        idleCloseJob = viewModelScope.launch {
            delay(IDLE_CLOSE_MS)
            if (!_state.value.running) {
                eventsJob?.cancel()
                eventsJob = null
            }
        }
    }

    private suspend fun eventLoop(id: String) = coroutineScope {
        val lease = launch {
            while (isActive) {
                delay(LEASE_RENEW_MS)
                if (!connected.value) continue
                try {
                    val body = api.post("/api/agent/${PiApi.encode(id)}/lease", JsonObject(emptyMap())).asObj()
                    if (body?.int("renewed") == 0) streamJob?.cancel() // server lost our stream: reconnect
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        try {
            while (isActive) {
                _state.update { it.copy(link = if (everConnected) LinkState.Reconnecting else LinkState.Connecting) }
                var fatal: String? = null
                streamJob = launch {
                    try {
                        api.events(id).collect { event ->
                            if (event.type == "startup_error") fatal = event.str("errorMessage") ?: "Agent failed to start"
                            onEvent(id, event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.status == 401 || e.status == 403 || e.status == 404) fatal = e.message
                    } catch (_: Exception) {
                        // network drop or half-open connection: retry below
                    }
                }
                streamJob?.join()
                connected.value = false
                fatal?.let { message ->
                    _state.update { it.copy(error = message) }
                    if (_state.value.running) settle(id)
                    return@coroutineScope
                }
                reconnectAttempt++
                _state.update { it.copy(link = LinkState.Reconnecting) }
                delay(minOf(1_000L * reconnectAttempt, 8_000L))
            }
        } finally {
            lease.cancel()
            connected.value = false
            _state.update { it.copy(link = LinkState.Idle) }
        }
    }

    private fun onEvent(id: String, event: JsonObject) {
        when (event.type) {
            "connected" -> {
                val reconnect = everConnected
                everConnected = true
                reconnectAttempt = 0
                connected.value = true
                _state.update { it.copy(link = LinkState.Live) }
                assembler.clear()
                publishStreaming()
                if (event.bool("isStreaming") == true) {
                    sdkActive = true
                    setRunning("Working…")
                }
                if (reconnect) {
                    // Events were lost while disconnected: reload history and re-check state.
                    viewModelScope.launch {
                        refreshSession(id)
                        reconcile(id)
                    }
                }
            }
            "agent_start" -> {
                sdkActive = true
                assembler.clear()
                publishStreaming()
                setRunning("Waiting for model…")
            }
            "message_start" -> {
                val message = event.obj("message") ?: return
                if (message.str("role") == "assistant") {
                    assembler.start(message)
                    streamDirty = true
                    setStatus("Responding…")
                }
            }
            "message_update" -> {
                val delta = event.obj("assistantMessageEvent") ?: return
                if (assembler.apply(delta)) {
                    streamDirty = true
                    val kind = delta.type.orEmpty()
                    setStatus(
                        when {
                            kind.startsWith("thinking") -> "Thinking…"
                            kind.startsWith("toolcall") -> "Preparing tool call…"
                            else -> "Responding…"
                        },
                    )
                }
            }
            "message_end" -> onMessageEnd(event.obj("message"))
            "tool_execution_start" -> {
                val toolId = event.str("toolCallId") ?: return
                val name = event.str("toolName").orEmpty()
                _state.update { it.copy(liveTools = it.liveTools + (toolId to LiveTool(toolId, name, ""))) }
                setStatus("Running $name…")
            }
            "tool_execution_update" -> {
                val toolId = event.str("toolCallId") ?: return
                val output = Messages.contentText(event.obj("partialResult")?.get("content"))
                _state.update { state ->
                    val tool = state.liveTools[toolId] ?: LiveTool(toolId, event.str("toolName").orEmpty(), "")
                    state.copy(liveTools = state.liveTools + (toolId to tool.copy(output = output)))
                }
            }
            "tool_execution_end" -> {
                val toolId = event.str("toolCallId") ?: return
                _state.update { it.copy(liveTools = it.liveTools - toolId) }
                if (_state.value.liveTools.isEmpty()) setStatus("Waiting for model…")
            }
            "agent_end" -> viewModelScope.launch { refreshSession(id) } // not final: retries/queues may follow
            "agent_settled" -> {
                sdkActive = false
                if (!promptPending) settle(id)
            }
            "prompt_done" -> {
                promptPending = false
                if (!sdkActive) settle(id) else viewModelScope.launch { refreshSession(id) }
            }
            "prompt_error" -> _state.update { it.copy(error = event.str("errorMessage") ?: "Prompt failed") }
            "extension_error" -> _state.update { it.copy(error = "Extension error: ${event.str("error")}") }
            "queue_update" -> _state.update {
                it.copy(queued = event.arr("steering").strings() + event.arr("followUp").strings())
            }
            "auto_retry_start" -> setStatus(
                "Retrying (${event.int("attempt") ?: "?"}/${event.int("maxAttempts") ?: "?"}): ${event.str("errorMessage").orEmpty()}",
            )
            "auto_retry_end" -> setStatus("Waiting for model…")
            "compaction_start", "auto_compaction_start" -> setStatus("Compacting context…")
            "compaction_end", "auto_compaction_end" -> {
                val error = event.str("errorMessage")
                if (error != null) _state.update { it.copy(error = "Compaction failed: $error") }
                else if (event.bool("aborted") != true) viewModelScope.launch { refreshSession(id) }
                setStatus("Waiting for model…")
            }
            "extension_ui_request" -> onExtensionRequest(event)
            "extension_ui_closed" -> _state.update {
                if (it.dialog?.id == event.str("id")) it.copy(dialog = null) else it
            }
            "session_info_changed" -> event.str("name")?.let { name -> _state.update { it.copy(title = name) } }
            "thinking_level_changed" -> event.str("level")?.let { level -> _state.update { it.copy(thinkingLevel = level) } }
            "startup_error" -> Unit // handled by the event loop
        }
    }

    private fun onMessageEnd(message: JsonObject?) {
        message ?: return
        val loaded = LoadedMessage("live-${localCounter++}", null, message)
        when (message.str("role")) {
            "user" -> {
                val text = Messages.userText(message["content"])
                val index = messages.indexOfFirst { it.pending && Messages.userText(it.json["content"]) == text }
                messages = if (index >= 0) messages.toMutableList().also { it[index] = loaded } else messages + loaded
            }
            "assistant" -> {
                assembler.clear()
                streamDirty = false
                messages = messages + loaded
                _state.update { it.copy(streaming = null) }
                setStatus("Waiting for model…")
            }
            else -> messages = messages + loaded
        }
        publishMessages()
    }

    private fun onExtensionRequest(event: JsonObject) {
        val id = event.str("id") ?: return
        when (val method = event.str("method")) {
            "select", "confirm", "input", "editor" -> _state.update {
                it.copy(
                    dialog = ExtensionDialog(
                        id = id,
                        method = method,
                        title = event.str("title").orEmpty(),
                        message = event.str("message"),
                        options = event.arr("options").strings(),
                        prefill = event.str("prefill"),
                        placeholder = event.str("placeholder"),
                        expiresAt = event.long("expiresAt"),
                    ),
                )
            }
            "notify" -> _state.update { it.copy(notice = event.str("message")) }
            "setTitle" -> event.str("title")?.let { title -> _state.update { it.copy(title = title) } }
            else -> Unit // status/widget/custom panels: not in this client yet
        }
    }

    // endregion

    // region publishing

    private suspend fun streamFlushLoop() {
        while (true) {
            delay(50) // coalesce token deltas into ~20 UI updates per second
            if (streamDirty) {
                streamDirty = false
                publishStreaming()
            }
        }
    }

    private fun publishStreaming() {
        val streaming = if (assembler.active) {
            ChatItem.Assistant("streaming", null, assembler.blocks(), null, null, streaming = true)
        } else null
        _state.update { it.copy(streaming = streaming) }
    }

    private fun publishMessages() {
        val items = messages.mapNotNull(Messages::item)
        val results = Messages.toolResults(messages)
        _state.update { it.copy(items = items, toolResults = results) }
    }

    // endregion

    private suspend fun runCatchingApi(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    private fun modelRef(json: JsonObject, idKey: String): ModelRef? {
        val provider = json.str("provider") ?: return null
        val id = json.str(idKey) ?: return null
        return ModelRef(provider, id)
    }

    private fun JsonObjectBuilder.putImage(image: ImagePayload) {
        put("type", "image")
        put("data", image.data)
        put("mimeType", image.mimeType)
    }

    private fun titleOf(info: JsonObject): String =
        info.str("name")?.takeIf { it.isNotBlank() }
            ?: info.str("firstMessage")?.takeUnless { it == "(no messages)" }?.let(SlashDisplay::display)
            ?: "New session"
}
