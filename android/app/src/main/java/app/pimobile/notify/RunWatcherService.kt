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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Foreground service that lives only while some session is running. pi-web's
 * own completion push is Web Push, which a native app can't receive, so this
 * follows pi-web's run-state stream ([RunStatus]) and notifies on running → idle
 * transitions, applying the same subagent suppression as pi-web's sidebar, and
 * when a session starts waiting on a blocking extension dialog (permission gates
 * included). Nothing is polled: the server pushes only changes.
 */
class RunWatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private val sessions = mutableMapOf<String, Pair<String, String?>>() // id -> (title, cwd)

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
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch(): Unit = coroutineScope {
        val app = application as PiApp
        var previous = emptySet<String>()
        var suppressed = emptySet<String>()
        /** Waiting sessions with a posted notification. */
        var notifiedWaiting = emptySet<String>()
        var posted: List<Pair<String, Boolean>>? = null
        var stopTimer: Job? = null
        var stopDelay = 0L
        fun scheduleStop(delayMs: Long) {
            if (stopTimer?.isActive == true && stopDelay == delayMs) return
            stopTimer?.cancel()
            stopDelay = delayMs
            stopTimer = launch {
                delay(delayMs)
                stopWatching()
            }
        }

        // The app refuses to run against an outdated server: nothing to watch.
        launch {
            app.runStatus.outdated.first { it }
            stopWatching()
        }
        // The session on screen matters too: a dialog left open when the user navigates
        // away (or leaves the app) notifies then, though the server state did not change.
        app.runStatus.snapshot.combine(AppVisibility.viewingSession) { snapshot, _ -> snapshot }.collect { snapshot ->
            if (snapshot == null) {
                // Reconnecting: keep the last state; give up only if the server stays unreachable.
                if (stopTimer?.isActive != true) scheduleStop(OFFLINE_STOP_MS)
                return@collect
            }
            val running = snapshot.running
            // Suppression comes from the previous state: a finished subagent leaves both lists at once.
            val finished = previous - running - suppressed
            val active = running - snapshot.suppressed
            if ((running + finished + snapshot.waiting).any { it !in sessions }) loadSessions(app.api)

            for (id in finished) {
                if (isOnScreen(id)) continue
                val (title, cwd) = sessions[id] ?: ("Session complete" to null)
                Notifications.finished(this@RunWatcherService, id, cwd, title)
            }
            // Answered, or resolved into a finished run.
            for (id in notifiedWaiting - snapshot.waiting) Notifications.cancelWaiting(this@RunWatcherService, id)
            // On screen, the chat shows the dialog and clears the notification; leaving it notifies again.
            val shown = notifiedWaiting.filterTo(mutableSetOf()) { it in snapshot.waiting && !isOnScreen(it) }
            for (id in snapshot.waiting) {
                if (id in shown || isOnScreen(id)) continue
                val (title, cwd) = sessions[id] ?: ("Session" to null)
                Notifications.waitingForInput(this@RunWatcherService, id, cwd, title)
                shown += id
            }
            notifiedWaiting = shown
            previous = running
            suppressed = snapshot.suppressed

            if (active.isEmpty()) {
                // A short grace lets queued follow-ups start without dropping the watch.
                scheduleStop(IDLE_STOP_MS)
            } else {
                stopTimer?.cancel()
                val items = active.map { id -> (sessions[id]?.first ?: "Session") to (id in snapshot.waiting) }
                // Re-posted only when its content changes: every post wakes the system.
                if (items != posted && Notifications.canPost(this@RunWatcherService)) {
                    getSystemService(NotificationManager::class.java).notify(
                        Notifications.ONGOING_ID,
                        Notifications.ongoing(this@RunWatcherService, items),
                    )
                    posted = items
                }
            }
        }
    }

    /** Main thread only (see [AppVisibility.isForeground]). */
    private fun isOnScreen(sessionId: String) =
        AppVisibility.isForeground && AppVisibility.viewingSessionId == sessionId

    private fun stopWatching() {
        loop?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        private const val IDLE_STOP_MS = 10_000L
        private const val OFFLINE_STOP_MS = 120_000L

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
