import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { createRunStatusStream } = await jiti.import("./run-status-stream.ts");
const decoder = new TextDecoder();

async function readWithin(reader, timeoutMs = 1_000) {
  let timeout;
  try {
    return await Promise.race([
      reader.read(),
      new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error("Timed out reading SSE chunk")), timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
}

function decodeData(chunk) {
  const text = decoder.decode(chunk.value);
  assert.match(text, /^data: .*\n\n$/);
  return JSON.parse(text.slice("data: ".length));
}

function status(overrides = {}) {
  return {
    sessionListVersion: 1,
    runningSessionIds: [],
    completionNotificationSuppressedSessionIds: [],
    waitingSessionIds: [],
    ...overrides,
  };
}

test("sends the snapshot on connect and again only when it changes", async () => {
  const abortController = new AbortController();
  let current = status({ runningSessionIds: ["b", "a"] });
  const stream = createRunStatusStream(
    new Request("http://localhost/running/events", { signal: abortController.signal }),
    () => current,
    { checkIntervalMs: 5, heartbeatIntervalMs: 60_000 },
  );
  const reader = stream.getReader();

  assert.deepEqual(decodeData(await readWithin(reader)), status({ runningSessionIds: ["a", "b"] }));

  // Same ids in another order: not a change.
  current = status({ runningSessionIds: ["a", "b"] });
  await new Promise((resolve) => setTimeout(resolve, 30));
  current = status({ runningSessionIds: ["a"], waitingSessionIds: ["a"] });
  assert.deepEqual(decodeData(await readWithin(reader)), status({ runningSessionIds: ["a"], waitingSessionIds: ["a"] }));

  abortController.abort();
  assert.equal((await readWithin(reader)).done, true);
});

test("stops reading the state once the client disconnects", async () => {
  const abortController = new AbortController();
  let reads = 0;
  const stream = createRunStatusStream(
    new Request("http://localhost/running/events", { signal: abortController.signal }),
    () => {
      reads++;
      return status();
    },
    { checkIntervalMs: 5, heartbeatIntervalMs: 60_000 },
  );
  const reader = stream.getReader();
  await readWithin(reader);
  abortController.abort();
  const readsAtAbort = reads;
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(reads, readsAtAbort);
});

test("sends heartbeats while nothing changes", async () => {
  const abortController = new AbortController();
  const stream = createRunStatusStream(
    new Request("http://localhost/running/events", { signal: abortController.signal }),
    () => status(),
    { checkIntervalMs: 5, heartbeatIntervalMs: 10 },
  );
  const reader = stream.getReader();
  await readWithin(reader);
  assert.equal(decoder.decode((await readWithin(reader)).value), ":\n\n");
  abortController.abort();
});

test("a failing state read skips the tick instead of closing the stream", async () => {
  const abortController = new AbortController();
  let fail = true;
  const originalError = console.error;
  console.error = () => {};
  try {
    const stream = createRunStatusStream(
      new Request("http://localhost/running/events", { signal: abortController.signal }),
      () => {
        if (fail) throw new Error("boom");
        return status({ sessionListVersion: 2 });
      },
      { checkIntervalMs: 5, heartbeatIntervalMs: 60_000 },
    );
    const reader = stream.getReader();
    await new Promise((resolve) => setTimeout(resolve, 20));
    fail = false;
    assert.deepEqual(decodeData(await readWithin(reader)), status({ sessionListVersion: 2 }));
    abortController.abort();
  } finally {
    console.error = originalError;
  }
});
