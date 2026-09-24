package app.pimobile.notify

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user is looking at, so notifications skip the session already on screen. */
object AppVisibility {
    private val viewing = MutableStateFlow<String?>(null)

    /** The session whose chat is on screen; null when none is, the app in background included. */
    val viewingSession: StateFlow<String?> = viewing.asStateFlow()

    var viewingSessionId: String?
        get() = viewing.value
        set(value) {
            viewing.value = value
        }

    /** Main thread only. */
    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
