package app.pimobile.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pimobile.MainActivity
import app.pimobile.R

object Notifications {
    const val CHANNEL_RUNNING = "running"
    const val CHANNEL_FINISHED = "finished"
    const val ONGOING_ID = 1
    private const val FINISHED_ID = 2

    const val EXTRA_SESSION_ID = "app.pimobile.extra.SESSION_ID"
    const val EXTRA_CWD = "app.pimobile.extra.CWD"
    /** One-shot marker: lets MainActivity consume each notification tap exactly once. */
    const val EXTRA_TOKEN = "app.pimobile.extra.TOKEN"

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RUNNING, "Running sessions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while pi is working, so the app can tell you when it's done"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_FINISHED, "Finished sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a session finishes its task"
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(context: Context, sessionId: String?, cwd: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
                cwd?.let { putExtra(EXTRA_CWD, it) }
                // Unique per post: re-posting refreshes it via FLAG_UPDATE_CURRENT, so every
                // new notification tap carries a fresh token (MainActivity consumes it once).
                // The ongoing notification opens no session, so its intent stays identical.
                putExtra(EXTRA_TOKEN, System.nanoTime().toString())
            }
        }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** [items] are (title, waiting) pairs; waiting sessions win the title. */
    fun ongoing(context: Context, items: List<Pair<String, Boolean>>): Notification {
        val titles = items.map { it.first }
        val waitingTitles = items.filter { it.second }.map { it.first }
        val title = when {
            waitingTitles.isNotEmpty() ->
                if (titles.size == 1) waitingTitles.first() else "Waiting for input"
            titles.isEmpty() -> "pi is working"
            titles.size == 1 -> titles.first()
            else -> "${titles.size} sessions running"
        }
        val text = when {
            waitingTitles.isNotEmpty() -> waitingTitles.joinToString(" · ")
            titles.size > 1 -> titles.joinToString(" · ")
            else -> "Working…"
        }
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent(context, null, null))
            .build()
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    fun finished(context: Context, sessionId: String, cwd: String?, title: String) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Task finished.")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openIntent(context, sessionId, cwd))
            .build()
        // Same tag scheme as pi-web's push, so repeats replace instead of stacking.
        NotificationManagerCompat.from(context).notify(tagFinished(sessionId), FINISHED_ID, notification)
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    fun waitingForInput(context: Context, sessionId: String, cwd: String?, title: String) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Waiting for your input.")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openIntent(context, sessionId, cwd))
            .build()
        // Own tag: repeats replace, and it never collides with the completion tag.
        NotificationManagerCompat.from(context).notify(tagWaiting(sessionId), FINISHED_ID, notification)
    }

    fun cancelWaiting(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(tagWaiting(sessionId), FINISHED_ID)
    }

    /** Cancels both one-shot notifications of a session: stale once the session is on screen. */
    fun cancelForSession(context: Context, sessionId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(tagFinished(sessionId), FINISHED_ID)
        manager.cancel(tagWaiting(sessionId), FINISHED_ID)
    }

    private fun tagFinished(sessionId: String) = "pi-session-complete:$sessionId"
    private fun tagWaiting(sessionId: String) = "pi-session-waiting:$sessionId"
}
