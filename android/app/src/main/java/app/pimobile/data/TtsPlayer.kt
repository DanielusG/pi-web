package app.pimobile.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pimobile.service.TtsPlaybackService
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** UI state of the TTS feature. */
sealed interface TtsUiState {
    data object Idle : TtsUiState

    /** Synthesis is in flight; the first bytes are not playing yet. */
    data class Loading(val key: String, val title: String) : TtsUiState

    data class Active(
        val key: String,
        val title: String,
        val isPlaying: Boolean,
        val speed: Float,
    ) : TtsUiState
}

/**
 * In-memory holder for active [TtsClient.Request] objects.
 * Avoids passing large JSON payloads across IPC via Intent extras, which risks
 * [android.os.TransactionTooLargeException] on lengthy assistant responses.
 */
object TtsSessionHolder {
    @Volatile
    private var current: Pair<String, TtsClient.Request>? = null

    fun put(requestId: String, request: TtsClient.Request) {
        current = requestId to request
    }

    fun get(requestId: String): TtsClient.Request? =
        current?.takeIf { it.first == requestId }?.second

    fun clear() {
        current = null
    }
}

/**
 * App-level TTS coordinator: sanitizes the text, streams audio through [TtsPlaybackService]
 * and drives it through a [MediaController]. One instance per process, owned by [app.pimobile.PiApp].
 */
class TtsPlayer(private val context: Context, private val settings: SettingsStore) {

    /** Kept in sync by PiApp from the settings flow. */
    var config: ServerConfig = ServerConfig()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<TtsUiState>(TtsUiState.Idle)
    val state: StateFlow<TtsUiState> = _state

    /** User-facing failure messages (shown as a snackbar by the chat screen). */
    var onError: ((String) -> Unit)? = null

    private var playJob: Job? = null
    private var controller: MediaController? = null
    private var activeKey: String? = null
    private var activeTitle: String = ""
    private var speed: Float = 1f

    init {
        scope.launch { speed = settings.ttsSpeed.first() }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            val s = _state.value
            when {
                s is TtsUiState.Loading && playing ->
                    _state.value = TtsUiState.Active(activeKey.orEmpty(), activeTitle, true, speed)
                s is TtsUiState.Active -> _state.value = s.copy(isPlaying = playing)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed = playbackParameters.speed
            val s = _state.value
            if (s is TtsUiState.Active) {
                _state.value = s.copy(speed = playbackParameters.speed)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // The service stops its player the moment it ends, so this controller may see
            // IDLE without ENDED. Both end the playback: stop() releases the controller,
            // letting the service leave the foreground and be destroyed right away.
            if (state == Player.STATE_ENDED || (state == Player.STATE_IDLE && _state.value is TtsUiState.Active)) stop()
        }

        override fun onPlayerError(error: PlaybackException) = fail(error)
    }

    private fun fail(error: PlaybackException) {
        onError?.invoke(
            when (error.errorCodeName) {
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                "ERROR_CODE_IO_NETWORK_CONNECTION_LOST",
                "ERROR_CODE_IO_NETWORK_TIMEOUT",
                -> "TTS server unreachable."
                else -> "TTS playback failed (${error.errorCodeName})."
            },
        )
        stop()
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@TtsPlayer.controller == null) return
            this@TtsPlayer.controller = null
            _state.value = TtsUiState.Idle
        }
    }

    /** Synthesizes [rawText] via streaming and starts playback. */
    fun play(key: String, rawText: String, sessionTitle: String) {
        val cfg = config
        if (cfg.ttsUrl.isBlank()) {
            onError?.invoke("TTS is not configured (Settings → Text to speech).")
            return
        }
        val text = TtsText.sanitize(rawText)
        if (text.isBlank()) return

        playJob?.cancel()
        controller?.stop()
        activeKey = key
        activeTitle = preview(text)
        _state.value = TtsUiState.Loading(key, activeTitle)

        playJob = scope.launch {
            val requestId = UUID.randomUUID().toString()
            val request = TtsClient.requestFor(cfg, text)
            TtsSessionHolder.put(requestId, request)

            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_PLAY
                putExtra(TtsPlaybackService.EXTRA_TITLE, sessionTitle.ifBlank { "Pi Mobile" })
                putExtra(TtsPlaybackService.EXTRA_SPEED, speed)
                putExtra(TtsPlaybackService.EXTRA_URL, request.url)
                putExtra(TtsPlaybackService.EXTRA_REQUEST_ID, requestId)
            }
            context.startService(intent)

            val c = bindController()
            if (c != null) {
                // If playback already began before listener attachment, promote to Active immediately.
                if (c.isPlaying && _state.value is TtsUiState.Loading) {
                    _state.value = TtsUiState.Active(activeKey.orEmpty(), activeTitle, true, c.playbackParameters.speed)
                }
                // Likewise a failure before attachment (e.g. unreachable server) would never be reported.
                c.playerError?.let(::fail)
            } else {
                if (isActive) {
                    _state.value = TtsUiState.Idle
                    onError?.invoke("Unable to start TTS playback service.")
                }
            }
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Client-side rate change: no re-synthesis, like Telegram's speed menu. */
    fun setSpeed(value: Float) {
        speed = value
        val s = _state.value
        if (s is TtsUiState.Active) {
            _state.value = s.copy(speed = value)
        }
        controller?.setPlaybackSpeed(value)
        scope.launch { settings.saveTtsSpeed(value) }
    }

    /** Stops playback (or cancels a running synthesis) and clears the state. */
    fun stop() {
        playJob?.cancel()
        playJob = null
        TtsSessionHolder.clear()
        controller?.let {
            it.stop()
            it.release()
        }
        controller = null
        activeKey = null
        _state.value = TtsUiState.Idle
        runCatching {
            context.startService(Intent(context, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_STOP
            })
        }
    }

    private suspend fun bindController(): MediaController? {
        val existing = controller
        if (existing != null && existing.isConnected) return existing

        return try {
            val token = SessionToken(context, ComponentName(context, TtsPlaybackService::class.java))
            val future = MediaController.Builder(context, token)
                .setListener(controllerListener)
                .buildAsync()
            val c = withTimeout(5000L) {
                suspendCancellableCoroutine { cont ->
                    future.addListener({
                        try {
                            cont.resume(future.get())
                        } catch (e: ExecutionException) {
                            cont.resumeWithException(e.cause ?: e)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                    cont.invokeOnCancellation { future.cancel(true) }
                }
            }
            c.addListener(playerListener)
            controller = c
            c
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun preview(text: String): String {
        val flat = text.replace(PREVIEW_SPACES, " ").trim()
        return if (flat.length > 60) flat.take(60) + "…" else flat
    }

    companion object {
        private val PREVIEW_SPACES = Regex("\\s+")
    }
}
