package app.pimobile.ui.sessions

import app.pimobile.data.PiApi
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A directory on the server, [path] spelled as the server sent it. */
data class ServerFolder(val name: String, val path: String)

/**
 * One answer of `GET /api/cwd/browse`: the folders inside [path]. A Windows server asked for no
 * path lists its drives instead: [path] is then empty and [folders] holds the drive roots.
 */
data class FolderListing(val path: String, val folders: List<ServerFolder>)

/** A breadcrumb step: the folder it opens (null: a Windows server's drive list) and its label. */
data class FolderCrumb(val path: String?, val label: String)

data class FolderBrowserState(
    /** The folder on screen; null before the first answer. */
    val listing: FolderListing? = null,
    /** A folder is being fetched: [listing] is still the previous one. */
    val loading: Boolean = false,
    /** Dot folders are listed too. */
    val showHidden: Boolean = false,
    val error: String? = null,
)

/**
 * The server's directories, browsed one folder at a time for a session's working directory, as
 * the web's directory picker does (`/api/cwd/browse`, lib/directory-browser.ts). Owned by
 * [SessionsViewModel], so the next "Other folder" opens where this one was left.
 */
class FolderBrowser(private val api: PiApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(FolderBrowserState())
    val state: StateFlow<FolderBrowserState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Opens the folder left last time, fetched again since folders come and go, or the server's
     * home (its drives on Windows) the first time and when that folder is gone.
     */
    fun open(): Job = load(_state.value.listing?.path?.ifEmpty { null }, homeOnFailure = true)

    /** Opens [path]; null is the server's home (its drives on Windows). A tap during a fetch replaces it. */
    fun go(path: String?): Job = load(path, homeOnFailure = false)

    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Creates [name] inside the folder on screen and opens it; false with [FolderBrowserState.error] set. */
    suspend fun create(name: String): Boolean {
        val parent = _state.value.listing?.path?.ifEmpty { null } ?: return false
        _state.update { it.copy(error = null) }
        return try {
            val body = api.post(BROWSE, buildJsonObject { put("path", parent); put("name", name.trim()) }).asObj()
            val created = body?.str("path") ?: throw IllegalStateException("The server did not say where it created the folder")
            go(created).join()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not create the folder") }
            false
        }
    }

    /**
     * The folder on screen checked by `POST /api/cwd/validate`, which also opens it to the file
     * routes: the directory a session starts in, or null with [FolderBrowserState.error] set.
     */
    suspend fun choose(): String? {
        val path = _state.value.listing?.path?.ifEmpty { null } ?: return null
        _state.update { it.copy(error = null) }
        return try {
            val body = api.post("/api/cwd/validate", buildJsonObject { put("cwd", path) }).asObj()
            body?.str("cwd") ?: path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Invalid directory") }
            null
        }
    }

    private fun load(path: String?, homeOnFailure: Boolean): Job {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        return scope.launch {
            val listing = try {
                fetch(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val home = if (homeOnFailure && path != null) runCatching { fetch(null) }.getOrNull() else null
                if (home == null) {
                    _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
                    return@launch
                }
                home
            }
            _state.update { it.copy(listing = listing, loading = false, error = null) }
        }.also { job = it }
    }

    private suspend fun fetch(path: String?): FolderListing {
        val query = if (path.isNullOrEmpty()) "" else "?path=${PiApi.encode(path)}"
        val body = api.get(BROWSE + query).asObj()
        return body?.let(::parse) ?: throw IllegalStateException("Unexpected answer from $BROWSE")
    }

    companion object {
        private const val BROWSE = "/api/cwd/browse"

        fun parse(body: JsonObject): FolderListing? {
            val path = body.str("path") ?: return null
            val rows = body.arr("drives") ?: body.arr("directories")
            val folders = rows.orEmpty().mapNotNull { row ->
                val obj = row.asObj() ?: return@mapNotNull null
                val folderPath = obj.str("path") ?: return@mapNotNull null
                ServerFolder(obj.str("name") ?: folderPath, folderPath)
            }
            return FolderListing(path, folders)
        }

        /** Dot folders: most of a home directory, and rarely where a project lives. */
        fun isHidden(folder: ServerFolder) = folder.name.startsWith(".")

        private val DRIVE = Regex("^([A-Za-z]:)(?:[\\\\/]|$)")

        /**
         * The folders from the root down to [path], each one a tap away: `/`, then every segment;
         * on Windows the drive list, the drive, then every segment.
         */
        fun crumbs(path: String): List<FolderCrumb> {
            if (path.isEmpty()) return listOf(FolderCrumb(null, "Drives"))
            DRIVE.find(path)?.let { match ->
                val drive = match.groupValues[1]
                return buildList {
                    add(FolderCrumb(null, "Drives"))
                    var at = "$drive\\"
                    add(FolderCrumb(at, drive))
                    segments(path.substring(match.range.last + 1)).forEach { segment ->
                        at = if (at.endsWith("\\")) at + segment else "$at\\$segment"
                        add(FolderCrumb(at, segment))
                    }
                }
            }
            if (path.startsWith("\\\\")) {
                // A UNC share: \\server\share is its root, as a drive is.
                val parts = segments(path)
                if (parts.size < 2) return listOf(FolderCrumb(path, path))
                return buildList {
                    var at = "\\\\${parts[0]}\\${parts[1]}"
                    add(FolderCrumb(at, "${parts[0]}\\${parts[1]}"))
                    parts.drop(2).forEach { segment ->
                        at = "$at\\$segment"
                        add(FolderCrumb(at, segment))
                    }
                }
            }
            return buildList {
                add(FolderCrumb("/", "/"))
                var at = ""
                segments(path).forEach { segment ->
                    at = "$at/$segment"
                    add(FolderCrumb(at, segment))
                }
            }
        }

        private fun segments(path: String) = path.split('/', '\\').filter { it.isNotEmpty() }
    }
}
