// Behaviour coverage for the fork's tool display helpers — the collapsed header's
// one-line summary and the expanded argument view (`ToolCallBlock` reads them).
// Upstream deleted `ToolDefinitionsPanel.test.mjs` in v0.12.0 (`f364bc3`) and these
// helpers never had a file of their own, so nothing else pins what a tool row shows.
import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const { toolSummaryLine, getToolArgsView } = await jiti.import("@/lib/tool-display");

test("a command's summary is its first line, trimmed and clipped", () => {
  assert.equal(toolSummaryLine("bash", { command: "  \nnpm test\nmore" }), "npm test");
  assert.equal(toolSummaryLine("run_command", { cmd: "ls -la" }), "ls -la");
  const long = "x".repeat(200);
  const line = toolSummaryLine("bash", { command: long });
  assert.equal(line.length, 161); // 160 + the single-char ellipsis
  assert.ok(line.endsWith("…"));
  assert.equal(toolSummaryLine("bash", {}), "");
  assert.equal(toolSummaryLine("bash", null), "");
  assert.equal(toolSummaryLine("bash", ["not", "a", "record"]), "");
});

test("file calls summarize as their path, with the read window and edit count", () => {
  assert.equal(toolSummaryLine("read", { path: "/a/b.ts" }), "/a/b.ts");
  assert.equal(toolSummaryLine("read", { path: "/a/b.ts", offset: 120, limit: 40 }), "/a/b.ts (from line 120, 40 lines)");
  assert.equal(toolSummaryLine("read", { path: "/a/b.ts", limit: 1 }), "/a/b.ts (1 lines)");
  assert.equal(toolSummaryLine("read", {}), "");
  assert.equal(toolSummaryLine("write", { path: "/a/b.ts" }), "/a/b.ts");
  assert.equal(toolSummaryLine("edit", { path: "/a/b.ts", edits: [{ oldText: "a" }, { oldText: "b" }] }), "/a/b.ts · 2 edits");
  assert.equal(toolSummaryLine("edit", { path: "/a/b.ts", edits: [{ oldText: "a" }] }), "/a/b.ts · 1 edit");
  assert.equal(toolSummaryLine("edit", { path: "/a/b.ts", edits: [] }), "/a/b.ts");
  assert.equal(toolSummaryLine("ls", { path: "/a" }), "/a");
});

test("searches, fetches and agent calls summarize by what they look for", () => {
  assert.equal(toolSummaryLine("grep", { pattern: "TODO", path: "/src" }), "TODO in /src");
  assert.equal(toolSummaryLine("find", { pattern: "x".repeat(150) }).length, 121);
  assert.equal(toolSummaryLine("grep", {}), "");
  assert.equal(toolSummaryLine("web_search", { query: "pi web fork" }), "pi web fork");
  assert.equal(toolSummaryLine("web_fetch", { url: "https://pi.dev" }), "https://pi.dev");
  assert.equal(toolSummaryLine("batch_web_fetch", { requests: [{ url: "a" }, { url: "b" }, { url: "c" }] }), "3 urls");
  assert.equal(toolSummaryLine("batch_web_fetch", { requests: [{ url: "a" }] }), "1 url");
  assert.equal(toolSummaryLine("batch_web_fetch", { requests: [] }), "");
  assert.equal(toolSummaryLine("Agent", { description: "Review the merge" }), "Review the merge");
  assert.equal(toolSummaryLine("ask_user_question", { questions: [{}, {}] }), "2 questions");
  assert.equal(toolSummaryLine("compress_tool", { tool_compressions: [{}, {}] }), "compress 2 results");
  assert.equal(toolSummaryLine("steer_subagent", { agent_id: "abc123" }), "abc123");
  assert.equal(toolSummaryLine("get_subagent_result", { agent_id: "abc123" }), "abc123");
});

test("an unknown tool falls back to well-known fields, then to its first string", () => {
  assert.equal(toolSummaryLine("mcp__anything__search", { query: "alpha" }), "alpha");
  assert.equal(toolSummaryLine("mcp__anything__run", { path: "/x" }), "/x");
  assert.equal(toolSummaryLine("mcp__anything__weird", { note: "first string wins" }), "first string wins");
  assert.equal(toolSummaryLine("mcp__anything__empty", { count: 3 }), "");
});

test("the expanded view picks pre, meta or json per tool", () => {
  assert.deepEqual(getToolArgsView("bash", { command: "npm test" }), { kind: "pre", text: "npm test" });
  assert.deepEqual(getToolArgsView("read", { path: "/a/b.ts", offset: 5, limit: 10 }), {
    kind: "meta",
    lines: ["/a/b.ts", "offset 5 · limit 10"],
  });
  assert.deepEqual(getToolArgsView("web_fetch", { url: "https://pi.dev" }), { kind: "meta", lines: ["https://pi.dev"] });
  assert.deepEqual(getToolArgsView("Agent", { description: "Review", prompt: "look here" }), {
    kind: "pre",
    label: "Review",
    text: "look here",
  });
});

test("a write keeps its file-text view only when nothing else would vanish", () => {
  // Upstream's rule (#1024): a call with any argument outside path/file_path/content
  // keeps the JSON, so the extra argument stays visible instead of disappearing.
  assert.deepEqual(getToolArgsView("write", { path: "/a.txt", content: "hello" }), {
    kind: "pre",
    label: "/a.txt",
    text: "hello",
  });
  assert.deepEqual(getToolArgsView("write", { file_path: "/a.txt", content: "hello" }), {
    kind: "pre",
    label: "/a.txt",
    text: "hello",
  });
  const withMode = getToolArgsView("write", { path: "/a.txt", content: "hello", mode: "644" });
  assert.equal(withMode.kind, "json");
  assert.ok(withMode.text.includes("644"));
  const empty = getToolArgsView("write", { path: "/a.txt", content: "" });
  assert.equal(empty.kind, "json");
});

test("anything else renders as pretty JSON, arrays and null included", () => {
  assert.deepEqual(getToolArgsView("whatever", ["a", "b"]), { kind: "json", text: JSON.stringify(["a", "b"], null, 2) });
  assert.deepEqual(getToolArgsView("whatever", null), { kind: "json", text: "null" });
  assert.equal(getToolArgsView("whatever", { a: 1 }).kind, "json");
});
