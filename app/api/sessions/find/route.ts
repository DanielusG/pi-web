import { NextResponse } from "next/server";
import { listAllSessions } from "@/lib/session-reader";
import { findSessions, loadPiSessionSearch, MAX_QUERY_CHARS, type SessionFindHit } from "@/lib/session-find";
import { compactFirstMessage, sessionProjectKey } from "@/lib/session-list-page";

export const dynamic = "force-dynamic";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;
/** A result row shows two lines of it, as the app's session list does. */
const FIRST_MESSAGE_CHARS = 200;

/**
 * GET /api/sessions/find?q=&project=&sort=recent|relevance&limit= — fork
 * addition for the Android client: every session searched with pi's `/resume`
 * rules (`lib/session-find.ts`). The web sidebar keeps upstream's
 * `/api/sessions/search`.
 */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const query = (params.get("q") ?? "").trim();
  const headers = { "Cache-Control": "no-store" };
  if (query.length > MAX_QUERY_CHARS) {
    return NextResponse.json({ error: `Search query exceeds ${MAX_QUERY_CHARS} characters` }, { status: 400, headers });
  }
  const sort = params.get("sort") === "recent" ? "recent" : "relevance";
  const rawLimit = Number(params.get("limit"));
  const limit = Number.isInteger(rawLimit) && rawLimit > 0 ? Math.min(rawLimit, MAX_LIMIT) : DEFAULT_LIMIT;
  const project = params.get("project") || undefined;

  const search = await loadPiSessionSearch();
  if (!search.ok) {
    return NextResponse.json({ error: `Session search is unavailable: ${search.reason}` }, { status: 503, headers });
  }
  try {
    // The catalogue only names the files to read; a stale one costs at most a
    // session created in the last seconds (see /api/sessions/search).
    const sessions = query && !request.signal.aborted ? await listAllSessions({ allowStale: true }) : [];
    const response = await findSessions(search, sessions, { query, project, sort, limit, signal: request.signal });
    return NextResponse.json({ ...response, sort, results: response.results.map(toWire) }, { headers });
  } catch (error) {
    return NextResponse.json({ error: String(error) }, { status: 500, headers });
  }
}

/** What a result row needs: the session without its file path, cut like the paged list's rows. */
function toWire({ session, score, nameMatch, snippet }: SessionFindHit) {
  return {
    id: session.id,
    cwd: session.cwd,
    ...(session.name ? { name: session.name } : {}),
    firstMessage: compactFirstMessage(session.firstMessage, FIRST_MESSAGE_CHARS),
    modified: session.modified,
    messageCount: session.messageCount,
    projectKey: sessionProjectKey(session),
    projectRoot: session.projectRoot ?? session.cwd,
    ...(session.branch ? { branch: session.branch } : {}),
    ...(session.relation?.kind === "subagent"
      ? { relation: { kind: "subagent", parentSessionId: session.relation.parentSessionId, profile: session.relation.profile } }
      : session.relation?.kind === "fork" ? { relation: { kind: "fork" } } : {}),
    score,
    ...(nameMatch ? { nameMatch } : {}),
    ...(snippet ? { snippet } : {}),
  };
}
