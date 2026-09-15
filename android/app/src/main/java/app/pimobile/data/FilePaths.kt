package app.pimobile.data

import android.webkit.MimeTypeMap
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class FileKind { Text, Image, Audio, Video, Pdf, Docx }

/**
 * Path helpers ported from pi-web's lib/file-paths.ts and lib/file-links.ts, so
 * links, tool paths and @mentions resolve exactly as they do on the web.
 */
object FilePaths {
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif")
    private val AUDIO = setOf("mp3", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "weba")
    private val VIDEO = setOf("mp4", "m4v", "webm", "mov", "ogv")
    private val MARKDOWN = setOf("md", "mdx")
    private val HTML = setOf("html", "htm")
    private val PROSE = MARKDOWN + setOf("txt", "rst", "adoc", "log")

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val DRIVE = Regex("^[a-zA-Z]:/")
    private val DRIVE_ANY_SLASH = Regex("^[a-zA-Z]:[\\\\/]")
    private val LINE_SUFFIX = Regex(":\\d+(?::\\d+)?$")
    private val RELATIVE_FILE_NAME = Regex("(^|/)\\.?[^/]+\\.[^/.]+$")

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return when {
            index > 0 -> trimmed.substring(0, index)
            index == 0 -> "/"
            else -> ""
        }
    }

    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun ext(path: String): String = name(path).substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kind(path: String): FileKind = when (ext(path)) {
        in IMAGE -> FileKind.Image
        in AUDIO -> FileKind.Audio
        in VIDEO -> FileKind.Video
        "pdf" -> FileKind.Pdf
        "docx" -> FileKind.Docx
        else -> FileKind.Text
    }

    fun isMarkdown(path: String) = ext(path) in MARKDOWN
    fun isHtml(path: String) = ext(path) in HTML

    /** Text that reads better wrapped on a phone. */
    fun isProse(path: String) = ext(path) in PROSE

    fun mime(path: String): String {
        val ext = ext(path)
        return when {
            ext == "svg" -> "image/svg+xml"
            ext == "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (kind(path) == FileKind.Text) "text/plain" else "application/octet-stream"
        }
    }

    /** Web: getFileApiUrl — `/api/files/<encoded segments>?type=…`. */
    fun api(path: String, type: String, sessionId: String? = null, vararg params: Pair<String, Any?>): String {
        val encoded = normalizeSlashes(path).split('/').filter { it.isNotEmpty() }.joinToString("/") { PiApi.encode(it) }
        val query = buildList {
            add("type=$type")
            if (!sessionId.isNullOrEmpty()) add("sessionId=${PiApi.encode(sessionId)}")
            params.forEach { (key, value) -> if (value != null) add("$key=${PiApi.encode(value.toString())}") }
        }
        return "/api/files/$encoded?" + query.joinToString("&")
    }

    /** Web: getRelativeFilePath. */
    fun relative(path: String, cwd: String): String {
        if (cwd.isEmpty()) return path
        val base = cwd.trimEnd('/')
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else path
    }

    fun isInside(candidate: String, root: String): Boolean {
        val file = normalize(candidate).trimEnd('/')
        val base = normalize(root).trimEnd('/')
        val insensitive = DRIVE.containsMatchIn(file) || DRIVE.containsMatchIn(base)
        val a = if (insensitive) file.lowercase(Locale.ROOT) else file
        val b = if (insensitive) base.lowercase(Locale.ROOT) else base
        return a == b || a.startsWith("$b/")
    }

    /** Web: atMention text for the composer (`@path ` or `@"path with spaces" `). */
    fun atMention(path: String, isDir: Boolean): String {
        val value = if (isDir) "$path/" else path
        return if (' ' in value) "@\"$value\" " else "@$value "
    }

    /** Web: buildFileLineMentionText — `@path:12 ` or `@path:12-18 `. */
    fun lineMention(path: String, first: Int, last: Int): String {
        val ref = if (' ' in path) "@\"$path\"" else "@$path"
        val range = if (first == last) ":$first" else ":${minOf(first, last)}-${maxOf(first, last)}"
        return "$ref$range "
    }

    /** Web: resolveLocalFilePath — tool arguments are paths, not hrefs (no URL or `:line` parsing). */
    fun resolveToolPath(filePath: String?, baseDir: String?): String? {
        if (filePath.isNullOrEmpty()) return null
        val windows = DRIVE_ANY_SLASH.containsMatchIn(filePath) || filePath.startsWith("\\\\") ||
            (baseDir != null && (DRIVE_ANY_SLASH.containsMatchIn(baseDir) || baseDir.startsWith("\\\\")))
        fun slashes(value: String) = if (windows) value.replace('\\', '/') else value
        val path = slashes(filePath)
        val base = baseDir?.takeIf { it.isNotEmpty() }?.let { slashes(it).trimEnd('/') }
        val candidate = when {
            DRIVE.containsMatchIn(path) || path.startsWith("//") -> path
            path.startsWith("/") -> {
                val windowsRoot = base?.let {
                    Regex("^([a-zA-Z]:)(?:/|$)").find(it)?.groupValues?.get(1)
                        ?: Regex("^(//[^/]+/[^/]+)(?:/|$)").find(it)?.groupValues?.get(1)
                }
                if (windowsRoot != null) windowsRoot + path else path
            }
            base == null -> return null
            else -> "$base/$path"
        }
        return normalize(candidate)
    }

    /** Web: resolveLocalFileHref — a markdown link that points at a local file, or null. */
    fun resolveHref(href: String?, baseDir: String?, relativeRoot: String? = baseDir): String? {
        if (href.isNullOrEmpty()) return null
        val clean = href.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return null

        val decoded = safeDecode(clean)
        val backslashUnc = decoded.startsWith("\\\\")
        val normalized = normalizeSlashes(decoded)
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.startsWith("/api/") || lower.startsWith("/_next/")) return null
        if (!backslashUnc && normalized.startsWith("//")) return null
        if (SCHEME.containsMatchIn(normalized) && !lower.startsWith("file:") && !DRIVE.containsMatchIn(normalized)) return null

        var relative = false
        val candidate = when {
            lower.startsWith("file:") -> fileUrlToPath(clean)
            DRIVE.containsMatchIn(normalized) || normalized.startsWith("/") -> normalized
            !baseDir.isNullOrEmpty() && looksLikeRelativeFileHref(normalized) -> {
                relative = true
                "${normalizeSlashes(baseDir).trimEnd('/')}/$normalized"
            }
            else -> null
        } ?: return null

        val filePath = normalize(candidate).replace(LINE_SUFFIX, "")
        if (relative && !relativeRoot.isNullOrEmpty() && !isInside(filePath, relativeRoot)) return null
        return filePath
    }

    private fun looksLikeRelativeFileHref(href: String): Boolean {
        if (href.startsWith("#") || href.startsWith("?")) return false
        if (href.startsWith("./") || href.startsWith("../")) return true
        if ('/' in href) return true
        return RELATIVE_FILE_NAME.containsMatchIn(href)
    }

    private fun fileUrlToPath(href: String): String? = runCatching {
        val uri = URI(href)
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val host = uri.host
        when {
            !host.isNullOrEmpty() -> "//$host${if (path.startsWith("/")) path else "/$path"}"
            Regex("^/[a-zA-Z]:/").containsMatchIn(path) -> path.substring(1)
            else -> path
        }
    }.getOrNull()

    // decodeURIComponent keeps '+'; URLDecoder would turn it into a space.
    private fun safeDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun normalizeSlashes(path: String): String =
        if (DRIVE_ANY_SLASH.containsMatchIn(path) || path.startsWith("\\\\")) path.replace('\\', '/') else path

    /** Web: normalizeLocalPath — resolves `.` and `..`, keeps the leading slash. */
    fun normalize(path: String): String {
        val normalized = normalizeSlashes(path)
        val drive = DRIVE.containsMatchIn(normalized)
        val unc = normalized.startsWith("//")
        val leadingSlash = normalized.startsWith("/") && !drive && !unc
        val parts = ArrayList<String>()
        for (part in normalized.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") {
                if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex)
                else if (!leadingSlash && !drive && !unc) parts += part
                continue
            }
            parts += part
        }
        val joined = parts.joinToString("/")
        return when {
            drive -> joined
            unc -> "//$joined"
            leadingSlash -> "/$joined"
            else -> joined
        }
    }
}
