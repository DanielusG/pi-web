import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./AgentsConfig.tsx", import.meta.url), "utf8");

test("keeps same-name profiles selectable by scope and groups writable sources first", () => {
  assert.match(source, /return `\$\{profile\.scope\}:\$\{profile\.name\}`/);
  assert.match(source, /\["project", "global", "workspace", "builtin"\] as const/);
  assert.match(source, /profile\.scope === scope/);
});

test("uses the Plugins-style accent status dot", () => {
  assert.match(source, /background: profile\.enabled \? "var\(--accent\)" : "var\(--text-dim\)"/);
  assert.doesNotMatch(source, /profile\.enabled \? "#16a34a"/);
});

test("treats global and project profiles as directly editable", () => {
  assert.match(source, /scope === "global" \|\| scope === "project"/);
  assert.match(source, /setMode\(isWritableScope\(profile\.scope\) \? "edit" : "view"\)/);
  assert.match(source, /selected && isWritableScope\(selected\.scope\) && mode === "edit"/);
});

test("offers both writable scopes when creating or overriding a read-only profile", () => {
  assert.match(source, /beginOverride\("global"\)/);
  assert.match(source, /beginOverride\("project"\)/);
  assert.match(source, /mode === "create" \|\| mode === "override"/);
  assert.match(source, /\["global", "project"\] as const/);
});

test("sends the selected scope for saves and the source scope for deletes", () => {
  assert.match(source, /JSON\.stringify\(\{ cwd, scope: targetScope, profile: draft \}\)/);
  assert.match(source, /JSON\.stringify\(\{ cwd, scope: selected\.scope, name: selected\.name \}\)/);
});

test("shows a Skills-style path row and enabled switch at the top of the editor", () => {
  assert.match(source, /function displayProfilePath\(profile: SubagentProfile, cwd: string\)/);
  assert.match(source, /profile\.scope === "project" \|\| profile\.scope === "workspace"/);
  assert.match(source, /`~\/\.pi\/agent\/agents\/\$\{draft\.name \|\| "\.\.\."\}\.md`/);
  assert.match(source, /role="switch"/);
  assert.match(source, /aria-checked=\{checked\}/);
  assert.match(source, /<EnabledToggle checked=\{draft\.enabled\}/);
  assert.doesNotMatch(source, /<Toggle label=\{t\("agents\.enabled"\)\}/);
});

test("persists existing profile toggles immediately without submitting unsaved fields", () => {
  assert.match(source, /const toggleEnabled = async \(enabled: boolean\)/);
  assert.match(source, /method: "PATCH"/);
  assert.match(source, /JSON\.stringify\(\{ cwd, scope: selected\.scope, name: selected\.name, enabled \}\)/);
  assert.match(source, /setDraft\(\(current\) => \(\{ \.\.\.current, enabled: saved\.enabled \}\)\)/);
  assert.doesNotMatch(source, /method: "PATCH"[\s\S]*?profile: draft/);
});

test("loads scoped models into a provider-grouped selector without manual entry", () => {
  assert.match(source, /fetch\(`\/api\/models\?cwd=\$\{encodeURIComponent\(cwd\)\}`/);
  assert.match(source, /const modelsByProvider = useMemo/);
  assert.match(source, /<select[\s\S]*?aria-label=\{t\("agents\.model"\)\}/);
  assert.match(source, /<optgroup key=\{provider\} label=\{provider\}>/);
  assert.match(source, /value=\{`\$\{model\.provider\}\/\$\{model\.id\}`\}/);
  assert.match(source, /agents\.modelUnavailable/);
  assert.doesNotMatch(source, /placeholder="provider\/modelId"/);
});
