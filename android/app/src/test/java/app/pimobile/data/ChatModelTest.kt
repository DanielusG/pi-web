package app.pimobile.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * mergeSnapshot keeps a locally appended tail that a stale snapshot predates:
 * the GET response and the SSE message_end events are unordered on the wire,
 * and pi persists a message only after emitting its message_end.
 */
class ChatModelTest {

    private fun userJson(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun user(key: String, text: String, pending: Boolean = false) =
        LoadedMessage(key, null, userJson(text), pending)

    private fun assistant(key: String, text: String, entryId: String? = null, stopReason: String = "stop") =
        LoadedMessage(
            key,
            entryId,
            buildJsonObject {
                put("role", "assistant")
                put("provider", "p")
                put("model", "m")
                put("stopReason", stopReason)
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            },
        )

    // unchanged local list: the snapshot wins outright

    @Test
    fun unchangedListReturnsFresh() {
        val local = listOf(user("a", "old"))
        val fresh = listOf(user("a", "old"), user("b", "new"))
        assertEquals(fresh, Messages.mergeSnapshot(fresh, local, local))
    }

    // tail grew while the GET was in flight

    @Test
    fun keepsOptimisticTailNotInSnapshot() {
        val local = listOf(user("e1", "old"))
        val fresh = listOf(user("e1", "old"))
        val optimistic = user("pending-0", "follow-up", pending = true)
        val current = local + optimistic
        assertEquals(fresh + optimistic, Messages.mergeSnapshot(fresh, local, current))
    }

    @Test
    fun dedupesDeliveredUserAlreadyInSnapshot() {
        val local = listOf(user("e1", "old"))
        // Delivered via SSE: no entryId, same text as the persisted copy.
        val delivered = user("live-1", "follow-up")
        val fresh = listOf(user("e1", "old"), user("e2", "follow-up"))
        val current = local + delivered
        assertEquals(fresh, Messages.mergeSnapshot(fresh, local, current))
    }

    @Test
    fun dedupesAssistantByFingerprint() {
        val local = listOf(user("e1", "question"))
        val live = assistant("live-1", "hello")
        val fresh = listOf(user("e1", "question"), assistant("e2", "hello", entryId = "e2"))
        val current = local + live
        assertEquals(fresh, Messages.mergeSnapshot(fresh, local, current))
    }

    @Test
    fun keepsAssistantNotInSnapshot() {
        val local = listOf(user("e1", "question"))
        val live = assistant("live-1", "hello")
        val fresh = listOf(user("e1", "question"))
        val current = local + live
        assertEquals(fresh + live, Messages.mergeSnapshot(fresh, local, current))
    }

    @Test
    fun assistantFingerprintIgnoresEntryIdAndKey() {
        val live = assistant("live-1", "hello")
        val persisted = assistant("e2", "hello", entryId = "e2")
        assertEquals(Messages.assistantFingerprint(live.json), Messages.assistantFingerprint(persisted.json))
    }

    @Test
    fun assistantFingerprintDistinguishesTextAndStopReason() {
        val a = assistant("k", "hello")
        val b = assistant("k", "goodbye")
        val c = assistant("k", "hello", stopReason = "error")
        assertNotEquals(Messages.assistantFingerprint(a.json), Messages.assistantFingerprint(b.json))
        assertNotEquals(Messages.assistantFingerprint(a.json), Messages.assistantFingerprint(c.json))
    }

    // structural changes are not tail growth: the snapshot wins

    @Test
    fun prependFallsBackToFresh() {
        val local = listOf(user("e2", "b"))
        val fresh = listOf(user("e2", "b"))
        val current = listOf(user("e1", "a")) + local
        assertEquals(fresh, Messages.mergeSnapshot(fresh, local, current))
    }

    @Test
    fun removalFallsBackToFresh() {
        val local = listOf(user("e1", "a"), user("pending-0", "b", pending = true))
        val fresh = listOf(user("e1", "a"))
        val current = listOf(user("e1", "a"))
        assertEquals(fresh, Messages.mergeSnapshot(fresh, local, current))
    }

    // user identity

    @Test
    fun userKeyMatchesTextOnlyMessages() {
        assertEquals(Messages.userKey(userJson("hi")["content"]), Messages.userKey(userJson("hi")["content"]))
        assertNotEquals(Messages.userKey(userJson("hi")["content"]), Messages.userKey(userJson("bye")["content"]))
    }

    @Test
    fun userKeyDistinguishesImages() {
        val withImage = buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", "hi")
                }
                addJsonObject {
                    put("type", "image")
                    put("data", "AA==")
                    put("mimeType", "image/png")
                }
            }
        }
        assertNotEquals(Messages.userKey(userJson("hi")["content"]), Messages.userKey(withImage["content"]))
    }

    @Test
    fun userKeyAcceptsStringAndBlockContent() {
        val blocks = buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", "hi")
                }
            }
        }
        assertEquals(Messages.userKey(userJson("hi")["content"]), Messages.userKey(blocks["content"]))
    }

    @Test
    fun assemblerDoesNotDuplicateFirstChunkLeakedInSnapshot() {
        // The message_start snapshot may already carry the block's first delta
        // (pi's partial is the live response so far). *_start must reset the
        // block so the deltas are not appended a second time (upstream 002400d,
        // #835: "stop duplicating the first streamed chunk").
        val assembler = StreamingAssembler()
        assembler.start(
            buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "ok")
                    }
                }
            },
        )
        assembler.apply(buildJsonObject {
            put("type", "text_start")
            put("contentIndex", 0)
        })
        assembler.apply(buildJsonObject {
            put("type", "text_delta")
            put("contentIndex", 0)
            put("delta", "ok")
        })
        val blocks = assembler.blocks()
        assertEquals(1, blocks.size)
        assertEquals("ok", (blocks[0] as Block.Text).text)
        assembler.apply(buildJsonObject {
            put("type", "text_end")
            put("contentIndex", 0)
            put("content", "ok")
        })
        assertEquals("ok", (assembler.blocks()[0] as Block.Text).text)
    }
}
