package app.pimobile.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Base64 image as pi-web sends it: `{type: "image", data, mimeType}`. */
data class ImagePayload(val data: String, val mimeType: String)

/** An image waiting in the composer. [thumbnail] is a small decoded copy for the strip. */
data class AttachedImage(val id: Long, val payload: ImagePayload, val thumbnail: Bitmap?)

class ImageTooLargeException : IOException("Images must be ${ImageAttachments.MAX_BYTES / (1024 * 1024)} MB or smaller")
class UnsupportedImageException : IOException("Unsupported image format")

/**
 * Mirrors pi-web's composer rules (lib/image-attachments.ts, ChatInput.compressImageFile):
 * at most 10 images of 10 MB each; files over 1 MB (except GIF) are re-encoded as
 * JPEG q85 with the long side capped at 1024 px, keeping the original when that
 * isn't smaller. Formats the model APIs don't accept (HEIC from phone cameras)
 * are always re-encoded.
 */
object ImageAttachments {
    const val MAX_IMAGES = 10
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val COMPRESS_THRESHOLD_BYTES = 1024 * 1024
    private const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 85
    private const val THUMBNAIL_PX = 192
    private val MODEL_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    fun load(resolver: ContentResolver, uri: Uri, id: Long): AttachedImage {
        val mimeType = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
        if (!mimeType.startsWith("image/")) throw UnsupportedImageException()
        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (declaredSize != null && declaredSize > MAX_BYTES) throw ImageTooLargeException()
        val bytes = resolver.openInputStream(uri)?.use(::readCapped) ?: throw IOException("Could not read the image")

        val payload = encode(bytes, mimeType)
        val sent = if (payload.data.length == base64Length(bytes.size)) bytes else Base64.decode(payload.data, Base64.NO_WRAP)
        return AttachedImage(id, payload, decode(sent, THUMBNAIL_PX))
    }

    private fun encode(bytes: ByteArray, mimeType: String): ImagePayload {
        val mustTranscode = mimeType !in MODEL_TYPES
        val shouldCompress = bytes.size > COMPRESS_THRESHOLD_BYTES && mimeType != "image/gif"
        val original = ImagePayload(Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
        if (!mustTranscode && !shouldCompress) return original

        val bitmap = decode(bytes, MAX_SIDE) ?: if (mustTranscode) throw UnsupportedImageException() else return original
        val jpeg = ByteArrayOutputStream().use { out ->
            // JPEG has no alpha: flatten onto white, as the web canvas does.
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            flat.recycle()
            out.toByteArray()
        }
        bitmap.recycle()
        return if (mustTranscode || jpeg.size < bytes.size) {
            ImagePayload(Base64.encodeToString(jpeg, Base64.NO_WRAP), "image/jpeg")
        } else original
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw ImageTooLargeException()
        }
        return out.toByteArray()
    }

    private fun base64Length(bytes: Int) = (bytes + 2) / 3 * 4

    /** Decodes with the long side capped at [maxSide] px and EXIF orientation applied. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val matrix = Matrix()
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        }
        if (matrix.isIdentity) return decoded
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    // Decoded message images, keyed by payload identity and size; bounded to ~24 MB.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Blocking: call off the main thread. */
    fun decodeBase64(data: String, maxSide: Int): Bitmap? {
        val key = "$maxSide:${data.length}:${data.hashCode()}"
        cache.get(key)?.let { return it }
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
        return decode(bytes, maxSide)?.also { cache.put(key, it) }
    }

    fun aspectRatio(bitmap: Bitmap): Float =
        if (bitmap.height == 0) 1f else (bitmap.width.toFloat() / bitmap.height).let { (it * 1000).roundToInt() / 1000f }
}
