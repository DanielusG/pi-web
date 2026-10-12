# Plan: merge upstream v0.12.0 → main, preserving fork deviations

Date: 2026-10-12. Approved by Daniele before any edit.

## Starting point (verified)

| | |
|---|---|
| fork `main` HEAD | `2e0be09` (`merge(upstream): integrate v0.11.1, preserving fork deviations`) |
| upstream `v0.11.1` (peeled) | `e783721` = merge-base, fork ahead of it by **747 commits** |
| upstream `v0.12.0` (peeled) | `894e1a2` — and `upstream/main` is at the same commit: **nothing after the tag**, unlike v0.11.1 (`a952ed5` #1176, the v0.11.1 pending follow-up, is inside this tag) |
| upstream range | 22 commits, 186 files, +4782 −6724 |
| dry-run `git merge-tree --write-tree --merge-base=v0.11.1 main v0.12.0` | tree `a52aedc`: **23 conflicts** — 12 content / **18 hunks**, 7 test files deleted upstream but modified in the fork, 4 `demo/` files (fork deleted them) |
| dependencies | **no bumps**: `package.json` / `package-lock.json` change only the `version` line. pi SDK stays `1.1.0`, `next` unchanged — nothing to reinstall |
| release health | no reverts, no fix-of-fix inside the range |
| Safari 16.2 | **zero lookbehinds added** across the whole diff → no iOS blank-page risk |
| Android contract | `lib/agent-event-wire.ts` changes the SSE shape: a non-assistant `message_start` now goes as `{ type, message: { role } }` only (an image read's base64 went out twice). Kotlin reads `message_start` **only when `role == "assistant"`** (`ChatViewModel.kt:1191`) → compatible. `?lite=1`, `?toolUpdates=tail`, `/api/sessions/[id]/subagents`, the paged session list and `waitingSessionIds` are untouched. `lib/agent-event-stream.ts` auto-merges (comments only) |
| fork additions surviving the auto-merge (checked in tree `a52aedc`) | `app/api/sessions/[id]/route.ts` keeps `contextUsage`, `wrapperRebuilt`, `?force=1`; `components/AppShell.tsx` wires `holdAudio` into `ChatWindow` (:2374); `lib/rpc-manager.ts` keeps `isAwaitingInput()` / `getAwaitingInputRpcSessionIds()` |
| JS baselines (before merge) | `tsc --noEmit` clean · `npm run lint` clean · `npm test` **2979 tests, 2977 pass, 1 fail** (`lib/web-auth.test.mjs`, environment: `PI_WEB_PASSWORD` is exported in this shell) |

## What upstream brings (22 commits)

High value for this fork's usage (headless server + Android + iOS 16.2 + heavy MCP/subagents):

- `e8d91ef` perf(chat): re-render only the streaming reply per chunk (#1191) — the streaming state
  leaves React for a `StreamingStore`; only a memo'd `StreamingReply` re-renders per chunk. This is
  upstream's answer to the same problem the fork solved with rAF delta coalescing.
- `b2b6a45` perf: image caching, subagent relation cache, view-cache sizing, deferred code
  highlighting (#1181), `046bc67` Prism languages on demand (#1183), `a952ed5` perf: streaming
  markdown blocks, session list payload, push title, SSE image echo (#1176).
- `b73fdeb` perf(sessions): rebuild the list once per finished run (#1177), `e493310` fix(sessions):
  guard the remaining in-flight-turn invalidation paths (#1173).
- `68f04ef` feat(subagents): connect `mcp.json` servers with a profile's `mcp: on` (#1187),
  `445b58c` feat(subagents): read pi's `model:level` suffix for a run's thinking (#1189).
- `d1529d0` / `7a416ce` audio: keep the chime's context running during a run (#1185), suspend it
  while idle (#1178) — the fork's completion sound on phones with the screen locked.
- `6835326` feat(terminal): Termux-style extra keys on portrait touch screens (#1166), `3ff7e1d`
  fix(terminal): keep input alive when the phone keyboard collapses the panel (#1160).
- `e83280e` fix(sessions): a link to a gone session shows no other session (#1180) +
  `d9adb38` feat(sessions): open sidebar sessions in a new browser tab (#1169) — saved rows become
  `<a class="session-tree-main" href="?session=<id">` with `isPlainActivation()`; the fork's URL
  scheme is the same `?session=`, so plain clicks stay in-page and modifier clicks are the browser's.
- `c8182b1` feat(sidebar): delete all sessions of a project from its menu (#1182) — new
  `POST /api/sessions/delete` + `lib/session-delete.ts`; `DELETE` stays in `app/api/sessions/[id]/route.ts`.
- `ae34240` feat(skills): render full instructions in settings (#1158) — new `GET /api/skills/content`.
- `e8b0236` fix(mcp): say each fact once in a server's detail pane (#1190), `5abdee3` fix(cli): exit
  when the event loop drains after the server probe (#1179), `93b3aba` feat(files): HTML preview
  above one 256 KB chunk (#1155).

Low value here: `f364bc3` test: drop assertions that restate the implementation's source text (it
collides with the fork's habit of asserting CSS token forms — see decision 2), `30af3b4` docs(release).

## Decisions taken (by Daniele, 2026-10-12)

1. **Delta coalescing**: keep the fork's rAF layer on top of upstream's `StreamingStore`. SSE events
   arrive as separate tasks, so React's batching does not merge them across tasks; on fast models
   (30–100 events/s) the fork's flush still cuts renders to ~1 per frame. One more deviation to
   maintain, accepted.
2. **The 7 test files upstream deleted** (`f364bc3`) but the fork had modified: keep the **4** that
   cover fork-only behaviour (`ChatWindow.extension-request` — inline dialog in the composer slot,
   `AppShell.mobile-toolbar`, `app/api/agent/events-route` — `?toolUpdates=tail`,
   `hooks/useAgentSession.test` — `flushPendingDeltas`), accept the **3** that duplicate upstream's
   own coverage (`app/api/sessions/context-route`, `components/AppShell.auto-name`,
   `components/ToolDefinitionsPanel`).
3. **Android scope**: only `docs/agents/android.md` inside this merge (the new `message_start` wire
   shape). Wiring Kotlin to the new bulk `POST /api/sessions/delete` (#1182) stays a separate task
   after the migration.
4. **CSS tokens**: retune upstream's new hardcoded colours — `#ef4444`/`#fff` on the delete-all
   button in `app/sidebar-menu.css` → `--danger` via `color-mix` (applied). The terminal extra-keys
   bar in `app/globals.css` (`#181b21`, `#242932`, `#2f3540`, `#60a5fa`) was to go on `--bg-panel` /
   `--border` / `--text` / `--accent`, **revised during implementation**: the terminal is dark on
   purpose in both themes (`.terminal-panel-header` was already hardcoded before the fork's CSS
   retune, and `TerminalPanel.tsx` hands xterm a hardcoded dark theme), so tokenizing only the bar
   would sit a light strip above a black terminal. Daniele chose to leave it upstream's dark
   palette; the reason is recorded in `docs/agents/client-platform.md`.
5. **Process**: same as v0.11.0 / v0.11.1 — this plan doc, merge on `main`, manual resolution,
   `tsc` + `lint` + `npm test` + Android `gradlew test` before any push, then REPORT + tag + push.
6. **Version**: `package.json` takes `0.12.0`; the fork tag becomes `v0.12.0-fork`.

## Step 0 — backup + baselines

- `git branch backup/main-pre-v0.12.0` (pattern: `backup/main-pre-v0.11.1`).
- JS baselines recorded above. Kotlin baseline: `android/gradlew test --offline` at HEAD.

## Step 1 — merge

`git merge --no-commit --no-ff v0.12.0` on `main`, so the tree can be inspected before committing.

## Step 2 — resolution order (18 measured content hunks + 11 modify/delete)

| # | File | Resolution |
|---|---|---|
| 1 | `demo/` ×4 (modify/delete) | `git rm -r -f demo` — keep the deletion, as at v0.11.0/v0.11.1 |
| 2 | `lib/streaming-message.ts`, `hooks/useStreamingSelector.ts` | clean: take upstream's `createStreamingStore()` and the three selectors (`selectIsStreaming`, `selectHasStreamingContent`, `selectStreamingMessage`) |
| 3 | `hooks/useAgentSession.ts` (3 hunks) | the heart, but mechanical: `dispatch` becomes `streamingStore.dispatch` (upstream :361-362); the fork's buffer stays where it is (`pendingDeltasRef` :1623, `scheduleDeltaFlush` :1624) and all eight `flushPendingDeltas()` sites stay before every `end`/`start`/`snapshot` transition (:1265, :1493, :1519, :1533, :1613, :1674, :1839, :1923) plus the unmount cancel effect; union the deps arrays (`[dispatch, flushPendingDeltas]` :1269, the long lists :1786 / :1926). Action signatures are unchanged, so the layer drops onto the store without a rewrite |
| 4 | `components/ChatWindow.tsx` (2 hunks, 430 conflicted lines) | hunk 1: `memo` in the React import (upstream). hunk 2: keep the fork's memoized `messageListNode` and fold in the four lines upstream changed inside the old IIFE (`showTimestamp && isStreaming && idx === messages.length - 1`, `isLiveTail` through `lastAnchorIdx`, `recoverTruncation`, one comment); replace the fork's inline streaming `MessageView` with upstream's `<StreamingReply store={streamingStore} onCommit={updatePromptAnchor} …/>`; adopt the `holdAudio` effect (#1185) and `getStreamingMessage` for the minimap |
| 5 | `components/MarkdownBody.tsx`, `components/ChatInput.tsx`, `components/ChatMinimap.tsx` | take upstream: it absorbed the fork's memoizations (`MarkdownBody` is `memo` there too, with `splitMarkdownBlocks` from the new `lib/markdown-blocks.ts`; `ChatInput` is `memo`). Only the fork's explanatory comment is lost, not the behaviour. `ChatInput` keeps the fork's `color-mix(var(--danger))` on the `onDismissCompactError` branch; `keepLineBreaks` survives on both sides |
| 6 | auto-merged files to verify | `components/AppShell.tsx` (`holdAudio` wiring + fork-only handlers), `lib/agent-event-wire.ts` (role-only `message_start`), `app/api/sessions/[id]/route.ts` (fork additions), `lib/rpc-manager.ts`, `components/ChatMinimap.tsx` (new `getStreamingMessage` prop), `lib/initial-navigation.ts`, `lib/session-reader.ts`, `lib/session-view-cache.ts` |
| 7 | the 7 upstream-deleted tests | `git add` the fork's version for the 4 kept ones and adapt them to `streamingStore`; `git rm` the 3 accepted deletions |
| 8 | `SessionTree.test.mjs`, `SessionSidebar.test.mjs`, `SidebarMenu.test.mjs`, `ProjectWorktreePicker.test.mjs`, `NewSessionContextBar.test.mjs`, `ChatAppearance.test.mjs` | take upstream's new assertions, re-add the fork's token forms (`--radius-sm` on rows, `color-mix` selection tint, `--shadow-md` menus, `--danger` force-delete frame, the hero ending at `{extensionDialogElement ?? chatInputElement}`); **recount** `ChatAppearance.test`'s `var(--chat-content-max-width, 820px)` occurrences in `ChatWindow.tsx` — `StreamingReply` changes them |
| 9 | `app/sidebar-menu.css` (+75), `app/globals.css` (+46) | decision 4: tokenize the new hardcoded colours, then align the two test files to the token forms |
| 10 | `AGENTS.md` + `docs/agents/*` | union the file map: `lib/session-delete.ts`, `lib/session-links.ts`, `lib/prism-languages.ts`, `lib/highlight-queue.ts`, `lib/terminal-extra-keys.ts`, `components/TerminalExtraKeys.tsx`, `lib/markdown-blocks.ts`, `hooks/useStreamingSelector.ts`, `app/api/sessions/delete/route.ts`, `app/api/skills/content/route.ts`; `client-platform.md` already carries upstream's extra-keys wording; `android.md` gets the role-only `message_start` note; `chat-ui.md` updates the "memoized markdown body" deviation, which is upstream's now; keep the `next dev` generated block out of the commit |
| 11 | `README.md` ×4, `lib/i18n/messages/{en,zh-CN,zh-TW}.ts` | auto — verify the new keys landed (delete-all, extra keys, skills instructions) and that keys upstream removed lost their consumers in lockstep |
| 12 | `package.json`, `package-lock.json` | take `0.12.0`; no dependency changed, nothing to reinstall |

## Step 3 — verification gate (must pass before any push)

`tsc --noEmit` → `npm run lint` → `npm test` (compare against 2979 / 2977 / 1; upstream's new files:
`lib/session-delete`, `lib/session-links`, `lib/prism-languages`, `lib/terminal-extra-keys`,
`lib/streaming-message`, `lib/session-view-cache`, `lib/subagent-mcp.integration`,
`app/api/sessions/delete-route`, `app/api/skills/content/route`, `components/McpConfig` helpers) →
`android/gradlew test --offline` → the CSS token-form tests. **Never `next build`** during dev; the
dev server on :30141 stays untouched (`lsof` checked: one healthy `next-server` already listening).
`npm run test:e2e` cannot run (Playwright browsers are not installed here) — record it in the report.

Extra checks during resolution:
- the `<a class="session-tree-main">` rows (#1169) still work with the fork's virtualized rows and
  focus targeting (`a[href], button`), and `sessionDeepLink()`'s `?session=` matches `AppShell`'s scheme.
- no conflict markers left anywhere (`docs/` and `android/` included), `git ls-files -u` empty.
- no lookbehind in any client-side file added by upstream (verified none in the range).

## Step 4 — close

Commit the merge (needs the gpg key `96FBC4F2284E3272` unlocked, or `--no-gpg-sign`), tag
`v0.12.0-fork`, push `origin main`, write `docs/REPORT-upstream-merge-v0.12.0.md`, update `AGENTS.md`
pending cleanups. Only this plan and the report get staged among the untracked `docs/` files, as at
the v0.11.1 merge.

## Risks and open questions

1. **`ChatAppearance.test.mjs` counts** depend on how many `var(--chat-content-max-width, 820px)`
   uses `StreamingReply` ends up with — resolved by recounting after the file is merged, not guessed.
2. **The fork's rAF layer above the store**: the flush sites must cover every path that leaves the
   stream (end, snapshot, start, resume :2635, session switch, submit). Missing one loses or
   reorders deltas. `hooks/retry-banner.test.mjs` and `hooks/context-usage.test.mjs` run extracted
   case clauses and needed the fork's `flushPendingDeltas` stub at v0.11.1 — expect the same here.
3. **`d9adb38` turns saved rows into links**: any fork code that queried `button` inside a row
   (focus, keyboard activation) needs the `a[href], button` form; the fork's `SessionTree.test.mjs`
   asserts geometry from markup forms.
4. **`b2b6a45` + `046bc67`** touch highlighting and the view cache: the fork's `MessageView` pending
   preview cache (`/api/edit-preview`) and `lib/tool-display.ts` tool summary must survive — the
   auto-merge needs an eye, not just `tsc`.
5. **Awaiting-input semantics** stay upstream's (decided at v0.11.1); `app/api/agent/running-events-route.test.mjs`
   still pins `waitingSessionIds: getAwaitingInputRpcSessionIds()`.
6. Not in scope: Kotlin wiring of the bulk delete (#1182) and of open-in-new-tab (#1169).
