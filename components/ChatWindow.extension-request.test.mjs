import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./ChatWindow.tsx", import.meta.url), "utf8");
const dialogStart = source.indexOf("function ExtensionDialog");
const customStart = source.indexOf("function ExtensionCustomPanel");
const dialogSource = source.slice(dialogStart, customStart);
const customSource = source.slice(customStart);

test("renders the extension dialog inline in the composer slot (fork layout)", () => {
  // While a blocking extension request is open, the dialog replaces the input bar.
  assert.match(source, /const extensionDialogElement = extensionDialog \?/);
  assert.match(source, /\{extensionDialogElement \?\? chatInputElement\}/);
  // The dialog is not an overlay: no absolute positioning in the dialog component.
  assert.doesNotMatch(dialogSource, /position: "absolute"/);
  assert.doesNotMatch(dialogSource, /inset: 0/);
  // The custom panel remains an overlay confined to the content region above the composer.
  assert.match(customSource, /position: "absolute"[\s\S]*?inset: 0/);
  assert.match(customSource, /pointerEvents: "none"/);
  assert.doesNotMatch(source, /z-\[100\]|zIndex: 100/);
  assert.match(customSource, /maxHeight: "min\(760px, 100%\)"/);
});

test("adds collapse without replacing cancel", () => {
  assert.match(dialogSource, /setCollapsed\(true\)/);
  assert.match(dialogSource, /chat\.extensionCollapse/);
  assert.match(dialogSource, /chat\.cancel/);
  assert.doesNotMatch(dialogSource, /chat\.extensionSkip/);
});

test("resets collapse state when a new extension request arrives", () => {
  assert.match(dialogSource, /setCollapsed\(false\);\s*}, \[request\]\)/);
  assert.match(source, /<ExtensionCustomPanel key=\{extensionCustomUi\.id\}/);
  assert.match(customSource, /if \(!collapsed\) inputRef\.current\?\.focus\(\);\s*}, \[collapsed\]\)/);
});
