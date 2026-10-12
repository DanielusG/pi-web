# Performance Review — App Android (`app.pimobile`)

**Data:** 2026-09-20
**Scope:** tutta la codebase Android (~45 file Kotlin, ~16k righe) + verifica dei comportamenti
lato server (pi-web) e SDK (`@earendil-works/pi-coding-agent`) che interagiscono con l'app.
**Criterio delle fix proposte:** zero cambio di funzionalità, zero cambio di resa visiva.
**Metodo:** lettura completa di ogni file; ogni affermazione è verificata sul codice (file:riga)
o sul bundle SDK. I costi sono stimati sui numeri reali del setup (LAN + Tailscale, LLM locale
Qwen 27B exl3 + MTP, output bash fino al cap SDK).

---

## TL;DR — tabella riassuntiva

| ID | Problema | Area | Severità | Fix |
|----|----------|------|----------|-----|
| P1 | Client HTTP senza `gzip` | rete | alta (banale) | app, 1 riga |
| P2 | `tool_execution_update` bash: snapshot completo ri-inviato ogni 100ms | rete + CPU | **alta (la più grande)** | pi-web (opt-in) + app |
| P3 | `RunWatcherService`: poll fisso 3s in background | rete / batteria | alta | app, event-driven |
| P4 | Re-parse markdown in main thread a 20Hz in streaming | CPU / heat | alta | app |
| P5 | Recomposition full-screen + fan-out a tutti gli item (20Hz) | CPU | alta | app |
| P6 | `streamFlushLoop`: timer 20Hz perpetuo | CPU | media | app |
| P7 | Rebuild output tool a ogni evento + `lines().takeLast(8)` | CPU | media | app |
| P8 | `refreshSession` su `agent_end` / ogni `onForeground` | rete | media | app (+ opz. pi-web) |
| P9 | Reconcile per turn boundary + poll 15s | rete | media | app |
| P10 | Doppio poll 3s (sessions screen + watcher) | rete | media-bassa | app |
| P11 | `messageTime`: `DateFormat` allocato a ogni recomposition | CPU | bassa | app |
| P12 | `PdfPages`: nessun cache dei bitmap renderizzati | CPU / IO | bassa | app |
| P13 | `HtmlDocument`: `view.tag = html` (memoria + confronto O(n)) | memoria | bassa | app |
| P14 | Animazioni infinite durante i run | GPU | **verificata OK** | — (vedi testo) |

---

## P1 — Il client HTTP non abilita `gzip`

**File:** `android/app/src/main/java/app/pimobile/data/PiApi.kt:44-53`
**Area:** rete (banda + tempo di radio)

### Come funziona oggi

I due `OkHttpClient` (`http` con readTimeout 120s, `sseHttp` con 75s) non hanno `.gzip()`,
quindi OkHttp non invia `Accept-Encoding: gzip`. Il server **comprime già quando il client lo
chiede** (`lib/json-response.ts:48-51`: se `Accept-Encoding` contiene gzip, la risposta JSON
passa per `CompressionStream("gzip")`). Risultato: ogni payload JSON — `GET /api/sessions`
(lista completa), `GET /api/sessions/[id]` (contesto, bounded a 50 entry ma con tool output
anche grossi), contenuti file — viaggia **non compresso**.

### Impatto

Ratio tipica 5-10x sul JSON. Un payload da 1-2 MB diventa 150-400 KB: su Tailscale/WAN sono
secondi di radio in stato ad alto consumo che scendono a frazioni di secondo. Il costo CPU di
decompressione è ~5-10 ms per 2 MB, fatto in trasparente da `GzipSource` sul thread dispatcher
IO (non main thread). Per i payload piccoli (poll del running, ~200 B) è neutro.

### Fix

`.gzip()` sul client `http` (non su `sseHttp`, che è streaming e il server lo serve con
`no-transform`). Una riga, nessun cambio di comportamento. Rapporto beneficio/rischio migliore
di tutto il report.

---

## P2 — `tool_execution_update` del bash: snapshot completo ri-inviato a ogni evento

**File:** SDK `@earendil-works/pi-coding-agent` (bundle `chunk-JVUZSMYM.js`, `createBashTool`);
client `ui/chat/ChatViewModel.kt` (handler `tool_execution_update` in `onEvent`);
display `ui/chat/ChatItems.kt:592`
**Area:** rete + CPU — **il singolo spreco maggiore individuato**

### Come funziona oggi

Verificato nel bundle SDK:

- il bash cattura l'output con `limits: { maxBytes: 51200, maxLines: 2000, retain: "tail" }`
  (cap di 50 KB / 2000 righe, mantenuta la coda);
- a ogni aggiornamento dell'output, throttled a `BASH_UPDATE_THROTTLE_MS = 100` (max ~10
  eventi/s), il tool chiama
  `onUpdate({ content: [{ type: "text", text: view.text }] })` dove **`view.text` è l'output
  accumulato completo, ri-inviato per intero a ogni evento** (non un delta);
- l'evento attraversa il wrapper e viene forwardato invariato sull'SSE
  (`lib/agent-event-wire.ts`, `toClientAgentEvent` passa `partialResult` com'è).

Nella pratica: un comando verbose che per 60 s continua a produrre output, raggiunti i 50 KB,
emette ~600 eventi × ~50 KB = **fino a ~30 MB di JSON in wire per singola tool call**. Lato
client: ~30 MB di JSON da parsare (IO thread), ~30 MB di stringhe ricopiate (rebuild dell'output
a ogni evento, vedi P7), ~30 MB scansionati da `lines().takeLast(8)` in main thread.
**L'app mostra solo le ultime 8 righe**: il resto è spreco puro. Il costo è quadratico in
(output × durata).

Nota: il server marca alcuni update con `checkpoint: true` (ogni `BASH_CHECKPOINT_INTERVAL_MS =
2000` ms) — è un hint per il TUI locale, il client non lo usa.

### Fix

1. **pi-web (la fix vera):** `toClientAgentEvent` (`lib/agent-event-wire.ts`) è già il punto di
   trasformazione wire — trasformare `tool_execution_update` per inviare solo la **coda**
   (es. ultime ~1-2 KB + totale caratteri/righe) invece dello snapshot completo. Per non rompere
   il web client: **opt-in via query param sull'SSE** (es. `?toolUpdates=tail`) che passa solo
   l'app; il web resta invariato. Il `tool_execution_end` finale porta comunque il risultato
   completo una volta sola (50 KB max, accettabile). Risparmio: da ~30 MB a decine di KB per
   tool call.
2. **App (complementare):** coalescing — durante una finestra di flush, processare solo l'ultimo
   update parziale (il tipo si può sniffare dall'inizio del JSON senza parsare gli altri) +
   finestra rolling delle ultime 8 righe nel VM (vedi P7).

---

## P3 — `RunWatcherService`: poll fisso a 3 secondi

**File:** `android/app/src/main/java/app/pimobile/notify/RunWatcherService.kt`
(`POLL_MS = 3_000` a riga 192; loop `watch()` righe 69-150)
**Area:** rete / batteria (background)

### Come funziona oggi

Service foreground `dataSync` che vive finché c'è almeno una sessione attiva (stop dopo
`IDLE_POLLS_BEFORE_STOP = 3` poll vuoti, ~9 s; `MAX_FAILURES = 40`). Ogni 3 s:

- `GET /api/agent/running` → `{ runningSessionIds, completionNotificationSuppressedSessionIds,
  sessionListVersion }`;
- `finished = previous - running - suppressed` → notifica di completamento;
- per ogni sessione attiva **tranne quella visualizzata** (`AppVisibility.viewingSessionId`):
  apre un SSE per-sessione usato **solo** per rilevare i dialog blocking (stato "in attesa"),
  con replay degli open dialog al riconnessione;
- aggiorna la notifica ongoing con lo stato delle sessioni.

### Impatto

Con un run lungo (10-60 min, caso tipico) sono centinaia di risvegli di rete; su Tailscale ogni
poll è un risveglio completo del radio. È il principale costo continuo in background. La
rilevazione del *completamento* dipende interamente da questo poll (latenza ≤ 3 s).

### Fattibilità dell'approccio event-driven (verificata)

**Sì, fattibile con modifiche solo all'app.** Catena verificata:

- l'SSE per-sessione (`lib/agent-event-stream.ts` + `lib/agent-event-wire.ts`) forwarda **tutti**
  gli eventi `JsonAgentSessionEvent` tranne `turn_start`/`turn_end`;
- `prompt_done` è emesso dal wrapper (`lib/rpc-manager.ts:646,658`) quando la Promise del
  prompt logico resolve — cioè a **fine run completo** (turni, retry, follow-up in coda
  inclusi). È il segnale affidabile di "task finita";
- `agent_settled` (evento SDK) copre i run injectati da extension;
- `agent_end` **da solo non è affidabile** (può fire a metà prompt: retry, compaction, messaggi
  in coda — è per questo che il web non chiude su di esso).

**Design proposto:**

1. **Bootstrap:** un solo `GET /api/agent/running` all'avvio (il service lo fa già come primo
   tick) → set iniziale delle sessioni attive.
2. **SSE per ogni sessione attiva, incluse quella "vista"** (oggi esclusa perché la chat ha il
   suo SSE): il watcher ha bisogno del `prompt_done` anche della sessione che l'utente sta
   guardando. Costo server di un secondo subscriber (con liveness lease) trascurabile.
3. **Su `prompt_done`/`agent_settled` della sessione X** → un `GET /api/agent/running` di
   verifica e applicazione della logica `finished` esistente (che gestisce anche
   `completionNotificationSuppressedSessionIds` per i subagent, senza ricopiarla). Latenza di
   notifica: ~1 RTT invece di ≤ 3 s.
4. **Tick di discovery lento (15-30 s):** serve solo a rilevare sessioni avviate **fuori
   dall'app** (web UI, TUI, altro dispositivo) — l'unico caso in cui il watcher non ha un SSE
   per la sessione. Finché non esiste un endpoint globale, questo poll residuo non si elimina.
5. **Reconnect con backoff** per gli SSE del watcher (oggi, se uno stream cade, si riapre "per
   caso" al tick successivo; in event-driven serve un loop di reconnect esplicito — pattern già
   presente nella chat VM).

**Risultato:** da poll fisso 3 s a ~1 ogni 15-30 s (solo discovery) + verifiche on-demand →
~90% di poll in meno sui run lunghi, latenza di notifica migliore di oggi.

**Follow-up opzionale (pi-web):** un broadcast globale del running-set (nuovo endpoint SSE,
~50 righe lato server) permetterebbe il 100% event-driven; non è prerequisito.

---

## P4 — Re-parse del markdown in main thread a 20 Hz durante lo streaming

**File:** `android/app/src/main/java/app/pimobile/ui/markdown/Markdown.kt:186`
(`MarkdownDocument`); `ui/markdown/MathNormalize.kt`
**Area:** CPU / heat

### Come funziona oggi

```kotlin
val state = remember(text) {
    parser.buildMarkdownTreeFromString(prepareMathMarkdown(text))
}
```

Durante lo streaming, il testo del messaggio in corso cresce a ogni flush (50 ms, vedi P6):
`text` cambia → `remember` scatta → **l'intero markdown accumulato viene ri-parsato
sincronamente sul main thread**, preceduto dai 2 pass line-based di `prepareMathMarkdown`
(~20 regex su tutto il testo). O(n) per tick, O(n²) per messaggio lungo (10-50k caratteri).
Il commento nel codice documenta la scelta ("parse asincrono = item vuoto per un frame, lista
che salta"): è un trade-off conscio, ma il costo main-thread in streaming è il hotspot CPU
nº 1 del client.

### Fix (visiva identica)

1. **Parse su `Dispatchers.Default`** tenendo in display il parse precedente finché il nuovo
   non è pronto (la libreria mikepenz supporta mantenere lo `State` esistente: nessun flash,
   solo un lag di parse di qualche ms che è invisibile a 20 Hz di flush).
2. `prepareMathMarkdown` si sposta insieme al parse, stesso thread.
3. Opzionale: alzare il flush da 50 a 100 ms (dimezza il lavoro; impercettibile sul testo).

---

## P5 — Recomposition full-screen a 20 Hz + fan-out a tutti gli item

**File:** `android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt:175`
(`val state by vm.state.collectAsStateWithLifecycle()`); items lambda righe ~540-640;
`ui/chat/ChatItems.kt:270` (firma di `AssistantMessage`)
**Area:** CPU

### Come funziona oggi

- `ChatUiState` è un unico data class grande: ogni `copy(...)` (20/s in streaming via P6, 10/s
  su update tool, a ogni reconcile) crea una nuova istanza → l'intera funzione `ChatScreen`
  (1957 righe) si ri-esegue.
- **Le lambda inline nei parametri item invalidano lo skip-recomposition di Compose.** Nel
  items lambda, ogni `AssistantMessage`/`UserBubble` riceve:
  - `onEditFromHere = if (canNavigate) { id -> ... }`, `onToolLongPress = { id -> ... }`,
    `onListen = { key, text -> ... }` — **closure inline: istanza nuova a ogni recomposition**;
  - `onLoadThinking = vm::loadFullThinking`, `onPreview = vm::loadEditPreview` — bound
    reference, anch'esse nuove a ogni recomposition.
  Le lambda non sono `@Stable` e l'`equals` è per riferimento → il confronto parametri fallisce
  **sempre** → **tutti gli item visibili si recompongono a ogni flush**, anche se non hanno
  cambiato nulla.
- In più, `AssistantMessage` riceve **4 mappe intere** (`toolResults`, `liveTools`,
  `fullThinking`, `editPreviews`): `liveTools` crea una nuova mappa a ogni
  `tool_execution_update` (~10/s su bash, throttled a 100 ms lato server) → recomposition di
  tutti i messaggi assistant visibili a 10 Hz durante l'esecuzione di un tool.

### Impatto

In streaming: 20 recomposition/s × (funzione ChatScreen + diff LazyColumn + recomposition di
ogni item visibile, 10-30 item). È il secondo hotspot CPU dopo P4 e lo rende più frequente.

### Fix (visiva identica)

1. **Hoistare i callback in valori stabili:** un oggetto "actions" creato una volta (o
   `remember` con key stabili) che legge lo stato mutabile (`editRequest`, `selectedToolId`)
   internamente, passato come singolo parametro con riferimento costante.
2. **Mappare per item:** `liveTools` filtrato per i `toolCallId` dei blocchi dell'item (i tool
   live appartengono di fatto solo allo streaming item) — oppure passarli solo allo streaming
   item; le altre 3 mappe cambiano solo a `publishMessages`, meno critiche.
3. (Opzionale, più invasivo) spezzare `ChatUiState` in sotto-stati così che il campo
   `streaming` non invalidi tutto.

---

## P6 — `streamFlushLoop`: timer 20 Hz perpetuo

**File:** `android/app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt:1304-1312`
**Area:** CPU

### Come funziona oggi

```kotlin
private suspend fun streamFlushLoop() {
    while (true) {
        delay(50) // coalesce token deltas into ~20 UI updates per second
        if (streamDirty) { streamDirty = false; publishStreaming() }
    }
}
```

Avviato in `init` (riga ~246) e **mai fermato**: gira per tutta la vita del ViewModel, anche a
idle. Il loop in sé è quasi gratis (delay + check booleano), ma è un coalescer **sempre
acceso**: 20 risvegli del main looper al secondo per sempre. Con un foreground service attivo
l'app è esente da Doze, quindi i tick partono puntuale anche con schermo spento e app in
background.

### Chiarimento sul ruolo del loop

Il loop **non è un poll**: i delta arrivano già come eventi SSE `message_update` immediati
(50-150/s con un modello locale veloce). Il loop è un **coalescer/rate-limiter** (come
`requestAnimationFrame`): senza, l'UI farebbe 100+ recomposition/s. Eliminarlo a favore di
"aggiorna a ogni evento" sarebbe peggio. Il problema è solo che non si ferma (qui) e che ogni
publish costa troppo (P4, P5).

### Fix

Avviare il loop al primo delta di streaming e cancellarlo al settle (o riavviarlo a ogni
nuovo run). Stesso meccanismo riusabile per coalescere i `tool_execution_update` (P7).

---

## P7 — Rebuild dell'output tool a ogni evento + `lines().takeLast(8)` a ogni recomposition

**File:** `ui/chat/ChatViewModel.kt` (handler `tool_execution_update` in `onEvent`);
`ui/chat/ChatItems.kt:592`
**Area:** CPU (main thread)

### Come funziona oggi

A ogni `tool_execution_update` (fino a ~10/s, throttled lato server; payload fino a 50 KB,
vedi P2):

1. `Messages.contentText(partialResult.content)` — ricostruisce **l'intera stringa** dell'output
   parziale (O(output));
2. aggiornamento della mappa `liveTools` (nuova istanza) → `StateFlow` emette → recomposition
   full-screen (P5);
3. `ToolCard` ricalcola `liveOutput.trimEnd().lines().takeLast(8).joinToString("\n")` a ogni
   recomposition (riga 592): O(n) sull'intero output (fino a 2000 righe) + alloc di
   `List<String>` + join, in main thread, **senza `remember`**.

Con output al cap (50 KB) e 10 eventi/s: ~500 KB/s di copie stringa + 10 scansioni full/s.

### Fix

1. **Coalescing nel flush loop (P6):** memorizzare solo l'ultimo partial per evento; costruire
   la stringa di display al flush (≤ 20/s) invece che a ogni evento. Il tipo di evento si può
   sniffare dall'inizio del JSON (`"type":"tool_execution_update"`) senza parsare quelli
   intermedi.
2. **Finestra rolling nel VM:** `LiveTool` mantiene incrementalmente le ultime 8 righe (o si
   scansiona solo l'ultima ~1 KB della stringa invece dell'intera) → `ToolCard` diventa O(1).
3. Con P2 (tail dal server) il dimensionamento scompare alla radice.

---

## P8 — `refreshSession` su `agent_end` e a ogni `onForeground`

**File:** `ui/chat/ChatViewModel.kt:878` (`refreshSession`), `:465` (`onForeground`),
`settle` (~riga 1031); server `app/api/sessions/[id]/route.ts`
**Area:** rete + CPU (parse)

### Come funziona oggi

`refreshSession` = `GET /api/sessions/[id]?deferThinking=1&deferMedia=1` → parse JSON completo
+ `mergeSnapshot` + `publishMessages` (remap O(n) di tutti i messaggi caricati). Viene fatto:

- a ogni `agent_end` (che può ripetersi per retry/messaggi in coda → più download dello stesso
  contesto per lo stesso prompt);
- a ogni `settle` (fine run);
- a **ogni `onForeground`** (ogni `STARTED`: schermo on, app switch, ritorno dalle
  notifiche) → refresh completo + reconcile + `refreshSubagents`.

**Correzione sul dimensionamento:** il server ha un default `tail=50`
(`lib/session-reader.ts`, `buildSessionContext`): il payload è bounded alle **ultime 50 entry**
del ramo attivo (con `hasMore`/`oldestEntryId` per la paginazione, che l'app usa via
`loadEarlier()`), non l'intera sessione. Quindi decine di KB, centinaia nel caso sfavorevole —
non i multi-MB ipotizzati nella prima stesura.

### Perché esiste (anti-desync)

Gli eventi SSE e la persistenza su file sono **non ordinati sul wire** (il server persiste il
messaggio solo dopo averlo emesso — commentato nel codice di `refreshSession`), e su mobile
l'SSE può cadere in background (switch rete, half-open) **senza replay** degli eventi
mancanti. Il reload è il modo "stupido ma sicuro" di convergere con lo stato persistito.

### Analisi del rischio se reso condizionale

- Run attivo quando backgroundi → completato mentre sei via → rientri: lo stato `running`
  diverge (app: running, server: idle) → **rilevabile** col reconcile economico
  (`GET /api/agent/[id]`, che `onForeground` già fa).
- Run avviato **altrove** (web/TUI) nella sessione in vista, completato prima del rientro:
  entrambi idle → **non rilevabile** senza un nuovo segnale. Conseguenza: chat stale finché non
  si naviga via e torna.

### Fix

- **Opzione A (app-only):** reload completo solo su mismatch del reconcile (running/isStreaming
  diverso dallo stato locale) o se l'ultima attività è vecchia; altrimenti solo reconcile.
  Accetta la piccola finestra del caso 2.
- **Opzione B (pi-web, piccola, zero finestra di desync):** endpoint meta leggero, es.
  `GET /api/sessions/[id]?meta=1` → `{ info.modified, messageCount, leafId }` (~200 byte;
  `info.modified` = mtime del file esiste già, oggi sepolto nel payload pesante). Si confronta
  con l'ultimo `modified` noto → reload completo solo se il file è cambiato.
- In entrambe: **coalescere** i `refreshSession` ravvicinati (debounce ~500 ms) così i
  `agent_end` multipli di uno stesso prompt costano un solo download.

---

## P9 — Reconcile per turn boundary + poll 15 s

**File:** `ui/chat/ChatViewModel.kt` (`reconcile` ~riga 971; `pollJob` avviato da
`setRunning`; handler `onEvent`)
**Area:** rete

### Come funziona oggi

- `reconcile()` = `GET /api/agent/[id]` → `{ running, state{ isStreaming, isPromptRunning,
  isBashRunning, isCompacting, model, contextUsage, thinkingLevel, queuedMessages, ... } }`.
- Viene chiamato **a ogni turn boundary**: `message_end` (assistant), `tool_execution_end`,
  fine compaction → in un run chattoso (20 tool call) sono 20 GET in più.
- Inoltre, mentre `running == true`, `pollJob` reconcile **ogni 15 s** (`STATE_POLL_MS`).

### Perché esiste

È la **rete di sicurezza** per gli eventi SSE persi (mobile: switch Wi-Fi/cellulare,
background, connessioni half-open): se il terminale evento è andato perso, il poll lo rileva
entro 15 s. Stesso pattern del web (`AGENTS.md`: reconciliation periodica + su
`visibilitychange`/`online`). Il poll tiene anche aggiornato l'indicatore di contesto (token
usage live) durante il run. **Non è il segnale primario** — quello è l'SSE.

### Fix

- Throttle del reconcile per-boundary (max 1 ogni N secondi) o rimozione dove ridondante: il
  poll a 15 s copre già la rilevazione con latenza bounded.
- Allungare il poll a 30-60 s mentre l'app è in foreground con SSE sano (il poll interviene
  raramente); mantenerlo più reattivo se serve al solo indicatore di contesto, che potrebbe
  essere alimentato da un campo dedicato degli eventi invece che dal poll.

---

## P10 — Doppio poll a 3 s con la sessions screen aperta

**File:** `ui/sessions/SessionsViewModel.kt:75` (`watch()`); `notify/RunWatcherService.kt`
**Area:** rete

### Come funziona oggi

`SessionsViewModel.watch()` fa un proprio `GET /api/agent/running` ogni 3 s, correttamente
lifecycle-tied (`repeatOnLifecycle(STARTED)` in `SessionsScreen.kt:109`). Se una sessione è
attiva, il `RunWatcherService` è vivo e fa **lo stesso GET sullo stesso endpoint ogni 3 s**:
due poll in parallelo.

### Fix

Quando il service è attivo, la schermata si nutre dello stato del service (che già mantiene
`PiApp.waitingSessionIds` come `StateFlow`; basterebbe esporre anche il running-set) invece di
pollare in parallelo. Alternativa minima: la screen polla a 10-15 s quando il service è attivo.

---

## P11 — `messageTime`: `DateFormat` allocato a ogni recomposition

**File:** `ui/Format.kt` (`messageTime`); `ui/chat/ChatItems.kt:169` (`MessageFooter`)
**Area:** CPU (alloc)

### Come funziona oggi

`messageTime()` crea `DateFormat.getTimeInstance(DateFormat.SHORT)` + `Calendar.getInstance()`
a **ogni chiamata**, senza `remember`, nel footer di ogni messaggio — che si recompose spesso
(a causa di P5). `DateFormat.getInstance` è un'alloc non banale.

### Fix

`remember(timestamp)` nel footer, o formatter statico reusabile.

---

## P12 — `PdfPages`: nessun cache dei bitmap renderizzati

**File:** `ui/files/FileMediaViews.kt` (`PdfDocument`, `PdfPage`)
**Area:** CPU / IO (feature usata di rado)

### Come funziona oggi

Ogni pagina visibile è renderizzata su `Dispatchers.IO` via `PdfRenderer` (bitmap ARGB_8888,
~7 MB a 1200×1600 px). Il `produceState` muore col recycle dell'item nel `LazyColumn`:
**lo scroll-back ri-renderizza ogni pagina** da zero.

### Fix

`LruCache` piccolo (4-6 pagine) keyed `(pdf, page, widthPx)`.

---

## P13 — `HtmlDocument`: `view.tag = html`

**File:** `ui/files/FileMediaViews.kt`
**Area:** memoria (feature usata di rado)

### Come funziona oggi

L'HTML completo viene duplicato nel tag della WebView (`view.tag = html`) e confrontato per
intero (string equality O(n)) a ogni recomposition, per decidere se ricaricare.

### Fix

Confrontare una firma (lunghezza + hash, o counter di generazione) invece della stringa intera.

---

## P14 — Animazioni infinite durante i run (verificata OK)

**File:** `ui/theme/Components.kt` (`StatusDot`, `ShimmerText`); usi in `ChatScreen.kt`,
`SessionsScreen.kt`, `ChatItems.kt`
**Area:** GPU

### Verifica della scoping (chiesta da Daniele)

Rilettura del codice: **la scoping è corretta**.

- `StatusDot`: `alpha` è letto *dentro* il composable `StatusDot` e applicato via
  `Modifier.alpha` sul Box di 6 dp. I genitori (TopAppBar, righe) non leggono `alpha` → non si
  recompongono. Per frame si invalida solo quel cerchietto.
- `ShimmerText`: `progress` letto dentro il composable, brush applicato a una singola riga di
  `Text` (`maxLines = 1`). Per frame: nuovo offset + redraw di quella riga.
- `CircularProgressIndicator` (SubagentBar): solo sé stesso.

In più, gating del vsync: schermo spento o finestra non visibile → il Choreographer non invoca
frame → le infinite transition non producono lavoro. Il costo residuo è solo: N layer minuscoli
a 60 fps **mentre l'utente guarda lo schermo durante un run** (con N dot in parallelo nella
sessions list: uno per riga attiva + header).

### Conclusione

Priorità bassa/accettabile: è il costo intrinseco di un'animazione infinita, piccolo e
proporzionato alla durata del run. Le uniche leve (N dot aggregati, frame rate ridotto) hanno
trade-off visivo → non toccare senza decisione esplicita.

---

## Verificato OK (niente da fare)

- **OkHttp:** i due client condividono la connection pool (`newBuilder`) → riuso connessioni;
  SSE letto su `Dispatchers.IO` con parse fuori dal main thread; readTimeout SSE 75 s coerente
  con il heartbeat server a 30 s (`lib/agent-event-stream.ts`).
- **Loop lifecycle-tied:** tutti i `watch()` (sessions, file viewer) sono dentro
  `repeatOnLifecycle(STARTED)`; nessun loop orfano; `RunWatcherService` si ferma da solo
  (3 poll idle / 40 failure) e fa cleanup in `onDestroy`.
- **ASR/TTS:** `AsrClient` con thread audio a blocking read (10 blocchi/s, timeout di sicurezza
  3 s/60 s/15 s, audio focus transiente); `TtsPlaybackService` con ExoPlayer
  `WAKE_MODE_NETWORK` che si stoppa da solo a fine playback; `TtsDataSource` con close che
  cancella la call.
- **Immagini:** decode campionario su `Dispatchers.Default`, `LruCache` 24 MB, cap 10 MB /
  1024 px; preview a 2048 px (2 bitmaps contemporanee, accettabile).
- **Debounce:** search files 200 ms, reload file viewer 250 ms.
- **WebView/VideoView:** WebView hardened (`blockNetworkLoads`, `allowFileAccess=false`,
  `shouldOverrideUrlLoading` che blocca la navigazione) e `destroy()` in `onRelease`;
  VideoView rilasciato in `onRelease`.
- **Cap di rendering:** diff 400 righe (`DiffView`), output tool clip 8000 char, `CodeBlock`
  bounded, `TextLines` in chunk da 256 KB.
- **Nessun logging spam** (un solo `Log.d` nel hack di riflessione LaTeX, già mitigato da
  `disablePreciseGlyphBounds()`).
- **Manifest:** permessi minimi coerenti con le feature; nessun wake lock selvaggio.
- **`ChatAutoScroll` (BottomFollow):** `snapshotFlow` che emette solo a change; `jumpTo` a ogni
  crescita dell'ultimo item (by design).
- **`Patch.kt` / `DiffRow`:** parse e `AnnotatedString` correttamente `remember`-ed.

### Note minori (non performance, solo segnale)

- `ImageAttachments`: key della cache = `len:hashCode` → collisione teorica (ignorable).
- `FileShare`: la cache `cacheDir/shared/<hash>` non viene mai pulita (crescita disco lenta).

---

## Priorità e workstream

### Workstream A — solo app (nessuna dipendenza da pi-web)

| # | Fix | Impatto | Rischio |
|---|-----|---------|---------|
| A1 | P1 `gzip()` | rete su tutti i payload | ~zero (1 riga) |
| A2 | P4 parse markdown off-main | CPU/heat in streaming | basso |
| A3 | P5 scoping recomposition (callback stabili + mappe per item) | CPU in streaming/run | medio (Compose) |
| A4 | P3 watcher event-driven (+ discovery 15-30 s) | batteria in background | medio-basso |
| A5 | P6 start/stop del flush loop | CPU a idle/background | ~zero |
| A6 | P7 coalescing + finestra rolling 8 righe | CPU main thread | basso |
| A7 | P9 throttle reconcile per-boundary + poll 30-60 s | rete durante i run | basso |
| A8 | P10 dedup poll sessions screen | rete | basso |
| A9 | P8 opzione A (reload condizionale su reconcile) | rete su sessioni lunghe | medio (finestra desync) |
| A10 | P11, P12, P13 | CPU/IO/memoria | ~zero |

### Workstream B — app + pi-web (i due cambi server sbloccano i risparmi maggiori)

| # | Fix | Impatto | Rischio |
|---|-----|---------|---------|
| B1 | P2 tail dei `tool_execution_update` via `?toolUpdates=tail` (opt-in, backward-compat col web) | **rete: da ~30 MB a decine di KB per tool call verbose** | basso (transformation in `toClientAgentEvent`) |
| B2 | P8 opzione B: endpoint `?meta=1` (modified + messageCount + leafId) | rete: ~200 B per foreground, zero finestra desync | basso |
| B3 | (opzionale) broadcast globale del running-set per watcher 100% event-driven | elimina anche il tick di discovery | medio (nuovo endpoint) |

**Ordine suggerito:** A1 + B1 (massimo rapporto risparmio/lavoro), poi A2+A3+A5+A6 (hotspot
streaming), poi A4 (+ B3 opzionale), poi il resto.
