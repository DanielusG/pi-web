# Plan: gzip — context route (server) + wire-behavior guard (Android)

**Source:** `docs/perf-verification/P1-gzip.md` (verdetto: PROBLEMA REALE, ESAGERATO).

## Scoperta durante l'implementazione

La metà client del report è basata su un presupposto errato:

- `OkHttpClient.Builder` in OkHttp 4.12 **non ha** un metodo `.gzip()` — la
  "fix di una riga a rischio zero" del report non compila.
- OkHttp 4 abilita il gzip **di default**: `BridgeInterceptor` (verificato sul
  bytecode del jar) aggiunge `Accept-Encoding: gzip` quando l'utente non lo
  imposta (e non c'è header `Range`) e decompresse trasparentemente le
  risposte `Content-Encoding: gzip` via `okio.GzipSource`.
- L'app non imposta `Accept-Encoding` né `Range` da nessuna parte, quindi il
  client Android **già inviava gzip e decomprimeva** — la fix client è moot.
  Verificato empiricamente: `PiApiTest` passa senza modifiche a `PiApi.kt`.

Il fix reale è solo lato server: il payload più grande per singola richiesta —
`/api/sessions/[id]/context` (mediana 79 KB, p90 226 KB, max 2,3 MB sui 2196
file reali) — bypassava l'helper `lib/json-response.ts`.

## Changes

### Server (il fix)

1. `app/api/sessions/[id]/context/route.ts`
   - Success path: `NextResponse.json({ context, contextUsage, tail, before })` →
     `jsonResponse(req, { context, contextUsage, tail, before: before ?? null })`.
   - Error path 404/500 restano `NextResponse.json` (coerente con gli altri 3
     endpoint; payload <1 KB, sotto la soglia gzip).

### Tests

2. `lib/json-response.test.mjs`
   - Caso positivo mancante (i 3 test esistenti erano tutti negativi): con
     `Accept-Encoding: gzip` + body ≥1 KB → `Content-Encoding: gzip`, `Vary`
     contiene `Accept-Encoding`, gunzip del body == JSON originale, output più
     piccolo dell'input.
3. `app/api/sessions/context-route.test.mjs`
   - Assert statico (pattern consolidato del file) che il success path passa per
     `jsonResponse(req, { context, contextUsage, tail, before: before ?? null })`.
4. `android/app/build.gradle.kts`
   - `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` (stessa
     versione di OkHttp).
5. Nuovo `android/app/src/test/java/app/pimobile/data/PiApiTest.kt`
   - MockWebServer in-process (socket localhost nella JVM del test, risposte
     canned, nessun server esterno). `PiApi` è puro JVM, niente Robolectric.
   - Test a: la richiesta porta `Accept-Encoding: gzip`.
   - Test b: una risposta `Content-Encoding: gzip` viene decompressa
     trasparentemente nel JSON atteso.
   - **Natura dei test**: verificano il comportamento wire *effettivo* di
     `PiApi` (oggi prodotto dal default di OkHttp, non da codice nostro). Sono
     un guard regression: falliscono se qualcuno imposta manualmente
     `Accept-Encoding` (es. esperimento brotli) o `Range`, disattivando il gzip
     trasparente di OkHttp.

### Doc

6. `android/README.md`
   - Riga 187: descrizione di `PiApi.kt` → `OkHttp REST + SSE flow, Basic auth, gzip, error mapping`
     (accurata: il client invia davvero gzip, via default OkHttp).

## Out of scope

- `PiApi.kt`: nessuna modifica necessaria (presupposto client del report errato).
- `/api/files`: il report originale lo elenca a torto tra i beneficiari; route
  streaming/binary con 6 request types.
- `/api/agent/*`: payload piccoli, non beneficiari reali per l'analisi del report.

## Verification

- `node --experimental-strip-types --test lib/json-response.test.mjs app/api/sessions/context-route.test.mjs` → 8/8 pass
- `node_modules/.bin/tsc --noEmit` → ok
- `npm run lint` → ok
- `cd android && ./gradlew testDebugUnitTest --tests "app.pimobile.data.PiApiTest"` → 2/2 pass (senza modifiche a PiApi.kt)
