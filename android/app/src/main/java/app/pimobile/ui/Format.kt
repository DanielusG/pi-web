package app.pimobile.ui

import android.text.format.DateFormat as AndroidDateFormat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val HOME_PREFIX = Regex("^(/home/[^/]+|/Users/[^/]+)")

fun shortPath(path: String): String = path.replace(HOME_PREFIX, "~")

fun baseName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

fun compactNumber(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> "${value / 1000}k"
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1000.0)
    else -> value.toString()
}

fun groupedNumber(value: Long): String = java.text.NumberFormat.getIntegerInstance().format(value)

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

/** Web: MessageView formatTime — the time today, otherwise the date too, with the year only when it differs. */
fun messageTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))
    val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    if (sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return time
    val pattern = AndroidDateFormat.getBestDateTimePattern(Locale.getDefault(), if (sameYear) "MMMd" else "yMMMd")
    return "${AndroidDateFormat.format(pattern, epochMillis)} $time"
}

fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return ""
    val seconds = (now - epochMillis) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        seconds < 7 * 86_400 -> "${seconds / 86_400}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
    }
}

/** ISO-8601 timestamps from the API as epoch millis; 0 when unparseable. */
fun isoMillis(iso: String): Long =
    runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
