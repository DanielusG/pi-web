package app.pimobile.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Voice-dictation client for the Nemotron 3.5 ASR streaming server.
 *
 * WebSocket JSON protocol (server at [asrBaseUrl], or [asrBaseUrl]/ws when the
 * configured URL doesn't already include the path):
 *   -> {"type":"start","language":"it-IT"}              <- {"type":"ready"}
 *   -> {"type":"audio","data":"<b64 int16 mono 16kHz>"}  <- {"type":"text","text":"<token>"}
 *   -> {"type":"stop"}                                  <- {"type":"done","full_text":"..."}
 *
 * All callbacks run on the main thread. One session per [start]; [onFinished] is
 * always called exactly once (full transcript, or null on failure) and [onError]
 * at most once, before it, with a user-visible message.
 */
class AsrClient(private val context: Context, private val asrBaseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Serializes audio sends with stop(): an audio message can never be sent
    // after the 'stop' message, no matter how the two threads interleave.
    private val sendLock = Any()

    private var webSocket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null

    private var cbToken: ((String) -> Unit)? = null
    private var cbFinished: ((String?) -> Unit)? = null
    private var cbError: ((String) -> Unit)? = null

    @Volatile private var readyReceived = false
    @Volatile private var stopping = false
    @Volatile private var finished = false
    @Volatile private var errorSent = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Connect and start a dictation session. Returns false if one is already running. */
    fun start(
        onToken: (String) -> Unit,
        onFinished: (String?) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (webSocket != null) return false
        cbToken = onToken
        cbFinished = onFinished
        cbError = onError
        if (!hasPermission()) {
            onError("Microphone permission not granted")
            onFinished(null)
            return false
        }
        requestAudioFocus()
        // The configured URL may already include the /ws path; don't double it.
        val base = asrBaseUrl.trim().trimEnd('/')
        val wsUrl = if (base.endsWith("/ws")) base else "$base/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // OkHttp buffers sends made before the connection is open, in order.
                    webSocket.send("""{"type":"start","language":"it-IT"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (msg.optString("type")) {
                        "ready" -> readyReceived = true
                        // Post to the main thread: the caller mutates Compose state.
                        "text" -> mainHandler.post { onToken(msg.optString("text")) }
                        "done" -> finish(msg.optString("full_text"))
                        "error" -> fail(msg.optString("message").ifEmpty { "ASR server error" })
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail("Cannot reach the ASR server")
                }
            },
        )
        startAudio()
        // The session never became ready: the server is down or the model failed to load.
        mainHandler.postDelayed({
            if (!readyReceived && !finished) fail("ASR server did not respond")
        }, 3000)
        // Safety: never hold the mic longer than this (e.g. a lost release event).
        mainHandler.postDelayed({
            if (!finished) stop()
        }, MAX_SESSION_MS)
        return true
    }

    /** End the session: the server flushes the held-back tokens, then `done` arrives. */
    fun stop() {
        synchronized(sendLock) {
            if (webSocket == null || stopping) return
            stopping = true
            webSocket?.send("""{"type":"stop"}""")
        }
        // The silence flush takes a second or two; never hold the mic forever.
        mainHandler.postDelayed({ finish(null) }, 15000)
    }

    // --- audio ---

    private fun startAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            fail("Unsupported audio configuration")
            return
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Could not open the microphone")
            return
        }
        recorder = rec
        rec.startRecording()
        val thread = Thread {
            val block = ByteArray(SAMPLE_RATE / 10 * 2) // 100 ms of 16-bit mono
            while (!stopping && !finished) {
                val n = rec.read(block, 0, block.size)
                if (n > 0) {
                    val b64 = Base64.encodeToString(block.copyOf(n), Base64.NO_WRAP)
                    val msg = """{"type":"audio","data":"$b64"}"""
                    synchronized(sendLock) {
                        if (!stopping) webSocket?.send(msg)
                    }
                } else if (n < 0) {
                    break
                }
            }
        }
        thread.isDaemon = true
        audioThread = thread
        thread.start()
    }

    private fun stopAudio() {
        stopping = true
        audioThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        audioThread = null
        recorder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        recorder = null
    }

    // --- audio focus ---

    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest?.let { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    // --- lifecycle ---

    private fun fail(message: String) {
        if (finished) return
        if (!errorSent) {
            errorSent = true
            mainHandler.post { cbError?.invoke(message) }
        }
        finish(null)
    }

    private fun finish(fullText: String?) {
        if (finished) return
        finished = true
        stopAudio()
        webSocket?.close(1000, null)
        webSocket = null
        abandonAudioFocus()
        mainHandler.post { cbFinished?.invoke(fullText) }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MAX_SESSION_MS = 60_000L
    }
}
