package app.pimobile.ui.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPagesTest {

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesProjectsWithTheirTotals() {
        val groups = SessionPages.parse(
            body(
                """{"projects":[{"key":"k1","root":"/work/alpha","total":42,"modified":"2026-09-26T10:05:00.000Z","sessions":[""" +
                    """{"id":"a","cwd":"/work/alpha","name":"Named","firstMessage":"hi","modified":"2026-09-26T10:05:00.000Z","messageCount":3},""" +
                    """{"id":"b","cwd":"/work/alpha","firstMessage":"(no messages)","modified":"2026-09-26T10:04:00.000Z"},""" +
                    """{"cwd":"/work/alpha","firstMessage":"no id"}]}],"recentCwds":["/work/alpha"]}""",
            ),
        )!!
        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals("k1", group.key)
        assertEquals("/work/alpha", group.root)
        assertEquals(42, group.total)
        assertEquals(listOf("a", "b"), group.sessions.map { it.id })
        assertEquals(listOf("Named", "New session"), group.sessions.map { it.title })
        assertEquals(3, group.sessions[0].messageCount)
    }

    @Test
    fun theFullListIsNotAPagedResponse() {
        assertNull(SessionPages.parse(body("""{"sessions":[],"sessionListVersion":1}""")))
    }

    @Test
    fun appendingSkipsSessionsAlreadyListed() {
        val row = { id: String -> SessionRow(id, id, null, "/w", 0L, 0, null) }
        val group = ProjectGroup("k", "/w", 12, listOf("e", "d", "c", "b", "a").map(row))
        // A new session pushed "a" from the first page into the next one.
        val next = ProjectGroup("k", "/w", 13, listOf("a", "z", "y").map(row))
        val merged = SessionPages.append(group, next)
        assertEquals(listOf("e", "d", "c", "b", "a", "z", "y"), merged.sessions.map { it.id })
        assertEquals(13, merged.total)
    }
}
