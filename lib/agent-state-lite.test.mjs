import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { liteAgentState } = await jiti.import("./agent-state-lite.ts");
const route = await readFile(new URL("../app/api/agent/[id]/route.ts", import.meta.url), "utf8");

test("the lite state leaves out only the system prompt", () => {
  const state = {
    sessionId: "s1",
    isStreaming: true,
    contextUsage: { percent: 12, contextWindow: 200000, tokens: 24000 },
    systemPrompt: "x".repeat(40_000),
    thinkingLevel: "medium",
  };
  const expected = { ...state };
  delete expected.systemPrompt;
  assert.deepEqual(liteAgentState(state), expected);
  assert.equal(state.systemPrompt.length, 40_000, "the live state is not modified");
});

test("a state that is not an object passes through", () => {
  for (const state of [undefined, null, "state", [1, 2]]) assert.deepEqual(liteAgentState(state), state);
});

test("GET /api/agent/[id] serves the lite state only when asked", () => {
  assert.match(route, /searchParams\.get\("lite"\) === "1"/);
  assert.match(route, /state: lite \? liteAgentState\(state\) : state/);
});
