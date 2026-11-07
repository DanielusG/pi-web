# Chat UI deviations from upstream

This fork carries a documented visual refresh (`docs/PLAN-ui-refresh.md`: Geist, tinted neutrals,
shadow layering, a radius scale, theme-aware colour tokens) plus a few layout decisions that differ
from upstream on purpose. Read this note before touching the chat surfaces, and before "fixing" a
deviation back to upstream's shape during a merge.

## Extension dialog renders inline in the composer slot
Upstream renders the `ExtensionDialog` as a non-blocking overlay over the message area (expanded
card bottom-anchored, collapsed pill top-anchored, pointer-events pass-through so input/stop stay
alive). This fork restores the pre-v0.9.0 layout: the dialog is rendered **inline in the composer
slot, replacing the input bar** — `{extensionDialogElement ?? chatInputElement}` — in both states:
expanded card (`maxHeight: EXTENSION_DIALOG_MAX_HEIGHT`) and collapsed pill (pulsing dot). The input
bar unmounts while a blocking request is open; dialog state (`value` / `collapsed`) resets on
`[request]`.

- `EXTENSION_DIALOG_MAX_HEIGHT = "min(45vh, 360px)"`, the title's max height is
  `calc(${EXTENSION_DIALOG_MAX_HEIGHT} * 0.4)`, and the title span is keyed by `request.id`.
- Upstream's queue indicator (`ExtensionWaitingCount`, the `waitingCount` prop both dialog variants
  now require) is rendered here too: expanded header order is label → waitingCount → countdown,
  collapsed pill order is waitingCount → countdown. `ChatWindow.extension-request.test.mjs` asserts
  that order, and slices the collapsed markup from `if (collapsed) {` (this fork's early return, not
  upstream's `{collapsed ? (` ternary).
- Upstream's #890 fix (a long title must shrink, not push the buttons out) is applied to the fork's
  layout: the header wrapper uses `flexShrink: 1, minHeight: 0`, not `flexShrink: 0`. The test
  asserts that and forbids `maxHeight: "50vh"`.
- `ExtensionCustomPanel` (method `custom`) is **not** part of this: it stays upstream's overlay
  confined to the content region above the composer.
- The native Android client mirrors the same inline layout
  (`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`) — see [android.md](android.md).
- `ChatAppearance.test.mjs` expects 4 uses of `var(--chat-content-max-width, 820px)` in
  `ChatWindow.tsx` (the messages container, the empty-page hero row, and the dialog's two width
  rules). None may be a literal `maxWidth: 820`.
- Upstream's #947 (a dialog sized to its own content) is **ported into the inline layout**, not taken
  as upstream wrote it. `lib/extension-dialog-fit.ts` measures the `pre` and `.markdown-table-wrap`
  blocks that scroll sideways (`fitExtensionDialogWidth`, with a `MutationObserver` on the body for
  code highlighted after first paint and `document.fonts.ready`), and the fork's card applies the
  result as `maxWidth: full ? "100%" : fitWidth !== null
  ? `max(var(--chat-content-max-width, 820px), ${fitWidth}px)` : "var(--chat-content-max-width, 820px)"`
  with `margin: "0 auto"` — the card keeps the chat column by default and grows past it only when its
  own content needs the room. Upstream's `EXTENSION_DIALOG_BASE_WIDTH` (560px) is unused here: the
  fork's starting width is the column's, not a fixed 560.
- **Upstream's sizing check in `e2e/extension-dialog.mjs` needs the fork's geometry**, and was
  adapted here (`checkExtensionDialogSizing`, green on both `main` and `backup/main-pre-v0.12.0`):
  prose expects `min(--chat-content-max-width, available)` read at runtime (`columnWidth(page)`,
  because the chat width is a browser setting) instead of `min(560, available)`; `availableWidth`
  reads the **parent's content box** (clientWidth minus its own horizontal padding) instead of
  subtracting the overlay's assumed 20px — the inline card sits in the composer slot and the
  custom panel in its own centred wrapper, and each pads itself differently. The code dialog no
  longer asserts "grew past 560": the fixture's code fits inside the 820px column, so growth is
  only forced by a narrower column or by the region's limit — what must hold is that the code
  shows whole unless the region caps the card.
- **Custom panels need no fit**: `ExtensionCustomPanel` sizes itself with `width: "max-content"`,
  `minWidth: "min(920px, 100%)",` `maxWidth: "100%"` (upstream's #1136 approach), so its wide
  lines already show whole. `fitExtensionDialogWidth` stays on the dialog card only — the panel
  failure seen at the v0.12.0 merge was `availableWidth`'s assumed padding, not a missing fit.
- Upstream's maximize toggle came along: `full` / `toggleFull` with `ExtensionSizeIcon` beside the
  collapse chevron. In the fork it raises the card to `maxHeight: "min(80vh, 760px)"` and lets it fill
  the row, instead of upstream's `"100%"` / `min(760px, 100%)` overlay geometry. A new request resets
  `collapsed`, `full` and `fitWidth` together.
- The status bar's command buttons (#1030: `onCommand={handleSend}`, `commandsDisabled={sessionBusy}`)
  are adopted unchanged; they work with the composer slot occupied by the dialog.
- Upstream v0.15.0 colours extension UI with pi's palette theme (`lib/extension-ui-theme.ts`) and
  strips ANSI before rendering it. The inline card took the same wraps: `stripAnsi` on the collapsed
  pill's answer and first message line, the header title in both places this fork renders it, the
  body `MarkdownBody`, and each option's `aria-label` and rendered label. Upstream's third title wrap
  has no fork counterpart — the fork labels the dialog through `dialogTitleId` / `aria-labelledby`
  rather than repeating the title in a hidden node — so the card has two title renders to upstream's
  three. `ChatWindow.extension-request.test.mjs` asserts the wrapped forms.
- What the inline card still **lacks** is upstream's `aria-expanded` on the collapsed pill and on
  the collapse button: this fork's own rewrite dropped both before v0.15.0, the merge kept the
  fork's shape, and nothing asserts the attribute. Worth adding on the next pass over the card.

## Message and composer geometry
- **User bubble**: asymmetric radius `"14px 14px 4px 14px"` with a `color-mix(in srgb, var(--accent)
  20%, transparent)` border, not upstream's symmetric `borderRadius: 12` + `rgba(59,130,246,0.2)`.
  Upstream's edit states are kept as they are: `isEditing` swaps in the accent-mixed fill, border and
  focus ring, and `onCancelEdit` renders the cancel control.
- **Composer**: card shadow is `var(--shadow-md)` (fork token) on top of upstream's mobile-aware
  padding; the send button keeps upstream's `aria-label` and its icon-only-on-mobile behaviour; the
  compact control is radius 10 and, since upstream's compaction rework, carries **no** streaming
  gating — `hooks/useAgentSession.test.mjs` forbids `isStreaming && !isCompacting` in that block and
  requires `cursor: "pointer"`.
- **Run controls stay icon-only**: the header cleanup (`docs/PLAN-header-cleanup.md`) removed the
  steer/followUp text labels, so upstream v0.15.0's `chat-composer-run-label` spans were dropped in
  the merge and the buttons keep only their `title` / `aria-label`. The row carries upstream's
  `chat-composer-row` class (the fork's `composer-card` was a class no stylesheet defined), and the
  compaction and Stop buttons take upstream's `controlSize` — 44px on a phone, 32px elsewhere —
  with the fork's `var(--danger)` tints.
- A text selection offers two actions, both upstream's now: **Quote** (#1502, `quoteSelectionHere`,
  the selection as a blockquote in the composer) and **Ask in side chat** (`askInSideChat`, which
  asks the fork route for a side chat). The fork's own selection action "Ask in new chat"
  (`onAskInNewChat`) is gone — Quote covers copying the selection — and
  `hooks/useAgentSession.test.mjs` pins `onClick={quoteSelectionHere}`.
- **Settings panels** (`ModelsConfig`, `PluginsConfig`, `SkillsConfig`, `DirectoryPicker`,
  `FileExplorer`, `ProjectTrustDialog`) follow upstream's rewritten shared `SettingsUi` blocks and
  localisation; the fork's contribution there is colour only — hardcoded hex replaced by
  `var(--danger)` / `var(--success)` / `var(--warning)` (`app/globals.css` defines both themes).
  Radii and shadows in those panels follow upstream, because the markup was rewritten.

## Tool call header and previews (`components/MessageView.tsx`)
The header is a **pill badge** (mono 11, radius 5, `var(--bg-subtle)` fill, error-tinted fill/border)
wrapping upstream's `mcpLabel` server/tool split (`var(--success)` normally, `var(--danger)` on
error, `title` carrying the raw `mcp__` name). After it:
- the preview span reads `patchLabel ?? (codemode ? codemodeScriptPreview(code) : summary)`, where
  `summary` comes from `toolSummaryLine()` in `lib/tool-display.ts`. Upstream v0.11.1 grew its own
  `getToolPreview()` for that fallback; the fork keeps its richer line, so upstream's function came
  in unused and was deleted at both the v0.11.1 and the v0.13.0 merge. What v0.13.0 brought is kept
  instead: `resultPatchPath` (#1460). A call that names **no** file — an anchor-addressed edit, a
  package tool — is labelled with the one file its result's patch changed; a call that does name a
  file keeps its own path. `components/MessageView.test.mjs` pins both halves of that rule;
- the fork's status icons (spinner while input streams, ✕ on error, ✓ on result) are kept alongside
  upstream's `codemodeCallCount`, duration and rotating chevron;
- an expanded codemode call shows its script in a `<pre>` before `ToolArgsBody`, and `ToolArgsBody`
  is skipped for codemode so the script is not shown twice;
- `ToolArgsBody` shows **upstream's flat rows** (#1324) as the argument view: `toolFieldsOfInput()`
  in `lib/tool-fields.ts` turns the input into `ToolField`s, `ToolFieldRows` in
  `components/MessageView.tsx` draws them — one row per argument, a long or multi-line value kept
  inside the card. The fork's own `getToolArgsView()` (`lib/tool-display.ts`) takes over exactly
  where rows cannot go — a nested subtree, an empty object, a write shown as its file text
  (`getWrittenFileText`, also in `components/MessageView.tsx`, keeps such a call out of rows), or a
  stream pi's partial parse could not resolve, which keeps its raw text;
- the final assistant message is split through upstream's `getFinalAnswerViews()` cache
  (`lib/turn-views.ts`, adopted at v0.11.1) instead of the fork's inline
  `splitFinalAssistantBlocks`/`withAssistantBlocks` pair; the fork's own `previewText()` custom-message
  preview was dropped too, as upstream's `isHiddenCustomMessage` + inline collapsed preview supersede it.

The native `ask_user_question`'s dialog (`components/AskUserQuestionDialog.tsx`) takes the same
composer slot, ahead of an extension dialog — `{askUserDialogElement ?? extensionDialogElement ??
chatInputElement}` — with the same chrome (pill, maximize, "+N more", Escape declines); in the
message list `BlockView` shows the waiting call as a running generic card and an answered one as
`components/AskUserQuestionCard.tsx` — see [tools.md](tools.md).

Fork-only previews live beside upstream's truncation recovery and compaction footer:
`ProposedChangesPreview` (word-level diff of an edit/write result, `lib/word-diff.ts`),
`ToolArgsBody` (`getToolArgsView()` in `lib/tool-display.ts`), and the pending diff fetched from
`/api/edit-preview` (`lib/edit-preview.ts`) for an edit/write call that has no result yet.

Upstream's v0.13.0 patch view is taken as it wrote it: `PatchDiffFiles` (exported) draws two code
columns on a wide viewport and one column on a phone (#1325, `useIsMobile`), and `DiffGapRow` marks
the lines a hunk leaves out between two hunks (#1304, `unifiedDiffLines`). The fork's `SplitFilesView`
keeps its word-diff enrichment over that: line rows carry `inline` segments (`lib/word-diff.ts`) and
`SplitDiffCellView` paints them, so a changed line arrives split over several spans. That is why
`MessageView.test.mjs` reads the rendered **text** (`html.replace(/<[^>]*>/g, "")`) before checking a
line shows whole — upstream asserts on markup this fork's highlighting necessarily breaks up.

`MessageView` has **no** `onNavigate` prop: upstream's edit flow ("Edit from here") navigates inside
`useAgentSession`'s `handleSend` (`handleNavigateRef`, with rollback when the prompt is rejected), so
the fork's per-message navigate prop was redundant and is gone.

## MarkdownBody is memoized — upstream's own now
`components/MarkdownBody.tsx` wraps the component in `memo` so history messages do not re-run the
react-markdown pipeline (remark + rehype + katex) when a parent re-renders for unrelated state.
Shallow compare is enough: `children` / `cwd` / `className` / `keepLineBreaks` are strings,
`isStreaming` is a boolean, `onOpenFile` is a stable `useCallback`. Since v0.12.0 upstream memoizes
it too (`MarkdownBlock` is the memo'd inner component, `lib/markdown-blocks.ts` splits a growing
reply into blocks that re-parse on their own), so merging a new upstream version takes theirs.
What stays ours is `keepLineBreaks` — the prop is on both sides.

## AppShell
The fork renders the message list through `ChatWindow`'s `messageListNode` memo, so upstream's
v0.12.2 turn features land there and not in the JSX: `renderMessage` takes `withNotices` and
`turnDurationMs`, keys off `messageKeys[idx]` (#1184 — a live message keeps its alias until the
file brings an entry id), and returns a `Fragment` so `TranscriptNoticeLines` can follow the view
without remounting it (#1265). The grouping loop counts `processMessageCount` (notice lines sit in
`processViews` too), pushes those lines alone when a turn shows nothing else, and gives
`durationMs` and `cacheMisses` to `ProcessDetailsGroup`; a turn with no process details puts
`turnDurationMs` under its answer instead. `lib/turn-duration.ts` formats the minutes and seconds,
and the bubble a send shows carries the browser's clock with no entry id, so it shows no time until
the reload after the run. `MessageView` keeps the fork's word-diff rows (`InlineLineRow` over
`lib/word-diff.ts`) beside upstream's `splitThinkingPieces` (#1196) and `formatTurnDuration`.

The fork's header cleanup removed the `systemPrompt` state and `SystemPromptPanel` from
`components/AppShell.tsx`. Upstream's session-switch resets still call `setBranchSwitchLocked(false)`
(keep) but also `setSystemPrompt(null)` (drop — that state does not exist here).

v0.15.0 rewrote the message list as an inline IIFE again; the merge took its content and kept the
memo. `renderMessage` gained the side-chat divider (`msg.customType === SIDE_BOUNDARY_MESSAGE_TYPE`
renders `SideChatDivider`), its options read `recoverTruncation?: boolean; turnDurationMs?: number;
withNotices?: boolean` in upstream's order, and the JSX still renders `{messageListNode}`. The mobile
toolbar actions are upstream's Agents button alone (`renderChatToolbarActions`: no System,
Full-history or Generate-title), and `handleAgentEnd` / `handleAttentionNeeded` decide through
`isHiddenRelation(selectedSession?.relation)` (`lib/session-family.ts`), so a subagent's or a side
chat's tab counts as hidden for sounds and browser attention.
