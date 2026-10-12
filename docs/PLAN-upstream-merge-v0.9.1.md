# Piano merge upstream v0.9.1

**Data:** 2026-09-11 · **Stato:** approvato da Daniele (Opzione A)

## Obiettivo

Integrare la release upstream **v0.9.1** (112 file, +4867/−796) preservando le
deviazioni intenzionali del fork:

- Dialog estensione **inline nello slot dell'input bar** (non l'overlay upstream)
- Word-level diff highlighting (edit tool)
- Token count reale in header
- Header cleanup + tool-aware call display + render perf
- Preview proposed edits

## Riferimenti

- Merge-base: `0d1df12` (v0.9.0)
- Target: `v0.9.1` = `upstream/main` = `8366762`
- Comando: `git merge v0.9.1`
- Merge simulato (`git merge-tree`): **12 file in conflitto**, tutti risolvibili,
  zero rewrite distruttivi.

## Decisione presa — Opzione A: adotta il sistema temi upstream

Upstream v0.9.1 introduce un'architettura multi-tema:
- nuovo `lib/theme.ts` (`THEME_OPTIONS`: light/dark/mist/rose/pine/auto,
  `isDarkTheme`, `isThemePreference`, `THEME_INIT_SCRIPT`)
- `useTheme.ts` applica `data-theme` + `.dark`; rimuove il ciclo `toggleTheme`
- `globals.css` con selettori `[data-theme]`, 5 palette, variabile `--accent-contrast`
- controlli tema spostati in **Settings**

**Risoluzione:** adottiamo l'architettura upstream e rimappiamo la palette del
fork nei blocchi `light`/`dark`. Per i `borderRadius` nei file cosmetici
manteniamo i valori più grandi del fork (design language del fork).

## Strategia file per file

### Tier 1 — Sistema temi
| File | Risoluzione |
|---|---|
| `lib/theme.ts` | Nuovo — da upstream |
| `hooks/useTheme.ts` | Adotta upstream (`data-theme` + `.dark`, no `toggleTheme`) |
| `app/globals.css` | Architettura `[data-theme]` upstream + palette fork rimappata + variabili extra fork (`--border-strong`, `--shadow-*`, `--assistant-bg`, `--tool-bg`, `--bg-subtle`) |
| `app/layout.tsx` | Già auto-mergeato (`THEME_INIT_SCRIPT`) — verificare |
| `lib/i18n/messages/{en,zh-CN,zh-TW}.ts` | Unione: chiavi 5-temi upstream + chiavi aggiunte fork |
| `components/AppShell.tsx` | Rimuovi toggle tema header fork (upstream: Settings + panel "system"); mantieni header cleanup fork; adotta panel "system" |
| `components/AppShell.mobile-toolbar.test.mjs` | Aggiorna a riscontro |

### Tier 2 — `ChatWindow.tsx` (rischio più alto, 4 hunks)
Preserva il layout fork "dialog rimpiazza l'input bar"
(`{extensionDialogElement ?? chatInputElement}`).
- Hunk 1: tieni wrapper `useMemo` fork per `messageListNode`, porta logica interna upstream (live-tail/anchor/timestamp, `isMessageGroupAnchor`)
- Hunk 2: adotta helper dialog upstream (`now`, `focusFirstOption`, `summary`, `remainingSeconds`)
- Hunk 3: corpo dialog — layout inline fork + markdown/scadenza/`confirm` upstream
- Hunk 4: pulsanti — adotta branch `confirm` + `var(--accent-contrast)`, tieni layout fork

### Tier 3 — `ModelsConfig.tsx` (9 hunks)
Adotta cambi funzionali upstream (quota provider, profili subagent); riapplica
preferenze cosmetiche fork (radius, variabili colore).

### Tier 4 — `hooks/useAgentSession.ts` (2 hunks)
Unione: `flushPendingDeltas` (fork) + `setActiveToolResults`/`syncLiveModel`
(upstream) + unione dipendenze.

### Tier 5 — Cosmetici (`ChatInput`, `DirectoryPicker`, `SessionSidebar`)
- `color: "#fff"` → `var(--accent-contrast)`
- `borderRadius`: mantieni valori fork (7/8/10)

### Tier 6 — Dipendenze
`package.json`/`package-lock.json` auto-mergeati → `npm install`.

## Verifica
1. `node_modules/.bin/tsc --noEmit`
2. `npm run lint`
3. Test: `AppShell.*`, `ChatWindow.extension-request`, `ChatInput`, `MessageView`,
   `useAgentSession`, `word-diff`, `edit-preview`
4. `npm run dev` + smoke: 5 temi senza FOUC · dialog estensione (confirm/select/input,
   markdown, scadenza, tastiera) · word-diff · token count header · streaming + SSE
   reconnect · quota provider · paginazione file grandi
5. **Niente `next build`**

## Ordine di esecuzione
1. Scrivi il piano in questo file
2. `git merge v0.9.1`
3. Risolvi Tier 1 → 2 → 3 → 4 → 5
4. `npm install`
5. Verifica completa
6. Commit del merge + summary
