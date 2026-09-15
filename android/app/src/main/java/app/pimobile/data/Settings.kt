package app.pimobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverStore by preferencesDataStore("server")
private val Context.chatStore by preferencesDataStore("chat")

data class ServerConfig(val baseUrl: String = "", val password: String = "") {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /** Accepts "192.168.1.5:30141", "http://host:30141/", "https://pi.example" … */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "http://$trimmed"
        }
    }
}

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("base_url")
    private val passwordKey = stringPreferencesKey("password")
    private val lastCwdKey = stringPreferencesKey("last_cwd")

    val config: Flow<ServerConfig> = context.serverStore.data.map {
        ServerConfig(it[urlKey].orEmpty(), it[passwordKey].orEmpty())
    }

    /** Last chat cwd, so the assistant trigger can open a fresh session there. */
    val lastCwd: Flow<String> = context.chatStore.data.map { it[lastCwdKey].orEmpty() }

    suspend fun save(config: ServerConfig) {
        context.serverStore.edit {
            it[urlKey] = config.baseUrl
            it[passwordKey] = config.password
        }
    }

    suspend fun saveLastCwd(cwd: String) {
        context.chatStore.edit { it[lastCwdKey] = cwd }
    }
}
