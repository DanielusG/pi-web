# Performance Review — pi-web Web UI

**Date:** 2026-07-22
**Symptom:** UI feels laggy on long sessions, worse with 2–3 files open in the sidebar; much more noticeable on mobile.

This document records a full read-through of the render pipeline (AppShell → SessionSidebar →
ChatWindow → MessageView → MarkdownBody / CodeBlock / MermaidBlock, plus ChatMinimap, FileViewer,
FileExplorer, useAgentSession, streaming-message) and ranks the issues found.

---

## How the lag actually happens

There are three cost profiles. "Long session + files open" hits all of them:

### A. Per-token cost (while the agent is streaming)

Every SSE `text_delta` is dispatched to the stream reducer individually — there is **no
client-side batching or throttling** (`lib/streaming-message.ts`: `applyDelta` produces a new
state object per event). A fast model can therefore re-render `ChatWindow` 30–100×/sec. Each of
those renders costs:

1. **The message-list IIFE in `ChatWindow.tsx` (lines ~704–896) runs on every render.**
   It loops over **all** messages (`for (let idx = 0; idx < messages.length;)`), building a
   `rendered` React-element array for every message — turn grouping into `ProcessDetailsGroup`,
   per-assistant `showTimestamp` forward scans, `findFinalAssistantIndex`, `extractTurnWrittenFiles`
   per turn — and only then slices it: `rendered.slice(startIndex)` (line 894) renders the last
   `visibleCount` (default 50, `VISIBLE_PAGE_SIZE` in `lib/chat-lazy-load.ts`). So even though
   only 50 messages are mounted, **element construction runs over the whole session on every
   streamed token** (O(n) allocation + work, n = total messages).
2. **`ChatInput` re-renders on every delta** — it is a non-memoized child of `ChatWindow`
   (element built at `ChatWindow.tsx:532–580`; `ChatInput.tsx:384` is `forwardRef` without a
   `memo` wrapper). It is a 2600-line component that also rebuilds/sorts the model-options list
   per render (`ChatInput.tsx:1289–1305`).
3. **`useMessageRefs` allocates an O(n) array on every `ChatWindow` render**
   (`ChatMinimap.tsx:748–752`: `refs.current = Array(count).fill(null).map(...)`, called from
   `ChatWindow.tsx:417`).
4. The streaming bubble's markdown is re-parsed per chunk (`MarkdownBody.tsx:18` — full
   react-markdown pipeline with gfm + katex). This is inherent to streaming, but see B/C: the
   *history* messages get re-parsed too when their identity churns (issue #1 below).
5. The prompt-anchor `useLayoutEffect` fires on every delta
   (`ChatWindow.tsx:519–521`, deps `[streamState.streamingMessage]`), and the live-follow
   `requestAnimationFrame` scroll runs per delta (already coalesced via `liveFollowFrameRef` — OK).

### B. Per-turn-end spike — the "long session" killer

On `agent_end`, `prompt_done`, `agent_settled`, `compaction_end` (and the 15 s reconcile path),
`useAgentSession` calls `loadSession()` (`hooks/useAgentSession.ts:460`):

- fetches the whole session (`/api/sessions/[id]?deferThinking=1&deferMedia=1`),
- then `setMessages(persistedMessages)` (line 481) with **brand-new object identities for every
  message**.

`MessageView` is `memo()`-ized with a comparator that checks `prev.message === next.message`
(`MessageView.tsx:250` + comparator) — so the memo is **defeated for all 50 visible messages** on
every re-fetch. Each re-render then re-runs the full react-markdown pipeline, **and** `CodeBlock`
flips out of its streaming fast-path (plain `<pre>` while streaming — the documented "single most
expensive part of streamed rendering" mitigation) into **Prism tokenization of every code block**.

In a code-heavy long session this is a one-shot, multi-hundred-ms spike at the end of *every*
turn. `loadContext()` (branch navigation, line 521–536) has the same problem.

### C. Constant background cost (files open / idle)

1. **Sidebar 2.5 s poll re-renders the whole sidebar unconditionally.**
   `SessionSidebar.tsx:139` (`RUNNING_SESSIONS_POLL_MS = 2500`) → poll effect (line ~504) calls
   `setRunningSessionIds(new Set(...))` on **every** poll even when the set content is unchanged →
   `SessionSidebar` re-renders → `buildSessionTree(filteredSessions)` recomputed with no `useMemo`
   (function at line 262, call at line 945) → the whole non-memoized `SessionTreeItem` /
   `SessionItem` tree re-renders with fresh inline closure props → `FileExplorer` re-renders
   (props like `onOpenFile={onOpenFile ?? (() => {})}` are new arrows every render).
   (Note: `AppShell.tsx:78–88` *does* content-compare before committing running ids, so AppShell
   itself does not re-render per poll — only the sidebar does.)
2. **Active file tab re-tokenizes on every AppShell re-render.** `AppShell` re-renders whenever
   `sessionStats` changes (pushed up by `ChatWindow`'s `statsKey` effect →
   `AppShell.tsx:194 handleSessionStatsChange` — changes on every `message_end`), branch data
   changes, or the running set changes. The active `FileViewer` (`AppShell.tsx:1851`) is not
   memoized, and `TextFileViewer` renders the **whole file through `SyntaxHighlighter` (Prism)**
   with a per-line `SourceCodeRenderer` (`FileViewer.tsx:1505–1536`, renderer at line 164). So
   with a file open, **every message completion during a run re-highlights the entire file**.
3. **Tab switching remounts the viewer** — `FileViewer` is keyed
   `${activeFileTab.id}:${viewerRevision}` (`AppShell.tsx` ~1851, comment: "Only the active
   viewer is mounted"), so flipping between 2–3 tabs re-fetches + re-tokenizes from scratch each
   time (no content cache across tab switches).
4. **ChatMinimap** (desktop; hidden on mobile via `!isMobile`):
   - `ResizeObserver` on the scroll container **and its firstElementChild** (the message content
     div, which grows every streamed token) → `syncLayout` → `measureNodes` (throttled 150 ms,
     `ChatMinimap.tsx:316`) which calls `getBoundingClientRect()` for **every** message ref —
     forced layout flushes every 150 ms while streaming.
   - `onMouseMove` on the rail calls `setMouseYRatio` on **every mousemove** (line 607–609) →
     minimap re-render; while hovered the preview panel renders `AssistantOutline` (ReactMarkdown
     with katex) for **every turn** of the session.
   - `allMessages = useMemo(... [messages, streamingMessage])` spreads a new array per delta.

---

## Ranked findings

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 1 | `loadSession`/`loadContext` replace all messages with new identities → every visible `MessageView` re-renders (full markdown re-parse + Prism on every code block) at end of each turn | `hooks/useAgentSession.ts:460–540` (setters at 481, 531); `MessageView.tsx:250` memo comparator | **Highest** — per-turn lag spike, worse on mobile |
| 2 | No delta batching: one `useReducer` dispatch per SSE event → full `ChatWindow` re-render per token; the O(n) list IIFE re-runs and un-memoized `ChatInput` re-renders each time | `lib/streaming-message.ts`; `hooks/useAgentSession.ts:1097–1117` (delta dispatch at 1114); `ChatWindow.tsx:704–896` (IIFE, `rendered` at 791, slice at 894); `ChatWindow.tsx:532–580` (ChatInput element); `ChatInput.tsx:384` | **Highest** during streaming |
| 3 | `MarkdownBody` not memoized — text blocks re-parse on any parent re-render (multiplies #1 and #2) | `MarkdownBody.tsx:18` | High |
| 4 | Sidebar 2.5 s poll: unconditional `setRunningSessionIds(new Set)` → full non-memoized tree re-render; `buildSessionTree` un-memoized; inline closure props into tree items and FileExplorer | `SessionSidebar.tsx:139, 504, 945, 1589+` | Medium — constant background load |
| 5 | Active `FileViewer` re-renders on every AppShell state change (stats per `message_end`, branch data, running set) → full-file Prism re-tokenization; tab switch remounts with no content cache | `FileViewer.tsx:1505` (SyntaxHighlighter), `AppShell.tsx:194, 1851` | Medium-high when files are open |
| 6 | `ChatMinimap`: RO on growing content → `measureNodes` every 150 ms with per-message `getBoundingClientRect`; `onMouseMove` → setState per move; hover preview renders ReactMarkdown for every turn | `ChatMinimap.tsx:316, 416, 558, 607` | Medium (desktop) |
| 7 | `AssistantMessageView.toolCallDurations` iterates the **entire** `toolResults` Map per assistant message → O(visible × total tool results) on a full re-render | `MessageView.tsx` (AssistantMessageView memo) | Low–medium, grows with session length |
| 8 | `useMessageRefs` O(n) allocation per ChatWindow render (`Array(count).fill(null).map`) | `ChatMinimap.tsx:748–752` | Low (fixed incidentally by #2) |
| 9 | `estimateTokens` char-loop over block text (mitigated by streaming cache); `safeJson` per render in custom messages; SMIL `animate` SVG running/unread indicators per session row | `MessageView.tsx`; `SessionSidebar.tsx` | Low |

### Already done well (don't waste effort here)

- 50-message windowed rendering + IntersectionObserver lazy loading of older pages
  (`lib/chat-lazy-load.ts`, `ChatWindow.tsx:313–345`).
- Deferred thinking/media in `loadSession` (`deferThinking=1&deferMedia=1`) + lazy thinking fetch
  with LRU cache (`MessageView.tsx`).
- 100 k-char markdown cap (`MAX_MARKDOWN_CHARS` → click-to-reveal `<pre>`) and tool-args cap.
- `CodeBlock` memoized + streaming fast-path (plain `<pre>` while streaming).
- `MermaidBlock` lazy dynamic import, rendered only when preview visible.
- `toolResultsMap` memoized in `ChatWindow` (prevents per-delta Map identity churn).
- AppShell content-compare bail-out for running-session ids (`AppShell.tsx:78–88`).
- Only the active file viewer mounted; per-tab lightweight state restored on activation.
- Edit-preview / bash-output / thinking LRU caches; deferred media.
- User images in history are data-URLs (memory, not a re-render cost).

---

## Fix plan (approved scope: #1 + #2 + #3 first)

### #1 — Identity-preserving session loads

- Add `sameMessage(a, b)` / small structural deep-equal (string fast-path) in `lib/normalize.ts`.
- In `loadSession` (`useAgentSession.ts:481`) and `loadContext` (`:531`): merge against the
  current `messages` — same index and deep-equal content → **keep the old object reference**;
  all-identical → return `prev` unchanged (zero re-render). Index matching is safe because any
  content mismatch falls back to the fresh object, and length mismatch falls back to full replace.
- Kills the end-of-turn spike without touching any rendering code.

### #2 — Coalesce stream deltas + stop per-delta O(n) work in ChatWindow

- `useAgentSession.ts` (message_update case, ~1109–1117): buffer `ClientAssistantMessageEvent`s
  in a ref and flush them in a single `requestAnimationFrame` — React 18 auto-batches the
  dispatches → **one render per frame** instead of one per SSE event. Flush pending deltas
  synchronously before every `dispatch({type:"end"})` site (`connected`, `agent_end`,
  `message_end`, send-error path) and on unmount so no deltas are lost or reordered.
- `ChatWindow.tsx:704–896`: wrap the list-building IIFE in `useMemo` keyed on
  `[messages, entryIds, visibleCount, streamState.isStreaming, sessionBusy, isNew,
  forkingEntryId, handleFork, handleNavigate, handleEditContent, toolResultsMap, modelNames,
  messageCwd, onOpenFile, t]` — deltas no longer rebuild the O(n) element array.
- `ChatWindow.tsx:532–580` + `ChatInput.tsx:384`: wrap `ChatInput` in `memo()` and audit the
  ~40 props for identity stability during pure delta streaming (handlers are already
  `useCallback`s; fix the few whose deps churn per delta).
- `ChatMinimap.tsx:748–752`: make `useMessageRefs` O(1) when count only grows (push, don't
  re-map the whole array).

### #3 — memo(MarkdownBody)

- `MarkdownBody.tsx:18`: wrap in `React.memo`. Shallow compare works: `children` is a string
  (value equality), `cwd`/`className` strings, `isStreaming` boolean, `onOpenFile` is a stable
  `useCallback` chain (AppShell `handleOpenLinkedFile` → MessageView prop → MarkdownBody).
  History messages then skip the react-markdown parse entirely when nothing changed.

### Follow-ups (not in this round)

- #4 sidebar poll content-compare + memoized tree items + `useMemo(buildSessionTree)`.
- #5 memoize Prism rows (`useMemo` on content/language/theme) or virtualize long files; cache
  file content across tab switches.
- #6 minimap incremental measurement, rAF-throttled mousemove, lazy hover previews.
- #7 pre-index `toolCallDurations` by message instead of scanning the whole Map per message.

### Verification (performed for the implemented fixes)

- `node_modules/.bin/tsc --noEmit` — clean.
- `npm run lint` — clean.
- `npm test` — 598 tests, 594 pass. The 4 failures (AppShell auto-name ×2, ImagePreview
  safe-area CSS, System-prompt-loader) were proven **pre-existing** by re-running the suite with
  the perf changes stashed: the identical 4 failures occur without them (they belong to the
  uncommitted auto-name / globals.css / system-prompt work in the tree).
- Updated the source-inspection regression test
  `hooks/useAgentSession.test.mjs` → “keeps one reducer-owned assistant partial and consumes
  Pi JSON deltas” to assert the new coalescing behavior (deltas pushed to `pendingDeltasRef` +
  `scheduleDeltaFlush()`, no direct dispatch in the stream case, rAF flush loop feeding the
  reducer).
- Manual check still recommended: open a long session, send a prompt — watch React DevTools
  render counts for `ChatWindow`/`ChatInput`/`MessageView` during streaming; verify the
  end-of-turn re-render no longer re-parses history (message identity preserved); verify
  streaming output, live-follow scroll, branch navigation, compaction, fork and model switch.

### Implemented (this round)

1. **Identity-preserving session loads** — `lib/normalize.ts`: `sameMessage()` (structural
   deep-equal) + `mergeMessageIdentities()`; `useAgentSession.ts`: `loadSession`/`loadContext`
   now merge fetched messages against current state keeping unchanged references
   (`stableStringArray` for entryIds as well).
2. **Coalesced stream deltas** — `useAgentSession.ts`: `pendingDeltasRef` +
   `scheduleDeltaFlush()` (rAF) + `flushPendingDeltas()`; every terminal transition
   (`connected`, `agent_start`, `agent_end`, `message_end`, `message_start` snapshot, send
   start/error, `settleUiStage`) flushes before dispatching; unmount cancels the frame.
3. **Memoized list construction** — `ChatWindow.tsx`: the O(n) message-list IIFE moved into
   `messageListNode = useMemo(...)`; only re-runs when list inputs change.
4. **Memoized `ChatInput`** — `ChatInput.tsx` wrapped in `memo(forwardRef(...))`; prop audit
   confirmed all ~40 props are referentially stable during pure delta streaming.
5. **Memoized `MarkdownBody`** — `MarkdownBody.tsx` wrapped in `memo()`.
6. **O(1) `useMessageRefs`** — `ChatMinimap.tsx` no longer re-allocates/re-maps the ref array
   on every render.
