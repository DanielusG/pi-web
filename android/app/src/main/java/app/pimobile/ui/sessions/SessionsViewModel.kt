package app.pimobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pimobile.data.PiApi
import app.pimobile.data.SlashDisplay
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.int
import app.pimobile.data.long
import app.pimobile.data.obj
import app.pimobile.data.str
import app.pimobile.data.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

data class SessionRow(
    val id: String,
    val title: String,
    val name: String?,
    val cwd: String,
    val modified: Long,
    val messageCount: Int,
    val branch: String?,
)

data class ProjectGroup(val root: String, val sessions: List<SessionRow>)

data class SessionsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val groups: List<ProjectGroup> = emptyList(),
    val running: Set<String> = emptySet(),
    val waiting: Set<String> = emptySet(),
    val expanded: Set<String> = emptySet(),
    val recentCwds: List<String> = emptyList(),
    val error: String? = null,
)

class SessionsViewModel(
    private val api: PiApi,
    private val onRunActive: () -> Unit = {},
    private val waitingSessionIds: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var listVersion: Long? = null

    init {
        // Waiting state is owned by RunWatcherService (its per-session SSE streams).
        viewModelScope.launch { waitingSessionIds.collect { waiting -> _state.update { it.copy(waiting = waiting) } } }
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch { load(force) }
    }

    fun toggleProject(root: String) = _state.update {
        it.copy(expanded = if (root in it.expanded) it.expanded - root else it.expanded + root)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Runs while the screen is visible: cheap running-state poll, full reload on version change. */
    suspend fun watch() {
        load(force = false)
        while (true) {
            delay(3_000)
            try {
                val body = api.get("/api/agent/running").asObj() ?: continue
                val running = body.arr("runningSessionIds").strings().toSet()
                _state.update { it.copy(running = running) }
                // Runs started elsewhere (e.g. pi-web in a browser) get notifications too.
                if (running.isNotEmpty()) onRunActive()
                val version = body.long("sessionListVersion")
                if (version != null && version != listVersion) load(force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // transient; the next tick retries
            }
        }
    }

    suspend fun validateCwd(raw: String): String? = try {
        val body = api.post("/api/cwd/validate", buildJsonObject { put("cwd", raw.trim()) }).asObj()
        body?.str("cwd") ?: raw.trim()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(error = e.message ?: "Invalid directory") }
        null
    }

    /** PATCH /api/sessions/[id]; returns an error message, or null on success. */
    suspend fun rename(id: String, name: String): String? = try {
        api.patch("/api/sessions/${PiApi.encode(id)}", buildJsonObject { put("name", name) })
        load(force = false)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Rename failed"
    }

    /** DELETE /api/sessions/[id]; returns an error message, or null on success. */
    suspend fun delete(id: String): String? = try {
        api.delete("/api/sessions/${PiApi.encode(id)}")
        load(force = false)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Delete failed"
    }

    private suspend fun load(force: Boolean) {
        _state.update { it.copy(refreshing = force && !it.loading) }
        try {
            val body = api.get("/api/sessions" + if (force) "?force=1" else "").asObj()
                ?: throw IllegalStateException("Empty response from /api/sessions")
            listVersion = body.long("sessionListVersion")
            val rows = body.arr("sessions").orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.obj("relation")?.str("kind") != "subagent" }
                .map { json -> (json.str("projectRoot") ?: json.str("cwd").orEmpty()) to toRow(json) }
            val groups = rows
                .groupBy({ it.first }, { it.second })
                .map { (root, sessions) -> ProjectGroup(root, sessions.sortedByDescending { it.modified }) }
                .sortedByDescending { group -> group.sessions.maxOf { it.modified } }
            val recent = rows.map { it.second }.sortedByDescending { it.modified }.map { it.cwd }.distinct().take(12)
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    groups = groups,
                    recentCwds = recent,
                    running = body.arr("runningSessionIds").strings().toSet(),
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: e.toString()) }
        }
    }

    private fun toRow(json: JsonObject): SessionRow {
        val first = json.str("firstMessage")?.takeUnless { it == "(no messages)" }?.let(SlashDisplay::display)
        return SessionRow(
            id = json.str("id").orEmpty(),
            title = json.str("name")?.takeIf { it.isNotBlank() } ?: first ?: "New session",
            name = json.str("name")?.takeIf { it.isNotBlank() },
            cwd = json.str("cwd").orEmpty(),
            modified = json.str("modified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            messageCount = json.int("messageCount") ?: 0,
            branch = json.str("branch"),
        )
    }
}
