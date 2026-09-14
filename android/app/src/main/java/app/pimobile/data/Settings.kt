package app.pimobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverStore by preferencesDataStore("server")

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

    val config: Flow<ServerConfig> = context.serverStore.data.map {
        ServerConfig(it[urlKey].orEmpty(), it[passwordKey].orEmpty())
    }

    suspend fun save(config: ServerConfig) {
        context.serverStore.edit {
            it[urlKey] = config.baseUrl
            it[passwordKey] = config.password
        }
    }
}
