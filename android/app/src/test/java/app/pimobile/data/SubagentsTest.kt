package app.pimobile.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Subagents.parse reads a GET /api/sessions/[id]/subagents response: the
 * session's direct subagent children only (the server filters and orders them).
 * The live `running` flag beats the file status, as on the web
 * (AgentSessionPanel); the client re-sorts running-first then newest-first.
 */
class SubagentsTest {

    private fun subagent(
        id: String,
        modified: String,
        running: Boolean = false,
        profile: String? = null,
        description: String? = null,
        status: String? = null,
        name: String? = null,
        firstMessage: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("modified", modified)
        put("running", running)
        if (profile != null) put("profile", profile)
        if (description != null) put("description", description)
        if (status != null) put("status", status)
        if (name != null) put("name", name)
        if (firstMessage != null) put("firstMessage", firstMessage)
    }

    private fun body(subagents: List<JsonObject>): JsonObject = buildJsonObject {
        putJsonArray("subagents") { subagents.forEach { add(it) } }
    }

    @Test
    fun liveRunningFlagBeatsFileStatus() {
        val fileRunning = subagent("a", "2026-01-01T01:00:00Z", running = true, status = "completed")
        val fileDone = subagent("b", "2026-01-01T02:00:00Z", running = false, status = "running")
        val parsed = Subagents.parse(body(listOf(fileRunning, fileDone)))
        assertEquals("running", parsed.first { it.id == "a" }.status)
        assertEquals(true, parsed.first { it.id == "a" }.running)
        // b is not live: the file status is kept as-is.
        assertEquals("running", parsed.first { it.id == "b" }.status)
        assertEquals(false, parsed.first { it.id == "b" }.running)
    }

    @Test
    fun sortsRunningFirstThenNewestFirst() {
        val oldRunning = subagent("a", "2026-01-01T01:00:00Z", running = true, status = "completed")
        val newerDone = subagent("b", "2026-01-01T03:00:00Z", running = false, status = "completed")
        val olderDone = subagent("c", "2026-01-01T02:00:00Z", running = false, status = "completed")
        // a is the live-running one despite being the oldest.
        val parsed = Subagents.parse(body(listOf(olderDone, newerDone, oldRunning)))
        assertEquals(listOf("a", "b", "c"), parsed.map { it.id })
    }

    @Test
    fun titleFallsBackDescriptionNameFirstMessageShortId() {
        val withDescription = subagent("a", "m", description = "do the thing", name = "n", firstMessage = "f")
        val withName = subagent("b", "m", name = "named", firstMessage = "f")
        val withFirstMessage = subagent("c", "m", firstMessage = "first message")
        val longId = "d".repeat(20)
        val bare = subagent(longId, "m")
        val parsed = Subagents.parse(body(listOf(withDescription, withName, withFirstMessage, bare)))
        assertEquals("do the thing", parsed.first { it.id == "a" }.title)
        assertEquals("named", parsed.first { it.id == "b" }.title)
        assertEquals("first message", parsed.first { it.id == "c" }.title)
        assertEquals("d".repeat(12), parsed.first { it.id == longId }.title)
    }

    @Test
    fun statusDefaultsToCompletedWhenAbsent() {
        val item = subagent("a", "m", running = false)
        val parsed = Subagents.parse(body(listOf(item)))
        assertEquals("completed", parsed.first().status)
    }

    @Test
    fun noSubagentsIsEmpty() {
        assertEquals(emptyList<SubagentInfo>(), Subagents.parse(body(emptyList())))
    }

    @Test
    fun relationOfPicksSubagentSessionsOnly() {
        val subagent = buildJsonObject {
            put("id", "a")
            put(
                "relation",
                buildJsonObject {
                    put("kind", "subagent")
                    put("parentSessionId", "root")
                    put("profile", "code-reader")
                    put("description", "Investigate auth bug")
                },
            )
        }
        val fork = buildJsonObject {
            put("id", "b")
            put(
                "relation",
                buildJsonObject {
                    put("kind", "fork")
                    put("originSessionId", "root")
                },
            )
        }
        val relation = Subagents.relationOf(subagent)
        assertEquals("code-reader", relation?.profile)
        assertEquals("Investigate auth bug", relation?.description)
        assertNull(Subagents.relationOf(fork))
        assertNull(Subagents.relationOf(null))
    }
}
