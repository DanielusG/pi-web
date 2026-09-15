package app.pimobile.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Files opened in the viewer, most recent first, per project root. Kept in memory for the process. */
object RecentFiles {
    private const val MAX = 5
    private val byRoot = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val all: StateFlow<Map<String, List<String>>> = byRoot.asStateFlow()

    fun add(root: String, path: String) {
        if (root.isEmpty()) return
        byRoot.update { current ->
            current + (root to (listOf(path) + current[root].orEmpty().filter { it != path }).take(MAX))
        }
    }
}
