package app.pimobile.notify

import app.pimobile.data.ApiException
import app.pimobile.data.PiApi
import app.pimobile.data.arr
import app.pimobile.data.long
import app.pimobile.data.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject

/** pi-web's server-wide run state, as pushed by `GET /api/agent/running/events`. */
data class RunSnapshot(
    val listVersion: Long?,
    val running: Set<String>,
    /** Running sessions whose completion must not notify (subagents). */
    val suppressed: Set<String>,
    /** Sessions blocked on a dialog that waits for the user's answer. */
    val waiting: Set<String>,
) {
    companion object {
        fun parse(json: JsonObject) = RunSnapshot(
            listVersion = json.long("sessionListVersion"),
            running = json.arr("runningSessionIds").strings().toSet(),
            suppressed = json.arr("completionNotificationSuppressedSessionIds").strings().toSet(),
            waiting = json.arr("waitingSessionIds").strings().toSet(),
        )
    }
}

/**
 * The app's single connection to pi-web's run-state stream, shared by the session list
 * and [RunWatcherService]: open while at least one of them collects [snapshot], closed
 * otherwise. The server sends the state on connect and then only when it changes, so
 * nothing is polled. A server without the stream (404) is reported through [outdated]:
 * the app asks for a pi-web update instead of falling back to polling.
 */
class RunStatus(private val api: PiApi, scope: CoroutineScope) {
    private val _outdated = MutableStateFlow(false)
    val outdated: StateFlow<Boolean> = _outdated.asStateFlow()

    /** The latest state; null while (re)connecting or when the server is [outdated]. */
    val snapshot: StateFlow<RunSnapshot?> = flow {
        var attempt = 0
        while (true) {
            try {
                api.jsonEvents(PATH).collect { json ->
                    attempt = 0
                    _outdated.value = false
                    emit(RunSnapshot.parse(json))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 404) {
                    _outdated.value = true
                    emit(null)
                    // Resume when a retry ([check], [require]) finds the stream.
                    _outdated.first { !it }
                    attempt = 0
                    continue
                }
            } catch (_: Exception) {
                // network drop or half-open connection: retry below
            }
            emit(null)
            delay(RECONNECT_DELAYS_MS[minOf(attempt++, RECONNECT_DELAYS_MS.lastIndex)])
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    /**
     * Opens the stream until its first event, recording the verdict in [outdated].
     * Throws [ServerOutdatedException] on 404, and the network error otherwise.
     */
    suspend fun require() {
        try {
            api.sse(PATH).first()
            _outdated.value = false
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            _outdated.value = true
            throw ServerOutdatedException()
        }
    }

    /** Startup check: only a 404 counts, an unreachable server says nothing about its version. */
    suspend fun check() {
        try {
            require()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    class ServerOutdatedException : IllegalStateException(
        "This pi-web is too old for Pi Mobile: it lacks $PATH. Update pi-web on the server.",
    )

    companion object {
        const val PATH = "/api/agent/running/events"
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
