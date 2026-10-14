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
     * Parses a GET /api/sessions/[id]/subagents response: the session's direct
     * subagent children only (the server filters and orders them). The live
     * `running` flag takes precedence over the file status, as on the web
     * (AgentSessionPanel); order is running-first, then newest first. The client
     * re-sorts defensively so the bar is correct even if the server order drifts.
     */
    fun parse(body: JsonObject): List<SubagentInfo> {
        return body.arr("subagents").orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { json ->
                val id = json.str("id").orEmpty()
                val live = json.bool("running") == true
                SubagentInfo(
                    id = id,
                    title = json.str("description")
                        ?: json.str("name")
                        ?: json.str("firstMessage")?.takeUnless { it == "(no messages)" }
                        ?: id.take(12),
                    profile = json.str("profile"),
                    status = if (live) "running" else json.str("status") ?: "completed",
                    running = live,
                    modified = json.str("modified").orEmpty(),
                )
            }
            .sortedWith(compareByDescending<SubagentInfo> { it.running }.thenByDescending { it.modified })
    }

    /**
     * True when the row's run is parked on a blocking extension dialog (a permission gate)
     * and needs a person: [waiting] is the server's `waitingSessionIds`, pushed by
     * `GET /api/agent/running/events`. Only an active row can be waiting — the server's set
     * is a subset of the running wrappers, so a finished row never turns orange on a stale id.
     */
    fun isWaiting(agent: SubagentInfo, waiting: Set<String>): Boolean =
        agent.active && agent.id in waiting

    /** The row's status word: a parked run says waiting, not running. */
    fun statusLabel(agent: SubagentInfo, waiting: Boolean): String = when {
        waiting -> "waiting"
        agent.active -> "running"
        else -> agent.status
    }

    /**
     * The bar's row order: a run that needs a person first, then the live ones, then newest
     * first. [parse] already sorts live-first the way the server sends it; this re-sorts with
     * the pushed waiting state on top, so the row to answer sits above the others.
     */
    fun order(subagents: List<SubagentInfo>, waiting: Set<String>): List<SubagentInfo> =
        subagents.sortedWith(
            compareByDescending<SubagentInfo> { isWaiting(it, waiting) }
                .thenByDescending { it.running }
                .thenByDescending { it.modified },
        )

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
