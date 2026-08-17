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

test("offers both writable scopes when creating a profile", () => {
  assert.match(source, /\{creating && \(/);
  assert.match(source, /\["global", "project"\] as const/);
  assert.doesNotMatch(source, /beginOverride|mode === "override"|agents\.readOnly|agents\.override/);
});

test("matches the Add skill sidebar action styling", () => {
  assert.match(source, /padding: "8px 6px", borderTop: "1px solid var\(--border\)"/);
  assert.match(source, /onClick=\{beginCreate\}[\s\S]*?padding: "7px 8px"[\s\S]*?background: creating \? "var\(--bg-selected\)" : "transparent"/);
  assert.match(source, /<svg width="13" height="13"[\s\S]*?t\("agents\.new"\)/);
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

test("renders the stable agent id as text outside create mode", () => {
  assert.match(source, /creating \? \(\s*<input aria-label=\{t\("agents\.name"\)\}/);
  assert.match(source, /<code style=\{\{ minHeight: 34,[\s\S]*?\{draft\.name\}[\s\S]*?<\/code>/);
  assert.doesNotMatch(source, /disabled=\{disabled \|\| !creating\}/);
});

test("keeps system instructions vertically resizable even when read-only", () => {
  assert.match(source, /<textarea aria-label=\{t\("agents\.prompt"\)\}[\s\S]*?readOnly=\{disabled\}/);
  assert.match(source, /maxHeight: "60vh"[\s\S]*?resize: "vertical"/);
});

test("duplicates any selected profile through the existing create flow", () => {
  assert.match(source, /function duplicateProfileName\(name: string, profiles: readonly SubagentProfile\[\]\)/);
  assert.match(source, /while \(existing\.has\(candidate\.toLowerCase\(\)\)\) candidate = `\$\{base\}-\$\{suffix\+\+\}`/);
  assert.match(source, /const beginDuplicate = \(\) =>/);
  assert.match(source, /\.\.\.editableProfile\(selected\),[\s\S]*?name,[\s\S]*?displayName: t\("agents\.copyName"/);
  assert.match(source, /setMode\("create"\)/);
  assert.match(source, /setTargetScope\(isWritableScope\(selected\.scope\) \? selected\.scope : "global"\)/);
  assert.match(source, /onClick=\{beginDuplicate\}[^>]*>[\s\S]*?t\("agents\.duplicate"\)/);
});

test("places duplicate and delete immediately before the enabled switch", () => {
  assert.match(source, /onClick=\{beginDuplicate\}[\s\S]*?onClick=\{\(\) => void remove\(\)\}[\s\S]*?<EnabledToggle checked=\{draft\.enabled\}/);
});

test("confirms deletion and limits it to writable profiles", () => {
  assert.match(source, /window\.confirm\(t\("agents\.deleteConfirm", \{ name: selected\.displayName \}\)\)/);
  assert.match(source, /selected && isWritableScope\(selected\.scope\) && mode === "edit"/);
  assert.match(source, /method: "DELETE"/);
});
