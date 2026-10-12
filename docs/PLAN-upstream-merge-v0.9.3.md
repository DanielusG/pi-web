# Plan: merge feat/android-client → main, poi sync upstream v0.9.3

Data: 2026-09-23 · Stato: **COMPLETATO** 2026-09-24 — merge commit `7fdd40a` (v0.9.3 → main), fix Android `69f052b`, spinti su origin, tag `v0.9.3-fork`. tsc/lint puliti, 1327/1327 test web, 105/105 test Android. Nota: SDK 0.87.1 + pi-smart-fetch@0.3.17 crashano il dev server Next (jiti lazy vs shim CJS nel dist dell'extension) — segnalare a pi/pi-smart-fetch; la prod non è stata verificata (no `next build` in dev).

## Punto di partenza (verificato con dry-run `git merge-tree`)

- `main` = v0.9.1 + 11 commit fork (deviazioni: dialog extension inline, word-level diff,
  token count header, first-message fork adoption, i18n keys, ecc.)
- `feat/android-client` = main + 38 commit (client Kotlin `android/` + server-side:
  `app/api/sessions/[id]/subagents/`, `lib/cold-context.ts`, gzip context route,
  `components/ChatWindow.tsx`, `components/MessageView.tsx`, `hooks/useAgentSession.ts`)
- `upstream v0.9.3` = v0.9.1 + 75 commit (198 file). `upstream/main` è avanti di soli
  2 commit (demo GitHub Pages #949/#950) — esclusi per scelta.
- Dry-run merge 1 (android → main): **0 conflitti**
- Dry-run merge 2 (v0.9.3 → risultato): **14 file in conflitto**

## Step 1 — `feat/android-client` → `main`

1. `git checkout main && git merge --no-ff feat/android-client`
2. Verifica: `node_modules/.bin/tsc --noEmit`, `npm run lint`, test `.test.mjs`
3. Push `origin main`

## Step 2 — `upstream v0.9.3` → `main`

`git merge v0.9.3` e risoluzione conflitti in questo ordine:

1. **`package.json` / `package-lock.json`** — prendere tutto da upstream:
   pi SDK `0.85.1 → 0.87.1`, Next `16.3.1 → 16.3.6`, undici/semver aggiornati.
   Campo `version` → `0.9.3` (convenzione: versione = upstream syncato).
2. **`AGENTS.md`** — unione dei due set di doc: nostre sezioni (android, subagents,
   gzip, cold-context) + nuove entry upstream (enabledModels toggles, demo, ecc.).
3. **`hooks/useAgentSession.ts`** (il più grosso) — strategia: upstream come base
   (Strict Mode event-stream fix #933, defaultTools fix #700, /auto-compact,
   visible-tail budget #810, subagent notification fix #891), poi riapplicare le
   nostre aggiunte: file-based context usage cold sessions (aee040e), subagent bar
   event-driven (0efc1c4).
4. **`components/ChatWindow.tsx`** — preservare la **fork deviation del dialog
   extension inline** (documentata in AGENTS.md, non "fixare" verso upstream) e la
   condizione di `ChatAppearance.test.mjs` (3× `var(--chat-content-max-width, 820px)`);
   prendere da upstream: scrollbar stabili/grabbable, scroll-to-latest,
   earlier-replies-after-subagent-notification, selection toolbar.
5. **`components/MessageView.tsx`** — le due feature convivono: nostro word-level
   diff highlight (60fdc7a/241d307) + upstream apply_patch split diff (#744),
   tool-result images while collapsed (#826), truncated-response surfacing (#830).
6. **`app/api/sessions/[id]/context/route.ts` + `[id]/route.ts`** — integrare il
   perf rewrite upstream (#928/#912, 234e19e) con nostro gzip (43ac89b) e
   cold-context (aee040e). Leggere attentamente il rewrite prima di combinare.
7. **`components/SessionSidebar.tsx`** — upstream: panes resizable (#825), file panel
   full-width toggle (#790), scroll work ridotto (f101948); preservare il nostro
   "Active" box e il polling running-state.
8. **`components/ModelsConfig.tsx`** — upstream: enabledModels switches (#930),
   manual catalog refresh (#914), OpenCode Go quota (#844), relative-time usage.
9. **`components/MarkdownBody.tsx` + `app/globals.css`** — prendere upstream,
   preservare gli stili fork (diff highlight, context chip).
10. **`lib/i18n/messages/{en,zh-CN,zh-TW}.ts`** — unione delle chiavi (nostre ~6 righe
    per subagent/context + nuove chiavi upstream).

### Verifica post-merge (obbligatoria)

- `npm install` (bump SDK), poi `node_modules/.bin/tsc --noEmit`, `npm run lint`
- Test suite `.test.mjs` completa (inclusi `ChatAppearance.test.mjs`,
  `streaming-message.test.mjs` upstream, `context-route.test.mjs` nostro)
- Dev server (`npm run dev`, mai `next build`): smoke test di session list,
  streaming SSE, dialog extension inline, subagent bar, context usage, file viewer
- **Contratto API Android**: diff delle shape di `/api/sessions`, `/api/sessions/[id]`,
  `/api/sessions/[id]/context`, `/api/sessions/[id]/subagents` e degli eventi SSE
  rispetto a quelle che il client Kotlin consuma
- **First-chunk duplication**: upstream ha fixato lato web (002400d,
  `lib/streaming-message.ts`); verificare se il client Android ha lo stesso bug
  nell'assemblaggio dello stream Kotlin
- Note SDK 0.86/0.87: provider-listing è capability-driven (lib/provider-listing.ts)
  — verificare che dual-auth provider restino una sola voce dopo il bump

### Chiusura

- Push `origin main`
- Tag `v0.9.3-fork` (convenzione di `v0.9.0-fork`)

## Rischi

- **Salto SDK pi 0.85.1 → 0.87.1**: il rischio più grosso; mitigato da typecheck +
  test + smoke test (la deviazione dialog inline e il tool preset sono i punti più
  sensibili al comportamento SDK).
- `useAgentSession.ts` e `ChatWindow.tsx`: conflitti ad alta densità, da risolvere
  a mano con attenzione alle deviazioni fork documentate.
- Combinazione gzip + perf rewrite sui route session: il punto dove più facile
  regredire una perf gain o rompere la shape della risposta.

## Decisioni già prese

- Target sync: tag `v0.9.3` (non `upstream/main`)
- Push dopo ogni step (main resta sempre in uno stato funzionante su origin)
- File untracked in `docs/` (PLAN-*, PERF-REVIEW*): non toccati
