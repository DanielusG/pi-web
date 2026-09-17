package app.pimobile.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.pimobile.MainActivity
import app.pimobile.data.TtsSessionHolder
import app.pimobile.media.TtsDataSource

/**
 * Foreground service that plays synthesized TTS audio: an ExoPlayer behind a
 * MediaSession, so playback survives background and screen-off, and the system
 * shows the media notification with lockscreen controls.
 */
class TtsPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val httpClient = TtsDataSource.httpClient()

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    stopPlayback()
                }
            }
        })
        player = p
        val s = MediaSession.Builder(this, p)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val p = player
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
            }
            ACTION_PLAY -> if (p != null) {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1f)
                val url = intent.getStringExtra(EXTRA_URL)
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                val metadata = MediaMetadata.Builder()
                    .setTitle(title.ifBlank { "Pi Mobile" })
                    .setArtist("Pi Mobile")
                    .build()
                val request = requestId?.let { TtsSessionHolder.get(it) }
                if (url != null && request != null) {
                    val factory = TtsDataSource.Factory(httpClient, request)
                    val item = MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(MimeTypes.AUDIO_MPEG)
                        .setMediaMetadata(metadata)
                        .build()
                    p.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(item))
                    p.playbackParameters = PlaybackParameters(speed, 1f)
                    p.prepare()
                    p.playWhenReady = true
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.let { removeSession(it) }
        session?.release()
        player?.release()
        session = null
        player = null
        TtsSessionHolder.clear()
        super.onDestroy()
    }

    private fun stopPlayback() {
        player?.stop()
        TtsSessionHolder.clear()
        stopSelf()
    }

    companion object {
        const val ACTION_PLAY = "app.pimobile.action.PLAY"
        const val ACTION_STOP = "app.pimobile.action.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_URL = "url"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
