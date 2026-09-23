package app.pimobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizationTest {

    // --- Server URL Normalization ---

    @Test
    fun `server url empty or blank returns empty string`() {
        assertEquals("", ServerConfig.normalizeUrl(""))
        assertEquals("", ServerConfig.normalizeUrl("   "))
        assertEquals("", ServerConfig.normalizeUrl(" \t\n "))
    }

    @Test
    fun `server url prepends http when scheme is missing`() {
        assertEquals("http://192.168.1.155:30141", ServerConfig.normalizeUrl("192.168.1.155:30141"))
        assertEquals("http://localhost:3000", ServerConfig.normalizeUrl("localhost:3000"))
        assertEquals("http://pi.local", ServerConfig.normalizeUrl("pi.local"))
    }

    @Test
    fun `server url preserves existing http and https schemes`() {
        assertEquals("http://192.168.1.155:30141", ServerConfig.normalizeUrl("http://192.168.1.155:30141"))
        assertEquals("https://pi.example.com", ServerConfig.normalizeUrl("https://pi.example.com"))
        assertEquals("https://pi.example.com:8443", ServerConfig.normalizeUrl("https://pi.example.com:8443"))
    }

    @Test
    fun `server url trims surrounding whitespace and trailing slashes`() {
        assertEquals("http://192.168.1.155:30141", ServerConfig.normalizeUrl("  http://192.168.1.155:30141/  "))
        assertEquals("http://192.168.1.155:30141", ServerConfig.normalizeUrl("192.168.1.155:30141///"))
        assertEquals("https://pi.example.com/subpath", ServerConfig.normalizeUrl("https://pi.example.com/subpath/"))
    }

    // --- ASR URL Normalization ---

    @Test
    fun `asr url empty or blank returns empty string`() {
        assertEquals("", ServerConfig.normalizeAsrUrl(""))
        assertEquals("", ServerConfig.normalizeAsrUrl("  "))
    }

    @Test
    fun `asr url prepends ws when scheme is missing`() {
        assertEquals("ws://192.168.1.56:8000/ws", ServerConfig.normalizeAsrUrl("192.168.1.56:8000/ws"))
        assertEquals("ws://localhost:8000/ws", ServerConfig.normalizeAsrUrl("localhost:8000/ws"))
    }

    @Test
    fun `asr url preserves existing ws and wss schemes`() {
        assertEquals("ws://192.168.1.56:8000/ws", ServerConfig.normalizeAsrUrl("ws://192.168.1.56:8000/ws"))
        assertEquals("wss://asr.example.com/ws", ServerConfig.normalizeAsrUrl("wss://asr.example.com/ws"))
    }

    @Test
    fun `asr url converts http to ws and https to wss`() {
        assertEquals("ws://192.168.1.56:8000/ws", ServerConfig.normalizeAsrUrl("http://192.168.1.56:8000/ws"))
        assertEquals("wss://asr.example.com/ws", ServerConfig.normalizeAsrUrl("https://asr.example.com/ws"))
    }

    @Test
    fun `asr url trims whitespace and trailing slashes`() {
        assertEquals("ws://192.168.1.56:8000/ws", ServerConfig.normalizeAsrUrl("  ws://192.168.1.56:8000/ws/  "))
        assertEquals("wss://asr.example.com/ws", ServerConfig.normalizeAsrUrl("https://asr.example.com/ws///"))
    }

    // --- TTS Endpoint URL Resolution (TtsClient.buildEndpoint) ---

    @Test
    fun `tts endpoint empty or blank returns empty string`() {
        assertEquals("", TtsClient.buildEndpoint(""))
        assertEquals("", TtsClient.buildEndpoint("   "))
    }

    @Test
    fun `tts endpoint resolves standard base url to v1 audio speech`() {
        assertEquals("http://192.168.1.56:8880/v1/audio/speech", TtsClient.buildEndpoint("http://192.168.1.56:8880"))
        assertEquals("http://192.168.1.56:8880/v1/audio/speech", TtsClient.buildEndpoint("192.168.1.56:8880"))
        assertEquals("http://localhost:8880/v1/audio/speech", TtsClient.buildEndpoint("localhost:8880/"))
    }

    @Test
    fun `tts endpoint avoids duplicate v1 when base ends with v1`() {
        assertEquals("https://api.openai.com/v1/audio/speech", TtsClient.buildEndpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/audio/speech", TtsClient.buildEndpoint("https://api.openai.com/v1/"))
        assertEquals("http://192.168.1.56:8880/v1/audio/speech", TtsClient.buildEndpoint("http://192.168.1.56:8880/v1"))
    }

    @Test
    fun `tts endpoint leaves full v1 audio speech path intact`() {
        assertEquals("http://192.168.1.56:8880/v1/audio/speech", TtsClient.buildEndpoint("http://192.168.1.56:8880/v1/audio/speech"))
        assertEquals("http://192.168.1.56:8880/v1/audio/speech", TtsClient.buildEndpoint("http://192.168.1.56:8880/v1/audio/speech/"))
        assertEquals("https://api.openai.com/v1/audio/speech", TtsClient.buildEndpoint("https://api.openai.com/v1/audio/speech"))
    }

    @Test
    fun `tts endpoint preserves custom subpaths and ports`() {
        assertEquals("https://proxy.example.com/tts/v1/audio/speech", TtsClient.buildEndpoint("https://proxy.example.com/tts"))
        assertEquals("https://proxy.example.com/tts/v1/audio/speech", TtsClient.buildEndpoint("https://proxy.example.com/tts/v1"))
    }
}
