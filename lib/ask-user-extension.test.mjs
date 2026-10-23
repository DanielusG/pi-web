import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { createAskUserExtension, preferPiWebAskUserExtension } = await jiti.import("./ask-user-extension.ts");
const { isNativeAskUserEnabled, writeNativeAskUserEnabled } = await jiti.import("./ask-user-settings.ts");

const HOST_PATH = "<inline:pi-web-ask-user>";
const conflict = (path) => `Tool "ask_user_question" conflicts with ${path}`;

test("the factory registers nothing while the switch is off", async () => {
  const registered = [];
  const pi = { registerTool(tool) { registered.push(tool.name); } };
  let enabled = false;
  const extension = createAskUserExtension(() => enabled);
  assert.equal(extension.name, "pi-web-ask-user");
  assert.equal(extension.hidden, true);
  await extension.factory(pi);
  assert.deepEqual(registered, []);
  // A reload runs the factory again and reads the switch then.
  enabled = true;
  await extension.factory(pi);
  assert.deepEqual(registered, ["ask_user_question"]);
});

test("while on, the native tool replaces the rpiv package wherever it was installed from", () => {
  const host = { path: HOST_PATH, tools: new Map([["ask_user_question", {}]]) };
  const npmInstall = {
    path: "/home/u/.pi/agent/npm/node_modules/@juicesharp/rpiv-ask-user-question/index.ts",
    tools: new Map([["ask_user_question", {}]]),
  };
  const gitCheckout = {
    path: "C:\\Users\\u\\.pi\\agent\\git\\github.com\\juicesharp\\rpiv-mono\\packages\\rpiv-ask-user-question\\index.ts",
    tools: new Map([["ask_user_question", {}]]),
  };
  // Another extension that happens to use the name stays, and the SDK reports the clash.
  const unrelated = { path: "/tmp/my-questions/index.ts", tools: new Map([["ask_user_question", {}]]) };
  // The package without the tool (an older build) is left alone.
  const toolless = { path: "/tmp/node_modules/@juicesharp/rpiv-ask-user-question/old.ts", tools: new Map() };
  const other = { path: "/tmp/other/index.ts", tools: new Map([["other", {}]]) };

  const result = preferPiWebAskUserExtension({
    extensions: [npmInstall, gitCheckout, unrelated, toolless, host, other],
    errors: [
      { path: HOST_PATH, error: conflict(npmInstall.path) },
      { path: gitCheckout.path, error: conflict(npmInstall.path) },
      { path: unrelated.path, error: conflict(npmInstall.path) },
      { path: HOST_PATH, error: conflict(unrelated.path) },
      { path: other.path, error: "other" },
    ],
  });
  assert.deepEqual(result.extensions.map((extension) => extension.path), [unrelated.path, toolless.path, HOST_PATH, other.path]);
  assert.deepEqual(result.errors, [
    { path: unrelated.path, error: conflict(npmInstall.path) },
    { path: HOST_PATH, error: conflict(unrelated.path) },
    { path: other.path, error: "other" },
  ]);
});

test("while off, the package is kept as it is", () => {
  const host = { path: HOST_PATH, tools: new Map() };
  const rpiv = { path: "/tmp/node_modules/@juicesharp/rpiv-ask-user-question/index.ts", tools: new Map([["ask_user_question", {}]]) };
  const base = { extensions: [rpiv, host], errors: [] };
  assert.equal(preferPiWebAskUserExtension(base), base);
});

test("the switch is off by default and when its file is damaged", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "pi-web-ask-user-settings-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const settingsPath = join(root, "pi-web-ask-user.json");

  assert.equal(isNativeAskUserEnabled(settingsPath), false);
  assert.equal(writeNativeAskUserEnabled(true, settingsPath), true);
  assert.deepEqual(JSON.parse(await readFile(settingsPath, "utf8")), { version: 1, enabled: true });

  await writeFile(settingsPath, JSON.stringify({ version: 1, enabled: true, futureSetting: 3 }));
  assert.equal(writeNativeAskUserEnabled(false, settingsPath), false);
  assert.deepEqual(JSON.parse(await readFile(settingsPath, "utf8")), { version: 1, enabled: false, futureSetting: 3 });

  await writeFile(settingsPath, "{ not json");
  assert.equal(isNativeAskUserEnabled(settingsPath), false);
  assert.throws(() => writeNativeAskUserEnabled(true, settingsPath));
  await writeFile(settingsPath, JSON.stringify({ enabled: "yes" }));
  assert.equal(isNativeAskUserEnabled(settingsPath), false);
});
