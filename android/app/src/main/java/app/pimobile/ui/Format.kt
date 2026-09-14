package app.pimobile.ui

import java.text.DateFormat
import java.util.Date

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
