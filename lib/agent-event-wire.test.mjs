import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const {
  isEventIncludedInSnapshot,
  isSystemMessageEvent,
  toClientAgentEvent,
} = await jiti.import("./agent-event-wire.ts");

function assistantMessage(text) {
  return {
    role: "assistant",
    content: [{ type: "text", text }],
    api: "anthropic-messages",
    provider: "anthropic",
    model: "claude-sonnet-4-6",
    usage: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      totalTokens: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    },
    stopReason: "pending",
    timestamp: 1,
  };
}

test("projects message_update onto Pi 0.84's JSON/RPC delta shape", () => {
  const partial = assistantMessage("Hello");
  const projected = toClientAgentEvent({
    type: "message_update",
    message: partial,
    assistantMessageEvent: {
      type: "text_delta",
      contentIndex: 0,
      delta: "o",
      partial,
    },
  });

  assert.deepEqual(projected, {
    type: "message_update",
    assistantMessageEvent: {
      type: "text_delta",
      contentIndex: 0,
      delta: "o",
    },
  });
  assert.equal(Object.hasOwn(projected, "message"), false);
  assert.equal(Object.hasOwn(projected.assistantMessageEvent, "partial"), false);
});

test("keeps tool identity on toolcall_start without sending the cumulative partial", () => {
  const partial = {
    ...assistantMessage(""),
    content: [{
      type: "toolCall",
      id: "call-write-1",
      name: "write",
      arguments: {},
      partialJson: "",
    }],
  };
  const projected = toClientAgentEvent({
    type: "message_update",
    message: partial,
    assistantMessageEvent: {
      type: "toolcall_start",
      contentIndex: 0,
      partial,
    },
  });

  assert.deepEqual(projected, {
    type: "message_update",
    assistantMessageEvent: {
      type: "toolcall_start",
      contentIndex: 0,
      id: "call-write-1",
      toolName: "write",
    },
  });
  assert.equal(Object.hasOwn(projected, "message"), false);
  assert.equal(Object.hasOwn(projected.assistantMessageEvent, "partial"), false);
});

test("refreshes late tool identity on toolcall_delta", () => {
  const partial = {
    ...assistantMessage(""),
    content: [{
      type: "toolCall",
      id: "call-late-1",
      name: "write",
      arguments: { path: "/tmp/file" },
      partialJson: '{"path":"/tmp/file"',
    }],
  };
  const projected = toClientAgentEvent({
    type: "message_update",
    message: partial,
    assistantMessageEvent: {
      type: "toolcall_delta",
      contentIndex: 0,
      delta: '"/tmp/file"',
      partial,
    },
  });

  assert.deepEqual(projected, {
    type: "message_update",
    assistantMessageEvent: {
      type: "toolcall_delta",
      contentIndex: 0,
      delta: '"/tmp/file"',
      id: "call-late-1",
      toolName: "write",
    },
  });
});

test("does not whitelist assistant delta event types", () => {
  const projected = toClientAgentEvent({
    type: "message_update",
    message: assistantMessage("future"),
    assistantMessageEvent: {
      type: "future_delta",
      contentIndex: 3,
      value: "kept",
      partial: assistantMessage("future"),
    },
  });

  assert.deepEqual(projected, {
    type: "message_update",
    assistantMessageEvent: {
      type: "future_delta",
      contentIndex: 3,
      value: "kept",
    },
  });
});

test("rejects a malformed message_update without breaking the stream", () => {
  assert.equal(toClientAgentEvent({
    type: "message_update",
    assistantMessageEvent: null,
  }), null);
});

test("recognizes only the in-flight event already covered by a snapshot", () => {
  const snapshot = assistantMessage("Hello");
  assert.equal(isEventIncludedInSnapshot({
    type: "message_update",
    message: snapshot,
    assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "o" },
  }, snapshot), true);
  assert.equal(isEventIncludedInSnapshot({
    type: "message_update",
    message: { ...snapshot },
    assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "!" },
  }, snapshot), false);
  assert.equal(isEventIncludedInSnapshot({
    type: "message_end",
    message: snapshot,
  }, snapshot), false);
});

test("keeps the existing event omissions and slim agent_end", () => {
  assert.equal(toClientAgentEvent({ type: "turn_start" }), null);
  assert.equal(toClientAgentEvent({ type: "turn_end" }), null);
  assert.deepEqual(
    toClientAgentEvent({ type: "agent_end", messages: [assistantMessage("done")] }),
    { type: "agent_end" },
  );

  const messageStart = { type: "message_start", message: assistantMessage("") };
  assert.strictEqual(toClientAgentEvent(messageStart), messageStart);
});

test("forwards tool execution progress without repeating tool arguments", () => {
  const partialResult = {
    content: [{ type: "text", text: "Running phase 2" }],
    details: { phase: 2 },
  };

  assert.deepEqual(toClientAgentEvent({
    type: "tool_execution_update",
    toolCallId: "call-workflow-1",
    toolName: "workflow",
    args: { large: "repeated input" },
    partialResult,
  }), {
    type: "tool_execution_update",
    toolCallId: "call-workflow-1",
    toolName: "workflow",
    partialResult,
  });
});

function projectedStreamBytes(totalLength) {
  const chunkSize = 64;
  let text = "";
  let bytes = 0;

  for (let offset = 0; offset < totalLength; offset += chunkSize) {
    const delta = "x".repeat(Math.min(chunkSize, totalLength - offset));
    text += delta;
    const partial = assistantMessage(text);
    const projected = toClientAgentEvent({
      type: "message_update",
      message: partial,
      assistantMessageEvent: {
        type: "text_delta",
        contentIndex: 0,
        delta,
        partial,
      },
    });

    assert.equal(Object.hasOwn(projected, "message"), false);
    assert.equal(Object.hasOwn(projected.assistantMessageEvent, "partial"), false);
    bytes += Buffer.byteLength(JSON.stringify(projected));
  }

  return bytes;
}

test("serialized streaming traffic grows linearly with response length", () => {
  const twoKiB = projectedStreamBytes(2 * 1024);
  const fourKiB = projectedStreamBytes(4 * 1024);
  assert.ok(fourKiB / twoKiB < 2.2, `expected near-linear growth, got ${fourKiB / twoKiB}`);
});

function projectedToolStreamBytes(totalLength) {
  const chunkSize = 64;
  let rawInput = "";
  let bytes = 0;

  for (let offset = 0; offset < totalLength; offset += chunkSize) {
    const delta = "x".repeat(Math.min(chunkSize, totalLength - offset));
    rawInput += delta;
    const partial = {
      ...assistantMessage(""),
      content: [{
        type: "toolCall",
        id: "call-write-1",
        name: "write",
        arguments: { content: rawInput },
        partialJson: rawInput,
      }],
    };
    const projected = toClientAgentEvent({
      type: "message_update",
      message: partial,
      assistantMessageEvent: {
        type: "toolcall_delta",
        contentIndex: 0,
        delta,
        partial,
      },
    });

    assert.equal(Object.hasOwn(projected, "message"), false);
    assert.equal(Object.hasOwn(projected.assistantMessageEvent, "partial"), false);
    bytes += Buffer.byteLength(JSON.stringify(projected));
  }

  return bytes;
}

test("serialized tool-input traffic grows linearly with write content", () => {
  const twoKiB = projectedToolStreamBytes(2 * 1024);
  const fourKiB = projectedToolStreamBytes(4 * 1024);
  assert.ok(fourKiB / twoKiB < 2.2, `expected near-linear growth, got ${fourKiB / twoKiB}`);
});

test("drops transcript system messages before they reach the browser", () => {
  // Pi >= 0.86 announces the persisted prompt and tool loadout as a system
  // message; it is never rendered and carries every tool schema.
  const systemMessage = {
    role: "system",
    content: "",
    sections: { preamble: "You are an expert coding assistant." },
    toolsAdded: [{ name: "read", description: "Read a file", parameters: { type: "object" } }],
    timestamp: 1,
  };
  assert.equal(toClientAgentEvent({ type: "message_start", message: systemMessage }), null);
  assert.equal(toClientAgentEvent({ type: "message_end", message: systemMessage }), null);
  assert.equal(isSystemMessageEvent({ type: "message_end", message: systemMessage }), true);
  assert.equal(isSystemMessageEvent({ type: "message_end", message: assistantMessage("hi") }), false);
  assert.equal(isSystemMessageEvent({ type: "agent_end" }), false);

  const userEnd = { type: "message_end", message: { role: "user", content: "hello", timestamp: 1 } };
  assert.strictEqual(toClientAgentEvent(userEnd), userEnd);
});

function toolUpdate(partialResult) {
  return {
    type: "tool_execution_update",
    toolCallId: "call-bash-1",
    toolName: "bash",
    args: { command: "make" },
    partialResult,
  };
}

function textOutput(text, details = { truncation: null }) {
  return { content: [{ type: "text", text }], details };
}

function outputText(event) {
  return event.partialResult.content[0].text;
}

const manyLines = Array.from({ length: 5000 }, (_, i) => `line ${i + 1}`).join("\n") + "\n";

test("tool updates keep the full output unless the client asks for the tail", () => {
  assert.equal(outputText(toClientAgentEvent(toolUpdate(textOutput(manyLines)))), manyLines);
  assert.equal(
    outputText(toClientAgentEvent(toolUpdate(textOutput(manyLines)), { toolUpdates: "full" })),
    manyLines,
  );
});

test("a tail leaves output within its limits unchanged", () => {
  const short = "compiling\n\nwarning: unused\ndone\n";
  const event = toolUpdate(textOutput(short, { exitCode: null }));
  assert.deepEqual(toClientAgentEvent(event, { toolUpdates: "tail" }), toClientAgentEvent(event));
});

test("a tail keeps the last 16 lines", () => {
  const tail = outputText(toClientAgentEvent(toolUpdate(textOutput(manyLines)), { toolUpdates: "tail" }));
  const expected = Array.from({ length: 16 }, (_, i) => `line ${4985 + i}`).join("\n") + "\n";
  assert.equal(tail, expected);
});

test("a tail of long lines stays within 8000 characters and starts at a line start", () => {
  const lines = Array.from({ length: 400 }, (_, i) => `${i}:${"x".repeat(500)}`);
  const text = lines.join("\n");
  const tail = outputText(toClientAgentEvent(toolUpdate(textOutput(text)), { toolUpdates: "tail" }));
  assert.ok(tail.length <= 8000, `tail is ${tail.length} characters`);
  assert.ok(tail.split("\n").length <= 16);
  assert.ok(text.endsWith(tail));
  assert.ok(lines.includes(tail.split("\n")[0]), "the first line is complete");
});

test("a tail of one huge line keeps its end", () => {
  const text = "y".repeat(200_000) + "END";
  const tail = outputText(toClientAgentEvent(toolUpdate(textOutput(text)), { toolUpdates: "tail" }));
  assert.equal(tail.length, 8000);
  assert.ok(tail.endsWith("END"));
});

test("a tail keeps the tool identity, details and non-text blocks", () => {
  const image = { type: "image", data: "AAAA", mimeType: "image/png" };
  const projected = toClientAgentEvent(toolUpdate({
    content: [{ type: "text", text: manyLines }, image],
    details: { truncation: { truncated: true }, fullOutputPath: "/tmp/out.log" },
  }), { toolUpdates: "tail" });
  assert.equal(projected.toolCallId, "call-bash-1");
  assert.equal(projected.toolName, "bash");
  assert.equal(Object.hasOwn(projected, "args"), false);
  assert.deepEqual(projected.partialResult.details, { truncation: { truncated: true }, fullOutputPath: "/tmp/out.log" });
  assert.deepEqual(projected.partialResult.content[1], image);
});

test("a tail passes updates without text content through", () => {
  for (const partialResult of [undefined, "progress", { details: { phase: 2 } }]) {
    assert.deepEqual(
      toClientAgentEvent(toolUpdate(partialResult), { toolUpdates: "tail" }),
      toClientAgentEvent(toolUpdate(partialResult)),
    );
  }
});

test("the tail option only touches tool progress updates", () => {
  const end = {
    type: "tool_execution_end",
    toolCallId: "call-bash-1",
    toolName: "bash",
    result: textOutput(manyLines),
    isError: false,
  };
  assert.deepEqual(toClientAgentEvent(end, { toolUpdates: "tail" }), end);
});
