# Plan: merge upstream v0.11.1 → main, preserving fork deviations

Date: 2026-10-11. Approved by Daniele before any edit.

## Starting point (verified)

| | |
|---|---|
| fork `main` HEAD | `342e824` (`docs(merge): record the v0.11.0 upstream merge plan and outcome`) |
| upstream `v0.11.0` (peeled) | `c9e1513` = merge-base, `HEAD..v0.11.0` = 0 commits behind |
| upstream `v0.11.1` (peeled) | `e783721` (tag object `80e006c`), published on npm 2026-10-10 |
| fork ahead of `v0.11.0` | 746 commits |
| upstream range | 46 commits, 193 files, +12195 −1625 |
| dry-run `git merge-tree --write-tree --merge-base=v0.11.0 HEAD v0.11.1` | tree `52c49a7`: **25 conflicts / 17 hunks** — 13 in `demo/` (modify/delete) + **12 real files** |
| dependencies | **no bumps**: `package.json` / `package-lock.json` change only the `version` line. No pi SDK bump, no `next` bump — the v0.11.0 merge's biggest risk is absent |
| release health | no reverts, no fix-of-fix; 3 commits after the tag on `upstream/main` (`a952ed5` perf #1176, `93b3aba` HTML preview #1155, `30af3b4` docs) |
| Safari 16.2 | the only `(?<` in the whole diff is a JSDoc comment in `lib/worktree.ts` (server-side) → no iOS blank-page risk |
| Android contract | `lib/agent-event-wire.ts`, `lib/agent-event-stream.ts`, `app/api/agent/[id]*`, `/lease`, `/subagents` untouched upstream → `?lite=1`, `?toolUpdates=tail`, SSE shapes survive. Only `app/api/sessions/route.ts` is affected |

## What upstream brings (46 commits)

High value for this fork's usage (headless server + Android + iOS 16.2 + heavy MCP/subagents):

- `8e602ca` discover external session files without manual refresh (#1100) — Android never
  polls the session list, it only watches `sessionListVersion`; upstream's `checkSessionMembership()`
  (throttled + `busy` guarded, called from `getSessionListVersion()`) gives it the auto-refresh.
- `89d7141` awaiting-input (#1145) — same feature the fork built as `waitingSessionIds`, but built on
  the protocol's own classification.
- `5fd1e67`, `e7d0fda`, `d474ce2`, `0699107`, `lib/stream-token-rate.ts` — session-switch fixes:
  retry banner / context % / t-s / history window after switches, first prompt lost when switching
  away, the same older page prepended twice, every answer re-rendered on each custom-panel keystroke,
  t/s measurement surviving remounts. Exactly the areas the fork had patched locally.
- `6425d0d` start the pi SDK import at startup, not on the first request (#1117) — headless box.
- `0db6734`, `fb6df88`, `81cc285`, `f9a370e` subagents: late tools (#1115), profile decides spawn
  params (#1138), opt-in codemode (#1097), removed worktree answers from its source repo (#1137).
- `4ad8aa3` file tree reloads while a run writes and on window focus (#1153), `11c2aa2` bare-clone
  worktrees (#1116), `650d7a3` `httpIdleTimeoutMs` applied to the Web UI (#1127), `574cbba` collapse
  a custom message from its header (#1133), `0e87681` dismissable compaction error (#1109).
- Features: `311a901` sub-agent work in a right-panel tab (#1129), `3f6baa9` attach any file (#1122),
  `f87afbb` CSV/TSV table viewer (#1150), `5220b88`+`50486b3` minimap numbering + lock toggle,
  `47d8b9a` compaction collapsed by default, `ee78105` project display name, `5af2e04` app links,
  `f98c088` CLI version/update/status/stop/open (#1154), `2adb185` PWA app name at runtime.

Low value here: the six sidebar-restyle commits (visual), `codex/ci-e2e` (Playwright not installed),
`1a56053` Kimi usage, `b6318be` discovery capabilities.

Cost: `app/sidebar.css` 1143 → 1618 lines; five upstream-authored tests assert the fork's token forms.

## Decisions taken (by Daniele, 2026-10-11)

1. **Sidebar restyle**: adopt upstream's `app/sidebar.css` and re-apply the fork's token retune on top,
   adapting the five token-form test files.
2. **Awaiting input**: delegate to upstream — retire the fork's `BLOCKING_DIALOG_METHODS` and reuse
   upstream's `isAwaitingInput()` / `getAwaitingInputRpcSessionIds()`; the Android field name
   `waitingSessionIds` stays.
3. **`recentCwds`**: remove it inside this merge (AGENTS.md pending cleanup).
4. **Process**: same as the v0.11.0 merge — plan doc, merge on `main`, manual resolution,
   `tsc` + `lint` + `npm test` + Android `gradlew test` before any push.

## Step 0 — backup + baselines

- `git branch backup/main-pre-v0.11.1` (pattern: `backup/main-pre-v0.11.0`).
- Record baselines: `node_modules/.bin/tsc --noEmit`, `npm run lint`, `npm test` counts
  (~2731 at the v0.11.0 merge), `android/.../gradlew test`.

## Step 1 — merge

`git merge --no-commit --no-ff v0.11.1` on `main` so the tree can be inspected before committing.

## Step 2 — resolution order (17 measured hunks)

| # | File | Hunks | Resolution |
|---|---|---|---|
| 1 | `lib/rpc-manager.ts` | 1 | Keep upstream's `isAwaitingInput()` + `getAwaitingInputRpcSessionIds()`; delete the fork's `BLOCKING_DIALOG_METHODS` (:134), `hasPendingBlockingDialog()` (:397), `getWaitingRpcSessionIds()` (:2276). Upstream's rule is identical to the fork's existing `isBlockingExtensionUiRequest` (`lib/browser-notifications.ts:35`), which upstream moves to `lib/extension-ui-queue.ts` and re-exports. |
| 2 | `app/api/agent/running/events/route.ts`, `lib/run-status-stream.ts` | clean | Fork-only files: change the import to `getAwaitingInputRpcSessionIds()` and keep emitting `waitingSessionIds` for Kotlin. Adapt `lib/rpc-manager-extension-ui.test.mjs:88-100`, `lib/run-status-stream.test.mjs`. |
| 3 | `app/api/sessions/route.ts` | 1 | Keep the fork's `sessionListing(sessions, searchParams)` (paging `perProject/recentHours/ids/firstMessageChars`) and add `awaitingInputSessionIds`. Remove `recentCwds` here: `RECENT_CWDS_LIMIT` + :66-67 in `lib/session-list-page.ts`, :44-52 in the route, `lib/session-list-page.test.mjs`, Kotlin fixture `SessionPagesTest.kt:20`. |
| 4 | `app/globals.css` | 1 | Take upstream's two `@source not` lines over the fork's token block (`../.claude` matters, `../demo` is inert now). |
| 5 | `app/sidebar.css` | 2 + ~475 new lines | `git checkout v0.11.1 -- app/sidebar.css`, then re-apply the fork's retune over the new rules: `--radius-md` on rows/groups, `--shadow-sm/-md`, `--danger` via `color-mix`, `transition: background .15s var(--ease)`; keep the three deliberate hardcoded exceptions (`#0891b2`, `rgba(0,0,0,.35)` backdrop, upward `0 -6px 20px`). The two padding hunks take upstream's geometry (`20px`, `5px`) with the fork's radius. `app/sidebar-menu.css` is untouched upstream. |
| 6 | `components/ChatWindow.tsx` | 3 | (a) keep `fitExtensionDialogWidth` (fork's inline-card use) + take `splitNoticeText`; `EXTENSION_DIALOG_BASE_WIDTH` stays unused here. (b) keep the fork's token styling of the update `<a>`. (c) hardest: keep the fork's `useMemo` `messageListNode` (:917) and move upstream's new `lastUserIdx` / `lastAnchorIdx` / `visibleRefIndexByMessage` logic into it. |
| 7 | `components/MessageView.tsx` | 4 | Adopt upstream's `onOpenSubagent` (311a901) wired through the fork's `AppShell` panel handling; keep the fork's `pendingToolPreviewCache` / `hasPreviewableInput` (`/api/edit-preview` word-diff); keep `cwd` where the fork's header needs it. |
| 8 | `hooks/useAgentSession.ts` | 2 | Union: keep the fork's `flushPendingDeltas()` call and its deps `scheduleDeltaFlush`/`flushPendingDeltas`; take upstream's `setRetryInfo(null)` retry-wait fix and its `onToolEnd` dep. |
| 9 | `components/ChatInput.tsx` | 1 | Take upstream's `onDismissCompactError ? "3px 3px 3px 10px" : "7px 10px"` branch (0e87681) and recolour with the fork's `color-mix(var(--danger))`. |
| 10 | `MessageView.test.mjs`, `NewSessionContextBar.test.mjs`, `SessionTree.test.mjs` | 3 | Take upstream's new assertions, re-add the fork's forms: upstream's `aria-label` ("View sub-agent work in side panel") since we adopt the tab; `NewSessionContextBar` hero regex rewritten on `e2fe4fb`'s markup but against the fork's `{extensionDialogElement ?? chatInputElement}` slot; `SessionTree.test.mjs` keeps the fork's `color-mix` selection rules and adopts the amber `session-tree-status-pulse` dot. Then re-touch `SidebarMenu/SidebarToast/ProjectWorktreePicker/SessionSidebar.test.mjs` against the new CSS. |
| 11 | `AGENTS.md` | 1 | Union the file map (upstream's new `lib/stream-token-rate.ts`, `lib/turn-views.ts`, `lib/notice-text.ts`, `lib/extension-ui-queue.ts` classification); drop the `recentCwds` pending-cleanup bullet; add `lib/session-list-scanner.ts`, `lib/session-liveness.ts`, `lib/project-identity.ts`, `lib/workspace-memory.ts` to the map (upstream-authored but the fork sits on them); keep the `next dev` block out of the commit. |
| 12 | `demo/` | 13 | `git rm -r demo` — keep the deletion, as decided at v0.11.0. |
| 13 | `package.json`, `package-lock.json` | — | Take `0.11.1`; no dependency changed, nothing to reinstall; fork deps and `allowScripts` stay. |
| 14 | README ×4, `lib/i18n/messages/*` ×3 | auto | Verify the new keys landed (en/zh-CN/zh-TW "Agent waiting for your input", CSV viewer, minimap lock). |

## Step 3 — verification gate (must pass before any push)

`tsc --noEmit` → `npm run lint` → `npm test` (expect upstream's new `session-membership` ×2,
`stream-token-rate`, `turn-views`, `subagent-late-tools`, `sw.test.mjs`) → `android/gradlew test`
(`SessionPagesTest` fixture without `recentCwds`) → the CSS token-form tests. **Never `next build`**
during dev; reuse the existing 30141 dev process (`lsof` first). `test:e2e` cannot run (Playwright
browsers missing) — record it in the report.

## Step 4 — close

Commit the merge, tag `v0.11.1-fork`, push `origin main`, write `docs/REPORT-upstream-merge-v0.11.1.md`,
update AGENTS.md pending cleanups (`recentCwds` done).

## Risks and open questions

1. **Android semantics change from the delegation**: upstream counts non-closed `custom` overlays and
   gates on `isRunning()`; the fork's list excluded `custom` and accepted alive-but-not-running
   wrappers. Adopting upstream means the Android "Waiting for input" notification also fires for open
   `custom` panels during a run, and stops when the run is no longer running. Proposal: adopt
   upstream's semantics (a parked run is by definition running).
2. **`toolSummaryLine()` (fork, `lib/tool-display.ts`) vs upstream's new inline `getToolPreview()`** in
   `MessageView.tsx`: proposal — keep the fork's (bash first line, `read` offset/limit details) and
   drop upstream's.
3. `--session-tree-inset-*` / `--sidebar-files-inset-right` are new upstream vars — verify they resolve
   in the fork's CSS layers.
4. Upstream's new client files have no lookbehind guard test (AGENTS.md rule) — optional: add guards.
5. `a952ed5` perf #1176 (streaming markdown + session list payload) is **after** the tag — decide later
   whether to cherry-pick.
6. `lib/session-list-scanner.ts` is upstream-owned but missing from AGENTS.md's map — it looks
   fork-only at merge time; the map fix is part of step 2.11.
