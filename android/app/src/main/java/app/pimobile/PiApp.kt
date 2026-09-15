package app.pimobile

import android.app.Application
import app.pimobile.data.PiApi
import app.pimobile.data.SettingsStore
import app.pimobile.notify.Notifications
import app.pimobile.notify.RunWatcherService
import app.pimobile.ui.markdown.disablePreciseGlyphBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Manual DI container: one API client and one settings store per process. */
class PiApp : Application() {
    val api = PiApi()
    lateinit var settings: SettingsStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        disablePreciseGlyphBounds()
        settings = SettingsStore(this)
        Notifications.createChannels(this)
        // Tiny preferences read; needed synchronously to pick the start screen.
        api.config = runBlocking { settings.config.first() }
        appScope.launch { settings.config.collect { api.config = it } }
    }

    /** Called whenever a run is seen while the UI is up; the watcher outlives the UI. */
    fun onRunActive() = RunWatcherService.start(this)
}
