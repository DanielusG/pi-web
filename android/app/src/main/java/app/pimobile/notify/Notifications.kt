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
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            cwd?.let { putExtra(EXTRA_CWD, it) }
        }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun ongoing(context: Context, titles: List<String>): Notification {
        val title = when (titles.size) {
            0 -> "pi is working"
            1 -> titles.first()
            else -> "${titles.size} sessions running"
        }
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (titles.size > 1) titles.joinToString(" · ") else "Working…")
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
        NotificationManagerCompat.from(context).notify("pi-session-complete:$sessionId", FINISHED_ID, notification)
    }
}
