package app.pimobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverStore by preferencesDataStore("server")
private val Context.chatStore by preferencesDataStore("chat")

data class ServerConfig(
    val baseUrl: String = "",
    val password: String = "",
    /** WebSocket ASR server for voice dictation; empty disables the feature. */
    val asrUrl: String = DEFAULT_ASR_URL,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /** Default voice-dictation server (laptop, Nemotron 3.5 ASR). */
        const val DEFAULT_ASR_URL = "ws://192.168.1.56:8000/ws"

        /** Accepts "192.168.1.5:30141", "http://host:30141/", "https://pi.example" … */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "http://$trimmed"
        }

        /** Accepts "ws://host:8000", "wss://host:8000", or bare "host:8000". */
        fun normalizeAsrUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) trimmed
            else "ws://$trimmed"
        }
    }
}

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("base_url")
    private val passwordKey = stringPreferencesKey("password")
    private val asrUrlKey = stringPreferencesKey("asr_url")
    private val lastCwdKey = stringPreferencesKey("last_cwd")

    val config: Flow<ServerConfig> = context.serverStore.data.map {
        // A missing key means "use the default"; an explicitly saved empty string disables dictation.
        ServerConfig(
            baseUrl = it[urlKey].orEmpty(),
            password = it[passwordKey].orEmpty(),
            asrUrl = it[asrUrlKey] ?: ServerConfig.DEFAULT_ASR_URL,
        )
    }

    /** Last chat cwd, so the assistant trigger can open a fresh session there. */
    val lastCwd: Flow<String> = context.chatStore.data.map { it[lastCwdKey].orEmpty() }

    suspend fun save(config: ServerConfig) {
        context.serverStore.edit {
            it[urlKey] = config.baseUrl
            it[passwordKey] = config.password
            it[asrUrlKey] = config.asrUrl
        }
    }

    suspend fun saveLastCwd(cwd: String) {
        context.chatStore.edit { it[lastCwdKey] = cwd }
    }
}
