# Plan: merge upstream v0.11.0 → main, preserving fork deviations

Data: 2026-10-09 · Stato: **COMPLETED** — merge commit `f4c8868` (signed), tag `v0.11.0-fork`.
Outcome details: [REPORT-upstream-merge-v0.11.0.md](REPORT-upstream-merge-v0.11.0.md).

## Starting point (verified)

- `main` = `ca59d9f feat(android): restore a chat's position and its unsent draft` (Daniele's commit of
  the staged Android chat-resume work). `origin/main` == `main` (0/0). Working tree clean apart from
  untracked `docs/PLAN-*` / `docs/PERF-REVIEW*`, which stay untracked (same as the v0.10.0 merge).
- `upstream/main` = `c9e1513 Release v0.11.0`. 49 upstream commits since the merge base; 743 fork commits
  ahead of it.
- **No graft needed this time.** `git merge-base main upstream/main` = `6fcd7d4 Release v0.10.0`, which is
  exactly the second parent of our last merge `1fab11c`, and `v0.10.0` is an ancestor of `upstream/main`.
  Upstream did not rewrite history again. (The tag `v0.10.5` = `7d5f108`, 2026-08-15, sits on the old
  pre-rewrite line and is not an ancestor of `upstream/main` — ignore it.)
- Conflict density measured with `git merge-tree --write-tree --merge-base=6fcd7d4 main upstream/main`
  (read-only, no working-tree change): **8 content conflicts + 5 `demo/` modify/delete**, 18 hunks total.
  The v0.10.0 merge had 19 conflicted files.

## What upstream brings (49 commits)

- **Sidebar rewrite**: project groups, pins and archive (`lib/project-groups.ts`, `lib/sidebar-prefs.ts`,
  `lib/session-ui-state.ts`, `hooks/useSessionUiState.ts`, `hooks/useGroupDrag.ts`), Sessions | Files split,
  one toolbar row, shared project/worktree picker (`lib/new-session-context.ts`) put on the brand's row for
  the new-session bar, fork a session from its row menu (`lib/sidebar-actions.ts`), forks named after the
  source with a short random suffix (`lib/session-fork-name.ts`), "show more" pages by 20, file search from
  the header, files tab flattened into the sidebar chrome. New CSS: `app/sidebar.css`, `app/sidebar-menu.css`.
- **Extension UI**: extension command buttons in the status bar (#1030), dialogs and the custom panel can be
  widened (`lib/extension-dialog-fit.ts`, #1032/#947).
- **Chat**: drop files onto the chat to upload and mention them (#1094), undoable file mentions (#1098),
  `write()` content rendered as readable file text (#1024), context usage refreshed between model calls (#1058),
  CJK emphasis (`remark-cjk-friendly`, #1072), long table cells wrapped (#1056), code/file-viewer backgrounds
  preserved across theme changes (#1060/#1095).
- **Settings**: custom font families and weights (#1074 → `lib/font-preferences.ts`, `hooks/useFontPreferences.ts`,
  `app/layout.tsx`), general page scrolled across the dialog's full width, its duplicated title dropped.
- **Subagents**: only the extensions a profile's `extensions:` list names (#1091), preload named skills (#1034),
  turn limits and terminal outcomes kept at the SDK boundary (#1093), resume options (#1055).
- **Models**: providers registered by extensions at `session_start` (#1071 → `lib/deferred-provider-models.ts`),
  catalog API protocol preserved for models-only providers (#1050).
- **Security / infra**: preview-mode secrets rotated at startup to close a proxy auth bypass (`cf3ebfb`,
  `lib/rotate-preview-secrets.ts`, `bin/rotate-preview-secrets.js`), static responses not held behind the SW
  cache write (#1089), failed uploads preserved + git diff targets authorized (#1039), SSE/shell fixes
  (#1059), deps: pi SDK `1.0.0 → 1.1.0`, `next 16.3.6 → 16.3.8` + `npm audit fix`.
- **Docs**: `docs/agents/*.md` updated (incl. "extensions must behave the same here as in the pi CLI"),
  `docs/worktrees.md` + `docs/worktrees.zh-CN.md`, `docs/adr/0006-mcp-and-code-mode.md` updated.
- `demo/` touched again upstream (5 files) while it is untracked/deleted in our index.

## Decisions taken (by Daniele, 2026-10-09)

1. **Extension dialog**: keep the fork's inline composer-slot dialog; port upstream's
   `lib/extension-dialog-fit.ts` fit/widening logic into the fork layout. The fork deviation stays
   documented in `docs/agents/chat-ui.md` — it is intentional, not a bug to "fix" toward upstream.
2. **`demo/`**: excluded from the index **and finally deleted from disk** this time. I untrack it and strip
   every reference; the `rm -rf demo` itself must be run by Daniele (the permission gate blocks `rm`).
3. **Scope**: nothing else excluded — all 49 upstream commits are adopted, including font preferences,
   pins/archive/project groups, fork-from-row-menu with source-based naming, drop-files-on-chat and the
   preview-secret rotation.
4. **Staging**: merge on `main` (no working branch), push only after the verification gate passes.
5. **Signing**: signing is available — merge with the repo's `commit.gpgsign=true`.
6. **`recentCwds`** (deprecated pending cleanup in `AGENTS.md`) stays **out of scope** for this merge.

## Step 0 — backup + baselines

```bash
git branch backup/main-pre-v0.11.0 main          # keeps the pre-merge state reachable
node_modules/.bin/tsc --noEmit                   # must be clean
npm run lint                                     # must be clean
npm test                                         # record the pass count as the fork baseline
(cd android && ./gradlew test)                   # record the Kotlin baseline if Gradle is usable
```

## Step 1 — merge

```bash
git merge --no-ff --no-commit upstream/main
```

Signed merge commit at the end: `merge(upstream): integrate v0.11.0, preserving fork deviations`.

## Step 2 — resolution order (18 measured hunks)

| File | Hunks | Clash | Resolution |
|---|---|---|---|
| `components/SessionSidebar.tsx` | 4 (one spans ~950 fork lines) | upstream near-total rewrite (3284 lines churned) vs our 82-line UI-refresh styling | upstream rewrite as base, re-apply fork row/card styling; no Android contract in this file |
| `components/ChatWindow.tsx` | 3 | all in the extension dialog + status bar | keep fork inline dialog in the composer slot; port upstream fit/widening into it; adopt `ExtensionStatusBar` `onCommand`/`commandsDisabled` (#1030) |
| `components/MessageView.tsx` | 3 | fork word-diff + tool summary line vs upstream `write()` file text (#1024) | both coexist |
| `hooks/useAgentSession.ts` | 1 | dep arrays: fork `scheduleDeltaFlush`/`flushPendingDeltas` vs upstream `refreshContextUsage`/`applyContextUsage` (#1058) | union the deps; adopt upstream refresh, keep cold-session usage, grace window, run-id guard, `/mcp`, built-in slash commands |
| `app/globals.css` | 2 | fork `"Geist Variable"` literal vs upstream `var(--font-ui)`/`--font-ui-weight` (#1074); upstream `.extension-status-*` CSS | adopt upstream font-var system (default Geist, keep `@fontsource-variable/geist`), keep fork chat/tool/dialog geometry |
| `package.json` | 1 | pi `1.0.0→1.1.0`, `next→16.3.8`, `remark-cjk-friendly`, version `0.11.0` | upstream as base + re-add fork deps (`@fontsource-variable/geist`, `ansi_up` in `dependencies`, `diff`, `allowScripts` node-pty) |
| `package-lock.json` | — | never hand-resolved | regenerate with `npm install` |
| `AGENTS.md` | 1 | fork sections vs upstream `docs/agents/` layout | adopt upstream structure, re-add fork notes (`android.md`, `chat-ui.md`, pending cleanups, Safari 16.2 note, the `next dev` agent-rules block) |
| `demo/*` ×5 | modify/delete | deleted in HEAD, modified upstream | keep deleted in the index |

Auto-merged but needing **semantic** re-verification: `lib/rpc-manager.ts` (fork `BLOCKING_DIALOG_METHODS` +
`getWaitingRpcSessionIds` must survive alongside upstream's subagent-skills binding and deferred-model logic),
`app/api/agent/running/route.ts` (new `sessionUiStateRevision` field; `lib/run-status-stream.ts` reads only
running + waiting ids, Android ignores unknown fields), `lib/session-reader.ts` / `lib/session-tree.ts`
(fork `lib/cold-context.ts` imports `sliceActiveBranch`), `app/api/sessions/[id]/route.ts`,
`hooks/useDragDrop.ts` vs drop-files-on-chat, `lib/i18n/messages/{en,zh-CN,zh-TW}.ts`.

Fork-only files that enter untouched but need the behavior re-checked: `lib/run-status-stream.ts`,
`lib/agent-state-lite.ts`, `lib/session-list-page.ts`, `lib/cold-context.ts`, `lib/word-diff.ts`,
`lib/edit-preview.ts`, `lib/tool-display.ts`, `app/api/agent/running/events/route.ts`,
`app/api/sessions/[id]/subagents/route.ts`. Upstream did **not** touch `app/api/sessions/route.ts`, and
nothing on the web side consumes `perProject`/`recentHours`/`ids`/`firstMessageChars` — only Kotlin does.

## Step 3 — demo/, finally removed

- `git rm -r --cached demo .github/workflows/demo-pages.yml` (keep the deletion).
- Strip references: `tsconfig.json` `exclude`, eslint `demo/**` ignore, `@source not "../demo"` in
  `app/globals.css`, README links (all locales), the `demo/` line in the `AGENTS.md` file map.
- Daniele runs `rm -rf demo` + deletes the workflow file on disk; then the eslint/tsconfig/globals.css
  concessions can be dropped too.

## Step 4 — verification gate (must pass before any push)

- `npm install` (SDK 1.1.0, next 16.3.8), `node_modules/.bin/tsc --noEmit`, `npm run lint`,
  `npm test` ≥ the Step 0 baseline.
- Dev-server smoke on `npm run dev` (**never** `next build`): session list + new sidebar groups/pins/archive,
  streaming SSE, inline extension dialog (widened), subagent bar, context usage on cold sessions, file
  viewer, Settings › MCP + Code mode after the SDK bump, font preferences, drop-files-on-chat.
- **Android contract**: diff the shapes of `/api/sessions` (`perProject`/`ids`/`recentHours`/`firstMessageChars`),
  `/api/sessions/[id]`, `/api/sessions/[id]/context`, `/api/sessions/[id]/subagents`,
  `/api/agent/[id]?lite=1`, `/api/agent/[id]/events?toolUpdates=tail`, `/api/agent/running/events` against
  what `android/app/src/main/java/app/pimobile/data/PiApi.kt` consumes; then `./gradlew test`.
- `npm run test:e2e` only if Playwright browsers are installed (they were not after the v0.10.0 merge).
- Manual interactions: font preferences vs fork CSS defaults, project groups/pins/archive vs our file-access
  allow-list and worktree grouping, drop-files-on-chat vs `lib/useDragDrop.ts`, MCP + Code mode built-ins
  vs Chat-only / `lib/exact-system-prompt.ts`, read-only MCP policy vs `PRESET_READ_ONLY`,
  subagent `extensions:`/skills preload vs the fork's subagent profiles.

## Step 5 — close

- Signed merge commit `merge(upstream): integrate v0.11.0, preserving fork deviations`; push `origin main`;
  tag `v0.11.0-fork` (convention of `v0.9.0-fork` / `v0.9.3-fork` / `v0.10.0-fork`).
- `docs/REPORT-upstream-merge-v0.11.0.md` with the outcome; flip this file's status to COMPLETED.
- Update `docs/agents/*.md` where behavior changed (sessions, tools, models, subagents, chat-ui,
  client-platform, android) and `AGENTS.md`'s file map with upstream's new modules.

## Risks

- `components/SessionSidebar.tsx`: the largest resolution by far — upstream rewrote it almost entirely.
  Mitigation: our fork diff there is only 82 styling lines and carries no Android contract.
- SDK `1.0.0 → 1.1.0` (minor, much safer than v0.10.0's major) touching `lib/pi-sdk-internals.ts`,
  `lib/model-runtime.ts`, subagent runtime and MCP host. Mitigation: typecheck + full test suite + smoke.
- `lib/rpc-manager.ts` auto-merged: a clean textual merge can still break the fork's `waitingSessionIds`
  feed for `/api/agent/running/events`. Mitigation: `run-status-stream.test.mjs` + the Android contract check.
- Font CSS: upstream's `var(--font-ui)` must still resolve to Geist by default or the fork's typography drifts.
- Upstream's status-bar commands call `handleSend` — verify it behaves with the fork's composer state and
  the inline dialog occupying the composer slot.
