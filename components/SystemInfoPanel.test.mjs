import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./SystemInfoPanel.tsx", import.meta.url), "utf8");

test("renders active tool definitions as a form beside the system prompt", () => {
  assert.match(source, /tools\?\.filter\(\(tool\) => tool\.active\)/);
  assert.match(source, /<section className="system-info-prompt"/);
  assert.match(source, /<aside className="system-info-tools"/);
  assert.match(source, /grid-template-columns: minmax\(0, 1fr\) minmax\(300px, 38%\)/);
  assert.match(source, /parameters\.properties/);
  assert.match(source, /parameters\.required/);
  assert.match(source, /field\.allowedValues/);
  assert.match(source, /field\.defaultValue/);
});

test("stacks the tool definitions below the prompt on narrow screens", () => {
  assert.match(
    source,
    /@media \(max-width: 640px\)[\s\S]*?\.system-info-panel \{[\s\S]*?display: block/,
  );
  assert.match(source, /\.system-info-tools \{[\s\S]*?border-top: 1px solid var\(--border\);[\s\S]*?border-left: none/);
});
