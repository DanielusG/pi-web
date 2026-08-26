import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const source = fs.readFileSync(new URL("./AppShell.tsx", import.meta.url), "utf8");

// The title-generate button (and its auto-naming triggers) was removed from the
// top bar; only the transient-session hydration behavior is asserted here.
test("会话落盘后会用服务端记录清除临时状态", () => {
  assert.match(source, /\{ \.\.\.prev, \.\.\.full, transient: full\.transient \?\? false \}/);
  assert.match(source, /if \(selectedSession\) hydrateSelectedSession\(selectedSession\.id\)/);
});
