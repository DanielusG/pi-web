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
    /** OpenAI-compatible TTS server (Kokoro) for "Listen"; empty disables the feature. */
    val ttsUrl: String = DEFAULT_TTS_URL,
    /** TTS model id sent in the request; blank lets the server pick its default. */
    val ttsModel: String = DEFAULT_TTS_MODEL,
    /** TTS voice id sent in the request; blank lets the server pick its default. */
    val ttsVoice: String = DEFAULT_TTS_VOICE,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /** Default voice-dictation server (laptop, Nemotron 3.5 ASR). */
        const val DEFAULT_ASR_URL = "ws://192.168.1.56:8000/ws"

        /** Default TTS server (laptop, Kokoro-FastAPI). */
        const val DEFAULT_TTS_URL = "http://192.168.1.56:8880"
        const val DEFAULT_TTS_MODEL = "kokoro"
        const val DEFAULT_TTS_VOICE = "if_sara"

        /** Accepts "192.168.1.5:30141", "http://host:30141/", "https://pi.example" … */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "http://$trimmed"
        }

        /** Accepts "ws://host:8000/ws", "wss://host:8000/ws", "http(s)://host", or bare "host:8000/ws". */
        fun normalizeAsrUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return when {
                trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
                trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
                trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
                else -> "ws://$trimmed"
            }
        }

        /** Accepts "192.168.1.56:8880", "http://host:8880", "https://api.openai.com/v1" … */
        fun normalizeTtsUrl(raw: String): String = normalizeUrl(raw)
    }
}

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("base_url")
    private val passwordKey = stringPreferencesKey("password")
    private val asrUrlKey = stringPreferencesKey("asr_url")
    private val ttsUrlKey = stringPreferencesKey("tts_url")
    private val ttsModelKey = stringPreferencesKey("tts_model")
    private val ttsVoiceKey = stringPreferencesKey("tts_voice")
    private val ttsSpeedKey = stringPreferencesKey("tts_speed")
    private val lastCwdKey = stringPreferencesKey("last_cwd")
    private val assistCwdKey = stringPreferencesKey("assist_cwd")

    val config: Flow<ServerConfig> = context.serverStore.data.map {
        // A missing key means "use the default"; an explicitly saved empty string disables the feature.
        ServerConfig(
            baseUrl = it[urlKey].orEmpty(),
            password = it[passwordKey].orEmpty(),
            asrUrl = it[asrUrlKey] ?: ServerConfig.DEFAULT_ASR_URL,
            ttsUrl = it[ttsUrlKey] ?: ServerConfig.DEFAULT_TTS_URL,
            ttsModel = it[ttsModelKey] ?: ServerConfig.DEFAULT_TTS_MODEL,
            ttsVoice = it[ttsVoiceKey] ?: ServerConfig.DEFAULT_TTS_VOICE,
        )
    }

    /** Last chat cwd, so the assistant trigger can open a fresh session there. */
    val lastCwd: Flow<String> = context.chatStore.data.map { it[lastCwdKey].orEmpty() }

    /** Project chosen in settings for the assistant trigger; empty = follow [lastCwd]. */
    val assistCwd: Flow<String> = context.chatStore.data.map { it[assistCwdKey].orEmpty() }

    /** Where the assistant trigger opens a fresh session: the chosen project, else the last cwd. */
    val assistLaunchCwd: Flow<String> = context.chatStore.data.map {
        it[assistCwdKey]?.takeIf(String::isNotBlank) ?: it[lastCwdKey].orEmpty()
    }

    /** Last TTS playback speed (e.g. 1.2f); missing key falls back to 1.0. */
    val ttsSpeed: Flow<Float> = context.chatStore.data.map { it[ttsSpeedKey]?.toFloatOrNull() ?: 1f }

    suspend fun saveTtsSpeed(speed: Float) {
        context.chatStore.edit { it[ttsSpeedKey] = speed.toString() }
    }

    suspend fun save(config: ServerConfig) {
        context.serverStore.edit {
            it[urlKey] = config.baseUrl
            it[passwordKey] = config.password
            it[asrUrlKey] = config.asrUrl
            it[ttsUrlKey] = config.ttsUrl
            it[ttsModelKey] = config.ttsModel
            it[ttsVoiceKey] = config.ttsVoice
        }
    }

    suspend fun saveLastCwd(cwd: String) {
        context.chatStore.edit { it[lastCwdKey] = cwd }
    }

    /** Blank clears the choice, so the assistant trigger falls back to the last cwd. */
    suspend fun saveAssistCwd(cwd: String) {
        context.chatStore.edit { if (cwd.isBlank()) it.remove(assistCwdKey) else it[assistCwdKey] = cwd }
    }
}
