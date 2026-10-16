# Plan: long custom messages collapse, and compress-tool's reminder renders as clean markdown

## Why

pi-compress-tool injects its periodic context reminder as a custom message
(`customType: "compression-reminder"`, `display: true`) whose `content` is several thousand
characters of prose + a candidate list + a call example.

- In pi's TUI the extension registers a renderer (`extensions/compress-tool.ts:851`) that draws
  **one collapsed line** unless `expanded`; `expanded` is `verbose || toolOutputExpanded` and
  `toolOutputExpanded` starts `false` (`interactive-mode.js:291,1059`, Ctrl+O toggles it).
- pi-web runs no TUI renderers, so `CustomMessageView` (`components/MessageView.tsx`) renders the
  raw text as Markdown **expanded by default** → the whole block lands in the chat, far more than
  the CLI shows. That is the display deviation AGENTS.md says to fix in the web client.
- The text is also not clean Markdown: the candidate lines are `|`-separated with no header row
  (so not a table), the `────` × 60 divider lines overflow sideways, and the `compress_tool({...})`
  example is a bare indented block.

## Decisions already agreed with Daniele

- pi-web: collapse-by-length (A) + capped expanded body (C).
- Preview in the collapsed header: the text's **first line** only (CSS ellipsis cuts it).
- Extension: fix the synthetic reminder text so it renders correctly as Markdown —
  **bullet list** for the candidates, **dividers removed**.

## pi-web changes

### `lib/message-display.ts`
- Extract `getFirstLinePreview(text: string): string` — the current `getThinkingPreview` body
  (`^[^\r\n]{0,240}` match, trimmed). `getThinkingPreview` delegates to it so its name and
  behaviour stay; `lib/message-display.test.mjs` keeps asserting it.

### `components/MessageView.tsx` (`CustomMessageView`)
- New `const CUSTOM_MESSAGE_COLLAPSE_CHARS = 1200` with a comment naming the CLI reason: a
  `registerMessageRenderer` message is drawn collapsed unless `--verbose`/Ctrl+O, and pi-web runs
  no renderers, so a long one must not open itself over the chat.
- Compute `text` **before** the hooks (pure call, safe) and start collapsed when it is longer than
  the threshold: `useState(() => text.length <= CUSTOM_MESSAGE_COLLAPSE_CHARS)`.
- Header preview (the `!contentExpanded` span): `getFirstLinePreview(text)` instead of
  `text.slice(0, 300).replace(/\s+/g, " ")`.
- Expanded body wrapper (the `padding: "6px 9px"` div holding images + `MarkdownBody`):
  `maxHeight: 360, overflowY: "auto"` — the card's own scroll, like the user bubble's
  `USER_BUBBLE_MAX_HEIGHT` and the tool-output `<pre>`s (320/360).
- Scope: `compaction` never reaches this component (`CompactionMessageView`). The two
  subagent-notification types (`pi-web:subagent-notification`, `subagent-notification`) are turn
  anchors announcing a completion — keep them **always expanded**, via one predicate beside the
  threshold check, so a completion's report is never hidden behind a click. Daniele confirmed this:
  they keep exactly today's behaviour (they are drawn expanded today — `lib/subagent-runtime.ts:836`
  sends `display: true` and `CustomMessageView` opens every message; there is no other subagent
  custom message in pi-web, and their content is the subagent's whole final report).

### `components/MessageView.test.mjs`
- Existing `"collapses a displayed custom message from its header alone"` keeps passing (its texts
  are short → still expanded); keep its assertions and drop the now-wrong comment "starts expanded,
  as pi's TUI draws a displayed custom message in full" into the new rule's wording.
- New cases: text over the threshold → `aria-expanded="false"` and the header preview is the first
  line only (not the second line's text); short text → still expanded; images-only → still expanded;
  a subagent-notification type over the threshold → still expanded; expanded body carries
  `maxHeight`/`overflowY`.

### `docs/agents/sessions.md`
- Rewrite the §"Custom messages collapse from its header" bullet: length-based default collapse with
  the first-line preview, the notification exemption, the capped scrolling body, and the CLI
  comparison (`toolOutputExpanded` default `false`) plus compress-tool's reminder as the case that
  drove it.

## pi-compress-tool changes (`/home/daniele/Desktop/pi-compress-tool`)

### `extensions/compress-tool-prompts.ts`
- `renderPromptExecutionLine` → a Markdown bullet:
  `` - `<toolId>` — <priority>, <formatTokens(originalTokenCount)> tok, `<toolName>`: <toolArgs> ``
  (drop `padStart(6)`: leading spaces in a list item are meaningless; keep the lowercase priority
  wording the model sees today). Used by both builders, so the manual `/compress` prompt is fixed
  by the same change.
- `buildReminderMessage`:
  - drop both `────────…` divider lines around the candidates;
  - `Available tool results for compression:` → `### Available tool results for compression`
    heading followed by the bullets;
  - wrap the `compress_tool({...})` example in a ```` ```ts ```` fence;
  - the small-tool `Note:` line stays a plain paragraph.
- `buildManualReminderMessage`: same — dividers dropped, bullets under its existing
  `## Tools the user selected for compression` heading, fenced example.
- Heading levels stay as they are (`## ⚠️ CRITICAL…`, `### …`): they render fine inside the card and
  re-litigating them is out of this change's scope.

### Tests and docs
- `tests/compress-tool-prompts.test.ts` is byte-for-byte snapshot characterization → regenerate
  `tests/__snapshots__/compress-tool-prompts.test.ts.snap` and read the diff; the two
  `toContain("1 small result under 800 tok is not listed…")` assertions stay untouched.
- Nothing parses the reminder text (replay keys on `customType` and `details`), and the TUI renderer
  builds its own lines from `details.executions` → the CLI look is unchanged by the text edit.
- `README.md` (AGENTS.md is a symlink — edit README directly): update the "Example Flow" snippet's
  reminder lines to the bullet form.

## Checks

- pi-web: `node_modules/.bin/tsc --noEmit`, `npm run lint`, `npm run test` (MessageView +
  message-display tests). Never `next build`.
- pi-compress-tool: `pnpm install --frozen-lockfile` (already installed), `pnpm run check`,
  `pnpm test`.

## Open questions for Daniele

- Threshold value: 1200 chars proposed (reminders run 4–6k, ordinary extension notices stay under).

Resolved: the subagent-notification exemption above (confirmed — current behaviour stays).
