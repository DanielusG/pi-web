import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { compactFirstMessage, pageSessionsByProject, withCompactFirstMessage } = await jiti.import("./session-list-page.ts");
const route = await readFile(new URL("../app/api/sessions/route.ts", import.meta.url), "utf8");

function session(id, projectKey, modified, extra = {}) {
  return {
    path: `/s/${id}.jsonl`,
    id,
    cwd: `/work/${projectKey}`,
    created: modified,
    modified,
    messageCount: 2,
    firstMessage: `first ${id}`,
    projectRoot: `/work/${projectKey}`,
    projectKey,
    ...extra,
  };
}

const at = (minute) => `2026-09-26T10:${String(minute).padStart(2, "0")}:00.000Z`;

test("each project keeps its newest sessions, newest project first, with totals", () => {
  const sessions = [
    session("a1", "alpha", at(1)),
    session("b1", "beta", at(2)),
    session("a2", "alpha", at(3)),
    session("a3", "alpha", at(5)),
    session("b2", "beta", at(4)),
  ];
  const { projects } = pageSessionsByProject(sessions, { perProject: 2 });
  assert.deepEqual(projects.map((p) => [p.key, p.total, p.modified, p.sessions.map((s) => s.id)]), [
    ["alpha", 3, at(5), ["a3", "a2"]],
    ["beta", 2, at(4), ["b2", "b1"]],
  ]);
  assert.equal(projects[0].root, "/work/alpha");
});

test("a later page of one project starts at the offset", () => {
  const sessions = Array.from({ length: 12 }, (_, i) => session(`a${i}`, "alpha", at(i)))
    .concat([session("b0", "beta", at(30))]);
  const { projects } = pageSessionsByProject(sessions, { perProject: 5, offset: 5, project: "alpha" });
  assert.equal(projects.length, 1);
  assert.equal(projects[0].total, 12);
  assert.deepEqual(projects[0].sessions.map((s) => s.id), ["a6", "a5", "a4", "a3", "a2"]);
  const past = pageSessionsByProject(sessions, { perProject: 5, offset: 20, project: "alpha" });
  assert.deepEqual(past.projects[0].sessions, []);
  assert.deepEqual(pageSessionsByProject(sessions, { perProject: 5, project: "gone" }).projects, []);
});

test("subagent runs are left out of pages and totals, forks stay", () => {
  const sessions = [
    session("parent", "alpha", at(1)),
    session("child", "alpha", at(9), { relation: { kind: "subagent", parentSessionId: "parent", profile: "explore", description: "", status: "completed" } }),
    session("fork", "alpha", at(5), { relation: { kind: "fork", originSessionId: "parent" } }),
  ];
  const { projects } = pageSessionsByProject(sessions, { perProject: 5 });
  assert.equal(projects[0].total, 2);
  assert.equal(projects[0].modified, at(5));
  assert.deepEqual(projects[0].sessions.map((s) => s.id), ["fork", "parent"]);
});

test("sessions without a project key group by project root, then cwd", () => {
  const sessions = [
    session("a", "x", at(1), { projectKey: undefined, projectRoot: "/repo", cwd: "/repo-worktrees/feat" }),
    session("b", "x", at(2), { projectKey: undefined, projectRoot: "/repo", cwd: "/repo" }),
    session("c", "x", at(3), { projectKey: undefined, projectRoot: undefined, cwd: "/tmp/scratch" }),
  ];
  const { projects } = pageSessionsByProject(sessions, { perProject: 5 });
  assert.deepEqual(projects.map((p) => [p.key, p.root, p.total]), [["/tmp/scratch", "/tmp/scratch", 1], ["/repo", "/repo", 2]]);
});

test("recent working directories are distinct and newest first", () => {
  const sessions = [
    session("a1", "alpha", at(1)),
    session("a2", "alpha", at(3), { cwd: "/work/alpha-worktrees/feat" }),
    session("a3", "alpha", at(5)),
    session("b1", "beta", at(4)),
  ];
  const { recentCwds } = pageSessionsByProject(sessions, { perProject: 1 });
  assert.deepEqual(recentCwds, ["/work/alpha", "/work/beta", "/work/alpha-worktrees/feat"]);
});

test("the cached list is not reordered or mutated", () => {
  const sessions = [session("a1", "alpha", at(1)), session("a2", "alpha", at(3))];
  const before = structuredClone(sessions);
  pageSessionsByProject(sessions, { perProject: 1 });
  assert.deepEqual(sessions, before);
});

test("first messages are cut to the limit with an ellipsis", () => {
  assert.equal(compactFirstMessage("short", 10), "short");
  assert.equal(compactFirstMessage("x".repeat(12), 10), `${"x".repeat(10)}…`);
  // A surrogate pair straddling the limit is dropped whole, never split.
  assert.equal(compactFirstMessage(`${"x".repeat(9)}😀tail`, 10), `${"x".repeat(9)}…`);
});

test("a skill expansion collapses to its command before it is cut", () => {
  const expansion = [
    '<skill name="review" location="/home/u/.pi/skills/review/SKILL.md">',
    "References are relative to /home/u/.pi/skills/review.",
    "",
    "x".repeat(5000),
    "</skill>",
    "",
    "check the auth module",
  ].join("\n");
  assert.equal(compactFirstMessage(expansion, 300), "/skill:review check the auth module");
});

test("an unchanged session keeps its identity", () => {
  const s = session("a", "alpha", at(1));
  assert.equal(withCompactFirstMessage(s, 300), s);
  const long = session("b", "alpha", at(1), { firstMessage: "y".repeat(400) });
  const compacted = withCompactFirstMessage(long, 300);
  assert.equal(compacted.firstMessage.length, 301);
  assert.equal(long.firstMessage.length, 400);
});

test("the route applies the opt-in parameters and keeps the full list otherwise", () => {
  assert.match(route, /sessionListing\(sessions, searchParams\)/);
  assert.match(route, /intParam\(searchParams, "perProject", 1, 100\)/);
  assert.match(route, /intParam\(searchParams, "firstMessageChars", 1, 10_000\)/);
  assert.match(route, /if \(perProject === undefined\) return \{ sessions: compact\(selected\) \}/);
  assert.match(route, /searchParams\.get\("ids"\)/);
});
