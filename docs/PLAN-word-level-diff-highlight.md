# Plan: VS Code-style word-level highlight in edit tool diffs (done)

The split diff shown for edit/write tool results (`SplitPatchView` in
`components/MessageView.tsx`) colored whole rows (green/red background) but
gave no indication of which part of a changed row actually differed. Goal:
intra-line ("word-level") highlighting like VS Code's diff editor — for
`const value = "old"` → `const value = "new"` only the changed word is
highlighted, on top of the row background.

## Data flow (unchanged)

Edit tool result carries a unified patch in `details.patch` /
`details.diff` → `getResultDiff()` in `MessageView.tsx` →
`PairedDiffResult` → `SplitPatchView`. The pending-edit preview
(`ProposedChangesPreview`, via `/api/edit-preview`) renders through the same
`SplitPatchView`, so it gets the highlight automatically.

## Changes

### `package.json` / `package-lock.json`
- `diff@^8.0.4` added as a direct dependency. It was already in the tree
  (declared by `pi-agent-core` and `pi-coding-agent`); npm dedupes to the
  same copy, no added weight.

### `lib/word-diff.ts` (new)
- `inlineDiff(oldText, newText): InlineDiff | null` where
  `InlineDiff = { old: InlineSegment[]; new: InlineSegment[] }` and
  `InlineSegment = { text: string; changed: boolean }`.
- Uses jsdiff's `diffWordsWithSpace` (whole-word diff, strict equality) and
  merges consecutive same-flag tokens into segments.
- Returns `null` (callers fall back to plain row rendering) when inline
  highlighting would be noise:
  - identical or one-sided (empty) text,
  - either line > 500 chars (`MAX_LINE_CHARS` — worst case is
    O(len_old × len_new); a fully divergent 500×500 pair costs ~30ms, and
    longer lines are usually minified anyway; VS Code likewise skips
    intra-line diffing on very long lines),
  - average changed-character fraction > 50% (`MAX_CHANGED_FRACTION`),
  - safety net: segments must reconstruct each original line exactly.

### `lib/word-diff.test.mjs` (new)
- 7 tests: word swap, one-sided insertion, single changed digit, unicode
  word chars, null for identical/empty/too-long/too-divergent pairs.

### `components/MessageView.tsx`
- `SplitPatchView`: the existing `useMemo` (keyed on patch `text`) now
  enriches each paired `removed`/`added` row with
  `inline: inlineDiff(left.text, right.text)` — computed once per patch,
  not per re-render during streaming.
- `SplitDiffCellView`: new optional `inline?: InlineSegment[]` prop;
  changed segments render with a stronger background
  (`color-mix(... var(--success) 32%)` / `var(--danger) 32%`) over the
  existing 12–13% row background.

## Decisions
- **Library, not hand-rolled.** jsdiff is battle-tested (Unicode-aware
  tokenization, Myers diff) and is the same library pi's own TUI diff view
  (`pi-coding-agent` `modes/interactive/components/diff.js`) uses for
  intra-line highlighting, so web UI and TUI stay consistent.
- **`diffWordsWithSpace` — whole words, strict equality.** After seeing the
  rendered result, char-level granularity (only the differing digit of
  `20` → `50`) was found too fine; whole-word highlighting reads better in
  code. `diffWordsWithSpace` (unlike `diffWords`) compares tokens with
  *strict* equality, so "common" parts are identical on both sides and both
  lines reconstruct exactly — the trim-equality variant drifts on the
  old side for pure insertions and reorders (verified during development).
  The exact-match reconstruction guard remains as a safety net against
  library contract changes.
- **`null` fallback instead of partial highlighting** for divergent lines:
  a full-row inline highlight adds nothing over the row background.
- **50% changed-fraction threshold** chosen so small edits stay precise
  (e.g. `20` → `50` highlights the whole number) while rewritten rows stay
  clean.

## Verification
- `npm test`: 860/860 pass (incl. 7 new word-diff tests).
- `node_modules/.bin/tsc --noEmit` and `npm run lint` clean.
- End-to-end check of the exact `SplitPatchView` pipeline
  (`parseUnifiedPatch` + `inlineDiff`) on a realistic multi-hunk patch:
  `const limit = 20` → `50` highlights the whole `20`/`50`; common words
  (e.g. a `404` preserved in a rewritten line) stay unhighlighted, matching
  VS Code's minimum-edit-script behavior.
