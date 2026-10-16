// Fork-only coverage for the context route's transfer rules. Upstream deleted
// `context-route.test.mjs` in v0.12.0 with the rest of the assertions that restate
// the implementation's source text (`f364bc3`); two of them guard behaviour only
// this fork has, so they live here under a name upstream does not touch:
// - `jsonResponse` (`lib/json-response.ts`) gzips the response when the client
//   accepts it. Upstream's own route does not care; the Android client and old
//   iOS Safari read a leaf's whole context, and this fork added the gzip path.
// - `?tail` and `?before` reach `buildSessionContext` with `excludeLeaf`, which is
//   how a prepended page never duplicates its own boundary (#555).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const routeSrc = readFileSync(new URL("./[id]/context/route.ts", import.meta.url), "utf8");

test("the context route answers through jsonResponse, so the page ships gzipped", () => {
  assert.match(routeSrc, /import \{ jsonResponse \} from "@\/lib\/json-response"/);
  assert.match(routeSrc, /return jsonResponse\(req, \{ context, contextUsage, tail, before: before \?\? null \}\);/);
  assert.doesNotMatch(routeSrc, /NextResponse\.json\(\{ context,/);
});

test("the context route wires ?tail and ?before, excluding the boundary on paging", () => {
  assert.match(routeSrc, /const tail = Number\.isFinite\(rawTail\) && rawTail > 0 \? Math\.min\(rawTail, 1000\) : 50/);
  assert.match(routeSrc, /const before = url\.searchParams\.get\("before"\)/);
  assert.match(routeSrc, /attachTranscriptNotices\(buildSessionContext\(entries as never, before \?\? leafId, \{[^}]*excludeLeaf: Boolean\(before\)/);
});
