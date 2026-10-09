package app.pimobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.pimobile.data.ChatResume
import app.pimobile.data.LOADING_KEY
import app.pimobile.data.isPseudoKey
import app.pimobile.data.positionCorrection
import app.pimobile.data.resolveAnchorIndex
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Keeps the chat pinned to its end while content streams in (web: getLiveFollowAttached), and
 * restores the position it was left at. Only the user's own gestures detach it, through
 * [connection]: scrolling toward older content detaches at once, bringing the list back to the
 * end re-attaches. Programmatic scrolls never reach the connection, so the auto-scroll can't
 * undo a detach — which is what lets a saved position survive content arriving.
 */
@Stable
class BottomFollow internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val reattachPx: Float,
    /** Where the chat was left when it last left the screen; null = nothing saved. */
    private val resume: ChatResume? = null,
) {
    /** A saved position starts detached, so the follow cannot undo it (see [onComposed]). */
    var attached by mutableStateOf(resume?.detached != true)
        private set

    /** The real message under the viewport's top edge, with the pixels into it: [resumeState]. */
    var anchorKey: String? = resume?.anchorKey
        private set
    var anchorOffset: Int = resume?.anchorOffset ?: 0
        private set

    fun updateAnchor(key: String, offset: Int) {
        anchorKey = key
        anchorOffset = offset
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0f && listState.canScrollBackward) attached = false
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y < 0f && listState.distanceToEnd() <= reattachPx) attached = true
            return Offset.Zero
        }

        // Every release ends in a fling, even a still one.
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!listState.canScrollForward) attached = true
            return Velocity.Zero
        }
    }

    fun attach() {
        attached = true
    }

    private var composedLastIndex = -1
    private var composedContent: Array<out Any?> = emptyArray()
    private var composedKeys: List<String>? = null
    private var firstPass = true

    /**
     * Called after each composition of the list with the index of its last item, the keys it
     * shows and the values it renders. While attached, a change is scrolled to by the same
     * measure pass that lays it out: a streaming update costs one frame. The fallback in
     * [rememberBottomFollow] can only jump after that layout, in a second frame.
     *
     * While detached, a change *above* the viewport is corrected the same way, so the item
     * under the top edge stays there instead of sliding away: "Load earlier messages"
     * prepending, and the "earlier" row disappearing once the history is all loaded. A change
     * below (a streaming append) leaves the position alone.
     *
     * The first pass applies the saved position instead of following. The keys must be the same
     * instance while the list is unchanged — a fresh list every composition would read as a
     * change and have the attached follow scroll on every recomposition.
     */
    fun onComposed(lastIndex: Int, keys: List<String>, vararg content: Any?) {
        val previous = composedKeys
        val changed = lastIndex != composedLastIndex || content.size != composedContent.size ||
            content.indices.any { content[it] !== composedContent[it] } || keys !== previous
        composedLastIndex = lastIndex
        composedContent = content
        composedKeys = keys
        // While the session loads there is only the "loading" row: nothing to follow or restore.
        if (!changed || keys.firstOrNull() == LOADING_KEY) return
        if (firstPass) {
            firstPass = false
            val wanted = resume?.takeIf { it.detached }
            if (wanted != null) {
                val index = resolveAnchorIndex(keys, wanted.anchorKey)
                if (index == null) {
                    // The message is gone (compaction, a branch switch, older pages not loaded):
                    // the chat opens at its end, as it did before this restore existed.
                    attached = true
                } else {
                    anchorKey = wanted.anchorKey
                    anchorOffset = wanted.anchorOffset
                    listState.requestScrollToItem(index, wanted.anchorOffset.coerceAtLeast(0))
                    return
                }
            } else if (resume == null && lastIndex > 0 &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                // Nothing in the store, but the nav entry's own saved state put the list back at
                // an index it chose: after a process kill the store is empty while that survives.
                attached = false
                refreshAnchor()
                return
            }
        }
        if (attached) {
            if (!listState.isScrollInProgress) listState.requestScrollToItem(lastIndex)
        } else {
            positionCorrection(
                previous.orEmpty(),
                anchorKey,
                anchorOffset,
                keys,
            )?.let { (index, offset) ->
                if (!listState.isScrollInProgress) listState.requestScrollToItem(index, offset.coerceAtLeast(0))
            }
            refreshAnchor()
        }
    }

    /** The resume to save when the chat leaves the screen, with the composer's text. */
    fun resumeState(draftText: String, cursor: Int): ChatResume = ChatResume(
        anchorKey = if (attached) null else anchorKey,
        anchorOffset = if (attached) 0 else anchorOffset,
        detached = !attached,
        draft = draftText,
        cursor = cursor,
        seenAt = System.currentTimeMillis(),
    )

    /** Reads the anchor from the layout that is on screen now (this runs before the next one). */
    private fun refreshAnchor() {
        val visible = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { item -> (item.key as? String)?.let { it to item.offset } }
        val (key, offset) = viewportAnchor(visible) ?: return
        anchorKey = key
        anchorOffset = offset
    }

    fun scrollToEnd() {
        attached = true
        scope.launch {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
}

@Composable
fun rememberBottomFollow(listState: LazyListState, resume: ChatResume? = null): BottomFollow {
    val scope = rememberCoroutineScope()
    val reattachPx = with(LocalDensity.current) { 32.dp.toPx() }
    // The saved position only decides how the follow starts: once it is running, gestures own it.
    val follow = remember(listState) { BottomFollow(listState, scope, reattachPx, resume) }
    // While attached, any growth below the viewport (images decoding, the keyboard opening, a
    // card expanding, content that [BottomFollow.onComposed] did not see) pulls the list back
    // to its end. A gesture in progress owns the list; the jump waits for it to finish.
    // While detached, continuously track the anchor message under the top edge of the viewport
    // as the user scrolls, so leaving the screen records the exact position.
    LaunchedEffect(follow, follow.attached) {
        if (follow.attached) {
            snapshotFlow {
                (listState.canScrollForward && !listState.isScrollInProgress) to listState.layoutInfo.totalItemsCount
            }.collect { (behind, count) ->
                if (behind && follow.attached && count > 0) listState.jumpTo(count - 1)
            }
        } else {
            snapshotFlow {
                val visible = listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { item -> (item.key as? String)?.let { it to item.offset } }
                viewportAnchor(visible)
            }.collect { anchor ->
                if (anchor != null) {
                    follow.updateAnchor(anchor.first, anchor.second)
                }
            }
        }
    }
    return follow
}

private suspend fun LazyListState.jumpTo(index: Int) {
    try {
        scrollToItem(index)
    } catch (e: CancellationException) {
        // A gesture took the list mid-jump: the next change retries. Rethrow only if we're gone.
        currentCoroutineContext().ensureActive()
    }
}

/**
 * The real message the viewport is resting on: its key and the pixels into it — positive when
 * the item began above the top edge, so `scrollToItem(index, offset)` puts the position back
 * exactly; negative when the edge is still above the item, which clamps to 0 on restore. Rows
 * that are not messages ([isPseudoKey]) anchor nothing: they come and go as the list changes.
 * [visible] is the viewport's items as (key, offset from its top edge), in order.
 */
internal fun viewportAnchor(visible: List<Pair<String, Int>>): Pair<String, Int>? {
    val real = visible.filter { !isPseudoKey(it.first) }
    val anchor = real.lastOrNull { it.second <= 0 } ?: real.firstOrNull() ?: return null
    return anchor.first to -anchor.second
}

/** Pixels of content below the viewport; 0 at the end. */
private fun LazyListState.distanceToEnd(): Float {
    if (!canScrollForward) return 0f
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
}

@Composable
fun JumpToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Pi.tokens
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .border(1.dp, t.border, CircleShape)
                .background(t.background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PiIcons.ArrowDown, contentDescription = "Scroll to bottom", tint = t.text, modifier = Modifier.size(20.dp))
        }
    }
}
