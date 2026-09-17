package app.pimobile.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds OpenAI-compatible TTS requests (`POST /v1/audio/speech`).
 */
object TtsClient {
    data class Request(val url: String, val body: String)

    /**
     * Resolves the full `/v1/audio/speech` endpoint from a user-provided base or endpoint URL.
     * Handles bare hosts, trailing slashes, existing `/v1`, and full `/v1/audio/speech` paths.
     */
    fun buildEndpoint(baseUrl: String): String {
        val normalized = ServerConfig.normalizeTtsUrl(baseUrl)
        if (normalized.isEmpty()) return ""
        return when {
            normalized.endsWith("/v1/audio/speech") -> normalized
            normalized.endsWith("/v1") -> "$normalized/audio/speech"
            else -> "$normalized/v1/audio/speech"
        }
    }

    fun requestFor(config: ServerConfig, text: String): Request {
        val url = buildEndpoint(config.ttsUrl)
        val body = buildJsonObject {
            if (config.ttsModel.isNotBlank()) put("model", config.ttsModel)
            put("input", text)
            if (config.ttsVoice.isNotBlank()) put("voice", config.ttsVoice)
            put("response_format", "mp3")
            put("stream", true)
        }.toString()
        return Request(url, body)
    }
}
