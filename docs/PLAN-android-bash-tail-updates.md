# Plan — P2: bash `tool_execution_update` in coda (tail) invece di snapshot completo

**Ref:** `docs/PERF-REVIEW-android.md` (P2) + `docs/perf-verification/P2-bash-snapshot.md`
**Data:** 2026-09-21
**Decisioni prese con Daniele:**
- Vista espansa live: accettata la coda (niente head+tail).
- Trasformazione tail generica su tutti i tool che emettono `tool_execution_update`.
- Lato app: solo il query param, niente coalescing/rolling window (materia di P7).

## Problema

Il tool bash (SDK `dist/core/tools/bash.js`) emette `tool_execution_update` throttled a
100ms ri-inviando a ogni evento l'output accumulato completo (cap SDK 50KB/2000 righe,
tail). Un comando verbose produce 5-15 MB di JSON in wire per tool call; l'app mostra
solo le ultime 8 righe. `tool_execution_end` porta comunque il risultato completo una
volta sola.

## Design

Opt-in lato client via query param sull'SSE: `GET /api/agent/[id]/events?toolUpdates=tail`.
Default (e valore sconosciuto) = `full` → web client invariato. In tail mode, il testo di
`partialResult.content` di ogni `tool_execution_update` viene sostituito con la coda:
ultime 16 righe, cap 8000 caratteri (allineato a `MAX_OUTPUT_CHARS` dell'app, così
`clip()` non aggiunge mai il suffisso "… N more" all'output live), taglio a confine di
riga. `details` passa invariato. Output sotto i cap passano identici. Tutti gli altri
eventi (incluso `tool_execution_end`) non toccati.

Dimensionamento: caso comune da ~50KB a ~1-2KB per evento (~30x); caso pathological
(righe enormi) bounded a 8000 char.

## File da modificare (in ordine)

1. **`lib/agent-event-wire.ts`**
   - Nuovo export `ClientAgentEventOptions { toolUpdates?: "full" | "tail" }`.
   - `toClientAgentEvent(event, options?)`: nel ramo `tool_execution_update`, se
     `options?.toolUpdates === "tail"` e `partialResult` è un oggetto con `content`
     array, mappa i blocchi `{type:"text", text}` sostituendo `text` con
     `tailText(text)`; gli altri campi di `partialResult` (es. `details`) passano
     invariati. `partialResult` non-oggetto passa invariato.
   - Helper privato `tailText(text)`:
     - se `text.length <= 8000`: niente da tagliare per byte; se righe > 16 → ultime 16;
       altrimenti testo identico.
     - altrimenti: `slice(-8000)`, drop della prima riga parziale (taglio al primo `\n`),
       poi ultime 16 righe se superate.
   - Costanti `TAIL_MAX_LINES = 16`, `TAIL_MAX_CHARS = 8000`.

2. **`lib/agent-event-stream.ts`**
   - `createAgentEventStream(req, sessionId, sessionPromise, options?: ClientAgentEventOptions)`;
     `forwardEvent` passa `options` a `toClientAgentEvent`.

3. **`app/api/agent/[id]/events/route.ts`**
   - Lettura `req.nextUrl.searchParams.get("toolUpdates")`; `"tail"` → tail, altrimenti
     `full`. Passato a `createAgentEventStream`.

4. **`lib/agent-event-wire.test.mjs`** — test nuovi:
   - default (no options): `tool_execution_update` con testo lungo passa invariato;
   - tail: testo piccolo (<16 righe, <8000 char) invariato byte-per-byte;
   - tail: 5000 righe corte → ultime 16;
   - tail: testo da 200KB → risultato ≤8000 char e ≤16 righe, prima riga completa;
   - tail: singola riga da 200KB → ≤8000 char;
   - tail: `details`/`toolCallId`/`toolName` invariati;
   - tail: `partialResult` non-oggetto o senza `content` → invariato;
   - altri tipi di evento non afectados dalle options.

5. **`android/app/src/main/java/app/pimobile/data/PiApi.kt`**
   - `events(sessionId)`: path `/api/agent/${encode(sessionId)}/events?toolUpdates=tail`;
     KDoc aggiornato (l'app opta per gli update in coda; il risultato completo arriva
     comunque con `tool_execution_end`). Copre sia `ChatViewModel` sia
     `RunWatcherService` (che legge solo eventi dialog → payload più piccoli pure lì).

**Non modificati:** web client (`useAgentSession.ts` — EventSource senza param),
`ChatViewModel.kt`, `ChatItems.kt`, `RunWatcherService.kt`, SDK.

## Delta visiva accettata

Espandendo un tool **in esecuzione** con output >8000 char, la vista espansa mostra la
coda (ultime 16 righe) invece delle prime 8000 char. Collapsed (ultime 8 righe) e stato
finale (dopo `tool_execution_end`) invariati.

## Verifica

- `node --experimental-strip-types --test lib/agent-event-wire.test.mjs`
- `node_modules/.bin/tsc --noEmit`
- `npm run lint`
- Android: `cd android && ./gradlew :app:compileDebugKotlin` (se l'environment lo permette)
- Smoke manuale opzionale: dev server + sessione con comando verbose, confrontare
  payload SSE con e senza `?toolUpdates=tail`.
