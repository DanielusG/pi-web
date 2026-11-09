package app.pimobile.ui.sessions

import app.pimobile.data.PiApi
import app.pimobile.data.SlashDisplay
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.bool
import app.pimobile.data.int
import app.pimobile.data.obj
import app.pimobile.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Text around a hit: [match] is what the query found. */
data class Highlight(val before: String, val match: String, val after: String)

/** Where a session's first hit sits: the message entry and its text block. */
data class SearchSnippet(val entryId: String?, val blockIndex: Int, val text: Highlight)

data class SearchHit(
    val row: SessionRow,
    val projectKey: String,
    val projectRoot: String,
    /** The subagent profile, when the hit is a subagent run. */
    val subagent: String?,
    /** The hit inside the session name, which is then [SessionRow.title]. */
    val nameMatch: Highlight?,
    val snippet: SearchSnippet?,
)

data class SearchResults(
    val hits: List<SearchHit>,
    /** Sessions in scope, and how many of them the server read before stopping. */
    val total: Int,
    val scanned: Int,
    /** More sessions matched than [hits] holds. */
    val hasMore: Boolean,
    /** The server's time budget stopped the scan: sessions after [scanned] were not searched. */
    val truncated: Boolean,
    /** pi refused the query (an invalid `re:` pattern): nothing matches, as in the TUI. */
    val queryError: String?,
)

/** pi's selector sorts: by its match score (the TUI's default with a query), or newest first. */
enum class SearchSort(val param: String, val label: String) {
    Relevance("relevance", "Relevance"),
    Recent("recent", "Recent"),
}

/** The project a search is limited to: a paged-list [ProjectGroup]'s key and root. */
data class SearchScope(val key: String, val root: String)

data class SessionSearchState(
    val open: Boolean = false,
    val query: String = "",
    val scope: SearchScope? = null,
    val sort: SearchSort = SearchSort.Relevance,
    val limit: Int = SessionSearch.PAGE,
    /** A request is on its way (the results shown, if any, are the previous query's). */
    val loading: Boolean = false,
    val results: SearchResults? = null,
    val error: String? = null,
)

/**
 * Every session searched with pi's `/resume` rules on the server
 * (`GET /api/sessions/find`, lib/session-find.ts): words, `"exact phrase"`,
 * `re:<pattern>`, scoped to one project or not. Owned by [SessionsViewModel],
 * so Back from a result's chat finds the search as it was left.
 */
@OptIn(FlowPreview::class)
class SessionSearch(private val api: PiApi, scope: CoroutineScope) {
    private val _state = MutableStateFlow(SessionSearchState())
    val state: StateFlow<SessionSearchState> = _state.asStateFlow()

    /** One search; [attempt] makes a retry of the same query a new request. */
    private data class Request(val query: String, val scope: SearchScope?, val sort: SearchSort, val limit: Int, val attempt: Int)

    private val requests = MutableStateFlow<Request?>(null)
    private var attempt = 0
    /** The request whose answer [SessionSearchState.results] holds. */
    private var shownFor: Request? = null

    init {
        scope.launch {
            // As the web sidebar's search: one request once typing pauses, the previous one dropped.
            requests.debounce(300).collectLatest { request ->
                if (request == null || request.query.isBlank() || request == shownFor) {
                    _state.update { it.copy(loading = false) }
                    return@collectLatest
                }
                try {
                    val body = api.get(url(request)).asObj() ?: throw IllegalStateException("Empty response from /api/sessions/find")
                    val results = parse(body)
                    shownFor = request
                    _state.update { it.copy(loading = false, results = results, error = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
                }
            }
        }
    }

    fun open() = _state.update { it.copy(open = true) }

    /** Leaving the search forgets it, as clearing the files screen's search does. */
    fun close() {
        requests.value = null
        shownFor = null
        _state.value = SessionSearchState()
    }

    fun setQuery(query: String) = change {
        it.copy(query = query, limit = PAGE, results = if (query.isBlank()) null else it.results, error = null)
    }

    fun setScope(scope: SearchScope?) = change { it.copy(scope = scope, limit = PAGE) }

    fun setSort(sort: SearchSort) = change { it.copy(sort = sort, limit = PAGE) }

    fun showMore() = change { it.copy(limit = minOf(it.limit + PAGE, MAX_LIMIT)) }

    /** The same query again, after an error. */
    fun retry() {
        attempt += 1
        change { it.copy(error = null) }
    }

    private fun change(transform: (SessionSearchState) -> SessionSearchState) {
        val next = transform(_state.value)
        // Cleared results answer nothing: typing the same query again must ask again.
        if (next.results == null) shownFor = null
        val request = Request(next.query.trim(), next.scope, next.sort, next.limit, attempt)
        _state.value = next.copy(loading = request.query.isNotBlank() && request != shownFor)
        requests.value = request
    }

    private fun url(request: Request): String =
        "/api/sessions/find?q=${PiApi.encode(request.query)}&sort=${request.sort.param}&limit=${request.limit}" +
            (request.scope?.let { "&project=${PiApi.encode(it.key)}" } ?: "")

    companion object {
        const val PAGE = 50
        /** The server's cap on one answer. */
        const val MAX_LIMIT = 200

        /** The answer of `GET /api/sessions/find`. */
        fun parse(body: JsonObject): SearchResults {
            val hits = body.arr("results").orEmpty().mapNotNull { element ->
                val json = element as? JsonObject ?: return@mapNotNull null
                val id = json.str("id") ?: return@mapNotNull null
                val name = json.str("name")?.takeIf { it.isNotBlank() }
                val first = json.str("firstMessage")?.takeUnless { it == "(no messages)" }?.let(SlashDisplay::display)
                val cwd = json.str("cwd").orEmpty()
                val relation = json.obj("relation")
                SearchHit(
                    row = SessionRow(
                        id = id,
                        title = name ?: first ?: "New session",
                        name = name,
                        cwd = cwd,
                        modified = json.str("modified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
                        messageCount = json.int("messageCount") ?: 0,
                        branch = json.str("branch"),
                    ),
                    projectKey = json.str("projectKey") ?: cwd,
                    projectRoot = json.str("projectRoot") ?: cwd,
                    subagent = if (relation?.str("kind") == "subagent") relation.str("profile") ?: "Subagent" else null,
                    nameMatch = if (name != null) json.obj("nameMatch")?.let(::highlight) else null,
                    snippet = json.obj("snippet")?.let { snippet ->
                        highlight(snippet)?.let { SearchSnippet(snippet.str("entryId"), snippet.int("blockIndex") ?: 0, it) }
                    },
                )
            }
            return SearchResults(
                hits = hits,
                total = body.int("total") ?: hits.size,
                scanned = body.int("scanned") ?: hits.size,
                hasMore = body.bool("hasMore") == true,
                truncated = body.bool("truncated") == true,
                queryError = body.str("queryError"),
            )
        }

        private fun highlight(json: JsonObject): Highlight? {
            val match = json.str("match") ?: return null
            return Highlight(json.str("before").orEmpty(), match, json.str("after").orEmpty())
        }
    }
}
