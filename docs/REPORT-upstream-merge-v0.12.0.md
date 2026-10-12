# Report: upstream v0.12.0 merged into main

Merged on 2026-10-12 with `git merge --no-commit --no-ff v0.12.0` (upstream `agegr/pi-web`, tag
`v0.12.0` → `894e1a2`). Plan: `docs/PLAN-upstream-merge-v0.12.0.md`. Backup branch:
`backup/main-pre-v0.12.0`.

Upstream range v0.11.1 → v0.12.0: 22 commits, 186 files, +4782 −6724. The fork was 747 commits ahead
of `v0.11.1`, which is the merge-base. **No dependency bumps**: `package.json` and
`package-lock.json` changed only the `version` line, so there was no pi SDK (`1.1.0`) or `next`
upgrade — nothing to reinstall. Unlike v0.11.1, `upstream/main` sits exactly on the tag: nothing
after it, so `a952ed5` (#1176, last merge's pending follow-up) is inside this one.

## Conflicts and how they went

`git merge-tree` predicted 23 conflicts / 18 content hunks before the merge and the real merge
matched it exactly: 4 in `demo/` (modify/delete — kept the deletion, `git rm -r -f demo`), 7 test
files upstream deleted and the fork had modified, and 12 real files.

| File | Resolution |
|---|---|
| `hooks/useAgentSession.ts` | three tiny hunks, all deps arrays: union of upstream's `dispatch` (now `streamingStore.dispatch`) with the fork's `flushPendingDeltas` / `scheduleDeltaFlush`. The fork's rAF buffer and its eight flush sites sit on upstream's store unchanged — the action signatures are the same |
| `components/ChatWindow.tsx` | fork's memoized `messageListNode` kept, upstream's three changed lines folded into it (`showTimestamp && isStreaming && idx === messages.length - 1`, `isLiveTail` through `lastAnchorIdx`, `recoverTruncation`); the fork's inline streaming `MessageView` replaced by upstream's memo'd `<StreamingReply store={streamingStore} onCommit={updatePromptAnchor} …/>`; the four `streamState.*` references became `isStreaming`; `memo` added to the React import, `useId` kept; upstream's `holdAudio` effect and `getStreamingMessage` adopted |
| `components/MarkdownBody.tsx`, `components/ChatInput.tsx` | upstream taken: it absorbed the fork's memoizations (`MarkdownBlock` is memo'd there, `lib/markdown-blocks.ts` splits a growing reply), so only the fork's comment was lost. `ChatInput` keeps the fork's `color-mix(var(--danger))` branch and `keepLineBreaks` is on both sides |
| `lib/agent-event-wire.test.mjs` | union: the fork's `?toolUpdates=tail` tests plus upstream's new "sends only the role of a `message_start` the browser does not build from" |
| the 7 upstream-deleted tests | 4 kept (fork-only behaviour): `app/api/agent/events-route` (`?toolUpdates=tail`), `components/AppShell.mobile-toolbar`, `components/ChatWindow.extension-request` (inline dialog in the composer slot), `hooks/useAgentSession`. 3 accepted as deletions: `app/api/sessions/context-route`, `components/AppShell.auto-name`, `components/ToolDefinitionsPanel` |
| `SessionTree.test.mjs`, `SessionSidebar.test.mjs`, `SidebarMenu.test.mjs`, `ProjectWorktreePicker.test.mjs`, `NewSessionContextBar.test.mjs`, `ChatAppearance.test.mjs` | upstream deleted the assertions that restate source text, which is exactly what the fork's versions assert (`--radius-sm` rows, `color-mix` selection tint, `--shadow-md` menus, `--danger` force-delete, the hero ending at the fork's composer slot, `var(--chat-content-max-width, 820px)` counts). Kept ours; `SidebarMenu` also took upstream's new "menu styles parse on Safari 16.2" header next to the fork's styles test |
| `app/sidebar-menu.css` | upstream's new delete-all confirmation button (`#ef4444`/`#fff`) retuned to `var(--danger)`, like the fork's `.sidebar-worktree-force` beside it |
| `app/globals.css` | upstream's terminal extra-keys bar **left with its hardcoded dark palette** — see the decision below |
| `AGENTS.md` | union: upstream's `client-platform` line (extra keys) then the fork's `android.md` / `chat-ui.md` notes and the pending-cleanups section |
| auto-merged, verified | `AppShell.tsx` wires `holdAudio` into `ChatWindow`; `app/api/sessions/[id]/route.ts` keeps the fork's `contextUsage` / `wrapperRebuilt` / `?force=1`; `lib/rpc-manager.ts` keeps `isAwaitingInput()` / `getAwaitingInputRpcSessionIds()`; `lib/agent-event-wire.ts` / `lib/agent-event-stream.ts` clean |

## Decisions taken here

- **Delta coalescing kept** (Daniele): the fork's rAF layer stays on top of upstream's
  `StreamingStore`. SSE events arrive as separate tasks, so React's batching does not merge them
  across tasks; on fast models the flush still cuts renders to about one per frame. One more
  deviation to maintain, accepted knowingly.
- **The 4 kept test files** above, against upstream's "no assertions that restate the
  implementation's source text". The fork's CSS token forms stay asserted on purpose.
- **Android scope**: `docs/agents/android.md` only. Wiring Kotlin to the new bulk
  `POST /api/sessions/delete` (#1182) and to open-in-new-tab (#1169) stays a separate task.
- **Terminal extra keys left dark** (Daniele, revising the plan): `.terminal-panel-header` was
  already hardcoded before this fork retuned the CSS, and `components/TerminalPanel.tsx` hands xterm
  a hardcoded dark theme (`background: "#111318"`), so tokenizing only the bar would put a light
  strip above a black terminal. The reason is recorded in `docs/agents/client-platform.md`.

## Semantics the Android client should expect

Upstream's wire slimming (#1176) sends a non-assistant `message_start` as `{ type, message: { role } }`
only — an image `read` used to go out twice. The app is unaffected: `ChatViewModel` opens the
streaming assembler only when `message.role == "assistant"`, and an assistant start still carries the
whole snapshot. Fork-only routes and fields (`/api/agent/running/events`, `?lite=1`,
`?toolUpdates=tail`, `/api/sessions/[id]/subagents`, the paged session list, `waitingSessionIds`) are
untouched. Saved sidebar rows became `<a class="session-tree-main" href>` links (#1169) whose href is
`?session=<id>` — the same scheme `AppShell` already uses, so plain clicks stay in-page and modifier
clicks open a browser tab. `docs/agents/android.md` records both.

## Verification

| Check | Before merge | After merge |
|---|---|---|
| `tsc --noEmit` | clean | clean |
| `npm run lint` | clean | clean |
| `npm test` | 2979 tests, 2977 pass, 1 fail | 2756 tests, 2754 pass, 1 fail |
| `android/gradlew test --offline` | rc=0 | rc=0 |
| dev server on :30141 | answering | answering (307) — untouched, no `next build` |

The single failure is the pre-existing environment one, unchanged: `lib/web-auth.test.mjs` "enables
password authentication only for a non-empty configured password" (`PI_WEB_PASSWORD` is exported in
this shell). The suite shrank by 223 tests because upstream deleted assertions and files (the fork
kept 4 of the 7 files it removed) — not because the fork lost coverage it had asked to keep.

JS files that needed fork-side repairs after the merge, all of them upstream deleting a helper or a
`readFile` the fork's assertions used: `SidebarMenu.test.mjs` (`cssRule()` and the `source` read),
`SessionSidebar.test.mjs` (`appShellSource`), `ProjectWorktreePicker.test.mjs` (`menuCss`),
`NewSessionContextBar.test.mjs` (`chatWindowSource`), `ChatAppearance.test.mjs` (all five reads plus
the `readFile` import). `hooks/useAgentSession.test.mjs` needed real rewrites: its streaming
assertions pinned the old `streamState` / `streamReducer` forms, so they now pin
`useStreamingSelector(streamingStore, …)`, `<StreamingReply store={streamingStore} …/>`,
`useState(createStreamingStore)` + `const dispatch = streamingStore.dispatch`, and the reducer read
from `lib/streaming-message.ts` (`streamStoreSource`). The prompt-anchor assertions held — upstream
kept the measurement in a `requestAnimationFrame` outside React's update cycle.

## What landed of value here

StreamingStore (#1191), image caching + view-cache sizing + deferred highlighting (#1181), Prism
languages on demand (#1183), streaming markdown blocks (#1176), session list rebuilt once per
finished run (#1177) + in-flight invalidation guards (#1173), subagents connecting `mcp.json` with a
profile's `mcp: on` (#1187) and `model:level` (#1189), the completion chime's context lifecycle
(#1178/#1185), terminal extra keys (#1166) + input surviving the keyboard collapse (#1160), delete
all sessions of a project (#1182), skills' full instructions (#1158), HTML preview above 256 KB
(#1155), a link to a gone session showing no other (#1180), the CLI exit fix (#1179).

Safari 16.2: **zero lookbehinds** were added anywhere in the range, so no blank-page risk.

## Leftovers

- Kotlin wiring of the bulk delete (`POST /api/sessions/delete`) and of the new-tab rows — separate
  task, per the decision above.
- `npm run test:e2e` cannot run here: Playwright browsers are not installed. Not part of the gate.
- The commit is gpg-signed with key `96FBC4F2284E3272`; if it is locked, unlock it
  (`printf '%s\n' "$PASSPHRASE" | ~/.local/bin/gpg-unlock`) or commit with `--no-gpg-sign`.
- `lib/extension-dialog-fit.ts`'s `EXTENSION_DIALOG_BASE_WIDTH` is still an unused fork export, and
  the Android refused-path cleanup in `NewSessionSheet` is still open — both unchanged in
  `AGENTS.md`'s pending cleanups.
