// GET /api/sessions/find (fork addition, Android client): pi's /resume search
// over the catalogue, answered in the trimmed shape the app parses
// (android/app/src/main/java/app/pimobile/ui/sessions/SessionSearch.kt).
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const { GET } = await jiti.import("./find/route.ts");

function row(dir, { id, cwd, name, modified, firstMessage = "first", text, relation, projectKey }) {
  const path = join(dir, `${id}.jsonl`);
  writeFileSync(path, [
    { type: "session", version: 3, id, timestamp: modified, cwd },
    { type: "message", id: `${id}-m`, parentId: null, timestamp: modified, message: { role: "user", content: text } },
  ].map((line) => JSON.stringify(line)).join("\n") + "\n");
  return {
    path, id, cwd, ...(name ? { name } : {}), created: modified, modified, messageCount: 1, firstMessage,
    projectRoot: cwd, projectKey: projectKey ?? cwd, branch: "main", ...(relation ? { relation } : {}), transient: false,
  };
}

function seed(t, sessions) {
  const previousCache = globalThis.__piSessionListCache;
  const previousGeneration = globalThis.__piSessionListGeneration;
  t.after(() => {
    globalThis.__piSessionListCache = previousCache;
    globalThis.__piSessionListGeneration = previousGeneration;
  });
  globalThis.__piSessionListGeneration = 0;
  globalThis.__piSessionListCache = { ts: Date.now(), generation: 0, data: sessions };
}

async function find(params) {
  const res = await GET(new Request(`http://localhost/api/sessions/find?${new URLSearchParams(params)}`));
  return { status: res.status, body: await res.json() };
}

test("find route: pi's matching, project scope, trimmed rows with the hit", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "pi-web-find-route-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  seed(t, [
    row(dir, { id: "a", cwd: "/work/alpha", name: "Alpha deploy", modified: "2026-01-01T00:00:00.000Z", text: "unrelated" }),
    row(dir, { id: "b", cwd: "/work/alpha", modified: "2026-01-03T00:00:00.000Z", firstMessage: "x".repeat(300), text: "the deploy failed" }),
    row(dir, { id: "c", cwd: "/work/beta", modified: "2026-01-02T00:00:00.000Z", text: "deploy beta",
      relation: { kind: "subagent", parentSessionId: "b", profile: "Explore", description: "d", status: "completed" } }),
    row(dir, { id: "s", cwd: "/work/alpha", modified: "2026-01-04T00:00:00.000Z", text: "deploy side", relation: { kind: "side", parentSessionId: "b" } }),
  ]);

  const relevance = await find({ q: "deploy" });
  assert.equal(relevance.status, 200);
  assert.equal(relevance.body.sort, "relevance");
  // pi ranks a name hit above a message hit; the side chat is left out.
  assert.deepEqual(relevance.body.results.map((hit) => hit.id), ["a", "b", "c"]);
  const [a, b, c] = relevance.body.results;
  assert.equal(a.path, undefined);
  assert.deepEqual(a.nameMatch, { before: "Alpha ", match: "deploy", after: "" });
  assert.equal(b.firstMessage.length, 201);
  assert.deepEqual(b.snippet, { entryId: "b-m", blockIndex: 0, before: "the ", match: "deploy", after: " failed" });
  assert.equal(b.projectKey, "/work/alpha");
  assert.equal(b.projectRoot, "/work/alpha");
  assert.equal(b.branch, "main");
  assert.deepEqual(c.relation, { kind: "subagent", parentSessionId: "b", profile: "Explore" });
  assert.equal(relevance.body.total, 3);
  assert.equal(relevance.body.hasMore, false);

  const recent = await find({ q: "deploy", sort: "recent", limit: "1" });
  assert.deepEqual(recent.body.results.map((hit) => hit.id), ["b"]);
  assert.equal(recent.body.hasMore, true);

  const scoped = await find({ q: "deploy", project: "/work/beta" });
  assert.deepEqual(scoped.body.results.map((hit) => hit.id), ["c"]);
  assert.equal(scoped.body.total, 1);

  const invalid = await find({ q: "re:(" });
  assert.equal(invalid.status, 200);
  assert.deepEqual(invalid.body.results, []);
  assert.ok(invalid.body.queryError);
});

test("find route: refuses an over-long query, answers an empty one with nothing", async (t) => {
  seed(t, []);
  assert.equal((await find({ q: "x".repeat(201) })).status, 400);
  const empty = await find({ q: "   " });
  assert.equal(empty.status, 200);
  assert.deepEqual(empty.body.results, []);
});
