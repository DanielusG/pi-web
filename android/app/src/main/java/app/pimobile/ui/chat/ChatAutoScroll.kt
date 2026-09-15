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
import app.pimobile.ui.theme.Pi
import app.pimobile.ui.theme.PiIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Keeps the chat pinned to its end while content streams in (web: getLiveFollowAttached).
 * Only the user's own gestures change it, through [connection]: scrolling toward older
 * content detaches at once, bringing the list back to the end re-attaches. Programmatic
 * scrolls never reach the connection, so the auto-scroll can't undo a detach.
 */
@Stable
class BottomFollow internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val reattachPx: Float,
) {
    var attached by mutableStateOf(true)
        private set

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

    fun scrollToEnd() {
        attached = true
        scope.launch {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
}

@Composable
fun rememberBottomFollow(listState: LazyListState): BottomFollow {
    val scope = rememberCoroutineScope()
    val reattachPx = with(LocalDensity.current) { 32.dp.toPx() }
    val follow = remember(listState) { BottomFollow(listState, scope, reattachPx) }
    // While attached, any growth below the viewport (stream deltas, tool output, images
    // decoding, the keyboard opening) pulls the list back to its end. A gesture in progress
    // owns the list; the jump waits for it to finish.
    LaunchedEffect(follow, follow.attached) {
        if (!follow.attached) return@LaunchedEffect
        snapshotFlow {
            (listState.canScrollForward && !listState.isScrollInProgress) to listState.layoutInfo.totalItemsCount
        }.collect { (behind, count) ->
            if (behind && follow.attached && count > 0) listState.jumpTo(count - 1)
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
