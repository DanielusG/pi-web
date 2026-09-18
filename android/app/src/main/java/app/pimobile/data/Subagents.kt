package app.pimobile.data

import kotlinx.serialization.json.JsonObject

/** One subagent of a session, as listed by GET /api/sessions. */
data class SubagentInfo(
    val id: String,
    /** Display title: description, else session name, else first message, else a short id. */
    val title: String,
    val profile: String?,
    /** starting | queued | running | completed | failed | aborted | interrupted. */
    val status: String,
    /** True when the session id is in the live runningSessionIds set. */
    val running: Boolean,
    /** ISO-8601 mtime, for sorting and relative time. */
    val modified: String,
) {
    /** Active states get the spinner and the accent color (web: StatusIcon). */
    val active: Boolean
        get() = status == "running" || status == "starting" || status == "queued"
}

/** The current session's own relation, when it is a subagent (info.relation). */
data class SubagentRelation(
    val profile: String?,
    val description: String?,
)

object Subagents {
    /**
     * Direct children of [sessionId] in a /api/sessions response. The live
     * running set takes precedence over the file status, as on the web
     * (AgentSessionPanel); order is running-first, then newest first.
     */
    fun parse(body: JsonObject, sessionId: String): List<SubagentInfo> {
        val running = body.arr("runningSessionIds").strings().toSet()
        return body.arr("sessions").orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { json ->
                val relation = json.obj("relation") ?: return@mapNotNull null
                if (relation.str("kind") != "subagent") return@mapNotNull null
                if (relation.str("parentSessionId") != sessionId) return@mapNotNull null
                val id = json.str("id").orEmpty()
                val live = id in running
                SubagentInfo(
                    id = id,
                    title = relation.str("description")
                        ?: json.str("name")
                        ?: json.str("firstMessage")?.takeUnless { it == "(no messages)" }
                        ?: id.take(12),
                    profile = relation.str("profile"),
                    status = if (live) "running" else relation.str("status") ?: "completed",
                    running = live,
                    modified = json.str("modified").orEmpty(),
                )
            }
            .sortedWith(compareByDescending<SubagentInfo> { it.running }.thenByDescending { it.modified })
    }

    /** Non-null when the session detail's info.relation marks it as a subagent. */
    fun relationOf(info: JsonObject?): SubagentRelation? {
        val relation = info?.obj("relation") ?: return null
        if (relation.str("kind") != "subagent") return null
        return SubagentRelation(
            profile = relation.str("profile"),
            description = relation.str("description"),
        )
    }
}
