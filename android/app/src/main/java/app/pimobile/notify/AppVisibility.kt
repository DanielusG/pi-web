package app.pimobile.notify

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/** What the user is looking at, so notifications skip the session already on screen. */
object AppVisibility {
    @Volatile
    var viewingSessionId: String? = null

    /** Main thread only. */
    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
