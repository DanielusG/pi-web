import { NextResponse } from "next/server";
import { jsonResponse } from "@/lib/json-response";
import {
  attachSessionProjectInfo,
  getSessionListVersion,
  listAllSessions,
  listSessionSummaries,
  mergeSessionLists,
} from "@/lib/session-reader";
import {
  getCompletionNotificationSuppressedRpcSessionIds,
  getRpcSessionInfos,
  getRunningRpcSessionIds,
} from "@/lib/rpc-manager";
import { startServerPerf } from "@/lib/perf";
import { pageSessionsByProject, withCompactFirstMessage } from "@/lib/session-list-page";
import type { SessionInfo } from "@/lib/types";

export const dynamic = "force-dynamic";

/** A whole number in [min, max], or undefined when the parameter is absent or malformed. */
function intParam(searchParams: URLSearchParams, name: string, min: number, max: number): number | undefined {
  const raw = searchParams.get(name);
  if (raw === null || !/^\d+$/.test(raw)) return undefined;
  return Math.min(max, Math.max(min, Number(raw)));
}

/**
 * The list with the Android client's opt-in trimming applied (lib/session-list-page.ts):
 * `ids` keeps only those sessions, `firstMessageChars` shortens first messages, and
 * `perProject` (with `offset`, `project`) answers with one page per project instead.
 * Without them, the full list. An older server ignores them and sends the full list.
 */
function sessionListing(sessions: SessionInfo[], searchParams: URLSearchParams) {
  const ids = searchParams.get("ids");
  const wanted = ids === null ? null : new Set(ids.split(","));
  const selected = wanted ? sessions.filter((session) => wanted.has(session.id)) : sessions;
  const firstMessageChars = intParam(searchParams, "firstMessageChars", 1, 10_000);
  const compact = (list: SessionInfo[]) => firstMessageChars === undefined
    ? list
    : list.map((session) => withCompactFirstMessage(session, firstMessageChars));
  const perProject = intParam(searchParams, "perProject", 1, 100);
  if (perProject === undefined) return { sessions: compact(selected) };
  const { projects, recentCwds } = pageSessionsByProject(selected, {
    perProject,
    offset: intParam(searchParams, "offset", 0, Number.MAX_SAFE_INTEGER),
    project: searchParams.get("project") ?? undefined,
  });
  return {
    projects: projects.map((project) => ({ ...project, sessions: compact(project.sessions) })),
    recentCwds,
  };
}

export async function GET(req: Request) {
  const perf = startServerPerf("GET /api/sessions");
  try {
    const searchParams = new URL(req.url).searchParams;
    const force = searchParams.get("force") === "1";
    // `summary=1` serves header/stat metadata so the sidebar can paint without
    // waiting for every session transcript to be parsed.
    const summary = searchParams.get("summary") === "1";
    perf?.span("start");
    const persistedSessionsPromise = summary
      ? listSessionSummaries()
      : listAllSessions({ force });
    // Capture before awaiting: mutations during the scan still require a later refresh.
    const sessionListVersion = getSessionListVersion();
    const [persistedSessions, runtimeSessions] = await Promise.all([
      persistedSessionsPromise,
      attachSessionProjectInfo(getRpcSessionInfos()),
    ]);
    perf?.span("scan+projects");
    const sessions = mergeSessionLists(persistedSessions, runtimeSessions);
    const body = {
      ...sessionListing(sessions, searchParams),
      sessionListVersion,
      runningSessionIds: getRunningRpcSessionIds(),
      completionNotificationSuppressedSessionIds: getCompletionNotificationSuppressedRpcSessionIds(),
    };
    const response = jsonResponse(req, body, { headers: { "Cache-Control": "no-store" } });
    return perf?.attach(response) ?? response;
  } catch (error) {
    return NextResponse.json(
      { error: String(error) },
      { status: 500, headers: { "Cache-Control": "no-store" } },
    );
  }
}
