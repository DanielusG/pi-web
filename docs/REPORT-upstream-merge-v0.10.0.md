# Report — Merge upstream v0.9.3 → v0.10.0

_Data: 2026-10-04. Fork point: `22f6e01` (tag `v0.9.3-fork`). Upstream: `upstream/main` = tag `v0.10.0`._

## 1. I numeri

| | Fork (HEAD) | Upstream (v0.10.0) |
|---|---|---|
| pi SDK | 0.87.1 | **1.0.0** (via 0.99.0/0.99.1/0.99.2) |
| Commits dal fork point | 736 | 778 |
| File modificati dal fork point | 685 | — |
| File totali in diff HEAD↔upstream | 671 | |
| File in conflitto (merge-tree) | **411** | |

Riduzione reale del carico di conflitti:

| Categoria | File | Righe di diff |
|---|---|---|
| Contenuto **identico** (risoluzione banale: prendere una delle due) | **274** | 0 |
| Easy (≤40 righe) | 69 | ~970 |
| Medium (41–200 righe) | 41 | ~4.600 |
| Hard (>200 righe) | 27 | ~14.600 |

Il lavoro vero è quindi **137 file**, ~20k righe, concentrate in ~15 file sostanziali.

## 2. Lavoro del fork già upstreamed (niente da fare semanticamente)

Molto del lavoro del fork è confluito in upstream (i PR sono gli stessi, es. #930, #874, #934, #505). Verificato byte-identico HEAD↔upstream:

- `lib/subagent-settings.ts`, `subagent-queue.ts`, `subagent-prompt.ts`, `subagent-input.ts`, `subagent-profile-precedence.ts`
- `app/api/web-auth/`, `lib/auth-throttle.ts`, `proxy.ts`
- `app/settings.css` al punto #930 (il fork non lo ha toccato dopo; upstream l'ha esteso col pane MCP)
- ADR 0001–0005 (upstream ha anche aggiunto **0006-mcp-and-code-mode**)

I 274 conflitti "identici" nascono da questo: stessa modifica applicata da due storie diverse.

## 3. Lavoro fork-only da preservare (nessun conflitto, ma da non perdere)

### `android/` — 95 file
Zero sovrapposizione con upstream. Nessun rischio.

### 19 file lib/app (funzionalità fork-only)

| File | Funzione | Nota |
|---|---|---|
| `app/api/agent/running/events/route.ts` + `lib/run-status-stream.ts` | SSE running+waiting per Android | Upstream ha modificato `agent/running/route.ts` → punto di integrazione |
| `lib/agent-state-lite.ts` | `?lite=1` (niente systemPrompt) | `agent/[id]/route.ts` modificato da entrambi |
| `lib/session-list-page.ts` | `?perProject=&project=&recentHours=` | `sessions/route.ts` modificato da entrambi |
| `app/api/sessions/[id]/subagents/route.ts` | subagent run per sessione | — |
| `lib/cold-context.ts` | usato dai route `sessions/[id]/context` e `sessions/[id]` | entrambi modificati |
| `lib/edit-preview.ts` + `app/api/edit-preview/route.ts` | "edit from here" / quoted selection | usato da `MessageView.tsx` (conflict hard) |
| `lib/word-diff.ts` | diff parola-per-parola per edit-preview | **usa il package `diff`, rimosso da upstream** → da riaggiungere in package.json |
| `lib/tool-display.ts` | display dei tool in MessageView | — |
| `lib/startup-preferences.ts` | persiste le preferenze esplicite di nuova sessione | ⚠️ **upstream ha rimosso questa feature** (`6a1246e` #871: "do not persist new-session model picks into global defaults"). Il fork la usa ancora (`rpc-manager.ts` importa `persistExplicitStartupPreferences`). **Decisione necessaria** |

## 4. Aree di conflitto reale

### A. Upgrade SDK 0.87.1 → 1.0.0 (la voce più grossa)

Novità SDK (changelog):
- **0.99.0**: codemode + MCP + `tool_search` come extension built-in; nuovo API tool exposure (`exposure`, `namespace`, `annotations`, `outputSchema`, `prepareLoadout()`, `ctx.executeTool()`, nested tool calls con `parentToolCallId`); **breaking**: `finishTurn` sostituisce `shouldStopAfterTurn`; API session v2 promossa a default; `ModelRuntime` ridisegnato (`getModels()`, `getModel()`, ecc. al posto di `getAll()`/`find()`/`getSnapshot()`); tema system; sign-in ChatGPT; virtual models; classifier models
- **0.99.2**: MCP exposure `codemode`/`direct`, deferred MCP tools, `mcp_servers` section nel system prompt
- **1.0.0**: MCP OAuth hardening (RFC 9207, credenziali per server), codemode più lean, `models.generateImages()`, Radius

File toccati (righe di diff fork↔upstream):
- `lib/rpc-manager.ts` (483) — upstream: `mcpHost` nel wrapper, `resolveActiveToolNames()` al posto di `withExtensionTools()`, shutdown deadline, `SESSION_TOOL_NAMES` (codemode, tool_search, subagent tools), rimozione di `persistExplicitStartupPreferences`
- `hooks/useAgentSession.ts` (409)
- `lib/agent-event-wire.ts` (142) + `agent-event-stream.ts` (144) — il fork ha qui `ClientAgentEventOptions` con `toolUpdates=tail` (Android); upstream ha aggiunto `isNestedToolExecutionEvent`
- `lib/session-reader.ts`, `lib/normalize.ts` (60), `lib/types.ts`, `lib/pi-types.ts`
- `lib/exact-system-prompt.ts` (chat-only + subagent replace mode) — da verificare contro il nuovo flusso `before_agent_start`
- subagent: `subagents.ts` (185), `subagent-runtime.ts` (64), `subagent-extension.ts` (22) — upstream ha aggiunto isolamento in worktree, resume, profili tintinweb

**Strategia**: upstream ha già fatto tutta l'adattazione a 1.0.0 → prendere l'adattazione upstream e riapplicare sopra le deviazioni fork (tool preset, chat-only, exact-system-prompt, toolUpdates=tail, lite=1).

### B. MCP / Code mode (sottosistema nuovo, ADR 0006)

~30 file nuovi da prendere in blocco: `lib/mcp-host.ts`, `mcp-command.ts`, `mcp-import-core.ts`, `mcp-read-only-policy.ts`, `mcp-tool-display.ts`, `components/McpConfig.tsx`, `mcp-config-helpers.ts`, ecc.

Punti di integrazione in conflitto:
- `lib/rpc-manager.ts` (parametro `mcpHost`, `MCP_WAIT_STOPPED_MESSAGE`)
- `app/api/tools/settings/route.ts` (128) — Code mode preference
- `lib/api-types.ts` (736, quasi tutto aggiuntivo: tipi MCP/Codemode)
- `components/AppShell.tsx` (401), `SettingsUi.tsx` (562), `SettingsPanel.tsx` (117), `ModelsConfig.tsx` (219)
- `components/MessageView.tsx` (658) — upstream renderizza le chiamate codemode come script + tool call (`show a codemode call as its script`); il fork ha deviato lo stesso file per edit-preview/word-diff

**Android**: non ha supporto MCP; i nuovi tool (`codemode`, `tool_search`, `mcp__*`) appariranno comunque nei pannello tool e nei tool call → MessageView Android da verificare, ma non bloccante.

### C. Extension dialog: fork inline vs upstream overlay + widgets

Il conflitto UI più delicato.
- **Fork**: dialog inline nel composer slot (`{extensionDialogElement ?? chatInputElement}`, `ChatWindow.tsx:1418`) — deviazione intenzionale documentata in AGENTS.md
- **Upstream**: overlay upstream + nuovi `ExtensionStatusBar` + `ExtensionWidgets` + custom UI + status bar (`ChatWindow.tsx:1374`)
- `ChatWindow.tsx` (1035 righe di diff) è il file più grosso del merge
- Test da riallineare: `ChatAppearance.test.mjs` (aspetta 3 usi del wrapper dialog), `ChatWindow.extension-request.test.mjs`, `e2e/extension-dialog.mjs`
- `android/.../ChatScreen.kt` specchia il layout fork → da aggiornare se il layout cambia

**Raccomandazione**: mantenere il layout inline del fork e integrare status bar/widgets upstream attorno al composer.

### D. i18n

Entrambi i lati hanno aggiunto la localizzazione in modo indipendente (base senza i18n):
- Fork: `en.ts`/`zh-CN.ts`/`zh-TW.ts` = 715 righe
- Upstream: 1312 righe a file (copre anche MCP, Code mode, ecc.)

Risolvibile meccanicamente: prendere la struttura upstream + aggiungere le chiavi fork-specifiche (Android non usa i18n web).

### E. project-trust

- Fork: restricted mode + gating extension per progetti non fidati (`f985923`, `a392b08`)
- Upstream: esteso per MCP (trust dialog elenca i server MCP, paste-add + trust nello stesso passo, fix per `.pi/mcp.json` ostile)
- 3 file ~1.400 righe: `lib/project-trust.ts` (415), `ProjectTrustDialog.tsx` (460), test (523)
- Merge semantico: base upstream + preservare il gating fork

### F. Settings — banale

`app/settings.css` (1230): il fork è identico a upstream al punto #930 e non ha più toccato il file → **prendere upstream** (superset col pane MCP).

### G. package.json / lock

- pi packages 0.87.1 → 1.0.0
- Aggiunto: `@types/mdast`
- Rimosso da upstream: `diff` (serviva solo al fork per `word-diff.ts` → riaggiungere), `@fontsource-variable/geist`
- `package-lock.json` (2076 righe) → rigenerare con `npm install`

### H. AGENTS.md (409 righe)

Merge documentale: sezioni fork (Android, route fork-only, dialog inline, throttle) + sezioni upstream (ADR 0006, MCP, codemode). Attenzione: la sezione "Extension dialog replaces the input bar" deve restare.

## 5. Stima dello sforzo

| Fase | Contenuto | Stima |
|---|---|---|
| 1. Setup | branch, `git merge upstream/main`, `npm install` | 0.5 g |
| 2. Banali (274) | bulk `git checkout` una delle due versioni, verifica test | 0.5–1 g |
| 3. Aree meccaniche | settings.css, api-types, deps, i18n, AGENTS.md | 1 g |
| 4. Subagent + project-trust | merge semantico | 1 g |
| 5. Core SDK | rpc-manager, useAgentSession, agent-event-*, session-reader, exact-system-prompt | 2–3 g |
| 6. UI | ChatWindow (dialog inline + widgets), MessageView (codemode + edit-preview), AppShell, ChatInput, SessionSidebar | 2–3 g |
| 7. Verifica | tsc, lint, test suite completa (200→~270 test file), e2e, manuale web + Android | 1–2 g |

**Totale: ~8–11 giorni-uomo** con verifica accurata. La parte 5+6 è quella che richiede attenzione: lì le deviazioni fork si intrecciano con le novità upstream.

## 6. Decisioni aperte

1. **`startup-preferences.ts`**: upstream ha rimosso la feature (#871). Il fork la usa ancora in `rpc-manager.ts`.
   - (a) seguire upstream e rimuoverla — il fork persisterà le preferenze solo in runtime come fa upstream, oppure
   - (b) mantenerla come deviazione fork
2. **Extension dialog**: mantenere il layout inline del fork (raccomandato, è documentato come intenzionale) — conferma?
3. **MCP/Code mode**: accettato in blocco (raccomandato — è anche la direzione dell'SDK 1.0.0). L'Android resterà senza MCP nella prima iterazione.
4. **`diff` package**: riaggiungere la dipendenza per `word-diff.ts` (alternativa: reimplementare).
5. **Versione post-merge**: bump a `0.10.x-fork`?

## 7. Piano di verifica post-merge

1. `node_modules/.bin/tsc --noEmit`
2. `npm run lint`
3. Suite completa: `node --test` su tutti i `*.test.mjs` (upstream porta i test da 200 a 267 file)
4. e2e: `e2e/run.mjs` (dialog, appearance, file panel, terminal, themes)
5. Manuale web: nuovo session + tool preset, chat-only, fork, branch, extension dialog inline, settings (MCP, Code mode), subagent, search, export
6. Manuale Android: SSE eventi, `?lite=1`, `?perProject=`, running/events, TTS
7. Dev server su 30141 (mai `next build` in dev)
