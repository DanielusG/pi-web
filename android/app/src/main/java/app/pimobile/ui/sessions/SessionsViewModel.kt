package app.pimobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pimobile.data.PiApi
import app.pimobile.data.SlashDisplay
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.int
import app.pimobile.data.long
import app.pimobile.data.str
import app.pimobile.data.strings
import app.pimobile.notify.RunSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * One project of the paged list: [sessions] holds the pages loaded so far, newest first,
 * out of [total] top-level sessions on the server.
 */
data class ProjectGroup(val key: String, val root: String, val total: Int, val sessions: List<SessionRow>)

data class SessionsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val groups: List<ProjectGroup> = emptyList(),
    val running: Set<String> = emptySet(),
    val waiting: Set<String> = emptySet(),
    /** Rows shown per project key, when more than [SessionPages.FIRST_PAGE]. */
    val shown: Map<String, Int> = emptyMap(),
    /** Projects whose next page is being fetched. */
    val loadingMore: Set<String> = emptySet(),
    val recentCwds: List<String> = emptyList(),
    val error: String? = null,
    /** The server answered with the full list: it predates the paged one this app needs. */
    val outdated: Boolean = false,
)

class SessionsViewModel(
    private val api: PiApi,
    private val onRunActive: () -> Unit = {},
    /** [app.pimobile.notify.RunStatus.snapshot]: collected only while the list is visible. */
    private val runStatus: StateFlow<RunSnapshot?> = MutableStateFlow(null),
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var listVersion: Long? = null

    /** Reloads and page fetches both rewrite [SessionsUiState.groups]: one at a time. */
    private val lists = Mutex()

    fun refresh(force: Boolean) {
        viewModelScope.launch { load(force) }
    }

    /** The next [SessionPages.MORE_PAGE] sessions of a project, fetched when not loaded yet. */
    fun showMore(key: String) {
        viewModelScope.launch { lists.withLock { more(key) } }
    }

    fun showLess(key: String) = _state.update { it.copy(shown = it.shown - key) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Runs while the screen is visible: live run state from the server's stream, full reload on version change. */
    suspend fun watch() {
        load(force = false)
        runStatus.filterNotNull().collect { snapshot ->
            _state.update { it.copy(running = snapshot.running, waiting = snapshot.waiting) }
            // Runs started elsewhere (e.g. pi-web in a browser) get notifications too.
            if (snapshot.running.isNotEmpty()) onRunActive()
            val version = snapshot.listVersion
            if (version != null && version != listVersion) load(force = false)
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

    private suspend fun load(force: Boolean) = lists.withLock {
        _state.update { it.copy(refreshing = force && !it.loading) }
        try {
            val body = api.get(
                "/api/sessions?perProject=${SessionPages.FIRST_PAGE}&firstMessageChars=${SessionPages.FIRST_MESSAGE_CHARS}" +
                    if (force) "&force=1" else "",
            ).asObj() ?: throw IllegalStateException("Empty response from /api/sessions")
            val pages = SessionPages.parse(body) ?: throw OutdatedServer()
            listVersion = body.long("sessionListVersion")
            // Projects opened with "Show more" keep their rows across reloads.
            val shown = _state.value.shown
            val groups = pages.map { group ->
                val wanted = minOf(shown[group.key] ?: 0, group.total)
                if (wanted <= group.sessions.size) group
                else fetchPage(group.key, group.sessions.size, wanted - group.sessions.size)
                    ?.let { SessionPages.append(group, it) } ?: group
            }
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    groups = groups,
                    // Whatever could not be refetched shrinks back to what is loaded.
                    shown = groups.mapNotNull { group ->
                        shown[group.key]?.let { group.key to minOf(it, group.sessions.size) }
                    }.toMap(),
                    recentCwds = body.arr("recentCwds").strings(),
                    running = body.arr("runningSessionIds").strings().toSet(),
                    error = null,
                    outdated = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(loading = false, refreshing = false, error = e.message ?: e.toString(), outdated = e is OutdatedServer)
            }
        }
    }

    private suspend fun more(key: String) {
        val group = _state.value.groups.firstOrNull { it.key == key } ?: return
        val wanted = minOf((_state.value.shown[key] ?: SessionPages.FIRST_PAGE) + SessionPages.MORE_PAGE, group.total)
        if (wanted > group.sessions.size) {
            _state.update { it.copy(loadingMore = it.loadingMore + key) }
            try {
                val page = fetchPage(key, group.sessions.size, wanted - group.sessions.size)
                _state.update { state ->
                    state.copy(groups = state.groups.map { if (it.key == key && page != null) SessionPages.append(it, page) else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = it.loadingMore - key, error = e.message ?: e.toString()) }
                return
            }
        }
        _state.update { state ->
            val loaded = state.groups.firstOrNull { it.key == key }?.sessions?.size ?: 0
            state.copy(shown = state.shown + (key to minOf(wanted, loaded)), loadingMore = state.loadingMore - key)
        }
    }

    /** [count] sessions of one project from [offset]; null when the project is gone. */
    private suspend fun fetchPage(key: String, offset: Int, count: Int): ProjectGroup? {
        val body = api.get(
            "/api/sessions?project=${PiApi.encode(key)}&offset=$offset&perProject=$count" +
                "&firstMessageChars=${SessionPages.FIRST_MESSAGE_CHARS}",
        ).asObj() ?: return null
        return SessionPages.parse(body)?.firstOrNull()
    }

    private class OutdatedServer : IllegalStateException("This pi-web is too old for the app: update it on the server.")
}

/**
 * The paged session list of `GET /api/sessions?perProject=` (lib/session-list-page.ts):
 * a few sessions per project with the project's total, and further pages on request.
 */
object SessionPages {
    /** Rows a project shows before "Show more", and the size of the first page. */
    const val FIRST_PAGE = 5
    const val MORE_PAGE = 10

    /** A row shows two lines of it; the server cuts the rest (skill expansions reach 400 KB). */
    const val FIRST_MESSAGE_CHARS = 200

    /** The projects of a paged response, or null when the server sent the full list instead. */
    fun parse(body: JsonObject): List<ProjectGroup>? = body.arr("projects")?.mapNotNull { element ->
        val json = element as? JsonObject ?: return@mapNotNull null
        val key = json.str("key") ?: return@mapNotNull null
        val sessions = json.arr("sessions").orEmpty().mapNotNull { (it as? JsonObject)?.let(::toRow) }
        ProjectGroup(key, json.str("root") ?: key, json.int("total") ?: sessions.size, sessions)
    }

    /** [page] appended to [group]; a session that moved up since the last page is not listed twice. */
    fun append(group: ProjectGroup, page: ProjectGroup): ProjectGroup {
        val known = group.sessions.mapTo(HashSet()) { it.id }
        return group.copy(total = page.total, sessions = group.sessions + page.sessions.filter { it.id !in known })
    }

    private fun toRow(json: JsonObject): SessionRow? {
        val id = json.str("id") ?: return null
        val first = json.str("firstMessage")?.takeUnless { it == "(no messages)" }?.let(SlashDisplay::display)
        return SessionRow(
            id = id,
            title = json.str("name")?.takeIf { it.isNotBlank() } ?: first ?: "New session",
            name = json.str("name")?.takeIf { it.isNotBlank() },
            cwd = json.str("cwd").orEmpty(),
            modified = json.str("modified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            messageCount = json.int("messageCount") ?: 0,
            branch = json.str("branch"),
        )
    }
}
