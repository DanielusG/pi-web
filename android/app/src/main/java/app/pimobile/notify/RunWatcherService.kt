package app.pimobile.notify

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pimobile.PiApp
import app.pimobile.data.PiApi
import app.pimobile.data.SlashDisplay
import app.pimobile.data.arr
import app.pimobile.data.asObj
import app.pimobile.data.str
import app.pimobile.data.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Foreground service that lives only while some session is running. pi-web's
 * own completion push is Web Push, which a native app can't receive, so this
 * polls GET /api/agent/running and notifies on running → idle transitions,
 * applying the same subagent suppression as pi-web's sidebar. On top of the
 * poll it keeps one SSE per active session (the viewed one excepted) to catch
 * blocking extension UI requests — permission gates included — and notify on
 * running → waiting transitions.
 */
class RunWatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private val sessions = mutableMapOf<String, Pair<String, String?>>() // id -> (title, cwd)
    private val sseJobs = mutableMapOf<String, Job>()
    private val waiting = mutableMapOf<String, String>() // id -> open dialog id

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        ServiceCompat.startForeground(
            this,
            Notifications.ONGOING_ID,
            Notifications.ongoing(this, emptyList()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        sseJobs.values.forEach { it.cancel() }
        sseJobs.clear()
        waiting.clear()
        (application as PiApp).waitingSessionIds.value = emptySet()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val api = (application as PiApp).api
        var previous = emptySet<String>()
        var suppressed = emptySet<String>()
        var idlePolls = 0
        var failures = 0
        while (true) {
            val body = try {
                api.get("/api/agent/running").asObj()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (body == null) {
                if (++failures >= MAX_FAILURES) break
                delay(POLL_MS)
                continue
            }
            failures = 0
            val running = body.arr("runningSessionIds").strings().toSet()
            // Suppression comes from the previous poll: a finished subagent leaves both lists at once.
            val finished = previous - running - suppressed
            val active = running - body.arr("completionNotificationSuppressedSessionIds").strings().toSet()
            if ((running + finished).any { it !in sessions }) loadSessions(api)

            for (id in finished) {
                if (AppVisibility.isForeground && AppVisibility.viewingSessionId == id) continue
                val (title, cwd) = sessions[id] ?: ("Session complete" to null)
                Notifications.finished(this, id, cwd, title)
                // A gate that resolved into a finished run leaves both states at once.
                if (waiting.remove(id) != null) Notifications.cancelWaiting(this, id)
            }
            suppressed = body.arr("completionNotificationSuppressedSessionIds").strings().toSet()
            previous = running

            // One SSE per active session; the viewed one is covered by the chat's own SSE.
            // The server replays open dialogs to new subscribers, so a (re)opened stream
            // recovers the waiting state even if the gate opened before the connection.
            val viewed = if (AppVisibility.isForeground) AppVisibility.viewingSessionId else null
            val desired = if (viewed != null) running - viewed else running
            for (id in sseJobs.keys.toList()) {
                if (id !in desired) {
                    sseJobs.remove(id)?.cancel()
                    // Also covers navigating to the session: the chat's dialog is on screen now.
                    if (waiting.remove(id) != null) Notifications.cancelWaiting(this, id)
                }
            }
            for (id in desired) {
                if (id !in sseJobs) {
                    sseJobs[id] = scope.launch {
                        try {
                            api.events(id).collect { event -> onWaitingEvent(id, event) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Dropped stream; the next tick reopens it while the session is active.
                        }
                    }
                }
            }
            publishWaiting()

            if (active.isEmpty()) {
                // A short grace lets queued follow-ups start without dropping the watch.
                if (++idlePolls >= IDLE_POLLS_BEFORE_STOP) break
            } else {
                idlePolls = 0
                if (Notifications.canPost(this)) {
                    getSystemService(NotificationManager::class.java).notify(
                        Notifications.ONGOING_ID,
                        Notifications.ongoing(this, active.map { id -> (sessions[id]?.first ?: "Session") to (id in waiting) }),
                    )
                }
            }
            delay(POLL_MS)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Blocking dialog methods only: notify/custom/widget/status never block the run. */
    private fun onWaitingEvent(sessionId: String, event: JsonObject) {
        when (event.str("type")) {
            "extension_ui_request" -> {
                val id = event.str("id") ?: return
                if (event.str("method") !in BLOCKING_DIALOG_METHODS) return
                if (waiting.putIfAbsent(sessionId, id) == null) {
                    val (title, cwd) = sessions[sessionId] ?: ("Session" to null)
                    if (!(AppVisibility.isForeground && AppVisibility.viewingSessionId == sessionId)) {
                        Notifications.waitingForInput(this, sessionId, cwd, title)
                    }
                }
            }
            "extension_ui_closed" -> {
                val id = event.str("id") ?: return
                if (waiting.remove(sessionId, id)) Notifications.cancelWaiting(this, sessionId)
            }
        }
        publishWaiting()
    }

    private fun publishWaiting() {
        (application as PiApp).waitingSessionIds.value = waiting.keys
    }

    private suspend fun loadSessions(api: PiApi) {
        try {
            api.get("/api/sessions").asObj()?.arr("sessions")?.forEach { element ->
                val json = element as? JsonObject ?: return@forEach
                val id = json.str("id") ?: return@forEach
                val title = json.str("name")?.takeIf { it.isNotBlank() }
                    ?: json.str("firstMessage")?.takeUnless { it == "(no messages)" }?.let(SlashDisplay::display)?.take(80)
                    ?: "Session complete"
                sessions[id] = title to json.str("cwd")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val POLL_MS = 3_000L
        private val BLOCKING_DIALOG_METHODS = setOf("select", "confirm", "input", "editor")
        private const val IDLE_POLLS_BEFORE_STOP = 3
        private const val MAX_FAILURES = 40 // ~2 minutes unreachable

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, RunWatcherService::class.java))
            } catch (_: Exception) {
                // Background starts are refused on Android 12+; the next foreground run retries.
            }
        }
    }
}
