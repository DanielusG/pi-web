// Fork-only coverage for the chat toolbar's Tools panel. Upstream deleted
// `ToolDefinitionsPanel.test.mjs` in v0.12.0 (`f364bc3`); the part worth keeping
// is the wiring of the toolbar action into the panel and the fork's header
// cleanup, which left `SystemPromptPanel` out of `AppShell` entirely.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const appShellSource = await readFile(new URL("./AppShell.tsx", import.meta.url), "utf8");
const panelSource = await readFile(new URL("./ToolDefinitionsPanel.tsx", import.meta.url), "utf8");

test("keeps the Tools toolbar action wired to its panel", () => {
  assert.match(appShellSource, /handleSystemInfoToggle\("tools", mobile\)/);
  assert.match(appShellSource, /activeTopPanel === "tools" && \(\s*<ToolDefinitionsPanel/);
  assert.match(panelSource, /tools\?\.filter\(\(tool\) => tool\.active && !tool\.declarationHidden\)/);
});

test("the fork's header cleanup left the system prompt out of AppShell", () => {
  assert.doesNotMatch(appShellSource, /SystemPromptPanel/);
  assert.doesNotMatch(appShellSource, /systemPrompt/);
});
