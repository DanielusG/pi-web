import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./running/events/route.ts", import.meta.url), "utf8");

test("run status SSE exposes the running snapshot plus waiting sessions without buffering", () => {
  assert.match(source, /createRunStatusStream\(req, \(\) => \(\{/);
  for (const field of ["sessionListVersion", "runningSessionIds", "completionNotificationSuppressedSessionIds", "waitingSessionIds"]) {
    assert.match(source, new RegExp(`${field}:`));
  }
  assert.match(source, /if \(req\.signal\.aborted\) return new Response\(null, \{ status: 204 \}\)/);
  assert.match(source, /"Cache-Control": "no-cache, no-transform"/);
  assert.match(source, /"X-Accel-Buffering": "no"/);
});
