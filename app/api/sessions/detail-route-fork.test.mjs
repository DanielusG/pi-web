// Fork-only coverage for `GET /api/sessions/[id]`. Upstream deleted
// `detail-route.test.mjs` in v0.12.0 with the assertions that restate the
// implementation's source text (`f364bc3`); the fields pinned here exist only
// because of this fork, so nothing else guards them:
// - `?force=1` evicts a wrapper whose file moved ahead on disk (the TUI writing
//   the same JSONL) and answers with `wrapperRebuilt: true` (#556).
// - `contextUsage` from `lib/cold-context.ts` — the indicator on a cold session.
// - `deferThinking` / `deferMedia` / `tail` — the trimmed reads the Android
//   client and the view cache ask for.
// - `jsonResponse` gzip on the detail payload.
// Plus the interaction with upstream's `catalogFirstMessage()` (#1177), which
// pre-cuts every list row's first message to 500 code units before the fork's
// `?firstMessageChars` ever runs.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createJiti } from "jiti";

const routeSrc = readFileSync(new URL("./[id]/route.ts", import.meta.url), "utf8");
const jiti = createJiti(import.meta.url, {
  alias: { "@": process.cwd() },
  interopDefault: true,
  moduleCache: false,
});
const { catalogFirstMessage } = await jiti.import("@/lib/session-reader");
const { compactFirstMessage } = await jiti.import("@/lib/session-list-page");

test("the detail route carries the fork-only fields", () => {
  assert.match(routeSrc, /const force = searchParams\.get\("force"\) === "1"/);
  assert.match(routeSrc, /if \(force && liveWrapper\?\.evictIfDiskAhead\(\)\)/);
  assert.match(routeSrc, /\.\.\.\(wrapperRebuilt \? \{ wrapperRebuilt: true \} : \{\}\)/);
  assert.match(routeSrc, /const contextUsage = await computeFileContextUsage\(entries, leafId, header\?\.cwd \|\| process\.cwd\(\)\)/);
  assert.match(routeSrc, /contextUsage,\n\s+totalActiveMs,/);
});

test("the detail route trims thinking, images and history per request", () => {
  assert.match(routeSrc, /const deferThinking = searchParams\.has\("deferThinking"\)/);
  assert.match(routeSrc, /const deferToolResultImages = searchParams\.has\("deferMedia"\)/);
  assert.match(routeSrc, /const tail = Number\.isFinite\(rawTail\) && rawTail > 0 \? Math\.min\(rawTail, 1000\) : 50/);
  assert.match(routeSrc, /buildSessionContext\(entries as never, leafId, \{\s*deferThinking,\s*deferToolResultImages,\s*tail,\s*sessionId: id,/);
});

test("the detail route answers through jsonResponse, so the payload ships gzipped", () => {
  assert.match(routeSrc, /import \{ jsonResponse \} from "@\/lib\/json-response"/);
  assert.match(routeSrc, /return perf\?\.attach\(jsonResponse\(/);
});

test("upstream's catalog cut caps the fork's ?firstMessageChars at 500", () => {
  // A list row's first message reaches the fork's trim already cut by
  // `catalogFirstMessage()` (lib/session-reader.ts), so asking for more than 500
  // characters cannot recover text the catalog dropped. Today Android asks 200
  // and notification titles 80, so nothing is lost; this pins the ceiling.
  const long = `${"x".repeat(900)} tail`;
  const catalog = catalogFirstMessage(long);
  assert.ok(catalog.length <= 500, `catalog cut grew past 500: ${catalog.length}`);
  assert.ok(!catalog.includes("tail"), "the catalog cut kept the text past its cap");
  assert.equal(compactFirstMessage(catalog, 2000).length, catalog.length + 0);
  assert.equal(compactFirstMessage(catalog, 20).length, 21); // 20 + the single-char ellipsis
});
