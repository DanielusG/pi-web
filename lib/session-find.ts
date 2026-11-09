import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { findPackageJSON } from "node:module";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";
import { sessionProjectKey } from "./session-list-page";
import type { SessionInfo } from "./types";

/**
 * Session search with pi's own `/resume` rules (fork addition, Android client).
 *
 * The matching is not reimplemented: pi's session selector keeps it in
 * `session-selector-search.js`, which the SDK package does not export, so it is
 * loaded by file URL from the SDK copy pi-web runs (the approach of
 * `lib/pi-sdk-internals.ts`). A query therefore means here what it means in
 * pi's TUI — fuzzy words, `"exact phrase"`, `re:<pattern>` — and follows pi
 * when an upgrade changes the rules. `lib/session-find.test.mjs` pins the
 * module's contract.
 *
 * pi-web adds what the TUI does not have: a project filter, a time budget, a
 * result cap, and where the first hit sits (message entry and text block) so
 * a client can show a snippet and open the session there.
 */

const SDK_PACKAGE = "@earendil-works/pi-coding-agent";
export const PI_SEARCH_MODULE = "dist/modes/interactive/components/session-selector-search.js";

export const MAX_QUERY_CHARS = 200;
const DEFAULT_BUDGET_MS = 10_000;
const READ_CONCURRENCY = 8;
const SNIPPET_CONTEXT_CHARS = 80;
const MAX_LINE_CHARS = 8 * 1024 * 1024;
/** Text kept between searches, so typing a longer query does not reread every file. */
const DOC_CACHE_CHARS = 32 * 1024 * 1024;

/** pi's `ParsedSearchQuery`. */
export interface PiParsedQuery {
  mode: "tokens" | "regex";
  tokens: { kind: "fuzzy" | "phrase"; value: string }[];
  regex: RegExp | null;
  error?: string;
}

/** The fields of pi's `SessionInfo` its matcher reads. */
export interface PiSearchSession {
  id: string;
  name?: string;
  cwd: string;
  allMessagesText: string;
  modified: Date;
}

export interface PiSessionSearch {
  parseSearchQuery(query: string): PiParsedQuery;
  matchSession(session: PiSearchSession, parsed: PiParsedQuery): { matches: boolean; score: number };
}

export type PiSessionSearchResult = ({ ok: true } & PiSessionSearch) | { ok: false; reason: string };

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Load pi's matcher without caching; `cwd` exists for tests, the server uses `loadPiSessionSearch()`. */
export async function importPiSessionSearch(cwd = process.cwd()): Promise<PiSessionSearchResult> {
  try {
    // Next.js runs the server with the project as cwd, so this is the SDK pi-web imports.
    const manifest = findPackageJSON(SDK_PACKAGE, pathToFileURL(join(cwd, "package.json")).href);
    if (!manifest) return { ok: false, reason: `cannot resolve ${SDK_PACKAGE} from ${cwd}` };
    const path = join(manifest, "..", PI_SEARCH_MODULE);
    const loaded = await import(/* webpackIgnore: true */ /* turbopackIgnore: true */ pathToFileURL(path).href) as Record<string, unknown>;
    if (typeof loaded.parseSearchQuery !== "function" || typeof loaded.matchSession !== "function") {
      return { ok: false, reason: `${PI_SEARCH_MODULE} no longer exports parseSearchQuery and matchSession` };
    }
    return {
      ok: true,
      parseSearchQuery: loaded.parseSearchQuery as PiSessionSearch["parseSearchQuery"],
      matchSession: loaded.matchSession as PiSessionSearch["matchSession"],
    };
  } catch (error) {
    return { ok: false, reason: `cannot load ${PI_SEARCH_MODULE}: ${errorMessage(error)}` };
  }
}

const SEARCH_KEY: symbol = Symbol.for("pi-web.piSessionSearch");

/** pi's matcher, loaded once per server process (route bundles and hot reload share it). */
export function loadPiSessionSearch(): Promise<PiSessionSearchResult> {
  const store = globalThis as Record<symbol, Promise<PiSessionSearchResult> | undefined>;
  return store[SEARCH_KEY] ??= importPiSessionSearch();
}

// ---------------------------------------------------------------------------
// The text pi searches, read from the session file
// ---------------------------------------------------------------------------

interface MessageText {
  entryId?: string;
  blocks: { blockIndex: number; text: string }[];
}

/** One file's searchable text: what pi's `SessionManager.listAll()` puts in `allMessagesText`. */
export interface SessionDoc {
  allMessagesText: string;
  messages: MessageText[];
}

/**
 * Every user and assistant message of the file, all branches, text blocks only:
 * the entries pi's `buildSessionInfo()` joins into `allMessagesText` (blocks with
 * a space, messages with a space). Thinking, tool calls and tool results are not
 * searched, as in the TUI.
 */
export async function readSessionDoc(path: string, signal?: AbortSignal): Promise<SessionDoc> {
  const messages: MessageText[] = [];
  const stream = createReadStream(path, { encoding: "utf8", signal });
  const lines = createInterface({ input: stream, crlfDelay: Infinity });
  try {
    for await (const line of lines) {
      // Most of a file is tool output; only a message line can hold searchable text.
      if (line.length > MAX_LINE_CHARS || !line.includes('"type":"message"')) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }
      if (entry?.type !== "message") continue;
      const role = entry.message?.role;
      if (role !== "user" && role !== "assistant") continue;
      const content: unknown = entry.message.content;
      const blocks = typeof content === "string"
        ? [{ blockIndex: 0, text: content }]
        : Array.isArray(content)
          ? content.flatMap((block, blockIndex) => block?.type === "text" && typeof block.text === "string" ? [{ blockIndex, text: block.text as string }] : [])
          : [];
      if (!blocks.some((block) => block.text)) continue;
      messages.push({ ...(typeof entry.id === "string" ? { entryId: entry.id } : {}), blocks });
    }
  } finally {
    lines.close();
    stream.destroy();
  }
  return {
    allMessagesText: messages.map((message) => message.blocks.map((block) => block.text).join(" ")).join(" "),
    messages,
  };
}

interface CachedDoc { size: number; mtimeMs: number; doc: SessionDoc }

interface DocCache { entries: Map<string, CachedDoc>; chars: number }

function docCache(): DocCache {
  const store = globalThis as typeof globalThis & { __piSessionFindDocs?: DocCache };
  return store.__piSessionFindDocs ??= { entries: new Map(), chars: 0 };
}

/** Drop the cached text (tests). */
export function clearSessionDocCache(): void {
  const cache = docCache();
  cache.entries.clear();
  cache.chars = 0;
}

async function sessionDoc(path: string, signal: AbortSignal): Promise<SessionDoc> {
  const cache = docCache();
  const info = await stat(path);
  const cached = cache.entries.get(path);
  if (cached && cached.size === info.size && cached.mtimeMs === info.mtimeMs) {
    // Least recently used first: a hit moves to the end.
    cache.entries.delete(path);
    cache.entries.set(path, cached);
    return cached.doc;
  }
  if (cached) {
    cache.entries.delete(path);
    cache.chars -= cached.doc.allMessagesText.length;
  }
  const doc = await readSessionDoc(path, signal);
  const chars = doc.allMessagesText.length;
  if (chars <= DOC_CACHE_CHARS / 4) {
    cache.entries.set(path, { size: info.size, mtimeMs: info.mtimeMs, doc });
    cache.chars += chars;
    for (const [key, entry] of cache.entries) {
      if (cache.chars <= DOC_CACHE_CHARS) break;
      cache.entries.delete(key);
      cache.chars -= entry.doc.allMessagesText.length;
    }
  }
  return doc;
}

// ---------------------------------------------------------------------------
// Where the hit is
// ---------------------------------------------------------------------------

export interface Highlight {
  before: string;
  match: string;
  after: string;
}

export interface Snippet extends Highlight {
  entryId?: string;
  blockIndex: number;
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Patterns that find, in one message, what pi matched literally: a fuzzy word as
 * written, a phrase with any whitespace between its words, a `re:` pattern as is.
 * A word pi matched only as scattered letters of the name or cwd has no place in
 * a message, so it locates nothing.
 */
function locatorPatterns(parsed: PiParsedQuery): RegExp[] {
  if (parsed.mode === "regex") return parsed.regex ? [new RegExp(parsed.regex.source, parsed.regex.flags.replace("g", ""))] : [];
  return parsed.tokens.flatMap((token) => {
    if (token.kind === "phrase") {
      const words = token.value.trim().split(/\s+/).filter(Boolean);
      return words.length ? [new RegExp(words.map(escapeRegExp).join("\\s+"), "i")] : [];
    }
    return token.value ? [new RegExp(escapeRegExp(token.value), "i")] : [];
  });
}

function collapse(text: string): string {
  return text.replace(/\s+/g, " ");
}

function snippetAround(text: string, start: number, end: number): Highlight {
  const from = Math.max(0, start - SNIPPET_CONTEXT_CHARS);
  const to = Math.min(text.length, end + SNIPPET_CONTEXT_CHARS);
  return {
    before: (from > 0 ? "…" : "") + collapse(text.slice(from, start)).trimStart(),
    match: collapse(text.slice(start, end)),
    after: collapse(text.slice(end, to)).trimEnd() + (to < text.length ? "…" : ""),
  };
}

function locate(name: string, doc: SessionDoc, patterns: RegExp[]): { nameMatch?: Highlight; snippet?: Snippet } {
  let nameMatch: Highlight | undefined;
  for (const pattern of patterns) {
    const hit = name ? pattern.exec(name) : null;
    if (hit) {
      nameMatch = { before: name.slice(0, hit.index), match: hit[0], after: name.slice(hit.index + hit[0].length) };
      break;
    }
  }
  for (const pattern of patterns) {
    for (const message of doc.messages) {
      for (const block of message.blocks) {
        const hit = pattern.exec(block.text);
        if (!hit) continue;
        return {
          ...(nameMatch ? { nameMatch } : {}),
          snippet: {
            ...(message.entryId ? { entryId: message.entryId } : {}),
            blockIndex: block.blockIndex,
            ...snippetAround(block.text, hit.index, hit.index + hit[0].length),
          },
        };
      }
    }
  }
  return nameMatch ? { nameMatch } : {};
}

// ---------------------------------------------------------------------------
// The search
// ---------------------------------------------------------------------------

export type SessionFindSort = "recent" | "relevance";

export interface SessionFindOptions {
  query: string;
  /** A project key (`sessionProjectKey`, the paged list's `ProjectPage.key`): only its sessions. */
  project?: string;
  /** `recent`: newest first, as pi's Recent sort; `relevance`: pi's score, then newest. */
  sort: SessionFindSort;
  limit: number;
  signal?: AbortSignal;
  budgetMs?: number;
}

export interface SessionFindHit {
  session: SessionInfo;
  score: number;
  nameMatch?: Highlight;
  snippet?: Snippet;
}

export interface SessionFindResponse {
  results: SessionFindHit[];
  /** Sessions in scope (after the project filter). */
  total: number;
  /** Sessions read before the search stopped. */
  scanned: number;
  /** More sessions matched than `results` holds. */
  hasMore: boolean;
  /** The time budget, the request or an unreadable file stopped the scan early. */
  truncated: boolean;
  /** pi refused the query (an invalid `re:` pattern): nothing matches, as in the TUI. */
  queryError?: string;
}

/**
 * Sessions matching `query` with pi's rules. Side chats are left out: each
 * copies its parent's text and never opens as the main chat.
 */
export async function findSessions(
  search: PiSessionSearch,
  sessions: readonly SessionInfo[],
  { query, project, sort, limit, signal: requestSignal, budgetMs = DEFAULT_BUDGET_MS }: SessionFindOptions,
): Promise<SessionFindResponse> {
  const candidates = sessions
    .filter((session) => !session.transient && session.path && session.relation?.kind !== "side")
    .filter((session) => project === undefined || sessionProjectKey(session) === project)
    .sort((a, b) => b.modified.localeCompare(a.modified));
  const response: SessionFindResponse = { results: [], total: candidates.length, scanned: 0, hasMore: false, truncated: false };
  if (!query.trim()) return response;
  if (query.length > MAX_QUERY_CHARS) throw new RangeError(`Search query exceeds ${MAX_QUERY_CHARS} characters`);

  const parsed = search.parseSearchQuery(query);
  if (parsed.error) return { ...response, queryError: parsed.error };
  const patterns = locatorPatterns(parsed);

  const timeout = AbortSignal.timeout(budgetMs);
  const signal = requestSignal ? AbortSignal.any([requestSignal, timeout]) : timeout;
  const hits: SessionFindHit[] = [];
  // Recent keeps the incoming order, so the newest `limit` hits are the answer
  // and one more says there are others; relevance has to score every session.
  const enough = sort === "recent" ? limit + 1 : Infinity;

  scan: for (let index = 0; index < candidates.length; index += READ_CONCURRENCY) {
    if (signal.aborted) {
      response.truncated = true;
      break;
    }
    const batch = candidates.slice(index, index + READ_CONCURRENCY);
    const docs = await Promise.all(batch.map((session) => sessionDoc(session.path, signal).catch(() => null)));
    for (const [offset, session] of batch.entries()) {
      const doc = docs[offset];
      if (!doc) {
        // A file deleted since the listing, or a read the budget cut short.
        response.truncated = true;
        continue;
      }
      response.scanned += 1;
      const match = search.matchSession({
        id: session.id,
        ...(session.name ? { name: session.name } : {}),
        cwd: session.cwd,
        allMessagesText: doc.allMessagesText,
        modified: new Date(session.modified),
      }, parsed);
      if (!match.matches) continue;
      hits.push({ session, score: match.score, ...locate(session.name ?? "", doc, patterns) });
      if (hits.length >= enough) break scan;
    }
  }

  if (sort === "relevance") {
    hits.sort((a, b) => a.score - b.score || b.session.modified.localeCompare(a.session.modified));
  }
  response.hasMore = hits.length > limit;
  response.results = hits.slice(0, limit);
  return response;
}
