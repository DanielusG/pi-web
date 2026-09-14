package app.pimobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(
    message: String,
    val status: Int,
    val code: String? = null,
) : IOException(message)

/**
 * Thin client for the pi-web HTTP API. Every response is handled as a
 * [JsonElement] tree: the backend passes pi SDK objects through verbatim, so
 * lenient tree access survives upstream shape changes better than strict DTOs.
 */
class PiApi {
    @Volatile
    var config: ServerConfig = ServerConfig()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // The server sends an SSE heartbeat every 30 s, so 75 s of silence means the
    // connection is half-open (typical after a mobile network switch).
    private val sseHttp = http.newBuilder()
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    suspend fun get(path: String): JsonElement = execute(request(path).get().build())

    suspend fun post(path: String, body: JsonObject): JsonElement = execute(
        request(path)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build(),
    )

    suspend fun delete(path: String): JsonElement = execute(request(path).delete().build())

    /** POST /api/agent/[id]; returns the `data` field of `{ success, data }`. */
    suspend fun command(sessionId: String, command: JsonObject): JsonElement {
        val body = post("/api/agent/${encode(sessionId)}", command)
        return (body as? JsonObject)?.get("data") ?: JsonNull
    }

    /**
     * GET /api/agent/[id]/events as a cold flow of `data:` payloads. The flow
     * completes when the server ends the stream and fails on network errors;
     * reconnect policy belongs to the caller.
     */
    fun events(sessionId: String): Flow<JsonObject> = callbackFlow {
        val call = sseHttp.newCall(
            request("/api/agent/${encode(sessionId)}/events")
                .header("Accept", "text/event-stream")
                .get()
                .build(),
        )
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw response.toApiException()
                    val source = response.body!!.source()
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> if (data.isNotEmpty()) {
                                val payload = data.toString()
                                data.clear()
                                runCatching { json.parseToJsonElement(payload).jsonObject }
                                    .onSuccess { send(it) }
                            }
                            line.startsWith(":") -> Unit // heartbeat / comment
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                        }
                    }
                }
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private fun request(path: String): Request.Builder {
        val cfg = config
        if (!cfg.isConfigured) throw ApiException("Server not configured", 0)
        val builder = Request.Builder().url(cfg.baseUrl + path)
        if (cfg.password.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic("pi", cfg.password))
        }
        return builder
    }

    // The body is read lazily from the socket, so the whole exchange must stay
    // off the main thread (await() resumes on the caller's dispatcher).
    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toApiException()
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    private fun Response.toApiException(): ApiException {
        val raw = runCatching { body?.string() }.getOrNull().orEmpty()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val serverMessage = obj?.get("error")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val message = when (code) {
            401 -> "Wrong password (HTTP 401)"
            403 -> "Server refused this host (HTTP 403). Use the IP address, or add the hostname to PI_WEB_ALLOWED_HOSTS."
            else -> serverMessage ?: raw.take(200).ifBlank { "HTTP $code" }
        }
        val errorCode = obj?.get("code")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        return ApiException(message, code, errorCode)
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
