package app.pimobile.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RunSnapshotTest {

    @Test
    fun parsesTheRunStatusEvent() {
        val json = Json.parseToJsonElement(
            """{"sessionListVersion":7,"runningSessionIds":["a","b"],""" +
                """"completionNotificationSuppressedSessionIds":["b"],"waitingSessionIds":["a"]}""",
        ).jsonObject
        assertEquals(
            RunSnapshot(listVersion = 7, running = setOf("a", "b"), suppressed = setOf("b"), waiting = setOf("a")),
            RunSnapshot.parse(json),
        )
    }

    @Test
    fun missingListsReadAsEmpty() {
        val snapshot = RunSnapshot.parse(Json.parseToJsonElement("""{"runningSessionIds":["a"]}""").jsonObject)
        assertEquals(RunSnapshot(listVersion = null, running = setOf("a"), suppressed = emptySet(), waiting = emptySet()), snapshot)
    }
}
