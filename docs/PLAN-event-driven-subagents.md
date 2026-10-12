# Plan — Subagent bar: endpoint mirato + refresh event-driven (no polling)

## Problema
`ChatViewModel.refreshSubagents()` scarica `GET /api/sessions` (lista completa, ~1–1.3 MB
con le 2167 sessioni di produzione) **ogni 5 s** durante ogni run attivo, solo per
estrarre i (pochi) subagent di una sessione. È la causa dominante dei 1.28 GB / 2 giorni
e del drain di batteria (radio + parsing JSON costante).

## Obiettivo
- Aggiornare la barra subagent **solo quando un subagent cambia** (event-driven, niente
  pull a vuoto ogni N secondi).
- Ogni refresh trasporta **pochi KB** (solo i subagent diretti della sessione), non la
  lista completa.

## Perché è event-driven "per free"
Dal punto di vista del genitore, un subagent è una **tool call `Agent`**
(`lib/subagent-extension.ts:130`, `name: "Agent"`). L'SSE del genitore
(`/api/agent/[id]/events`) forwarda già `tool_execution_start` / `tool_execution_end`
(`OMITTED_EVENT_TYPES` contiene solo `turn_start`/`turn_end`). Il client Android li
riceve già in `onEvent`. Inoltre la spawn/finish di un subagent chiama
`invalidateSessionListCache()` (`lib/subagent-runtime.ts`, 10 call site), quindi la
cache server-side si azzera esattamente quando serve.

## Cambiamenti

### 1. Server — nuovo endpoint mirato
**File:** `app/api/sessions/[id]/subagents/route.ts` (nuovo)

`GET /api/sessions/[id]/subagents` → `{ subagents: [...] }`
- Reusa `listAllSessions()` (già cached, TTL 30 s) e filtra
  `relation.kind === "subagent" && relation.parentSessionId === id`.
- Ogni item: `{ id, name, firstMessage, profile, description, status, modified, running }`
  con `running` da `getRunningRpcSessionIds()`.
- Payload: pochi KB (di solito 0–5 subagent diretti).
- Nota: la cache si invalida a ogni spawn/finish subagent, quindi il fetch post-evento
  restituisce dati freschi.

### 2. Client — parser + fetch mirato
**File:** `android/.../data/Subagents.kt`
- Nuovo parser per la forma `{ subagents: [...] }` (invece di filtrare la lista
  completa). `SubagentInfo` invariato.

**File:** `android/.../ui/chat/ChatViewModel.kt`
- `refreshSubagents()` → chiama `GET /api/sessions/[id]/subagents` (pochi KB) invece di
  `GET /api/sessions`.

### 3. Client — rimuovi il polling, aggiungi i trigger event-driven
**File:** `android/.../ui/chat/ChatViewModel.kt`
- **Rimuovi** `startSubagentLoop()`, `subagentPollJob`, `subagentPollActive()`
  (il loop di 5 s) e la chiamata a `startSubagentLoop()` in `init`.
- **Trigger di `refreshSubagents()`** (tutti a costo pochi KB):
  - `init` (se `sessionId != null`) — già presente.
  - `onForeground()` — già presente (copre il ritorno da background).
  - `onEvent` → `tool_execution_start` con `toolName == "Agent"` (subagent dispatchato).
  - `onEvent` → `tool_execution_end` con `toolName == "Agent"` (subagent terminato).
  - `onEvent` → `connected` con `reconnect == true` (recupera eventi persi su
    riconnessione SSE).
  - `abortSubagent()` — già presente.
  - `settle()` — già presente (fine run).

## Effetto atteso
- Niente più fetch da ~1.2 MB ogni 5 s → elimina la causa dominante del traffico.
- Aggiornamenti solo su cambiamento reale (o su foreground/reconnect come recovery).
- Batteria: niente più wake-up radio/CPU da polling costante.

## Open question / note
- **Timing start:** al `tool_execution_start` il file del subagent potrebbe non essere
  ancora pronto; refresh su start **e** end copre entrambi gli stati (running →
  terminale). Costo trascurabile (pochi KB).
- **Safety net:** propongo di NON tenere alcun poll (in linea col principio). La
  recovery è su reconnect + foreground. Se vuoi una rete di sicurezza, un poll a 60 s
  sarebbe l'unico residuo — decisionale.
- Il fix è ortogonale ai contributori secondari (`refreshSession` a turn boundary,
  `SessionsViewModel.watch`); si possono affrontare dopo se serve.
