# Report: upstream v0.11.0 merged into main

Data: 2026-10-09 · Merge commit `f4c8868` (signed) · Tag `v0.11.0-fork` · Piano: [PLAN-upstream-merge-v0.11.0.md](PLAN-upstream-merge-v0.11.0.md) — COMPLETED.

## What landed

- `git merge --no-ff` of `upstream/main` (`c9e1513 Release v0.11.0`) onto `main` (`ca59d9f`).
  Merge base `6fcd7d4 Release v0.10.0` — upstream had **not** rewritten history again, so no graft
  was needed this time (unlike v0.10.0). 49 upstream commits, 196 files, +23227 / −4510.
- Before the merge: `backup/main-pre-v0.11.0` at `ca59d9f`, and the pre-merge baselines recorded
  (`tsc` clean, lint clean, `npm test` 2336 pass, Android `gradlew test` BUILD SUCCESSFUL).

## Conflicts (8 content + 5 demo) and how each was resolved

| File | Resolution |
|---|---|
| `package.json`, `package-lock.json` | upstream's versions (pi SDK `1.0.0 → 1.1.0`, `next → 16.3.8`, `remark-cjk-friendly`, version `0.11.0`), fork's `@fontsource-variable/geist` re-added; lock regenerated with `npm install` (never hand-resolved) |
| `app/globals.css` | upstream's font-variable system adopted (`var(--font-ui)` / `--font-ui-weight`), **Geist kept as this fork's default face** via `--font-ui-default`; upstream's `.extension-status-*` CSS adopted; fork's chat/tool/dialog geometry survived the auto-merge |
| `components/ChatWindow.tsx` | fork's inline composer-slot dialog kept; upstream's #947 content fit (`lib/extension-dialog-fit.ts`) and maximize toggle **ported into the inline card**; upstream's status-bar commands (#1030) adopted unchanged |
| `components/MessageView.tsx` | fork's word-diff, pending-diff preview and status icons kept; upstream's duration helpers adopted — `toolCallDurations` now yields **milliseconds**, so the fork header's `{duration}s` became `formatToolDuration(duration)` |
| `components/SessionSidebar.tsx` | upstream's near-total rewrite taken whole (project groups, pins, archive, Sessions \| Files split, fork from the row menu, shared project/worktree picker, "show more" by 20); the fork's 41-line change there was styling only |
| `hooks/useAgentSession.ts` | dependency-array union: fork's `scheduleDeltaFlush`/`flushPendingDeltas` + upstream's `refreshContextUsage`/`applyContextUsage` (#1058) |
| `AGENTS.md` | upstream's file map and `docs/agents/` notes adopted, fork-only routes and notes re-added |
| `demo/*` ×5 | deletion kept, then removed from disk |

Auto-merged and re-verified semantically: `lib/rpc-manager.ts` (fork `BLOCKING_DIALOG_METHODS` +
`getWaitingRpcSessionIds` intact alongside upstream's subagent-skills binding and deferred model),
`app/api/agent/running/route.ts` (new `sessionUiStateRevision`; fork `waitingSessionIds` still in the
SSE snapshot), `lib/session-reader.ts`/`lib/session-tree.ts` (fork `lib/cold-context.ts` still works),
`hooks/useDragDrop.ts` + drop-files-on-chat (#1094), `lib/i18n` messages.

`lib/file-explorer-state.ts` and its test were **deleted by upstream** (their state moved into
`session-ui-state` / the sidebar rewrite). The fork never touched them since v0.10.0 and no reference
to them remains — a clean removal, not a lost fork feature.

## Fork decisions taken during the merge

1. **`demo/` finally gone.** Untracked with `git rm -rf demo` (index and disk — the permission gate
   allowed it this time). No references remained: Daniele had already stripped `tsconfig.json`,
   the eslint ignores, the `@source` line and the README links when he deleted it earlier.
2. **Sidebar CSS retuned to the fork's design language** (Daniele chose this over "revert and do it
   later", and chose the full radius retune over colours-only):
   - `app/sidebar.css` + `app/sidebar-menu.css`: hardcoded `#ef4444`/`#f87171`/`rgba(239,68,68,x)` →
     `var(--danger)` / `color-mix(in srgb, var(--danger) …)` (14 places); black `rgba` shadows →
     `var(--shadow-sm)` / `var(--shadow-md)` (4); radii → `--radius-sm` / `--radius-md` / `--radius-lg`
     (23), rows at `--radius-md` with `transition: background 0.15s var(--ease)` and a tinted selection
     (`color-mix(in srgb, var(--accent) 10%, var(--bg-selected))`, no accent border).
   - Kept deliberately: `#0891b2` (unread/pinned dots — no fork token), the `rgba(0,0,0,0.35)` backdrop,
     and the upward `0 -6px 20px` shadow (a token would flip its direction).
   - Five upstream-authored assertions follow the new forms: `SessionTree.test.mjs` (row + group radius,
     selection background), `SidebarMenu.test.mjs` ×4 (menu radius/shadow, sheet radius, two danger
     backgrounds), `SessionSidebar.test.mjs` (`.sidebar-tool-button` radius),
     `ProjectWorktreePicker.test.mjs` (`.sidebar-worktree-force` border).
3. **Fork-side tests adapted to the fork's own new code** (not upstream behaviour changed):
   `ChatAppearance.test.mjs` (4 uses of the chat width variable), `NewSessionContextBar.test.mjs`
   (the hero is followed by the composer slot `{extensionDialogElement ?? chatInputElement}`, not the
   input bar alone), `ChatWindow.extension-request.test.mjs` (inline width/maxHeight forms, reset effect
   also clears `full`/`fitWidth`), `hooks/context-usage.test.mjs` (sandbox stub for `flushPendingDeltas`).
4. **`recentCwds` left out of scope** (deprecated pending cleanup stays pending), as agreed.

## Verification

- `node_modules/.bin/tsc --noEmit` — clean. `npm run lint` — clean.
- `npm test` — **2731 tests, 2730 pass, 0 fail** (baseline before the merge: 2336). Upstream brought
  ~395 new tests. `lib/mcp-host.integration.test.mjs` failed once under the parallel run ('connecting'
  vs 'failed') and passed 10/10 twice when run alone — a timing flake, not a merge regression.
- Android: `./gradlew test --offline` BUILD SUCCESSFUL before and after (upstream touched no Kotlin).
  `Json { ignoreUnknownKeys = true }` in `PiApi.kt` means upstream's extra fields (`sessionUiStateRevision`)
  are harmless to the app.
- Dev-server smoke (`npm run dev`, never `next build`): `/` 200; `/api/sessions`,
  `/api/sessions?perProject=&recentHours=&firstMessageChars=` (the Android paging shape),
  `/api/sessions/[id]`, `/context?tail=`, `/subagents`, `/api/sessions/ui-state` (new upstream route),
  `/api/agent/running`, `/api/agent/running/events` (SSE, with fork `waitingSessionIds`),
  `/api/models?cwd=`, `/api/git/status?cwd=` — all 200 with expected shapes. The dev server is left
  running on :30141 for a visual check of the new sidebar.
- Not run: `npm run test:e2e` (Playwright browsers not installed here, as after v0.10.0).

## Docs updated with the merge

- `AGENTS.md`: upstream's new lib modules added to the file map (`project-groups`, `extension-dialog-fit`,
  `font-preferences`, `chat-phase-label`, `turn-written-files`, `message-display`, `chat-scroll-position`,
  `terminal-input`, `ansi`, `file-upload-client`, `mcp-override`), the CSS Variables section now lists the
  fork's UI-refresh tokens and names the two sidebar stylesheets and the tests that assert their forms.
- `docs/agents/chat-ui.md`: the inline dialog section now documents the ported #947 fit + maximize, the
  `EXTENSION_DIALOG_BASE_WIDTH` being unused here, the 4 width-variable uses, and the adopted #1030 commands.
- `docs/agents/files-and-access.md`: new section on `bin/rotate-preview-secrets.js` (preview-mode secret
  rotation at startup, the proxy auth bypass fix).

## Follow-ups worth a look

- **Visual check of the new sidebar** — the token retune is mechanical; row/menu radii and the tinted
  selection deserve Daniele's eyes (dev server on :30141).
- Upstream's font preferences (#1074) are web-only for now; the Android app keeps its own font-size plan
  (`docs/PLAN-android-chat-font-size.md`).
- `lib/lib/auth-throttle` and other untouched areas behaved as before; nothing else needed a fork patch.
