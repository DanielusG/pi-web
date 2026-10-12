# Plan: merge upstream (agegr/pi-web) 77 commits

## Context
- Upstream `agegr/pi-web` `main` is **77 commits ahead** of our merge base `2a6e537` (v0.8.9).
- We have **1 local commit** (`916feb4`, edit-preview feature, unpushed) + **uncommitted WIP** (27 files + `lib/tool-display.ts`).
- Tree-level merge has **2 content conflicts**: `components/ChatWindow.tsx`, `components/MessageView.tsx`. No untracked-file collisions.
- Decisions (Daniele): **commit WIP first**, **merge** (not rebase).

## Steps

### 1. Commit the WIP
- `git add` the 27 modified files **plus `lib/tool-display.ts`** (imported by WIP `MessageView.tsx`; without it the commit won't typecheck).
- Do **not** commit the scratch `.py` files (`check_*.py`, `test_*.py`) or `docs/PLAN-*.md` / `docs/PERF-REVIEW.md` — they're untracked, unrelated to the code, and don't block the merge.
- Commit message per git-commit skill (scope: the WIP is the UI-refresh / i18n batch).

### 2. Add upstream remote + fetch
- `git remote add upstream https://github.com/agegr/pi-web.git`
- `git fetch upstream main`

### 3. Merge
- `git merge upstream/main` (merge commit, keeps history).
- Resolve the 2 conflicts (`ChatWindow.tsx`, `MessageView.tsx`) by hand — both sides rewrote the same regions heavily (upstream ±160/168 lines; ours +453/248). Strategy: keep both feature sets (upstream's changes + our edit-preview/tool-display work), reconciling shared regions.
- Conflict areas are the main risk; resolve carefully and re-check the whole file, not just the conflict hunks.

### 4. Sync dependencies
- Upstream changed `package.json` (16 lines) — run `npm install` after the merge.

### 5. Verify (per AGENTS.md; never `next build`)
- `node_modules/.bin/tsc --noEmit`
- `npm run lint`
- `npm run test`
- Fix anything that surfaces.

### 6. Summary
- Report merged state, conflict resolution notes, and verification results. Push is left to Daniele.
