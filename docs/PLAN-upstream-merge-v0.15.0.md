# Plan — merge upstream v0.15.0

Range: `d128bae4..v0.15.0` — 52 commit, 176 file, +6782/−1648. `merge-base` è esattamente
`d128bae4 "Release v0.13.0"`, quindi nessuna deriva accumulata; il fork era 773 commit avanti
(`e22bef40`, il commit di docs della merge precedente). Backup: `backup/main-pre-v0.15.0`.

## Decisioni prese

- **Dipendenze**: `@earendil-works/*` 1.4.0 → 1.5.0 dentro la stessa merge, ora **otto** pacchetti:
  è nuovo `@earendil-works/pi-schema`. `typebox` 1.3.27 arriva da upstream (il `Type` non è più
  esportato da `@earendil-works/pi-ai`), Next 16.3.8 → 16.4.4, undici 8.11.0 → 8.12.3. Ogni
  pi-package con `npm install --save-exact`, perché `lib/pi-packages.test.mjs` esige che ogni
  pacchetto pi dell'albero sia una dipendenza diretta bloccata.
- **Side chats (ADR 0007)**: entrano per intero — `/side` (#1469), cancellazione dallo switcher
  Agents (#1507), tab ripristinati con la sessione principale (#1512). `components/SubagentViewer.tsx`
  è **cancellato da upstream** e sostituito da `components/SessionChatPanel.tsx` (chat completa del
  figlio): qui non genera conflitto, il viewer del fork era già diverso ma sparisce comunque.
- **Quote (#1502)**: prende la selezione del testo; l'Ask-in-new-chat del fork (`onAskInNewChat`)
  esce di scena. Resta Ask-in-side-chat.
- **`.github/` e `demo/`**: restano cancellati. A differenza di v0.13.0, upstream li ha *modificati*
  (`publish.yml` rifatto in `2bf9c6e5`, `demo-pages.yml`, 10 file `demo/*`), quindi arrivano come
  12 `modify/delete` invece che come file nuovi.
- **Docker**: entra così com'è (`Dockerfile`, `.dockerignore`, `.env.example`, `docs/docker.md`).

## Dry run (`git merge-tree --write-tree main v0.15.0`)

25 percorsi in conflitto: **12 `modify/delete`** (`.github/workflows/{publish,demo-pages}.yml` +
10 `demo/*`) + **13 `content`**:

```
AGENTS.md
app/sidebar.css
components/AppShell.tsx
components/ChatInput.tsx
components/ChatWindow.quote-caret.test.mjs
components/ChatWindow.tsx
components/TabBar.tsx
docs/agents/subagents.md
lib/i18n/messages/en.ts
lib/i18n/messages/zh-CN.ts
lib/i18n/messages/zh-TW.ts
package-lock.json
package.json
```

Auto-fusi ma da riverificare: `lib/rpc-manager.ts`, `lib/session-fork.ts`, `lib/pi-sdk-internals.ts`,
`components/{ChatWindow,MessageView,SessionSidebar,SessionTree,FileViewer,MarkdownBody}.tsx`,
`app/{globals,sidebar,settings}.css`, `lib/i18n/messages/*`, `hooks/useAgentSession.ts`,
`docs/agents/*`, `README.md`.

Upstream non tocca nessun file in `android/` (0 file).

## Aree semantiche

1. **Side chats e famiglia** — arrivano intere da upstream (ADR 0007, #1469/#1512):
   `lib/side-session.ts`, `lib/session-family.ts` (`isHiddenRelation`: subagent *o* side),
   `lib/session-tool-selection.ts`, `lib/side-chat-client.ts`, `components/side-tab-state.ts`,
   `components/SessionChatPanel.tsx`. Il fork prima della merge non aveva side chat
   (`lib/side-session.ts` assente a `e22bef40`), quindi `handleDeleteSideChat` e la ripresa dei tab
   side al cambio di sessione (`sideTabOwnerRef`, `rememberSideTabs`) si prendono come upstream li
   ha scritti; `lib/session-family.ts` e `lib/session-tool-selection.ts` esistevano in entrambi i
   genitori e il fork non li aveva toccati.
2. **Modelli** — nomi provider (#1514), toggle scoped/all nel selettore (#1486/#1503), switch del
   modello a run in corso (#1527), riordino dei modelli di un provider custom, e **#1529**: i
   pattern `enabledModels` di un provider signed-out contano come *unmatched* — restano alle
   toggle, spariscono solo con `prune`.
3. **Mobile** — `57078bb5`: `--top-bar-height` (36px; 44px sotto `(max-width: 640px), (pointer:
   coarse)`), Agents accanto a
   More, Settings a un livello per volta su telefono con Back di sistema.
4. **Estensioni** — `lib/extension-ui-theme.ts` (palette di pi) e `stripAnsi` prima del rendering.
5. **CSS/token** — `sidebar.css` in conflitto (selettori confirm-delete senza scope, celle a
   `--top-bar-height`); i test del fork asseriscono forme a token.
6. **SDK 1.4 → 1.5** — audit a parte: `Type` in typebox, `clientRegistration` con `"auto"`, export
   HTML senza la patch resa obsoleta (`1fbf6c66`), terminazione dei server stdio in pi-mcp.
7. **Test che assumono forme vecchie** — `chat-composer-run-label`, Ask-in-new-chat sulla selezione,
   celle 36px della sidebar, chiavi `subagent.viewer.*` morte col viewer.

## Gate

`tsc --noEmit`, `npm run lint`, `npm test`; Android `gradlew --offline compileDebugKotlin` dove c'è
l'SDK; `npm run test:e2e` in un worktree detached con `npm ci --include=dev` (mai in un checkout con
dev server attivo, mai `next build`).

## Aftermath

- Versione per il rilascio: npm pubblica già `@agegr/pi-web@0.15.0`, quindi la prossima release del
  fork servirà `0.15.1` o un prerelease.
- Tag locale `v0.15.0-fork` sul commit di docs, come per `v0.13.0-fork`. Il push resta a Daniele.
- `docs/PLAN-upstream-merge-v0.15.0.md`, `docs/REPORT-upstream-merge-v0.15.0.md`, `AGENTS.md` e le
  note in `docs/agents/*` aggiornate.
