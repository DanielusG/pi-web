# Plan — merge upstream v0.16.0

Range: `f130910a..v0.16.0` — 55 commit, 189 file, +8242/−736. `merge-base` è esattamente
`f130910a "Release v0.15.0"`, quindi nessuna deriva accumulata; il fork è 785 commit avanti
(`8c4f5c2b`, il fix dei menu del composer Android). Backup: `backup/main-pre-v0.16.0`.

## Decisioni prese

- **Dipendenze**: `@earendil-works/*` `1.5.0 → 1.5.2` (sempre **otto** pacchetti, `e911f7bc`),
  ognuno con `npm install --save-exact` perché `lib/pi-packages.test.mjs` esige il pin esatto.
  Nel range Next ed `undici` non si muovono. Restano le righe del fork:
  `@fontsource-variable/geist`, `ansi_up`, `diff`, `allowScripts`; `version` va a `0.16.0`.
- **edit-preview (#1625)**: upstream aggiunge un `lib/edit-preview.ts` col nome dello stesso file
  del fork → add/add. Il fork è server-side (`/api/edit-preview` esegue il vero tool edit
  dell'SDK sul file, con le sue validatezze e un unified patch reale); upstream calcola il diff
  dai soli argomenti senza leggere il file, perché il browser non può. Il TUI di pi legge il file
  (`computeEditsDiff()`) e il server di pi-web può: **resta il file del fork**, `editPreviewFile`
  di upstream e il suo test escono. `MessageView` continua a chiamare `/api/edit-preview`.
- **`demo/`**: 15 `modify/delete` (globals.css, AnsiText, AppShell, MermaidBlock, MessageView,
  ModelsConfig, ansi, clipboard, file-links, le tre locale, model-discovery, session-search,
  mock/search): restano cancellati con `git rm`. `.github/` upstream non l'ha toccato, niente
  conflitto qui.
- **Unread (#1559/#1618)**: nuovi `lib/unread-markers.ts` e `lib/user-attention.ts`, entrano come
  upstream li ha scritti; `lib/favicon-badge.ts` del fork resta com'è.
- **Ricerca multi-termine (#1616)**: entra in `lib/session-search.ts` (auto-fuso); il
  `/api/sessions/find` del fork (`lib/session-find.ts`, matcher `/resume` dell'SDK) è separato e
  non toccato.
- **Tool menu a interruttori (#1612) e `powershell-settings`**: `docs/agents/tools.md` è in
  conflitto; le route auto-fuse si riverificano contro i preset del fork.
- **Subagent (#1613 trust del genitore, #1607 figlio in coda)**: `lib/subagent-runtime.ts` in
  conflitto, accanto allo schema profile-owned del fork.
- **Modelli (#1567 display name, #1605 Test come una chat, #1547 reasoning/modalità, #1629
  llama.cpp)**: `components/ModelsConfig.tsx` in conflitto.
- **pi `1.5.0 → 1.5.2`**: salto di patch; il gate che se ne accorge è `lib/pi-packages.test.mjs`
  più le integration test contro un `AgentSession` vero.

## Dry run (`git merge-tree --write-tree main v0.16.0`)

30 percorsi in conflitto: **15 `modify/delete`** (tutto `demo/*`) + **15 fra `content` e
`add/add`**:

```
AGENTS.md
app/api/sessions/[id]/context/route.ts
components/ChatWindow.tsx
components/MessageView.tsx
components/ModelsConfig.tsx
docs/agents/subagents.md
docs/agents/tools.md
hooks/context-usage.test.mjs
hooks/retry-banner.test.mjs
hooks/useAgentSession.ts
lib/edit-preview.ts            (add/add)
lib/edit-preview.test.mjs      (add/add)
lib/subagent-runtime.ts
package-lock.json
package.json
```

Auto-fusi ma da riverificare: `lib/rpc-manager.ts` (+417 upstream),
`components/{SessionSidebar,ChatInput,FileViewer,FileExplorer,DirectoryPicker,SessionTree,AppShell,SettingsPanel}.tsx`,
`lib/{git-changes,mcp-host,session-tree,session-ui-state-shared,session-ui-state,tool-fields,notice-text,ansi,markdown-blocks,clipboard,file-links,terminal-manager,session-search}.ts`,
`lib/i18n/messages/*`, `bin/{network-addresses,run-records}.js`, `app/{globals,settings}.css`,
`e2e/*`, e i molti test nuovi che assumono le forme nuove.

Upstream non tocca nessun file in `android/` (0 file).

## Aree semantiche

1. **edit-preview** — decisione sopra: vince la variante server-side del fork.
2. **Chat** — `hooks/useAgentSession.ts` (+275 upstream): fork-draft (#1539), handler
   `user_bash` (#1531), messaggi d'estensione a run fermo (#1538), richieste di shutdown (#1564),
   code ripristinate su Stop (#1576), focus attraverso la compaction (#1556), fallback di `/copy`
   (#649), `setEditorText`/`pasteToEditor` (#1537); qui ci sono `ask_user`, gli slash command
   built-in, le side chat e la reconciliation del fork.
3. **MessageView** — anteprime leggibili delle tool card (#1535), pattern nel grep chiuso (#1620),
   tempo corso della card in corso (#1606), «Stopped» e risposta tagliata (#1533/#6aff), e il
   pending preview con word-diff del fork.
4. **Sidebar** — riga della sessione aperta rivelata (#1614), unread che si spegne negli altri
   browser (#1618), progetto nei risultati di ricerca (#1582), directory picker che segue il
   percorso battuto (#1550): `SessionSidebar.tsx` (+320) si fonde da solo, ma i gruppi e le pin e
   la sezione file del fork vanno riverificati.
5. **Subagent** — trust del progetto al figlio (#1613), sessione del figlio in coda tenuta (#1607).
6. **Modelli** — display name di un provider custom (#1608), etichetta «Provider ID» (#1567),
   Test che prepara la probe come una chat (#1605), `reasoning_levels` e modalità lette al fetch
   (#1547), provider built-in llama.cpp (#1629).
7. **Docs** — `subagents.md` e `tools.md` in conflitto di contenuto; `AGENTS.md` pure.

## Gate

`tsc --noEmit`, `npm run lint`, `npm test` in serie (`env -u PI_WEB_PASSWORD`,
`--test-concurrency=1`); `lib/pi-packages.test.mjs`. Android `gradlew --offline
compileDebugKotlin`: non possibile qui (niente `java`, niente `~/Android/Sdk`) — zero file
`android/` toccati da upstream, si corre sulla macchina con l'SDK. `npm run test:e2e`: serve il
Chromium headless con le sue dipendenze di sistema (`libnspr4` ecc., `playwright install-deps`
come root) — si verifica se parte, altrimenti si registra per Daniele. Mai `next build`, mai
`--webpack`.

## Aftermath

- Versione: npm ha già `@agegr/pi-web@0.16.0`, la prossima release del fork sarà `0.16.1` o un
  prerelease.
- Tag locale `v0.16.0-fork` annotato sul commit di docs, come `v0.15.0-fork`. Il push resta a
  Daniele.
- `docs/PLAN-upstream-merge-v0.16.0.md`, `docs/REPORT-upstream-merge-v0.16.0.md`, `AGENTS.md` e le
  note in `docs/agents/*` aggiornate nel commit di docs dopo la merge.
- Round di review con subagent indipendenti sulla merge finita; quello che è valido si applica.
- Commit firmati con la chiave inoltata (`96FBC4F2284E3272`); se il gpg-agent risponde
  `NEED_PASSPHRASE`, si chiede a Daniele di sbloccarla sull'host.
