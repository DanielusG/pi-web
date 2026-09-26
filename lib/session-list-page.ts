import { skillExpansionToCommand } from "./slash-display";
import type { SessionInfo } from "./types";

/**
 * Opt-in shapes of `GET /api/sessions` for the Android client, which shows a few
 * sessions per project: the full list is 7 MB on a busy machine, most of it first
 * messages the app cuts to one line anyway. The web sidebar keeps the full list.
 */

/** Distinct working directories offered when starting a new session. */
const RECENT_CWDS_LIMIT = 12;
const ELLIPSIS = "…";

export interface ProjectPage {
  /** Grouping identity (`SessionInfo.projectKey`); pass it back as `project`. */
  key: string;
  /** Display and file-browsing root of the project. */
  root: string;
  /** Top-level sessions of the project, not just the ones in `sessions`. */
  total: number;
  /** Most recent activity among all its top-level sessions. */
  modified: string;
  sessions: SessionInfo[];
}

export interface SessionListPageOptions {
  /** Sessions per project; the window starts at `offset` within each project. */
  perProject: number;
  offset?: number;
  /** Only this project (a `ProjectPage.key`): the next page of one group. */
  project?: string;
}

export function sessionProjectKey(session: SessionInfo): string {
  return session.projectKey ?? session.projectRoot ?? session.cwd;
}

/**
 * Groups top-level sessions by project, newest project first, and keeps one window
 * of each. Subagent runs are left out: they belong under their parent, which a
 * paged list cannot show without paging them too.
 */
export function pageSessionsByProject(
  sessions: SessionInfo[],
  { perProject, offset = 0, project }: SessionListPageOptions,
): { projects: ProjectPage[]; recentCwds: string[] } {
  const topLevel = sessions
    .filter((session) => session.relation?.kind !== "subagent")
    .sort((a, b) => b.modified.localeCompare(a.modified));
  const groups = new Map<string, SessionInfo[]>();
  for (const session of topLevel) {
    const key = sessionProjectKey(session);
    const group = groups.get(key);
    if (group) group.push(session);
    else groups.set(key, [session]);
  }
  const projects: ProjectPage[] = [];
  // Map order is first-seen order, which the sort made newest-activity first.
  for (const [key, group] of groups) {
    if (project !== undefined && key !== project) continue;
    projects.push({
      key,
      root: group[0].projectRoot ?? group[0].cwd,
      total: group.length,
      modified: group[0].modified,
      sessions: group.slice(offset, offset + perProject),
    });
  }
  const recentCwds = [...new Set(topLevel.map((session) => session.cwd).filter(Boolean))]
    .slice(0, RECENT_CWDS_LIMIT);
  return { projects, recentCwds };
}

/**
 * The first message as a list shows it: a skill expansion back to its `/skill:`
 * command (the expansion embeds the whole skill file, and a cut one no longer
 * collapses), then at most `maxChars` characters.
 */
export function compactFirstMessage(text: string, maxChars: number): string {
  const display = skillExpansionToCommand(text) ?? text;
  if (display.length <= maxChars) return display;
  let end = maxChars;
  // Never split a surrogate pair.
  const code = display.charCodeAt(end - 1);
  if (code >= 0xd800 && code <= 0xdbff) end -= 1;
  return display.slice(0, end) + ELLIPSIS;
}

export function withCompactFirstMessage(session: SessionInfo, maxChars: number): SessionInfo {
  const firstMessage = compactFirstMessage(session.firstMessage, maxChars);
  return firstMessage === session.firstMessage ? session : { ...session, firstMessage };
}
