package app.pimobile.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import app.pimobile.data.TtsClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A [DataSource] that plays a TTS stream. Media3's HTTP sources only issue GET/HEAD
 * against the DataSpec URI (and [androidx.media3.exoplayer.source.ProgressiveMediaSource]
 * rebuilds the DataSpec from the URI alone), so this source issues the
 * OpenAI-compatible `POST /v1/audio/speech` itself and streams the response body.
 */
class TtsDataSource(
    private val client: OkHttpClient,
    private val request: TtsClient.Request,
) : BaseDataSource(true), DataSource {

    @Volatile private var call: okhttp3.Call? = null
    @Volatile private var source: okio.BufferedSource? = null
    @Volatile private var closed = false
    @Volatile private var currentUri: Uri? = null
    /** Set once [transferStarted] ran: [transferEnded] requires it. */
    @Volatile private var opened = false

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        closed = false
        currentUri = dataSpec.uri
        val position = dataSpec.position
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(request.body.toRequestBody("application/json".toMediaType()))
            .build()
        val httpCall = client.newCall(httpRequest)
        call = httpCall
        val response = httpCall.execute()
        if (!response.isSuccessful) {
            val errorDetails = response.body?.string()?.take(200)?.let { " - $it" }.orEmpty()
            response.close()
            throw IOException("TTS server error: HTTP ${response.code}$errorDetails")
        }
        val responseBody = response.body ?: run {
            response.close()
            throw IOException("TTS server returned empty body (HTTP ${response.code})")
        }
        val bodySource = responseBody.source()
        if (position > 0) {
            try {
                bodySource.skip(position)
            } catch (_: java.io.EOFException) {
                // Stream shorter than expected; the extractor resyncs on the next frame.
            }
        }
        source = bodySource
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(out: ByteArray, offset: Int, length: Int): Int {
        val s = source ?: throw IOException("Not opened")
        val n = s.read(out, offset, length)
        if (n > 0) {
            bytesTransferred(n)
        }
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            source?.close()
        } catch (_: IOException) {
        }
        call?.cancel()
        source = null
        call = null
        currentUri = null
        // After a failed open (e.g. TTS server unreachable) there is no transfer to end:
        // calling transferEnded() would throw and hide the open error from the player.
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Creates [TtsDataSource]es for the immutable [request]. */
    class Factory(
        private val client: OkHttpClient,
        private val request: TtsClient.Request,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TtsDataSource(client, request)
    }

    companion object {
        /** OkHttp client for the TTS stream: generous read timeout, chunks arrive steadily. */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
