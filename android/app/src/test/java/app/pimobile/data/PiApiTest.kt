package app.pimobile.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.zip.GZIPOutputStream

/**
 * Network-contract tests for [PiApi] against an in-process [MockWebServer]
 * (real localhost socket in the test JVM, canned responses — no external
 * server). The server half of the contract is covered by
 * lib/json-response.test.mjs on the pi-web side.
 */
class PiApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PiApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = PiApi()
        api.config = ServerConfig(baseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get sends Accept-Encoding gzip`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        runBlocking { api.get("/x") }

        assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun `get transparently decompresses gzip responses`() {
        val payload = """{"value":"hello gzip"}"""
        val gzipBody = Buffer().also { buf ->
            GZIPOutputStream(buf.outputStream()).use { it.write(payload.toByteArray()) }
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzipBody),
        )

        val result = runBlocking { api.get("/x") }

        assertEquals("hello gzip", (result as JsonObject).getValue("value").jsonPrimitive.content)
    }
}
