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
- `ChatAppearance.test.mjs` expects 3 uses of `var(--chat-content-max-width, 820px)` in
  `ChatWindow.tsx` (the third is the dialog wrapper, which must not use a literal `maxWidth: 820`).

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
  `summary` comes from `toolSummaryLine()` in `lib/tool-display.ts` — **not** upstream's
  `getToolPreview()`, which this fork does not have;
- the fork's status icons (spinner while input streams, ✕ on error, ✓ on result) are kept alongside
  upstream's `codemodeCallCount`, duration and rotating chevron;
- an expanded codemode call shows its script in a `<pre>` before `ToolArgsBody`, and `ToolArgsBody`
  is skipped for codemode so the script is not shown twice.

Fork-only previews live beside upstream's truncation recovery and compaction footer:
`ProposedChangesPreview` (word-level diff of an edit/write result, `lib/word-diff.ts`),
`ToolArgsBody` (`getToolArgsView()` in `lib/tool-display.ts`), and the pending diff fetched from
`/api/edit-preview` (`lib/edit-preview.ts`) for an edit/write call that has no result yet.

`MessageView` has **no** `onNavigate` prop: upstream's edit flow ("Edit from here") navigates inside
`useAgentSession`'s `handleSend` (`handleNavigateRef`, with rollback when the prompt is rejected), so
the fork's per-message navigate prop was redundant and is gone.

## MarkdownBody is memoized
`components/MarkdownBody.tsx` wraps the component in `memo` so history messages do not re-run the
react-markdown pipeline (remark + rehype + katex) when a parent re-renders for unrelated state.
Shallow compare is enough: `children` / `cwd` / `className` / `keepLineBreaks` are strings,
`isStreaming` is a boolean, `onOpenFile` is a stable `useCallback`. Keep the wrapper when merging —
upstream's version is unmemoized.

## AppShell
The fork's header cleanup removed the `systemPrompt` state and `SystemPromptPanel` from
`components/AppShell.tsx`. Upstream's session-switch resets still call `setBranchSwitchLocked(false)`
(keep) but also `setSystemPrompt(null)` (drop — that state does not exist here).
