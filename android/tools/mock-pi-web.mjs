// Mock pi-web backend for exercising the Android client without an LLM.
// Wire shapes follow the contract mapped from pi-web main (df32731).
// Auth: Basic pi:test.  Extras: POST /mock/drop kills all SSE sockets.
import http from "node:http";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.PORT || 30150);
const AUTH = "Basic " + Buffer.from("pi:test").toString("base64");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let listVersion = 1;
const sessions = new Map();

function iso(minutesAgo) { return new Date(Date.now() - minutesAgo * 60_000).toISOString(); }
let entrySeq = 0;
const entryId = () => (++entrySeq).toString(16).padStart(8, "0");

function makeSession({ id = randomUUID(), cwd, name, ageMin = 5, messages = [] }) {
  const s = {
    id, cwd, name, created: iso(ageMin + 60), modified: iso(ageMin),
    entries: messages.map((m) => ({ id: entryId(), message: m })),
    clients: new Set(), live: false, running: false, aborted: false,
    model: { provider: "anthropic", id: "claude-sonnet-4-5" }, thinkingLevel: "medium",
    uiWaiters: new Map(), fullThinking: new Map(), snapshot: null,
  };
  sessions.set(id, s);
  return s;
}

function info(s) {
  const firstUser = s.entries.find((e) => e.message.role === "user");
  const text = firstUser ? (typeof firstUser.message.content === "string" ? firstUser.message.content : firstUser.message.content.find((b) => b.type === "text")?.text) : "(no messages)";
  return {
    path: `/home/demo/.pi/agent/sessions/x/${s.id}.jsonl`, id: s.id, cwd: s.cwd, ...(s.name ? { name: s.name } : {}),
    created: s.created, modified: s.modified, messageCount: s.entries.length, firstMessage: text,
    projectRoot: s.cwd, projectKey: s.cwd, branch: "main", transient: false,
  };
}

const LONG_MD = `Here's what I found and changed.

## Summary
The health endpoint now lives in \`app/api/health/route.ts\` and returns the **uptime** and the number of *running* sessions.

\`\`\`ts
export async function GET() {
  return Response.json({ ok: true, uptime: process.uptime() });
}
\`\`\`

1. Added the route
2. Wired it into the proxy allow-list
3. Added a unit test

| File | Change |
|------|--------|
| app/api/health/route.ts | new |
| proxy.ts | +3 −1 |

> Note: the endpoint is unauthenticated on purpose so load balancers can probe it.

See [the Next.js docs](https://nextjs.org/docs) for route handlers.`;

// Unified patches in the SDK's edit `details.patch` format (4 lines of context).
const RATE_PATCH = [
  "--- lib/rate-limit.ts",
  "+++ lib/rate-limit.ts",
  "@@ -8,9 +8,10 @@",
  ' import { getClientIp } from "./request";',
  " ",
  " const WINDOW_MS = 60_000;",
  "-const MAX_REQUESTS = 20;",
  "+const MAX_REQUESTS = 50;",
  "+const BURST = 10;",
  " ",
  " export function isRateLimited(req: Request): boolean {",
  "   const ip = getClientIp(req);",
  "-  const hits = store.get(ip) ?? 0;",
  "+  const hits = (store.get(ip) ?? 0) + 1;",
  "   return hits > MAX_REQUESTS;",
  "@@ -41,3 +42,3 @@",
  " export function resetLimits() {",
  "-  store.clear();",
  "+  store.clear(); // also drops burst tokens",
  " }",
  "",
].join("\n");

const HEALTH_PATCH = [
  "--- app/api/health/route.ts",
  "+++ app/api/health/route.ts",
  "@@ -1,5 +1,5 @@",
  ' export const dynamic = "force-dynamic";',
  " ",
  " export async function GET() {",
  "-  return Response.json({ ok: true });",
  "+  return Response.json({ ok: true, uptime: process.uptime() });",
  " }",
  "",
].join("\n");

// ---- seed data -------------------------------------------------------------
const rich = makeSession({
  cwd: "/home/demo/projects/pi-web", name: "Add a health endpoint", ageMin: 3,
  messages: [
    { role: "user", content: "Can you add a health endpoint to the API?", timestamp: Date.now() - 600000 },
    { role: "assistant", provider: "anthropic", model: "claude-sonnet-4-5", stopReason: "toolUse", timestamp: Date.now() - 590000,
      content: [
        { type: "thinking", thinking: "The user wants a health endpoint.", deferred: true },
        { type: "text", text: "I'll look at the existing API routes first." },
        { type: "toolCall", toolCallId: "t1", toolName: "bash", input: { command: "ls app/api" } },
      ] },
    { role: "toolResult", toolCallId: "t1", toolName: "bash", content: [{ type: "text", text: "agent\nauth\nmodels\nsessions\nskills\n" }], isError: false, timestamp: Date.now() - 589000 },
    { role: "assistant", provider: "anthropic", model: "claude-sonnet-4-5", stopReason: "toolUse", timestamp: Date.now() - 580000,
      content: [
        { type: "toolCall", toolCallId: "t2", toolName: "write", input: { path: "app/api/health/route.ts", content: "export async function GET() {\n  return Response.json({ ok: true, uptime: process.uptime() });\n}\n" } },
        { type: "toolCall", toolCallId: "t3", toolName: "read", input: { path: "proxy.ts" } },
      ] },
    { role: "toolResult", toolCallId: "t2", toolName: "write", content: [{ type: "text", text: "Wrote 3 lines" }], isError: false },
    { role: "toolResult", toolCallId: "t3", toolName: "read", content: [{ type: "text", text: "ENOENT: no such file" }], isError: true },
    { role: "assistant", provider: "anthropic", model: "claude-sonnet-4-5", stopReason: "toolUse", timestamp: Date.now() - 575000,
      content: [
        { type: "text", text: "The limiter is too strict for health probes, so I'll relax it." },
        { type: "toolCall", toolCallId: "t4", toolName: "edit", input: { path: "lib/rate-limit.ts", edits: [
          { oldText: "const MAX_REQUESTS = 20;", newText: "const MAX_REQUESTS = 50;\nconst BURST = 10;" },
          { oldText: "const hits = store.get(ip) ?? 0;", newText: "const hits = (store.get(ip) ?? 0) + 1;" },
          { oldText: "store.clear();", newText: "store.clear(); // also drops burst tokens" },
        ] } },
      ] },
    { role: "toolResult", toolCallId: "t4", toolName: "edit", isError: false, timestamp: Date.now() - 571000,
      content: [{ type: "text", text: "Successfully replaced 3 block(s) in lib/rate-limit.ts." }],
      details: { diff: "", patch: RATE_PATCH, firstChangedLine: 11 } },
    { role: "bashExecution", command: "npm test", output: "✓ 214 tests passed\n", exitCode: 0, cancelled: false, truncated: false },
    { role: "assistant", provider: "anthropic", model: "claude-sonnet-4-5", stopReason: "stop", timestamp: Date.now() - 570000,
      content: [{ type: "text", text: LONG_MD }] },
  ],
});
rich.fullThinking.set("0:0", "The user wants a health endpoint.\nFirst I should see how routes are organised under app/api, then add a minimal GET handler and make sure the proxy does not block it.");

const long = [];
for (let i = 1; i <= 40; i++) {
  long.push({ role: "user", content: `Question number ${i}: what does step ${i} do?` });
  long.push({ role: "assistant", stopReason: "stop", content: [{ type: "text", text: `Step ${i} prepares the build cache and prints a short report.` }] });
}
makeSession({ cwd: "/home/demo/projects/pi-web", ageMin: 90, messages: long });
makeSession({ cwd: "/home/demo/projects/pi-web", name: "Fix Windows paths in worktrees", ageMin: 60 * 26, messages: [{ role: "user", content: "fix paths" }] });
makeSession({ cwd: "/home/demo/projects/shop-backend", name: "Exponential retry backoff (interrupted)", ageMin: 30, messages: [
  { role: "user", content: "Make the webhook retry backoff exponential" },
  { role: "assistant", stopReason: "aborted", timestamp: Date.now() - 1800000, content: [
    { type: "text", text: "I'll switch the fixed delay to exponential backoff." },
    { type: "toolCall", toolCallId: "p1", toolName: "edit", input: { path: "src/webhooks/retry.ts", edits: [
      { oldText: "const delay = 5000;", newText: "const delay = Math.min(1000 * 2 ** attempt, 60_000);" },
    ] } },
  ] },
] });
makeSession({ cwd: "/home/demo/projects/shop-backend", name: "Stripe webhook retries", ageMin: 60 * 5, messages: [{ role: "user", content: "stripe" }] });
makeSession({ cwd: "/home/demo/projects/shop-backend", ageMin: 60 * 50, messages: [{ role: "user", content: "Explain the order state machine" }] });

// ---- helpers ---------------------------------------------------------------
function normalize(m) {
  if (m.role !== "assistant" || !Array.isArray(m.content)) return m;
  return { ...m, content: m.content.map((b) => b.type === "toolCall" && b.id ? { type: "toolCall", toolCallId: b.id, toolName: b.name, input: b.arguments } : b) };
}
function emit(s, ev) { for (const res of s.clients) res.write(`data: ${JSON.stringify(ev)}\n\n`); }
function push(s, m) { s.entries.push({ id: entryId(), message: normalize(m) }); s.modified = new Date().toISOString(); }
function check(s) { if (s.aborted) throw new Error("aborted"); }

function stats(s) {
  const count = (role) => s.entries.filter((e) => e.message.role === role).length;
  const toolCalls = s.entries.reduce((n, e) => n + (Array.isArray(e.message.content) ? e.message.content.filter((b) => b.type === "toolCall").length : 0), 0);
  const turns = count("assistant");
  const tokens = { input: 1840 * turns, output: 612 * turns, cacheRead: 38200 * turns, cacheWrite: 4100 * turns };
  tokens.total = tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
  return {
    userMessages: count("user"), assistantMessages: turns, toolCalls, toolResults: count("toolResult"), totalMessages: s.entries.length,
    tokens, cost: turns * 0.0213,
  };
}

function context(s, before) {
  let list = s.entries;
  if (before) { const i = list.findIndex((e) => e.id === before); list = i >= 0 ? list.slice(0, i) : list; }
  const start = Math.max(0, list.length - 50);
  const slice = list.slice(start);
  return {
    messages: slice.map((e) => e.message), entryIds: slice.map((e) => e.id),
    oldestEntryId: slice[0]?.id ?? null, hasMore: start > 0,
    thinkingLevel: s.thinkingLevel, model: { provider: s.model.provider, modelId: s.model.id },
  };
}

async function streamBlock(s, msg, index, kind, text) {
  const field = kind === "thinking" ? "thinking" : "text";
  msg.content[index] = { type: kind, [field]: "" };
  emit(s, { type: "message_update", assistantMessageEvent: { type: `${kind}_start`, contentIndex: index } });
  for (let i = 0; i < text.length; i += 7) {
    check(s);
    const delta = text.slice(i, i + 7);
    msg.content[index][field] += delta;
    emit(s, { type: "message_update", assistantMessageEvent: { type: `${kind}_delta`, contentIndex: index, delta } });
    await sleep(18);
  }
  emit(s, { type: "message_update", assistantMessageEvent: { type: `${kind}_end`, contentIndex: index, content: text } });
}

async function runPrompt(s, message) {
  s.running = true; s.aborted = false; s.live = true; listVersion++;
  let current = null;
  try {
    emit(s, { type: "agent_start" });
    const user = { role: "user", content: message, timestamp: Date.now() };
    push(s, user);
    emit(s, { type: "message_start", message: user });
    emit(s, { type: "message_end", message: user });
    await sleep(400);

    current = { role: "assistant", provider: "anthropic", model: s.model.id, content: [], stopReason: "toolUse", timestamp: Date.now() };
    s.snapshot = current;
    emit(s, { type: "message_start", message: { ...current, content: [] } });
    await streamBlock(s, current, 0, "thinking", "The user asked: \"" + message.slice(0, 60) + "\". I should inspect the repository state before answering, then summarise.");
    await streamBlock(s, current, 1, "text", "Let me check the repository state first.");
    const callId = "call_" + Math.random().toString(36).slice(2, 8);
    const args = { command: "git status --short && npm test" };
    emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_start", contentIndex: 2, id: callId, toolName: "bash" } });
    const raw = JSON.stringify(args);
    for (let i = 0; i < raw.length; i += 6) { check(s); emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_delta", contentIndex: 2, id: callId, toolName: "bash", delta: raw.slice(i, i + 6) } }); await sleep(30); }
    current.content[2] = { type: "toolCall", id: callId, name: "bash", arguments: args };
    emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_end", contentIndex: 2, toolCall: current.content[2] } });
    emit(s, { type: "message_end", message: current });
    push(s, current); s.snapshot = null; current = null;

    if (/confirm/i.test(message)) {
      const uiId = randomUUID();
      emit(s, { type: "extension_ui_request", id: uiId, method: "confirm", title: "Allow bash?", message: `Run \`${args.command}\` in ${s.cwd}?` });
      const answer = await new Promise((resolve) => s.uiWaiters.set(uiId, resolve));
      emit(s, { type: "extension_ui_closed", id: uiId });
      if (!answer.confirmed) throw new Error("denied");
    }

    emit(s, { type: "tool_execution_start", toolCallId: callId, toolName: "bash", args });
    const lines = [" M lib/rpc-manager.ts", "?? app/api/health/route.ts", "", "> pi-web@0.9.1 test", "▶ session-reader (12 tests)", "▶ request-security (31 tests)", "▶ worktree (18 tests)", "✓ 214 tests passed"];
    let out = "";
    for (const line of lines) { check(s); out += line + "\n"; emit(s, { type: "tool_execution_update", toolCallId: callId, toolName: "bash", partialResult: { content: [{ type: "text", text: out }], isError: false } }); await sleep(450); }
    emit(s, { type: "tool_execution_end", toolCallId: callId, toolName: "bash", isError: false });
    const result = { role: "toolResult", toolCallId: callId, toolName: "bash", content: [{ type: "text", text: out }], isError: false, timestamp: Date.now() };
    emit(s, { type: "message_start", message: result });
    emit(s, { type: "message_end", message: result });
    push(s, result);
    await sleep(300);

    current = { role: "assistant", provider: "anthropic", model: s.model.id, content: [], stopReason: "toolUse", timestamp: Date.now() };
    s.snapshot = current;
    emit(s, { type: "message_start", message: { ...current, content: [] } });
    await streamBlock(s, current, 0, "text", "Tests pass. Now I'll expose the uptime in the health route.");
    const editId = "call_" + Math.random().toString(36).slice(2, 8);
    const editArgs = { path: "app/api/health/route.ts", edits: [{ oldText: "return Response.json({ ok: true });", newText: "return Response.json({ ok: true, uptime: process.uptime() });" }] };
    emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_start", contentIndex: 1, id: editId, toolName: "edit" } });
    const editRaw = JSON.stringify(editArgs);
    for (let i = 0; i < editRaw.length; i += 12) { check(s); emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_delta", contentIndex: 1, id: editId, toolName: "edit", delta: editRaw.slice(i, i + 12) } }); await sleep(25); }
    current.content[1] = { type: "toolCall", id: editId, name: "edit", arguments: editArgs };
    emit(s, { type: "message_update", assistantMessageEvent: { type: "toolcall_end", contentIndex: 1, toolCall: current.content[1] } });
    emit(s, { type: "message_end", message: current });
    push(s, current); s.snapshot = null;
    const editStartedAt = current.timestamp;
    current = null;
    emit(s, { type: "tool_execution_start", toolCallId: editId, toolName: "edit", args: editArgs });
    await sleep(1200);
    const editResult = { role: "toolResult", toolCallId: editId, toolName: "edit", isError: false, timestamp: editStartedAt + 2000,
      content: [{ type: "text", text: "Successfully replaced 1 block(s) in app/api/health/route.ts." }],
      details: { diff: "", patch: HEALTH_PATCH, firstChangedLine: 4 } };
    emit(s, { type: "tool_execution_end", toolCallId: editId, toolName: "edit", result: { content: editResult.content, details: editResult.details }, isError: false });
    emit(s, { type: "message_start", message: editResult });
    emit(s, { type: "message_end", message: editResult });
    push(s, editResult);
    await sleep(300);

    current = { role: "assistant", provider: "anthropic", model: s.model.id, content: [], stopReason: "stop", timestamp: Date.now() };
    s.snapshot = current;
    emit(s, { type: "message_start", message: { ...current, content: [] } });
    await streamBlock(s, current, 0, "text", LONG_MD);
    emit(s, { type: "message_end", message: current });
    push(s, current); s.snapshot = null; current = null;
    emit(s, { type: "agent_end" });
  } catch (err) {
    if (current) { current.stopReason = "aborted"; emit(s, { type: "message_end", message: current }); push(s, current); s.snapshot = null; }
    emit(s, { type: "agent_end" });
  } finally {
    await sleep(60);
    s.running = false; listVersion++;
    emit(s, { type: "agent_settled" });
    emit(s, { type: "prompt_done" });
  }
}

// ---- http ------------------------------------------------------------------
function json(res, status, body) { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); }
async function readBody(req) { let data = ""; for await (const chunk of req) data += chunk; try { return JSON.parse(data || "{}"); } catch { return {}; } }

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const p = url.pathname;
  console.log(new Date().toISOString().slice(11, 19), req.method, p + url.search);
  if (p === "/mock/drop") { for (const s of sessions.values()) for (const c of s.clients) c.destroy(); return json(res, 200, { dropped: true }); }
  if (req.headers.authorization !== AUTH) { res.writeHead(401, { "WWW-Authenticate": 'Basic realm="Pi Web"' }); return res.end("Authentication required"); }

  let m;
  if (p === "/api/sessions" && req.method === "GET") {
    const list = [...sessions.values()].map(info).sort((a, b) => b.modified.localeCompare(a.modified));
    return json(res, 200, { sessions: list, sessionListVersion: listVersion, runningSessionIds: [...sessions.values()].filter((s) => s.running).map((s) => s.id), completionNotificationSuppressedSessionIds: [] });
  }
  if (p === "/api/agent/running") return json(res, 200, { sessionListVersion: listVersion, runningSessionIds: [...sessions.values()].filter((s) => s.running).map((s) => s.id), completionNotificationSuppressedSessionIds: [] });
  if (p === "/api/models") return json(res, 200, {
    models: {}, defaultModel: { provider: "anthropic", modelId: "claude-sonnet-4-5" },
    modelList: [
      { id: "claude-sonnet-4-5", name: "Claude Sonnet 4.5", provider: "anthropic", input: ["text", "image"] },
      { id: "claude-opus-4-5", name: "Claude Opus 4.5", provider: "anthropic", input: ["text", "image"] },
      { id: "gpt-5", name: "GPT-5", provider: "openai", input: ["text"] },
    ],
    thinkingLevels: { "anthropic:claude-sonnet-4-5": ["off", "low", "medium", "high"], "anthropic:claude-opus-4-5": ["off", "low", "medium", "high", "xhigh"], "openai:gpt-5": ["minimal", "low", "medium", "high"] },
  });
  if (p === "/api/cwd/validate") { const b = await readBody(req); return json(res, 200, { success: true, cwd: String(b.cwd).replace(/^~/, "/home/demo"), projectRoot: b.cwd, projectKey: b.cwd }); }
  if (p === "/api/agent/new") {
    const b = await readBody(req);
    const s = makeSession({ cwd: b.cwd, ageMin: 0 });
    if (b.provider) s.model = { provider: b.provider, id: b.modelId };
    if (b.thinkingLevel) s.thinkingLevel = b.thinkingLevel;
    s.live = true; listVersion++;
    return json(res, 200, { success: true, sessionId: s.id, data: null, model: { provider: s.model.provider, modelId: s.model.id }, thinkingLevel: s.thinkingLevel });
  }
  if (p === "/api/edit-preview") {
    const b = await readBody(req);
    const edits = Array.isArray(b.input?.edits) ? b.input.edits : [];
    if (!edits.length) return json(res, 400, { error: "edits must be a non-empty array" });
    let line = 20;
    const hunks = edits.map((e) => {
      const oldLines = String(e.oldText).split("\n");
      const newLines = String(e.newText).split("\n");
      const hunk = [
        `@@ -${line},${oldLines.length + 2} +${line},${newLines.length + 2} @@`,
        " async function retry(attempt: number) {",
        ...oldLines.map((l) => "-  " + l),
        ...newLines.map((l) => "+  " + l),
        "   await sleep(delay);",
      ].join("\n");
      line += 30;
      return hunk;
    });
    return json(res, 200, { patch: `--- ${b.input.path}\n+++ ${b.input.path}\n${hunks.join("\n")}\n` });
  }
  if ((m = p.match(/^\/api\/sessions\/([^/]+)$/))) {
    const s = sessions.get(decodeURIComponent(m[1])); if (!s) return json(res, 404, { error: "Session not found" });
    return json(res, 200, { sessionId: s.id, filePath: info(s).path, info: info(s), leafId: s.entries.at(-1)?.id ?? null, tree: [], context: context(s), stats: stats(s), totalActiveMs: 754000 });
  }
  if ((m = p.match(/^\/api\/sessions\/([^/]+)\/context$/))) {
    const s = sessions.get(decodeURIComponent(m[1])); if (!s) return json(res, 404, { error: "Session not found" });
    return json(res, 200, { context: context(s, url.searchParams.get("before")), tail: 50, before: url.searchParams.get("before") });
  }
  if ((m = p.match(/^\/api\/sessions\/([^/]+)\/entries\/([^/]+)\/thinking$/))) {
    const s = sessions.get(decodeURIComponent(m[1]));
    const idx = s?.entries.findIndex((e) => e.id === m[2]);
    const full = s?.fullThinking.get(`${idx - 1 < 0 ? 0 : 0}:${url.searchParams.get("blockIndex")}`);
    return json(res, 200, { thinking: full ?? "(full thinking text)" });
  }
  if ((m = p.match(/^\/api\/agent\/([^/]+)\/events$/))) {
    const s = sessions.get(decodeURIComponent(m[1])); if (!s) { res.writeHead(404); return res.end("Session not found"); }
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache, no-transform", Connection: "keep-alive" });
    res.write(":\n\n");
    s.live = true;
    await sleep(150);
    s.clients.add(res);
    res.write(`data: ${JSON.stringify({ type: "connected", sessionId: s.id, isStreaming: s.running })}\n\n`);
    if (s.snapshot) res.write(`data: ${JSON.stringify({ type: "message_start", message: s.snapshot })}\n\n`);
    const hb = setInterval(() => res.write(":\n\n"), 30000);
    req.on("close", () => { clearInterval(hb); s.clients.delete(res); });
    return;
  }
  if ((m = p.match(/^\/api\/agent\/([^/]+)\/lease$/))) {
    const s = sessions.get(decodeURIComponent(m[1]));
    return json(res, 200, { success: true, renewed: s ? s.clients.size : 0 });
  }
  if ((m = p.match(/^\/api\/agent\/([^/]+)$/))) {
    const s = sessions.get(decodeURIComponent(m[1])); if (!s) return json(res, 404, { error: "Session not found" });
    if (req.method === "GET") {
      if (!s.live) return json(res, 200, { running: false });
      return json(res, 200, { running: true, state: { sessionId: s.id, isStreaming: s.running, isPromptRunning: s.running, isBashRunning: false, isCompacting: false, model: s.model, thinkingLevel: s.thinkingLevel, queuedMessages: { steering: [], followUp: [] }, contextUsage: { percent: 23.4, contextWindow: 200000, tokens: 46800 } } });
    }
    const b = await readBody(req);
    switch (b.type) {
      case "prompt":
        if (s.running) {
          emit(s, { type: "queue_update", steering: [b.message], followUp: [] });
          setTimeout(() => { emit(s, { type: "queue_update", steering: [], followUp: [] }); }, 2500);
          return json(res, 200, { success: true, data: null });
        }
        runPrompt(s, String(b.message ?? ""));
        return json(res, 200, { success: true, data: null });
      case "abort": s.aborted = true; return json(res, 200, { success: true, data: null });
      case "set_model": s.model = { provider: b.provider, id: b.modelId }; return json(res, 200, { success: true, data: { id: b.modelId, provider: b.provider } });
      case "set_thinking_level": s.thinkingLevel = b.level; return json(res, 200, { success: true, data: null });
      case "extension_ui_response": { const w = s.uiWaiters.get(b.id); s.uiWaiters.delete(b.id); w?.(b); return json(res, 200, { success: true, data: null }); }
      default: return json(res, 200, { success: true, data: null });
    }
  }
  json(res, 404, { error: "not mocked: " + p });
});
server.listen(PORT, "0.0.0.0", () => console.log("mock pi-web on", PORT));
