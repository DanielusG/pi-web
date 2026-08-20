import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const layoutSource = await readFile(new URL("./layout.tsx", import.meta.url), "utf8");
const cssSource = await readFile(new URL("./globals.css", import.meta.url), "utf8");

test("uses an offline-safe system monospace stack", () => {
  assert.doesNotMatch(layoutSource, /next\/font\/google|Noto_Sans_Mono|font-noto-mono/);
  assert.match(layoutSource, /className="notranslate"/);
  assert.match(cssSource, /--font-mono: [^;]*ui-monospace[^;]*monospace;/);
  assert.doesNotMatch(cssSource, /font-noto-mono/);
});
