# Plan: Show actual context token count in the header stats

Display-only change. The header stats button showed only the percentage of the
context window used (e.g. `45% / 200k`). The actual token count was already
fetched end-to-end but never rendered.

## Data flow (unchanged)

`lib/rpc-manager.ts` calls `inner.getContextUsage()` and exposes
`{ percent, contextWindow, tokens }` in the agent state →
`hooks/useAgentSession.ts` propagates `state.contextUsage` →
`components/ChatWindow.tsx` forwards it via `onContextUsageChange` →
`components/AppShell.tsx` renders it. `ContextUsage.tokens: number | null`
exists in `lib/pi-types.ts` and mirrors pi's SDK type.

## Changes

### `components/AppShell.tsx`
- `renderSessionStatsButton` (header, desktop + mobile): when `tokens` is
  known, render `90k / 200k (45%)` — tokens first, compact form, percentage in
  parentheses. Mobile now shows the same string (previously just `45%`).
- Same button's tooltip: `context: 90,123 / 200,000 tokens (45.0%)` with exact
  `toLocaleString(locale)` numbers (previously `context: 45.0% of 200,000 tokens`).
- Session stats panel (`toggleTopPanel("session")`), `context` row in
  `extraTokenRows`: same order with exact numbers,
  `90,123 / 200,000 (45.0%)`.
- Fallbacks when `tokens` is `null` (e.g. before the first
  `getContextUsage()` after opening a session): desktop keeps the old
  `45% / 200k` (or `? / 200k`), mobile keeps `45%`, tooltip keeps the old
  wording. Percentages above 70/90% keep the existing warning/danger colors.

## Decisions
- Format `90k / 200k (45%)` chosen over `45% (90k / 200k)` (user preference).
- Compact form in the header (space-constrained bar), exact numbers in tooltip
  and stats panel.
- `contextUsage.tokens` is the live context size (tokens of the current
  conversation window), not the cumulative `sessionStats.tokens.total` — the
  two are different quantities; the header shows the former.

## Verification
- `node_modules/.bin/tsc --noEmit` — clean
- Manual: header shows `Xk / Yk (Z%)` on desktop and mobile once the session
  has state; tooltip and stats panel show exact numbers.

## Status
Done — commit `621df34` (`feat(header): show actual token count in context usage display`).
