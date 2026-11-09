package app.pimobile.ui.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answer of `GET /api/sessions/find` (app/api/sessions/find-route.test.mjs pins the server side). */
class SessionSearchTest {

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesHitsWithTheirNameMatchAndSnippet() {
        val results = SessionSearch.parse(
            body(
                """{"results":[""" +
                    """{"id":"a","cwd":"/work/alpha/sub","name":"Alpha deploy","firstMessage":"hi","modified":"2026-01-01T00:00:00.000Z",""" +
                    """"messageCount":3,"projectKey":"k-alpha","projectRoot":"/work/alpha","branch":"main","score":1,""" +
                    """"nameMatch":{"before":"Alpha ","match":"deploy","after":""}},""" +
                    """{"id":"b","cwd":"/work/beta","firstMessage":"/skill:x","modified":"2026-01-02T00:00:00.000Z","messageCount":1,""" +
                    """"projectKey":"/work/beta","projectRoot":"/work/beta","relation":{"kind":"subagent","parentSessionId":"a","profile":"Explore"},""" +
                    """"snippet":{"entryId":"e1","blockIndex":2,"before":"the ","match":"deploy","after":" failed"}},""" +
                    """{"cwd":"/no/id"}],""" +
                    """"total":40,"scanned":12,"hasMore":true,"truncated":false,"sort":"relevance"}""",
            ),
        )
        assertEquals(listOf("a", "b"), results.hits.map { it.row.id })
        val (a, b) = results.hits
        assertEquals("Alpha deploy", a.row.title)
        assertEquals(Highlight("Alpha ", "deploy", ""), a.nameMatch)
        assertEquals("k-alpha", a.projectKey)
        assertEquals("/work/alpha", a.projectRoot)
        assertEquals("main", a.row.branch)
        assertNull(a.snippet)
        assertNull(a.subagent)
        assertEquals("Explore", b.subagent)
        assertNull(b.nameMatch)
        assertEquals(SearchSnippet("e1", 2, Highlight("the ", "deploy", " failed")), b.snippet)
        assertEquals(40, results.total)
        assertEquals(12, results.scanned)
        assertTrue(results.hasMore)
        assertFalse(results.truncated)
        assertNull(results.queryError)
    }

    @Test
    fun aRefusedPatternComesBackAsTheQueryError() {
        val results = SessionSearch.parse(
            body("""{"results":[],"total":5,"scanned":0,"hasMore":false,"truncated":false,"queryError":"Invalid regular expression"}"""),
        )
        assertTrue(results.hits.isEmpty())
        assertEquals("Invalid regular expression", results.queryError)
    }

    @Test
    fun aNameMatchWithoutANameIsIgnored() {
        // The title is then the first message, which the server did not mark.
        val results = SessionSearch.parse(
            body(
                """{"results":[{"id":"a","cwd":"/w","firstMessage":"hello","modified":"2026-01-01T00:00:00.000Z",""" +
                    """"nameMatch":{"before":"","match":"x","after":""}}]}""",
            ),
        )
        assertEquals("hello", results.hits[0].row.title)
        assertNull(results.hits[0].nameMatch)
        assertEquals("/w", results.hits[0].projectRoot)
    }
}
