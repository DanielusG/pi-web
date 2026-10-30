# Plan — merge upstream v0.13.0

Range: `v0.12.2..v0.13.0` — 99 commit, 267 file, +21494/−1625. `merge-base` è esattamente
`b017cd4 Release v0.12.2`, quindi nessuna deriva accumulata.

## Decisioni prese

- **Dipendenze**: bump `@earendil-works/*` 1.2.0 → 1.4.0 dentro la stessa merge. Set di pacchetti
  invariato (7, nessun nuovo pacchetto transitivo nell'albero). Dopo la fusione:
  `npm install --save-exact` su tutti e sette perché `lib/pi-packages.test.mjs` esige che ogni
  pacchetto pi dell'albero sia una dipendenza diretta al versione bloccata.
- **Debug panel (#1443)**: entra, così com'è (`components/DebugPanel.tsx`,
  `lib/{debug-snapshot,page-debug,page-stream-registry,debug-types}.ts`, `app/api/agent/debug`).
- **`.github/`**: resta cancellato (stance delle pending cleanups). `publish.yml` è un file *nuovo*
  di upstream, quindi non genera conflitto: va rimosso dopo la fusione e la nota in AGENTS.md
  aggiornata a coprire anche lui.
- **`feat/server-status-card`**: accantonato, non entra prima della merge.
- **`demo/*`**: resta cancellato (26 `modify/delete`, tutti lì).

## Dry run (`git merge-tree --write-tree main v0.13.0`)

45 percorsi in conflitto: **26 `modify/delete` tutti in `demo/*`** + **19 `content`**:

```
AGENTS.md
app/api/agent/[id]/events/route.ts
app/api/agent/[id]/route.ts
app/api/sessions/route.ts
app/globals.css
components/AppShell.tsx
components/FileExplorer.tsx
components/MessageView.tsx
components/ModelsConfig.tsx
docs/agents/sessions.md
docs/agents/subagents.md
e2e/extension-dialog.mjs
hooks/retry-banner.test.mjs
hooks/useAgentSession.ts
lib/agent-event-stream.ts
lib/subagent-extension.integration.test.mjs
lib/subagent-extension.ts
package.json
package-lock.json
```

Auto-fusi ma da riverificare: `lib/rpc-manager.ts`, `lib/subagent-runtime.ts`, `lib/subagents.ts`,
`lib/agent-event-wire.ts`, `app/sidebar.css`, `app/settings.css`,
`components/{ChatInput,ChatWindow,FileViewer,TabBar}.tsx`, `lib/i18n/messages/*`, `README.md`.

Upstream non tocca nessun file fork-only (nessun `ask_user_question`, nessuna rotta Android,
nessun `session-fork/delete/list-page/cold-context/word-diff/edit-preview/tool-display`,
nessun `session-liveness/mcp-host/pi-sdk-internals/builtin-extensions/session-reader/session-list-scanner`).

## Aree semantiche

1. **Subagents** — upstream #1440 (modello/thinking del subagent nella lista a tre righe), #1448
   (wait budgets), #1386 (run interrotto non più "running"), #1437 (notifica a gruppo+reply finale).
   Il fork: `lib/subagent-input.ts` cancellato, il profilo possiede model/thinking/isolation,
   `resume` rifiuta run isolati.
2. **Stream e liveness** — upstream #1445 (un countdown di idle per sessione), #1328 (pagine
   nascoste rilasciano gli stream), #1426 (coda prompt in compaction), #1457 (prompt mai arrivato),
   #1348 (fase mantenuta al refresh), #1451 (rename via AgentSession). Il fork porta `?lite=1`,
   `?toolUpdates=tail`, `lib/run-status-stream.ts`, awaiting-input: il client Android dipende dal wire.
3. **MessageView** — upstream #1460, #1324, #1304, #1444, #1396 contro il nostro tool summary line,
   righe word-diff, dialog inline.
4. **CSS** — `globals.css` in conflitto, `sidebar.css`/`settings.css` auto-fusi: le regole nuove di
   upstream sono a colori hardcoded e vanno riportate ai token (4 test ne asseriscono le forme).
5. **SDK pi 1.2.0→1.4.0** — audit in corso in parallelo; le sue conclusioni possono imporre fix
   extra prima dei gate.

## Gate

`tsc --noEmit`, `npm run lint`, `npm test`, Android `gradle --offline`, `npm run test:e2e` in un
worktree usa-e-getta sotto `/tmp`.

## Aftermath

- `package.json` versione: upstream ha già pubblicato `@agegr/pi-web@0.13.0` su npm, quindi il fork
  non può pubblicare `0.13.0`; si decide al momento del rilascio (`0.13.1` o prerelease).
- Tag `v0.13.0-fork` sul commit di docs che registra l'esito, come per `v0.12.2-fork`.
- `docs/REPORT-upstream-merge-v0.13.0.md` con l'esito, e `AGENTS.md` + `docs/agents/*` aggiornati.
