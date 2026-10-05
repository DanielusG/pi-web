import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const agentEventsSource = await readFile(new URL("./[id]/events/route.ts", import.meta.url), "utf8");
const agentEventStreamSource = await readFile(new URL("../../../lib/agent-event-stream.ts", import.meta.url), "utf8");

test("agent SSE starts sessions asynchronously and disables response buffering", () => {
  assert.match(agentEventsSource, /createAgentEventStream\(req, id, sessionPromise, \{ toolUpdates \}\)/);
  assert.match(agentEventsSource, /sessionPromise = startRpcSession\([\s\S]*?\.then\(\(result\) => result\.session\)/);
  assert.doesNotMatch(agentEventsSource, /await startRpcSession\(/);
  assert.match(agentEventsSource, /if \(req\.signal\.aborted\) return new Response\(null, \{ status: 204 \}\)/);
  assert.match(agentEventsSource, /"Cache-Control": "no-cache, no-transform"/);
  assert.match(agentEventsSource, /"X-Accel-Buffering": "no"/);
});

test("agent SSE reuses one TextEncoder per stream", () => {
  assert.equal((agentEventStreamSource.match(/new TextEncoder\(\)/g) ?? []).length, 1);
  assert.match(agentEventStreamSource, /controller\.enqueue\(encoder\.encode\(/);
});

test("agent SSE sends tool output tails only to clients that ask for them", () => {
  assert.match(agentEventsSource, /searchParams\.get\("toolUpdates"\) === "tail" \? "tail" : "full"/);
  // The per-client choice reaches the projection, and every projected event goes
  // through the coalescing sender (`clientOptions` is named apart from the
  // per-write `options` that carries `droppable`).
  assert.match(agentEventStreamSource, /toClientAgentEvent\(event, clientOptions\)/);
  assert.match(agentEventStreamSource, /if \(clientEvent\) sendClientEvent\(clientEvent\)/);
});
