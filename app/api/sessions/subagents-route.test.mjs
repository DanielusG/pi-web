// Coverage for GET /api/sessions/[id]/subagents: the lightweight subagent-bar
// endpoint. It must return only the session's direct subagent children (not
// forks, not other parents' subagents, not unrelated sessions), ordered
// running-first then newest-first, with the live `running` flag computed from
// the registry. Mirrors the mocking style of runtime-route.test.mjs.
import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const { GET } = await jiti.import("./[id]/subagents/route.ts");

const id = "parent-session";
const context = { params: Promise.resolve({ id }) };

function session(info) {
  return {
    path: `/tmp/${info.id}.jsonl`,
    id: info.id,
    cwd: "/tmp",
    created: "2026-01-01T00:00:00.000Z",
    modified: info.modified ?? "2026-01-01T00:00:00.000Z",
    messageCount: 1,
    firstMessage: info.firstMessage ?? "(no messages)",
    ...(info.name !== undefined ? { name: info.name } : {}),
    ...(info.relation ? { relation: info.relation } : {}),
    transient: false,
  };
}

test("subagents route: direct children only, running-first then newest", async (t) => {
  const previousCache = globalThis.__piSessionListCache;
  const previousGeneration = globalThis.__piSessionListGeneration;
  const previousRegistry = globalThis.__piSessions;
  t.after(() => {
    globalThis.__piSessionListCache = previousCache;
    globalThis.__piSessionListGeneration = previousGeneration;
    globalThis.__piSessions = previousRegistry;
  });

  globalThis.__piSessionListGeneration = 0;
  globalThis.__piSessionListCache = {
    ts: Date.now(),
    generation: 0,
    data: [
      session({ id: "a", modified: "2026-01-01T01:00:00.000Z", relation: { kind: "subagent", parentSessionId: id, profile: "Explore", description: "task a", status: "completed" } }),
      session({ id: "b", modified: "2026-01-01T03:00:00.000Z", relation: { kind: "subagent", parentSessionId: id, profile: "Explore", description: "task b", status: "completed" } }),
      session({ id: "c", modified: "2026-01-01T02:00:00.000Z", relation: { kind: "subagent", parentSessionId: "elsewhere", profile: "Explore", description: "task c", status: "completed" } }),
      session({ id: "d", modified: "2026-01-01T04:00:00.000Z", relation: { kind: "fork", originSessionId: id } }),
      session({ id: "e", modified: "2026-01-01T05:00:00.000Z" }),
    ],
  };
  globalThis.__piSessions = new Map([
    ["a", { isAlive: () => true, isRunning: () => true, sessionId: "a" }],
  ]);

  const res = await GET(new Request(`http://localhost/api/sessions/${id}/subagents`), context);
  assert.equal(res.status, 200);
  const body = await res.json();
  // Only a and b are direct subagents of `id`; c (other parent), d (fork), e (none) excluded.
  assert.deepEqual(body.subagents.map((s) => s.id), ["a", "b"]);
  // a is live-running -> first, even though b is newer.
  assert.equal(body.subagents[0].id, "a");
  assert.equal(body.subagents[0].running, true);
  assert.equal(body.subagents[1].id, "b");
  assert.equal(body.subagents[1].running, false);
  // Per-item fields are flattened (no nested relation).
  assert.equal(body.subagents[0].profile, "Explore");
  assert.equal(body.subagents[0].description, "task a");
  assert.equal(body.subagents[0].status, "completed");
  assert.equal(body.subagents[0].firstMessage, "(no messages)");
});

test("subagents route: empty when the session has no subagents", async (t) => {
  const previousCache = globalThis.__piSessionListCache;
  const previousGeneration = globalThis.__piSessionListGeneration;
  const previousRegistry = globalThis.__piSessions;
  t.after(() => {
    globalThis.__piSessionListCache = previousCache;
    globalThis.__piSessionListGeneration = previousGeneration;
    globalThis.__piSessions = previousRegistry;
  });

  globalThis.__piSessionListGeneration = 0;
  globalThis.__piSessionListCache = {
    ts: Date.now(),
    generation: 0,
    data: [session({ id: "x", relation: { kind: "fork", originSessionId: id } })],
  };
  globalThis.__piSessions = new Map();

  const res = await GET(new Request(`http://localhost/api/sessions/${id}/subagents`), context);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body.subagents, []);
});
