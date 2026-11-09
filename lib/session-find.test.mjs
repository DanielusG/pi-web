// pi's /resume search behind GET /api/sessions/find (fork addition, Android
// client). The matcher is pi's own module, loaded from the SDK; these tests pin
// that contract, check that pi-web feeds it the text pi's own SessionManager
// builds, and cover what pi-web adds: project scope, sort, cap, snippet.
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import { createJiti } from "jiti";
import { SessionManager } from "@earendil-works/pi-coding-agent";

const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const {
  PI_SEARCH_MODULE,
  clearSessionDocCache,
  findSessions,
  importPiSessionSearch,
  readSessionDoc,
} = await jiti.import("./session-find.ts");

const piSearchModule = await import(pathToFileURL(join(process.cwd(), "node_modules/@earendil-works/pi-coding-agent", PI_SEARCH_MODULE)).href);

function tempDir(t) {
  const dir = mkdtempSync(join(tmpdir(), "pi-web-find-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

let entryCounter = 0;
function message(role, content, extra = {}) {
  entryCounter += 1;
  return {
    type: "message",
    id: extra.id ?? `e${entryCounter}`,
    parentId: extra.parentId ?? null,
    timestamp: extra.timestamp ?? "2026-01-01T00:00:00.000Z",
    message: { role, content, timestamp: Date.parse(extra.timestamp ?? "2026-01-01T00:00:00.000Z") },
  };
}

/** A session file as pi writes it, and the catalogue row pi-web's list gives it. */
function sessionFile(dir, { id, cwd = "/work/alpha", name, modified = "2026-01-01T00:00:00.000Z", entries, relation, projectKey }) {
  const path = join(dir, `${modified.replace(/[:.]/g, "-")}_${id}.jsonl`);
  const lines = [
    { type: "session", version: 3, id, timestamp: modified, cwd },
    ...entries,
    ...(name ? [{ type: "session_info", id: `${id}-info`, parentId: null, timestamp: modified, name }] : []),
  ];
  writeFileSync(path, lines.map((line) => JSON.stringify(line)).join("\n") + "\n");
  return {
    path,
    id,
    cwd,
    ...(name ? { name } : {}),
    created: modified,
    modified,
    messageCount: entries.length,
    firstMessage: "first",
    projectRoot: cwd,
    projectKey: projectKey ?? cwd,
    ...(relation ? { relation } : {}),
    transient: false,
  };
}

async function piSearch() {
  const search = await importPiSessionSearch();
  assert.equal(search.ok, true, search.reason);
  return search;
}

test("loads pi's own session-selector matcher from the SDK pi-web runs", async () => {
  const search = await piSearch();
  assert.equal(search.parseSearchQuery, piSearchModule.parseSearchQuery);
  assert.equal(search.matchSession, piSearchModule.matchSession);
  assert.deepEqual(search.parseSearchQuery('foo "node cve" bar').tokens, [
    { kind: "fuzzy", value: "foo" },
    { kind: "phrase", value: "node cve" },
    { kind: "fuzzy", value: "bar" },
  ]);
  assert.equal(search.parseSearchQuery("re:[").mode, "regex");
  assert.ok(search.parseSearchQuery("re:[").error);

  const missing = await importPiSessionSearch(tempDir({ after() {} }));
  assert.equal(missing.ok, false);
});

test("reads the text pi's SessionManager searches: user and assistant text of every branch", async (t) => {
  const dir = tempDir(t);
  const row = sessionFile(dir, {
    id: "doc",
    modified: "2026-01-02T00:00:00.000Z",
    entries: [
      message("user", "plain user text"),
      message("assistant", [
        { type: "thinking", thinking: "hidden thought" },
        { type: "text", text: "first block" },
        { type: "toolCall", id: "call", name: "read", arguments: { path: "secret-arg" } },
        { type: "text", text: "second block" },
      ]),
      message("toolResult", [{ type: "text", text: "tool output" }]),
      // Another branch: pi reads every entry of the file, not one leaf's path.
      message("user", [{ type: "text", text: "other branch" }], { parentId: "e1" }),
      message("user", [{ type: "image", data: "AAAA", mimeType: "image/png" }]),
    ],
  });
  const doc = await readSessionDoc(row.path);
  const [sdk] = await SessionManager.list("/work/alpha", dir);
  assert.equal(doc.allMessagesText, sdk.allMessagesText);
  assert.equal(doc.allMessagesText, "plain user text first block second block other branch");
  assert.deepEqual(doc.messages[1].blocks, [{ blockIndex: 1, text: "first block" }, { blockIndex: 3, text: "second block" }]);
});

test("matches and orders sessions exactly as pi's selector does", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const rows = [
    sessionFile(dir, { id: "named", name: "Deploy pipeline", modified: "2026-01-01T00:00:00.000Z", entries: [message("user", "unrelated words")] }),
    sessionFile(dir, { id: "body", modified: "2026-01-03T00:00:00.000Z", entries: [message("user", "we fixed the deploy script yesterday")] }),
    sessionFile(dir, { id: "scattered", cwd: "/work/dxeyplxoxy", modified: "2026-01-04T00:00:00.000Z", entries: [message("user", "nothing here")] }),
    sessionFile(dir, { id: "phrase", modified: "2026-01-02T00:00:00.000Z", entries: [message("assistant", [{ type: "text", text: "the node\n   CVE list" }])] }),
    sessionFile(dir, { id: "none", modified: "2026-01-05T00:00:00.000Z", entries: [message("user", "something else")] }),
  ];
  // pi's own list of the directory, in pi's own order (newest first).
  const sdkSessions = await SessionManager.listAll(dir);
  assert.equal(sdkSessions.length, rows.length);
  const search = await piSearch();
  for (const query of ["deploy", "dply", '"node cve"', "re:deplo?y", "deploy yesterday", "zzz"]) {
    for (const sort of ["relevance", "recent"]) {
      const expected = piSearchModule.filterAndSortSessions(sdkSessions, query, sort).map((session) => session.id);
      const response = await findSessions(search, rows, { query, sort, limit: 50 });
      assert.deepEqual(response.results.map((hit) => hit.session.id), expected, `${sort} ${query}`);
      assert.equal(response.scanned, rows.length);
      assert.equal(response.truncated, false);
    }
  }
});

test("locates the hit: name highlight, message entry and text block, collapsed context", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const long = "x".repeat(120);
  const row = sessionFile(dir, {
    id: "located",
    name: "Release notes draft",
    entries: [
      message("user", "nothing to see"),
      message("assistant", [
        { type: "thinking", thinking: "release" },
        { type: "text", text: "intro" },
        { type: "text", text: `${long} the  Release\nNotes  are ready ${long}` },
      ], { id: "hit-entry" }),
    ],
  });
  const search = await piSearch();
  const [hit] = (await findSessions(search, [row], { query: '"release notes"', sort: "relevance", limit: 10 })).results;
  assert.deepEqual(hit.nameMatch, { before: "", match: "Release notes", after: " draft" });
  assert.equal(hit.snippet.entryId, "hit-entry");
  assert.equal(hit.snippet.blockIndex, 2);
  assert.equal(hit.snippet.match, "Release Notes");
  assert.ok(hit.snippet.before.startsWith("…"));
  assert.ok(hit.snippet.before.endsWith("x the "));
  assert.ok(hit.snippet.after.startsWith(" are ready x"));
  assert.ok(hit.snippet.after.endsWith("…"));

  const [regexHit] = (await findSessions(search, [row], { query: "re:read[yz]", sort: "relevance", limit: 10 })).results;
  assert.equal(regexHit.snippet.match, "ready");
  assert.equal(regexHit.nameMatch, undefined);

  // A word pi matched only as scattered letters of the name has no place in a message.
  const [fuzzyHit] = (await findSessions(search, [row], { query: "rlsdrft", sort: "relevance", limit: 10 })).results;
  assert.equal(fuzzyHit.session.id, "located");
  assert.equal(fuzzyHit.snippet, undefined);
  assert.equal(fuzzyHit.nameMatch, undefined);
});

test("scopes to one project, leaves side chats out, keeps subagents", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const rows = [
    sessionFile(dir, { id: "alpha", cwd: "/work/alpha", entries: [message("user", "shared needle")] }),
    sessionFile(dir, { id: "alpha-worktree", cwd: "/work/alpha-worktrees/feature", projectKey: "/work/alpha", entries: [message("user", "shared needle")] }),
    sessionFile(dir, { id: "beta", cwd: "/work/beta", entries: [message("user", "shared needle")] }),
    sessionFile(dir, { id: "side", cwd: "/work/alpha", relation: { kind: "side", parentSessionId: "alpha" }, entries: [message("user", "shared needle")] }),
    sessionFile(dir, { id: "sub", cwd: "/work/alpha", relation: { kind: "subagent", parentSessionId: "alpha", profile: "Explore", description: "d", status: "completed" }, entries: [message("user", "shared needle")] }),
  ];
  const search = await piSearch();
  const scoped = await findSessions(search, rows, { query: "needle", project: "/work/alpha", sort: "recent", limit: 10 });
  assert.deepEqual(scoped.results.map((hit) => hit.session.id).sort(), ["alpha", "alpha-worktree", "sub"]);
  assert.equal(scoped.total, 3);
  const all = await findSessions(search, rows, { query: "needle", sort: "recent", limit: 10 });
  assert.equal(all.total, 4);
  assert.ok(!all.results.some((hit) => hit.session.id === "side"));
});

test("recent stops after the newest hits; relevance scores everything; both cap at the limit", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const rows = Array.from({ length: 6 }, (_, index) => sessionFile(dir, {
    id: `s${index}`,
    modified: `2026-01-0${index + 1}T00:00:00.000Z`,
    entries: [message("user", index === 0 ? "needle" : `needle number ${index}`)],
  }));
  const search = await piSearch();
  const recent = await findSessions(search, rows, { query: "needle", sort: "recent", limit: 2 });
  assert.deepEqual(recent.results.map((hit) => hit.session.id), ["s5", "s4"]);
  assert.equal(recent.hasMore, true);
  assert.equal(recent.scanned, 3);
  const relevance = await findSessions(search, rows, { query: "needle", sort: "relevance", limit: 2 });
  assert.equal(relevance.scanned, 6);
  assert.equal(relevance.hasMore, true);
  assert.equal(relevance.results.length, 2);
});

test("an invalid re: pattern matches nothing and says why; an empty query searches nothing", async (t) => {
  const dir = tempDir(t);
  const rows = [sessionFile(dir, { id: "one", entries: [message("user", "text")] })];
  const search = await piSearch();
  const invalid = await findSessions(search, rows, { query: "re:(", sort: "relevance", limit: 10 });
  assert.deepEqual(invalid.results, []);
  assert.ok(invalid.queryError);
  const empty = await findSessions(search, rows, { query: "  ", sort: "relevance", limit: 10 });
  assert.deepEqual(empty.results, []);
  assert.equal(empty.scanned, 0);
  await assert.rejects(findSessions(search, rows, { query: "x".repeat(201), sort: "relevance", limit: 10 }), RangeError);
});

test("a file written since the last search is read again", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const row = sessionFile(dir, { id: "grows", entries: [message("user", "before")] });
  const search = await piSearch();
  assert.equal((await findSessions(search, [row], { query: "later", sort: "recent", limit: 10 })).results.length, 0);
  writeFileSync(row.path, JSON.stringify(message("user", "later words")) + "\n", { flag: "a" });
  utimesSync(row.path, new Date(), new Date(Date.now() + 1000));
  assert.equal((await findSessions(search, [row], { query: "later", sort: "recent", limit: 10 })).results.length, 1);
});

test("an unreadable file marks the answer partial without hiding the others", async (t) => {
  clearSessionDocCache();
  const dir = tempDir(t);
  const rows = [
    sessionFile(dir, { id: "ok", entries: [message("user", "needle")] }),
    { ...sessionFile(dir, { id: "gone", entries: [message("user", "needle")] }), path: join(dir, "missing.jsonl") },
  ];
  const search = await piSearch();
  const response = await findSessions(search, rows, { query: "needle", sort: "relevance", limit: 10 });
  assert.deepEqual(response.results.map((hit) => hit.session.id), ["ok"]);
  assert.equal(response.truncated, true);
});
