package app.pimobile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pimobile.data.FilePaths
import app.pimobile.data.PiApi
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.bool
import app.pimobile.data.int
import app.pimobile.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FileEntry(val name: String, val path: String, val isDir: Boolean)

/** One row of `git status`; [code] is M, A, D, R, U or C. */
data class GitChange(val path: String, val code: String, val status: String)

data class DirState(
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

enum class FilesTab { Files, Changes }

data class FilesUiState(
    val root: String,
    val dir: String,
    /** Listings by directory, kept while browsing so going back is instant. */
    val dirs: Map<String, DirState> = emptyMap(),
    val tab: FilesTab = FilesTab.Files,
    val isRepo: Boolean = false,
    val changes: List<GitChange> = emptyList(),
    val changeByPath: Map<String, GitChange> = emptyMap(),
    /** Directories under the root that contain a change, for the dot on folder rows. */
    val changedDirs: Set<String> = emptySet(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val query: String = "",
    val results: List<FileEntry>? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
    val refreshing: Boolean = false,
) {
    val current: DirState get() = dirs[dir] ?: DirState(loading = true)
}

@OptIn(FlowPreview::class)
class FilesViewModel(val api: PiApi, root: String, val sessionId: String?) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState(root = root, dir = root))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private val queries = MutableStateFlow("")
    private var started = false

    init {
        open(root)
        viewModelScope.launch { fetchGit() }
        viewModelScope.launch {
            // Web: FileExplorer searches the server file index as you type.
            queries.debounce(200).distinctUntilChanged().collectLatest { search(it) }
        }
    }

    /** Coming back to the screen: files may have changed meanwhile. */
    fun onStart() {
        if (started) refresh(silent = true) else started = true
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val dir = _state.value.dir
            coroutineScope {
                launch { fetchDir(dir) }
                launch { fetchGit() }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun open(dir: String) {
        _state.update { it.copy(dir = dir) }
        val cached = _state.value.dirs[dir]
        if (cached?.loading == true) return
        // A cached listing shows at once and revalidates in the background.
        viewModelScope.launch { fetchDir(dir) }
    }

    /** Back inside the explorer: one level up, until the root. */
    fun up(): Boolean {
        val s = _state.value
        if (s.dir == s.root || !FilePaths.isInside(s.dir, s.root)) return false
        open(FilePaths.parent(s.dir))
        return true
    }

    fun retry() = open(_state.value.dir)

    fun selectTab(tab: FilesTab) = _state.update { it.copy(tab = tab) }

    fun setQuery(query: String) {
        _state.update {
            it.copy(
                query = query,
                results = if (query.isBlank()) null else it.results,
                searching = query.isNotBlank(),
                searchError = null,
            )
        }
        queries.value = query.trim()
    }

    private suspend fun fetchDir(dir: String) {
        _state.update { s ->
            val previous = s.dirs[dir] ?: DirState()
            s.copy(dirs = s.dirs + (dir to previous.copy(loading = true, error = null)))
        }
        val next = try {
            val body = api.get(FilePaths.api(dir, "list", sessionId)).asObj()
            val entries = body?.arr("entries").orEmpty().mapNotNull { element ->
                val entry = element.asObj() ?: return@mapNotNull null
                val name = entry.str("name") ?: return@mapNotNull null
                FileEntry(name, FilePaths.join(dir, name), entry.bool("isDir") == true)
            }
            DirState(entries = entries, loaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (_state.value.dirs[dir] ?: DirState()).copy(loading = false, error = e.message ?: "Could not list this folder")
        }
        _state.update { it.copy(dirs = it.dirs + (dir to next)) }
    }

    private suspend fun fetchGit() {
        val root = _state.value.root
        val body = try {
            api.get("/api/git/status?cwd=${PiApi.encode(root)}").asObj()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val isRepo = body.bool("isGitRepository") == true
        val changes = if (!isRepo) emptyList() else body.arr("files").orEmpty().mapNotNull { element ->
            val file = element.asObj() ?: return@mapNotNull null
            val path = file.str("filePath") ?: return@mapNotNull null
            GitChange(path, file.str("code") ?: "M", file.str("status") ?: "modified")
        }
        val dirs = HashSet<String>()
        for (change in changes) {
            var parent = FilePaths.parent(change.path)
            while (parent.isNotEmpty() && parent != root && FilePaths.isInside(parent, root)) {
                if (!dirs.add(parent)) break
                parent = FilePaths.parent(parent)
            }
        }
        _state.update {
            it.copy(
                isRepo = isRepo,
                changes = changes,
                changeByPath = changes.associateBy { change -> change.path },
                changedDirs = dirs,
                additions = body.int("additions") ?: 0,
                deletions = body.int("deletions") ?: 0,
                tab = if (isRepo) it.tab else FilesTab.Files,
            )
        }
    }

    private suspend fun search(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(results = null, searching = false) }
            return
        }
        val root = _state.value.root
        val results = try {
            api.get("/api/file-index?cwd=${PiApi.encode(root)}&q=${PiApi.encode(query)}").asObj()
                ?.arr("matches").orEmpty().mapNotNull { element ->
                    val match = element.asObj() ?: return@mapNotNull null
                    val relative = match.str("path")?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    FileEntry(FilePaths.name(relative), FilePaths.join(root, relative), match.bool("isDir") == true)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { if (it.query.trim() == query) it.copy(searching = false, searchError = e.message ?: "Search failed") else it }
            return
        }
        _state.update { if (it.query.trim() == query) it.copy(results = results, searching = false) else it }
    }
}
