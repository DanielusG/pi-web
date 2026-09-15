package app.pimobile.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pimobile.data.DiffFile
import app.pimobile.data.FileKind
import app.pimobile.data.FilePaths
import app.pimobile.data.Patch
import app.pimobile.data.PiApi
import app.pimobile.data.RecentFiles
import app.pimobile.data.asObj
import app.pimobile.data.bool
import app.pimobile.data.long
import app.pimobile.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ViewMode { Source, Preview, Diff }

data class FileViewerState(
    val path: String,
    val root: String,
    val kind: FileKind,
    val loading: Boolean = true,
    val error: String? = null,
    // Text, in server chunks of 256 KB.
    val content: String = "",
    val lines: List<String> = emptyList(),
    /** Longest line in characters (tabs as 4), for the no-wrap scroll width. */
    val maxLineLength: Int = 0,
    val truncated: Boolean = false,
    val nextOffset: Long = 0,
    val size: Long? = null,
    val loadingMore: Boolean = false,
    val image: Bitmap? = null,
    /** An image type Android can't decode (SVG); offered through "open with". */
    val imageUnsupported: Boolean = false,
    val pdf: File? = null,
    /** Docx converted to HTML by the server. */
    val html: String? = null,
    val diff: List<DiffFile>? = null,
    val diffResolved: Boolean = false,
    val deleted: Boolean = false,
    val mode: ViewMode = ViewMode.Source,
    val live: Boolean = false,
    /** Bumped when a binary file is downloaded again, so its view reloads. */
    val revision: Int = 0,
    val notice: String? = null,
) {
    val previewable: Boolean
        get() = kind == FileKind.Text && (FilePaths.isMarkdown(path) || FilePaths.isHtml(path))

    val modes: List<ViewMode>
        get() = buildList {
            if (kind == FileKind.Text && !deleted) {
                add(ViewMode.Source)
                if (previewable) add(ViewMode.Preview)
            }
            if (diff != null) add(ViewMode.Diff)
        }
}

class FileViewerViewModel(
    val api: PiApi,
    val path: String,
    root: String,
    val sessionId: String?,
    private val requestedDiff: Boolean,
    private val cacheDir: File,
) : ViewModel() {
    private val _state = MutableStateFlow(
        FileViewerState(
            path = path,
            root = root,
            kind = FilePaths.kind(path),
            mode = if (requestedDiff) ViewMode.Diff else ViewMode.Source,
        ),
    )
    val state: StateFlow<FileViewerState> = _state.asStateFlow()

    /** The mode the user picked; reloads keep it while it's still available. */
    private var chosenMode: ViewMode? = null
    private var loadedAt = 0L
    private var reloadJob: Job? = null

    init {
        RecentFiles.add(root, path)
        viewModelScope.launch { loadAll() }
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch { loadAll() }
    }

    /** Fetch again while keeping what is on screen. */
    fun reload() = scheduleReload()

    fun selectMode(mode: ViewMode) {
        chosenMode = mode
        _state.update { it.copy(mode = mode) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun loadMore() {
        val start = _state.value
        if (!start.truncated || start.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val body = api.get(FilePaths.api(path, "read", sessionId, "offset" to start.nextOffset)).asObj()
                val content = start.content + body?.str("content").orEmpty()
                val text = withContext(Dispatchers.Default) { TextLines.of(content) }
                _state.update {
                    // A reload replaced the content meanwhile: this chunk no longer fits.
                    if (it.content !== start.content) it.copy(loadingMore = false)
                    else it.copy(
                        content = content,
                        lines = text.lines,
                        maxLineLength = text.maxLength,
                        truncated = body?.bool("truncated") == true,
                        nextOffset = body?.long("nextOffset") ?: it.nextOffset,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, notice = e.message ?: "Could not load more") }
            }
        }
    }

    /**
     * Web: the viewer's live watch. On `connected` (when the content predates the
     * connection) and on `change`, the content and the git diff are fetched again.
     */
    suspend fun watch() {
        val kind = _state.value.kind
        if (kind == FileKind.Audio || kind == FileKind.Video) return
        var failures = 0
        while (true) {
            val connectStartedAt = SystemClock.elapsedRealtime()
            try {
                api.sse(FilePaths.api(path, "watch", sessionId)).collect { event ->
                    when (event.name) {
                        "connected" -> {
                            failures = 0
                            _state.update { it.copy(live = true) }
                            if (loadedAt in 1 until connectStartedAt) scheduleReload()
                        }
                        "change" -> scheduleReload()
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(live = false) }
                throw e
            } catch (_: Exception) {
                // Retried below.
            }
            _state.update { it.copy(live = false) }
            failures++
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }

    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(250) // editors often write a file in several steps
            loadAll()
        }
    }

    private suspend fun loadAll() {
        coroutineScope {
            launch { loadContent() }
            launch { loadDiff() }
        }
        loadedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(loading = false, mode = pickMode(it)) }
    }

    /** Web: FileViewer's mode rules. */
    private fun pickMode(s: FileViewerState): ViewMode {
        val modes = s.modes
        chosenMode?.let { if (it in modes) return it }
        if (s.deleted && s.diff != null) return ViewMode.Diff
        if (requestedDiff && s.diff != null) return ViewMode.Diff
        if (s.previewable && !s.truncated) return ViewMode.Preview
        return modes.firstOrNull() ?: ViewMode.Source
    }

    private suspend fun loadContent() {
        val firstLoad = loadedAt == 0L
        try {
            when (_state.value.kind) {
                FileKind.Text -> {
                    val body = api.get(FilePaths.api(path, "read", sessionId)).asObj()
                    val content = body?.str("content").orEmpty()
                    val text = withContext(Dispatchers.Default) { TextLines.of(content) }
                    _state.update {
                        it.copy(
                            content = content,
                            lines = text.lines,
                            maxLineLength = text.maxLength,
                            truncated = body?.bool("truncated") == true,
                            nextOffset = body?.long("nextOffset") ?: content.length.toLong(),
                            size = body?.long("size"),
                            error = null,
                        )
                    }
                }
                FileKind.Image -> {
                    val bytes = api.bytes(FilePaths.api(path, "read", sessionId))
                    val bitmap = withContext(Dispatchers.Default) { decodeSampled(bytes, MAX_IMAGE_SIDE) }
                    _state.update { it.copy(image = bitmap, imageUnsupported = bitmap == null, error = null) }
                }
                FileKind.Pdf -> {
                    val file = File(cacheDir, "viewer/${Integer.toHexString(path.hashCode())}.pdf")
                    api.download(FilePaths.api(path, "read", sessionId), file)
                    _state.update { it.copy(pdf = file, revision = it.revision + 1, error = null) }
                }
                FileKind.Docx -> {
                    val html = api.text(FilePaths.api(path, "preview", sessionId))
                    _state.update { it.copy(html = html, error = null) }
                }
                FileKind.Audio, FileKind.Video -> Unit // streamed by the player
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed live reload keeps what is on screen.
            if (firstLoad) _state.update { it.copy(error = e.message ?: "Could not open this file") }
        }
    }

    private suspend fun loadDiff() {
        val root = _state.value.root
        if (root.isEmpty()) {
            _state.update { it.copy(diff = null, diffResolved = true) }
            return
        }
        val body = try {
            api.get("/api/git/diff?cwd=${PiApi.encode(root)}&path=${PiApi.encode(path)}").asObj()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val patch = body?.takeIf { it.bool("supported") == true }?.str("patch")
        val files = patch?.let { withContext(Dispatchers.Default) { Patch.parse(it) } }?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(diff = files, diffResolved = true, deleted = body?.str("status") == "deleted") }
    }

    private class TextLines(val lines: List<String>, val maxLength: Int) {
        companion object {
            fun of(content: String): TextLines {
                if (content.isEmpty()) return TextLines(emptyList(), 0)
                var lines = content.split('\n').map { it.removeSuffix("\r") }
                if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
                val max = lines.maxOf { line -> line.length + 3 * line.count { it == '\t' } }
                return TextLines(lines, max)
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 2048

        fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
