# Plan: Android — subagent bar (visualizzazione subagenti nativi)

## Obiettivo

Portare sull'app Android la visualizzazione dei subagenti nativi di pi-web, come il
picker web (`components/AgentSessionPanel.tsx`), con il UX scelto da Daniele:

- **Barra collassabile sopra il composer** (stesso stile del `TtsBar`):
  - **collassata** (stato di default dopo interazione utente): solo la riga
    intestazione con conteggio, es. `Subagenti  3 · 1 running`;
  - **espansa**: lista righe cliccabili con stato live
    (running/completed/failed/aborted).
  - Al primo apparire (nuovi subagenti / apertura chat) parte **espansa**;
    la scelta collassa/espandi resta per la vita dello screen (`remember`),
    non persistita a disco.
- **Tap su una chip** → entri nella chat del subagente (è una sessione normale:
  storico completo + composer attivo, come nella web).
- **Tasto back** → torna alla sessione padre (il subagente è un push nel NavHost).
- **Nesting ricorsivo**: la barra appare in qualsiasi sessione che abbia figli.
- **Abort** della v1: long-press su una chip running → conferma →
  `POST /api/subagents/[id] {action:"abort"}`. Niente steer.

## Decisioni già prese

| Scelta | Esito |
|---|---|
| Fonte dati | Riuso di `GET /api/sessions` (già cacheata, include `relation` + `runningSessionIds`). **Zero cambi server.** |
| Contenuto barra | Solo subagenti (niente riga "sessione principale"); il ritorno è il back. |
| Nesting | Ricorsivo: qualsiasi sessione con figli mostra la barra. |
| Azioni | Solo abort (endpoint esistente). Steer fuori scope. |

## Fatti rilevanti

- I subagenti sono sessioni normali: `.jsonl` proprio, `GET /api/sessions/[id]` e
  SSE `/api/agent/[id]/events` funzionano già. `ChatScreen`/`ChatViewModel` non
  hanno nulla di speciale da fare per aprirli.
- `GET /api/sessions` restituisce per ogni sessione
  `relation: {kind:"subagent", parentSessionId, profile, description, status}`
  (vedi `lib/session-reader.ts`) e `runningSessionIds` a livello di risposta.
- `SessionsViewModel.kt:135` filtra i subagenti dalla lista: **resta così** (la
  lista non si popola); la chat li fetcha separatamente.
- `ChatScreen` setta `AppVisibility.viewingSessionId` (linea 279) → il polling
  della barra può rispettalo.
- La top bar del subagente deve dirlo che sei dentro un subagente: `info.relation`
  è già presente nella risposta di `GET /api/sessions/[id]` (oggi ignorata in
  `ChatViewModel`).

## Cambiamenti (ordine di implementazione)

### 1. `android/.../data/Subagents.kt` (nuovo) + test

- `data class SubagentInfo(id, title, profile, status, running, modified)`.
- Funzione pura `parseSubagents(body: JsonObject, sessionId: String): List<SubagentInfo>`:
  legge `sessions[]`, filtra `relation.kind == "subagent" &&
  relation.parentSessionId == sessionId`, applica `runningSessionIds`
  (running live ha priorità sullo status del file), ordina running-prima poi
  `modified` desc (stesso criterio del pannello web).
  - `title` = `relation.description` → `name` → `firstMessage` → id corto.
- Unit test `android/app/src/test/.../data/SubagentsTest.kt` sul parsing
  (modello: `ChatModelTest.kt`): filtro per padre, running live > status file,
  ordinamento, nested (un subagente con figli viene filtrato via al livello padre).

### 2. `android/.../ui/chat/ChatViewModel.kt`

- `ChatUiState`: nuovo campo `subagents: List<SubagentInfo> = emptyList()` e
  `subagentRelation: SubagentRelation? = null` (profile/description della
  sessione corrente, se è un subagente).
- `refreshSubagents()`: `GET /api/sessions` → `parseSubagents(...)`.
  Chiamata: dopo il primo load della sessione; su turn boundary (stessi hook
  dove già si riconcilia il context: `settle()`, `message_end`,
  `tool_execution_end`); dopo un abort.
- Loop leggero dedicato (non riutilizzo `pollJob`, che vive solo mentre la
  sessione stessa è running): ogni 5 s finché
  `AppVisibility.isForeground && viewingSessionId == sessionId` e
  (lista non vuota oppure la sessione corrente è running — è in questo stato che
  un `Agent` tool call può partorire subagenti). Stop altrimenti; riattivato al
  prossimo send/turn.
- `abortSubagent(id)`: `POST /api/subagents/{id}` `{action:"abort"}`; su 409
  ("not running") non è un errore → refresh.
- In `refreshSession` (dove oggi si legge `info`, ~linea 839-852): se
  `info.relation.kind == "subagent"` popola `subagentRelation`.

### 3. `android/.../ui/chat/ChatScreen.kt`

- Nuovo composable `SubagentBar` (stile `TtsBar`, linee 780+): sopra il
  composer, nel `bottomBar` del `Scaffold`, come primo elemento della colonna
  (sopra sia il `Composer` che `ExtensionDialogView`). Visibile solo se
  `state.subagents.isNotEmpty()`. **Collassabile** (deciso da Daniele):
  - Design finale (richiesta di Daniele post-PR): **bolla rounded a
    larghezza contenuta** — `RoundedCornerShape(26.dp)` + `background(t.surface)`
    + `border(1.dp, t.border)`, margini 12dp, identica al composer; non una
    striscia full-width con divider.

  ```
  collassata:                                    espansa:
  ┌──────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
  │  Subagenti   3 · 1 running           ⌄  │  │  Subagenti   3 · 1 running           ⌃  │
  └──────────────────────────────────────────┘  │  ◌ Investigate auth  code-reader  run   │
                                                │  ◌ Fix README        writer       run   │
                                                │  ✓ Check deps        bash         5m     │
                                                └──────────────────────────────────────────┘
  ```

  - Riga intestazione (sempre visibile, tap = toggle): label `Subagenti`,
    conteggio totale, `· N running` in accent solo se N > 0, chevron
    ⌄/⌃. Stato collassato in `remember` (default espansa).
  - Righe (solo espansa, vertical scroll se tante): icona stato (spinner
    running / check completed / X failed / quadrato aborted, palette del
    pannello web), titolo, `profilo · tempo relativo` in muted, stato/testo
    a destra. Stesso criterio di ordinamento del pannello web (running
    prima, poi `modified` desc).
  - Tap su riga → `onOpenSubagent(id)`.
  - Long-press su riga running → dialog di conferma "Interrompi questo
    subagente?" → `vm.abortSubagent(id)` (deciso da Daniele).
- Top bar: se `state.subagentRelation != null`, sottotitolo/badge sotto il titolo
  (es. `Subagent · <profile|description>`) per orientarsi dentro il subagente.
- Callback `onOpenSubagent: (String) -> Unit` aggiunto alla firma di `ChatScreen`.

### 4. `android/.../MainActivity.kt`

- Nel composable `CHAT_ROUTE`: cabla `onOpenSubagent` a
  `nav.navigate(chatRoute(id, cwd))` — **push** (nessun `popUpTo`), così il back
  risale alla sessione padre. La cwd è quella della chat corrente
  (`vm.state.value.cwd`).

### 5. `android/.../ui/theme/Icons.kt` (se serve)

- Icona "bot/robot" per i subagenti se non c'è già qualcosa di riutilizzabile
  (il pannello web usa un rect con "occhi").

## Fuori scope

- Steer (`POST /api/subagents/[id] {action:"steer"}`).
- Subagenti nella lista sessioni (restano filtrati in `SessionsViewModel`).
- Qualsiasi cambio al server Next.js.
- Ricerca/filtro nella barra (la web la aggiunge solo sopra gli 8; qui le chip
  scorrono orizzontalmente).

## Verifica

- `cd android && ./gradlew :app:testDebugUnitTest` (nuovo `SubagentsTest`).
- Manuale: sessione con subagenti (feature built-in attiva) → barra appare al
  primo `Agent` tool call; stati live; tap → chat subagente con storico; back →
  genitore; long-press running → abort; nesting di livello 2.
