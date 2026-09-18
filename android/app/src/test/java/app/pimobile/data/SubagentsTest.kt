package app.pimobile.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Subagents.parse mirrors the web's AgentSessionPanel: direct children only,
 * live running set beats the file status, running-first then newest-first.
 */
class SubagentsTest {

    private fun session(
        id: String,
        modified: String,
        kind: String? = null,
        parent: String? = null,
        profile: String? = null,
        description: String? = null,
        status: String? = null,
        name: String? = null,
        firstMessage: String? = null,
    ): JsonObject {
        val relation: JsonObject? = if (kind != null) {
            buildJsonObject {
                put("kind", kind)
                if (parent != null) put("parentSessionId", parent)
                if (profile != null) put("profile", profile)
                if (description != null) put("description", description)
                if (status != null) put("status", status)
            }
        } else null
        return buildJsonObject {
            put("id", id)
            put("modified", modified)
            if (name != null) put("name", name)
            if (firstMessage != null) put("firstMessage", firstMessage)
            if (relation != null) put("relation", relation)
        }
    }

    private fun body(sessions: List<JsonObject>, running: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            putJsonArray("sessions") { sessions.forEach { add(it) } }
            putJsonArray("runningSessionIds") { running.forEach { add(JsonPrimitive(it)) } }
        }

    @Test
    fun filtersDirectChildrenOnly() {
        val parent = session("root", "2026-01-01T00:00:00Z")
        val child = session("a", "2026-01-01T01:00:00Z", "subagent", "root", "p", "task a", "completed")
        val grandchild = session("b", "2026-01-01T02:00:00Z", "subagent", "a", "p", "task b", "completed")
        val otherChild = session("c", "2026-01-01T03:00:00Z", "subagent", "elsewhere", "p", "task c", "completed")
        val fork = session("d", "2026-01-01T04:00:00Z", "fork", "root")
        val parsed = Subagents.parse(body(listOf(parent, child, grandchild, otherChild, fork)), "root")
        assertEquals(listOf("a"), parsed.map { it.id })
    }

    @Test
    fun liveRunningSetBeatsFileStatus() {
        val fileRunning = session("a", "2026-01-01T01:00:00Z", "subagent", "root", "p", "a", "completed")
        val fileDone = session("b", "2026-01-01T02:00:00Z", "subagent", "root", "p", "b", "running")
        val parsed = Subagents.parse(body(listOf(fileRunning, fileDone), running = listOf("a")), "root")
        assertEquals("running", parsed.first { it.id == "a" }.status)
        assertEquals(true, parsed.first { it.id == "a" }.running)
        // b is no longer in the live set: the file status is kept as-is.
        assertEquals("running", parsed.first { it.id == "b" }.status)
        assertEquals(false, parsed.first { it.id == "b" }.running)
    }

    @Test
    fun sortsRunningFirstThenNewestFirst() {
        val oldRunning = session("a", "2026-01-01T01:00:00Z", "subagent", "root", "p", "a", "completed")
        val newerDone = session("b", "2026-01-01T03:00:00Z", "subagent", "root", "p", "b", "completed")
        val olderDone = session("c", "2026-01-01T02:00:00Z", "subagent", "root", "p", "c", "completed")
        // a is the live-running one despite being the oldest.
        val parsed = Subagents.parse(body(listOf(olderDone, newerDone, oldRunning), running = listOf("a")), "root")
        assertEquals(listOf("a", "b", "c"), parsed.map { it.id })
    }

    @Test
    fun titleFallsBackDescriptionNameFirstMessageShortId() {
        val withDescription = session("a", "m", "subagent", "root", "p", "do the thing", "completed", name = "n", firstMessage = "f")
        val withName = session("b", "m", "subagent", "root", "p", null, "completed", name = "named", firstMessage = "f")
        val withFirstMessage = session("c", "m", "subagent", "root", "p", null, "completed", firstMessage = "first message")
        val longId = "d".repeat(20)
        val bare = session(longId, "m", "subagent", "root", "p", null, "completed")
        val parsed = Subagents.parse(body(listOf(withDescription, withName, withFirstMessage, bare)), "root")
        assertEquals("do the thing", parsed.first { it.id == "a" }.title)
        assertEquals("named", parsed.first { it.id == "b" }.title)
        assertEquals("first message", parsed.first { it.id == "c" }.title)
        assertEquals("d".repeat(12), parsed.first { it.id == longId }.title)
    }

    @Test
    fun noSubagentsIsEmpty() {
        val root = session("root", "2026-01-01T00:00:00Z")
        assertEquals(emptyList<SubagentInfo>(), Subagents.parse(body(listOf(root)), "root"))
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
