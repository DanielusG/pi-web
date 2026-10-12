// Behaviour coverage for the cold-session context indicator (`lib/cold-context.ts`)
// — the usage a session shows before any AgentSession exists for it. The fork added
// this file; nothing tested it, so the compaction rules could regress unnoticed.
// No model runtime is resolved here: the entries carry no `model_change`, so
// `contextWindow` stays null and no project services are created.
import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const { computeFileContextUsage } = await jiti.import("@/lib/cold-context");

const header = { id: "h1", parentId: null, type: "session", timestamp: "2026-01-01T00:00:00.000Z", cwd: "/tmp", version: 3 };
const user = { id: "m1", parentId: "h1", type: "message", message: { role: "user", content: "hello there" } };
const reply = (id, parentId, stopReason, totalTokens) => ({
  id,
  parentId,
  type: "message",
  message: {
    role: "assistant",
    content: [{ type: "text", text: "a reply" }],
    usage: { input: totalTokens - 20, output: 20, cacheRead: 0, cacheWrite: 0, totalTokens },
    stopReason,
  },
});
const compaction = (parentId) => ({ id: "c1", parentId, type: "compaction", summary: "compact", firstKeptEntryId: "m1", tokensBefore: 900 });

test("a cold session with assistant usage reports its tokens", async () => {
  const entries = [header, user, reply("m2", "m1", "end_turn", 150)];
  assert.deepEqual(await computeFileContextUsage(entries, "m2", "/tmp"), { tokens: 150, contextWindow: null, percent: null });
});

test("no leaf means no branch, not a wrong number", async () => {
  const entries = [header, user, reply("m2", "m1", "end_turn", 150)];
  const usage = await computeFileContextUsage(entries, null, "/tmp");
  assert.equal(usage.contextWindow, null);
  assert.equal(usage.percent, null);
  assert.ok(Number.isFinite(usage.tokens));
});

test("an empty session reports zero tokens instead of null", async () => {
  assert.deepEqual(await computeFileContextUsage([], undefined, "/tmp"), { tokens: 0, contextWindow: null, percent: null });
});

test("a compaction with no kept usage reports nothing rather than the old total", async () => {
  // The indicator must not show the pre-compaction total: `tokensBefore` is not
  // current context, and an aborted or errored reply after the summary carries no
  // usable usage either.
  const summaryLeaf = [header, user, compaction("m1")];
  assert.deepEqual(await computeFileContextUsage(summaryLeaf, "c1", "/tmp"), { tokens: null, contextWindow: null, percent: null });

  const aborted = [...summaryLeaf, reply("m3", "c1", "aborted", 70)];
  assert.deepEqual(await computeFileContextUsage(aborted, "m3", "/tmp"), { tokens: null, contextWindow: null, percent: null });

  const settled = [...summaryLeaf, reply("m2", "c1", "end_turn", 70)];
  assert.deepEqual(await computeFileContextUsage(settled, "m2", "/tmp"), { tokens: 70, contextWindow: null, percent: null });
});
