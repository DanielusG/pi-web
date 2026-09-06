# Plan: merge upstream v0.9.0 (agegr/pi-web)

**STATUS: DONE** (merge committed; dev-server restart + browser smoke test pending for Daniele — needs `kill` of the pre-merge next-serv process, which the autonomy run was not allowed to do).

## Context
- Upstream `main` is at `0d1df12` (Release v0.9.0), **49 commits / 122 files / +9744 −1654** ahead of our merge base `28bab3c` (v0.8.11).
- Our fork has **6 commits** since the merge base (edit-preview, header/token-count, word-diff x2, header cleanup/render perf, previous merge).
- Working tree is clean (only untracked docs/scratch — no collisions).
- Dry-run (`git merge-tree`): **no file renames/deletions upstream**, 10 files with 31 conflict blocks.

## Decisions (Daniele)
1. **Extension dialogs**: **HYBRID** — upstream's implementation as base (non-blocking pointer-events overlay, input/stop stay alive, collapse pill, keyboard nav, countdown, `extension_ui_closed`, stop-unwinding in rpc-manager) but **anchored bottom** in the message area instead of centered. Drop our inline input-bar replacement from 916feb4. See step 2b for the exact delta.
2. **Tool args display**: keep our **`ToolArgsBody`** (tool-aware rendering) and add upstream's `var(--chat-font-size-offset)` so it respects the new font-size setting.
3. **Dependencies**: take upstream's pi packages **0.85.1** (required by their new features).

## Steps

### 1. Merge
- `git merge upstream/main` (merge commit, keeps history — same approach as the previous merge).

### 2. Conflict resolution (10 files)
| File | Strategy |
|---|---|
| `package.json` | Take upstream (pi 0.85.1, new scripts). |
| `package-lock.json` | Take upstream result, then `npm install` to reconcile. |
| `components/ChatWindow.tsx` (13 blocks) | Take upstream as base: keep their quoted-selection, non-blocking collapsible `ExtensionDialog`, NoticeShelf, lazy-load imports. **Drop** our `extensionDialogElement` input-bar replacement and our restyled `ExtensionDialog` (keep upstream's with `collapsed` state + keyboard nav + countdown). Keep our non-dialog changes (sound wrapper etc. if present on our side). Then apply the hybrid bottom-anchoring delta (2b). |

### 2b. Hybrid bottom-anchoring (post-conflict, single localized change in `ExtensionDialog`)
In the overlay wrapper (`position: absolute; inset: 0; pointerEvents: none`):
- `alignItems: collapsed ? "flex-start" : "center"` → `collapsed ? "flex-start" : "flex-end"` (expanded card docks at the bottom of the message area, just above the input bar; collapsed pill stays top-anchored).
- Wrapper `padding: 20` → `padding: 12px ${CHAT_COLUMN_PADDING}px` and card `width: min(560px, 100%)` → `maxWidth: var(--chat-content-max-width, 820px); width: 100%`, so the card aligns with the message column like an inline element.
- Keep everything else upstream (collapse, keyboard, countdown, Escape, shadow/border) untouched.
- Add a short note in `AGENTS.md` (Key Design Decisions) that the dialog is intentionally bottom-anchored vs upstream, so future merges don't "fix" it back.
| `components/MessageView.tsx` (4 blocks) | Union of imports (our word-diff + their `getThinkingPreview`/`isThinkingExpandedByDefault`). Keep our `ToolArgsBody` **and** our edit-preview `ToolCallBlock` logic; apply `calc(12px + var(--chat-font-size-offset, 0px))` inside `ToolArgsBody`. Keep our `var(--danger)` color tokens. Take their thinking-block expansion preference. |
| `components/SessionSidebar.tsx` (3 blocks) | Reconcile — inspect per hunk (their windowed list + search vs our changes). |
| `hooks/useAgentSession.ts` (1 block) | Keep **both**: our rAF delta-flush block + their `initialScrollDoneRef = Boolean(opts.deferInitialScroll)`. |
| `components/ChatInput.tsx` (1 block) | Reconcile per hunk (their Alt+Enter follow-up, quoted-branch vs our input changes). |
| `app/globals.css` (2 blocks) | Union of both style additions. |
| `lib/i18n/messages/{en,zh-CN,zh-TW}.ts` (2 blocks each) | Union of both key sets (our edit-preview keys + their new feature keys). |

### 3. Dependencies
- `npm install` (pi 0.84.3 → 0.85.1, plus any new upstream deps).

### 4. Verify (per AGENTS.md — never `next build`)
- `node_modules/.bin/tsc --noEmit`
- `npm run lint`
- `npm run test` (unit suite — note upstream widened the test glob and added e2e; e2e needs a live server, run unit tests only)
- Fix anything that surfaces; if the SDK bump (0.85.1) breaks our custom code, flag it before adapting.

### 5. Smoke test
- Start/reuse dev server (port 30141), open a session, verify: extension dialog behavior (collapsible, non-blocking), edit preview still renders, word-diff highlight, session search, terminal tab.

### 6. Summary
- Report merged state, conflict-resolution notes, verification results. Push left to Daniele (local is also 1 commit ahead of origin: `241d307`).

## Notes from execution
- `package.json`: also re-added our dropped deps `diff@^8.0.4` (word-diff) and `@fontsource-variable/geist@^5.3.0` (font) after taking upstream's file; installed with `npm install --include=dev` (npm 12 omits dev deps by default here).
- `node-pty` (new terminal feature) had no linux-x64 prebuild → approved its install script + `npm rebuild node-pty` (built from source, OK).
- Lost-in-auto-merge code restored: `onSystemPromptChange` (ChatWindow Props + useAgentSession options/destructuring/effect), our process-group helpers (`countToolCalls`, `hasDisplayableProcessMessage`, `isGroupAnchor` + `CustomMessage` import), SessionSidebar refresh state (later removed, see below).
- **Sidebar refresh button removed** (followed upstream): upstream deliberately removed the manual refresh button in v0.9.0 and added a regression test asserting its absence (`SessionSidebar.test.mjs`); our fork had kept it from the merge base.
- `SessionSidebar`: kept our search-toggle placement; dropped upstream's sidebar new-session button (our fork owns the top-bar New button per PLAN-new-button-header.md); kept upstream's windowed list + search + `onOpenTerminal`.
- `ChatWindow`: kept our memoized `messageListNode` (process groups + lazy window) and patched it with upstream's `searchBlock` support (MessageView prop + `revealProcess` on `ProcessDetailsGroup` + useMemo deps); adopted upstream container (dialog overlay, quoted-selection pointer capture, scroll-restore visibility, chat-content-max-width var); dropped our `extensionDialogElement` input-bar replacement.
- `ExtensionDialog`: full upstream implementation (collapse pill, keyboard nav, countdown, Escape, `extension_ui_closed`) + hybrid bottom-anchoring delta (see 2b).
- Tests adapted to fork UI: `ChatAppearance.test.mjs` width-var count 2→3 (hybrid dialog card), `ChatInput.test.mjs` compact send button text→`title="Send"` (icon-only send button).
- Verification: `tsc --noEmit` clean, `npm run lint` clean, `npm run test` 966/966 pass.
- Push left to Daniele (local is also 1 commit ahead of origin: `241d307`).
