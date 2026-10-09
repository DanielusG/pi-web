package app.pimobile.data

import java.util.concurrent.ConcurrentHashMap

/** The list's keys in order — the one source for the chat's last index and for its anchors. */
fun chatListKeys(
    loading: Boolean,
    hasMore: Boolean,
    showEmpty: Boolean,
    itemKeys: List<String>,
    streaming: Boolean,
): List<String> = buildList(itemKeys.size + 5) {
    if (loading) add(LOADING_KEY)
    if (hasMore) add(EARLIER_KEY)
    if (showEmpty) add(EMPTY_KEY)
    addAll(itemKeys)
    if (streaming) add(STREAMING_KEY)
    add(BOTTOM_KEY)
}

/** Items the chat shows that are not messages: they come and go, so they anchor nothing. */
fun isPseudoKey(key: Any?): Boolean =
    key == LOADING_KEY || key == EARLIER_KEY || key == EMPTY_KEY || key == STREAMING_KEY || key == BOTTOM_KEY

const val LOADING_KEY = "loading"
const val EARLIER_KEY = "earlier"
const val EMPTY_KEY = "empty"
const val STREAMING_KEY = "streaming"
const val BOTTOM_KEY = "bottom"

/** Where the chat was left, with the unsent composer text. */
data class ChatResume(
    /** The entryId of the real message under the viewport's top edge; null = at the end. */
    val anchorKey: String?,
    /** Pixels into that item: positive when the item began above the edge, so restoring with
     * `scrollToItem(index, offset)` puts the position back exactly. */
    val anchorOffset: Int,
    /** False = the follow was attached, so the chat belongs at its end. */
    val detached: Boolean,
    val draft: String,
    val cursor: Int,
    val seenAt: Long,
)

/** The anchor's index in the current list, or null when it is gone (compaction, a branch
 * switch, a deleted message, older pages not loaded any more) — the chat then opens at its end. */
fun resolveAnchorIndex(keys: List<String>, anchorKey: String?): Int? {
    if (anchorKey == null) return null
    val index = keys.indexOf(anchorKey)
    return if (index < 0) null else index
}

/**
 * The scroll that keeps the position when the list changed above the viewport — "Load earlier
 * messages" prepending, or the "earlier" row disappearing. The LazyList keeps the index, so
 * without this the content slides away from under the top edge. Returns the new index and offset
 * for [anchorKey], or null when [anchorKey] is absent, unchanged, or not in both lists.
 */
fun positionCorrection(
    previousKeys: List<String>,
    anchorKey: String?,
    anchorOffset: Int,
    keys: List<String>,
): Pair<Int, Int>? {
    if (anchorKey == null) return null
    val oldIndex = previousKeys.indexOf(anchorKey)
    val newIndex = keys.indexOf(anchorKey)
    return if (oldIndex < 0 || newIndex < 0 || newIndex == oldIndex) null
    else newIndex to anchorOffset.coerceAtLeast(0)
}

/** A fresh session has no id until its first prompt: its draft is kept under its directory. */
fun sessionKeyFor(id: String?, cwd: String): String = if (id.isNullOrBlank()) "new:$cwd" else id

/** The most recent [max] entries, newest first. */
fun pruneResume(entries: Map<String, ChatResume>, max: Int): Map<String, ChatResume> {
    if (entries.size <= max) return entries
    return entries.entries.sortedByDescending { it.value.seenAt }.take(max).associate { it.key to it.value }
}

/** Past this, a prompt would not fit the model's context window either. */
const val MAX_DRAFT_CHARS = 500_000

/** Sessions kept: the chat's own state is small, and this bounds the worst case. */
const val MAX_RESUME_ENTRIES = 24

/**
 * Positions and drafts, per session, for the process. A plain map, not a `StateFlow` like
 * `RecentFiles`: nothing observes it (it is read once when the chat composes), and an observed
 * flow would recompose the screen on every scroll frame.
 *
 * Where the chat was left and what the composer held. The store lives on [app.pimobile.PiApp],
 * not in the `ChatViewModel`: that one is scoped to a nav back stack entry and is cleared when
 * the entry is popped — going Back to the session list, `/clone` replacing the chat, the
 * assistant trigger popping the chats below it. In-process only: `RunWatcherService` keeps the
 * process alive while a session runs, so reopening the app usually still has these.
 */
class ChatResumeStore {
    private val entries = ConcurrentHashMap<String, ChatResume>()

    fun peek(key: String): ChatResume? = entries[key]

    /** Saves the resume, or drops the entry when there is nothing to say: at the end and an
     * empty composer is where a chat opens anyway, so the entry would only add noise. */
    fun save(key: String, resume: ChatResume) {
        if (!resume.detached && resume.draft.isBlank()) {
            drop(key)
            return
        }
        entries[key] = resume.clipped()
        if (entries.size > MAX_RESUME_ENTRIES) {
            // The oldest go first: `pruneResume` says which ones stay.
            val keep = pruneResume(entries.toMap(), MAX_RESUME_ENTRIES).keys
            entries.keys.filter { it !in keep }.forEach { entries.remove(it) }
        }
    }

    /** A fresh session takes its id with the first prompt: what was saved under `new:` moves. */
    fun move(from: String, to: String) {
        val resume = entries.remove(from) ?: return
        save(to, resume.copy(seenAt = System.currentTimeMillis()))
    }

    fun drop(key: String) {
        entries.remove(key)
    }

    val size: Int get() = entries.size
}

private fun ChatResume.clipped(): ChatResume {
    if (draft.length <= MAX_DRAFT_CHARS) return this
    return copy(draft = draft.substring(0, MAX_DRAFT_CHARS), cursor = cursor.coerceAtMost(MAX_DRAFT_CHARS))
}
