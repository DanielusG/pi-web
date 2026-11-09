package app.pimobile.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A document waiting in the composer: a private copy in the app cache, so a
 * failed send can keep it after the picker's read grant has lapsed. It goes to
 * the server only at send time (POST /api/sessions/[id]/attachments).
 */
data class AttachedFile(val id: Long, val name: String, val size: Long, val mimeType: String?, val file: File)

class FileTooLargeException : IOException("Files must be ${FileAttachments.MAX_BYTES / (1024 * 1024)} MB or smaller")

/** The server's limits (lib/session-attachments.ts) with the composer's own count, as for images. */
object FileAttachments {
    const val MAX_FILES = 10
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    private const val STALE_MS = 24L * 60 * 60 * 1000

    /** Blocking: copies [uri] into [dir]. Copies left a day ago by a killed process go first. */
    fun load(resolver: ContentResolver, uri: Uri, id: Long, dir: File): AttachedFile {
        var name: String? = null
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0)
                if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
            }
        }
        if ((declaredSize ?: 0) > MAX_BYTES) throw FileTooLargeException()

        dir.mkdirs()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_MS) it.delete() }
        val copy = File(dir, UUID.randomUUID().toString())
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not read the file")
            input.use { source ->
                copy.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw FileTooLargeException()
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return AttachedFile(id, displayName, copy.length(), resolver.getType(uri), copy)
    }
}
