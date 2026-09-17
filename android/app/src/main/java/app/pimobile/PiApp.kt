package app.pimobile

import android.app.Application
import app.pimobile.data.PiApi
import app.pimobile.data.SettingsStore
import app.pimobile.data.TtsPlayer
import app.pimobile.notify.Notifications
import app.pimobile.notify.RunWatcherService
import app.pimobile.ui.markdown.disablePreciseGlyphBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Manual DI container: one API client, one settings store and one TTS player per process. */
class PiApp : Application() {
    val api = PiApi()
    lateinit var settings: SettingsStore
        private set
    /** Initialized lazily: [settings] is assigned in [onCreate]. */
    val tts by lazy { TtsPlayer(this, settings) }

    /** Session ids blocked on a user-input dialog; maintained by [RunWatcherService]. */
    val waitingSessionIds = MutableStateFlow<Set<String>>(emptySet())

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        disablePreciseGlyphBounds()
        settings = SettingsStore(this)
        Notifications.createChannels(this)
        // Tiny preferences read; needed synchronously to pick the start screen.
        api.config = runBlocking { settings.config.first() }
        tts.config = api.config
        appScope.launch { settings.config.collect { api.config = it; tts.config = it } }
    }

    /** Called whenever a run is seen while the UI is up; the watcher outlives the UI. */
    fun onRunActive() = RunWatcherService.start(this)
}
