import { NextResponse } from "next/server";
import { jsonResponse } from "@/lib/json-response";
import { listAllSessions } from "@/lib/session-reader";
import { getRunningRpcSessionIds } from "@/lib/rpc-manager";

export const dynamic = "force-dynamic";

/**
 * GET /api/sessions/[id]/subagents
 *
 * Direct subagent children of one session only. This is the lightweight
 * replacement for the Android client pulling the full /api/sessions list
 * (all sessions, ~1MB+) just to render the subagent bar of a single session.
 *
 * Reuses the cached session list (invalidated on every subagent spawn/state
 * change/finish), so a refresh triggered by the parent's SSE `Agent` tool
 * event reads fresh data. Response is a few KB (usually 0–5 items), ordered
 * running-first then newest-first — the client renders it as-is.
 */
export async function GET(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  try {
    const { id } = await params;
    const [sessions, runningIds] = await Promise.all([
      listAllSessions(),
      Promise.resolve(getRunningRpcSessionIds()),
    ]);
    const running = new Set(runningIds);
    const subagents = sessions
      .filter((s) => s.relation?.kind === "subagent" && s.relation.parentSessionId === id)
      .map((s) => {
        const relation = s.relation as {
          profile: string;
          description: string;
          status: string;
        };
        return {
          id: s.id,
          name: s.name ?? null,
          firstMessage: s.firstMessage,
          profile: relation.profile,
          description: relation.description,
          status: relation.status,
          modified: s.modified,
          running: running.has(s.id),
        };
      })
      .sort((a, b) => {
        if (a.running !== b.running) return a.running ? -1 : 1;
        return b.modified.localeCompare(a.modified);
      });
    return jsonResponse(req, { subagents }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return NextResponse.json(
      { error: String(error) },
      { status: 500, headers: { "Cache-Control": "no-store" } },
    );
  }
}
