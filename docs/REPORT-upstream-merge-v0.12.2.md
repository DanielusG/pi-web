# Upstream merge report: v0.12.2

Merged 2026-10-16. Upstream `v0.12.2` (`b017cd4`, and `upstream/main` is that tag — nothing after
it) into fork `main`, which was at `4d8ebb1` (`refactor(subagents): drop input_files, model, and
isolation from the Agent tool`) with a clean tree. Merge-base `f83b1a5` = upstream `v0.12.1`; the
fork is 756 commits ahead. Backup branch `backup/main-pre-v0.12.2`. Tag `v0.12.2-fork`.

This was the largest range since the original merge: **51 commits, 181 files, +8708 −811**. The dry
run (`git merge-tree --write-tree --merge-base=v0.12.1 main v0.12.2`, tree `d801838`) predicted 23
conflicts and the real merge produced exactly those 23.

## Dependencies

`package.json` moved **only `version`** and `package-lock.json` 4 lines — no SDK bump. So there was
no install step, and `lib/pi-packages.test.mjs` passes with the pins unchanged: every
`@earendil-works/*` package at `=1.2.0` (`pi-agent-core`, `pi-ai`, `pi-codemode`, `pi-coding-agent`,
`pi-mcp`, `pi-telemetry`, `pi-tui`).

## The 10 modify/delete conflicts

Kept the fork's deletions with `git rm`: `demo/components/MessageView.tsx`,
`demo/components/TabBar.tsx`, `demo/hooks/useAudio.ts`, `demo/lib/message-display.ts`,
`demo/lib/subagent-input.ts`, `demo/lib/thinking-expansion-preference.ts`,
`demo/mock/captured/tools.json`, `demo/mock/files.ts` (the fork deleted the demo app), plus
`lib/subagent-input.ts` and `lib/subagent-input.test.mjs` from `4d8ebb1`. Nothing in
`app/ components/ hooks/ lib/ e2e/` or the configs references `demo/` afterwards.

## The 13 content conflicts

| File | Resolution |
|---|---|
| `lib/subagent-runtime.ts` | Fork base: `profileWhere` in errors, profile owns model/thinking/isolation, blanket resume refusal. Kept upstream's `parentModelRuntime` (used by the services and the profile-model parse), `persistChildSession` (#1198) and `worktreeExists` in `recordResult` (#1202). Dropped `expectedSubagentModel` and the `subagent-input` import. Both resume guards now stand: the fork's refusal of any run with `worktreePath`, then upstream's refusal of a missing cwd. |
| `lib/subagent-extension.ts` | Fork's Agent schema (no `input_files` / `model` / `isolation`). `worktreeOutcomeText` is upstream's #1202 text with the fork's `run.resumed` guard; `subagentFinalText` keeps upstream's ordering (the line after the status, before the child's words) and applies it to `interrupted` too, which upstream left out. |
| `lib/subagent-extension.test.mjs` | Both sides' cases: upstream's #1202 outcome test verbatim, the fork's isolated-run test rewritten to the recorded-`worktreeExists` wording (it asserts `interrupted` / `aborted` / `failed` carry the line and a resumed run says nothing). 23/23. |
| `lib/subagent-runtime.integration.test.mjs` | Upstream's two new tests kept ("a run stopped before its prompt keeps a session file", "a deleted child stays deleted"). Its `input_files` sizing and call-model tests dropped (the fork has no per-call options); its extension-registered profile-model test kept without `inputFiles`; its #1202 test kept with isolation driven by the profile (`writeIsolatedProfile`) instead of `request.isolation`, and the fork's two isolation tests kept. Also removed a duplicated `const exec = promisify(execFile)` the auto-merge left. 42/42. |
| `app/api/sessions/[id]/context/route.ts` | Union: upstream's `attachTranscriptNotices(readSessionTranscriptNotices(), buildSessionContext())` around the fork's `jsonResponse` and `contextUsage`; `computeFileContextUsage` now reads the same `entries` the context is built from instead of asking again. |
| `components/AppShell.tsx` | Fork's context line (tokens / window, then the percent) kept, calling upstream's `formatCompactCount` — the fork's local `formatCompact` alias exists only in the stats scope. |
| `components/ChatWindow.tsx` | Upstream's turn features ported **into the fork's `messageListNode` memo**, not into the JSX: `withNotices` / `turnDurationMs` options, `messageKeys[idx]` (#1184), `noticeLines` in a `Fragment`, `processMessageCount`, the notice-lines-only push, `summarizeCacheMisses` + `durationMs` on `ProcessDetailsGroup`, `turnDurationMs` under a lone answer, `messageKeys` in the memo's deps. React import gained `Fragment` **and** `useId` (the fork's inline dialog needs it). |
| `components/MessageView.tsx` | Union imports: upstream's `splitThinkingPieces` (#1196) and `formatTurnDuration` beside the fork's `inlineDiff` and `InlineLineRow`, and `type SplitDiffRow` kept for the fork's row type. |
| `components/FileExplorer.tsx` | Upstream's line: the overwrite button passes `pendingConflict.directory` (v0.12.2 drop targets). |
| `hooks/useAgentSession.ts` | Union: upstream's `carryMessageKeys` aliases (#1184) **and** the fork's identity-preserving `mergeMessageIdentities` / `stableStringArray`. Upstream carries aliases in this one place too, so the fork's second identity spot needs none. |
| `components/SidebarMenu.test.mjs` | Both tests kept: the fork's CSS token-form test and upstream's `menuBodyFocusTarget` test. |
| `AGENTS.md` | Hand union of the three regions (the fork's `lib/` file list over upstream's two reworded lines, upstream's `useTerminalTheme.ts` line over the fork's `android/` block, upstream's `client-platform.md` line over the fork's `android.md` / `chat-ui.md` lines and its `## Pending cleanups`). Added the one new file upstream's own update missed (`lib/message-signatures.ts`, also into `sessions.md`'s Files list) and `lib/terminal-theme.ts` to the file map. The `next dev` generated block stayed out. |
| `docs/agents/subagents.md` | The fork's rewrite from `4d8ebb1` with upstream's #1202 outcome bullet (adjusted for the fork's `interrupted` and `resumed` rules), upstream's `## Agent switcher usage` section, and notes that #1199's sizing is moot here. |

## Semantics git could not see

- **CSS**: `app/globals.css` (+328), `app/settings.css` (+134) and `app/sidebar.css` (+52) merged
  without conflicts. `globals.css` still defines the 20 fork tokens; `sidebar.css` and
  `sidebar-menu.css` gained no hardcoded colours over the fork's own (7 and 2, same as before). One
  real regression: upstream's rewrite dropped `.session-tree.is-mobile
  .session-tree-archive-group` from the mobile `font-size: 13px` rule while `SessionTree.tsx` still
  uses that class — restored, and `SessionTree.test.mjs` "row CSS stays flat, themed and quiet"
  passes again. `app/settings.css` keeps 16 hardcoded `#ef4444` rules (the fork's pending cleanup,
  one added by upstream); the note in `AGENTS.md` now says 16.
- **Wire vs fork-only routes**: `lib/message-signatures.ts` strips `thinkingSignature`,
  `textSignature` and `thoughtSignature` on the SSE wire, in `lib/session-reader.ts` and in
  `lib/project-tree.ts`. `lib/agent-state-lite.ts` (`?lite=1`) only drops `systemPrompt`, so the two
  are orthogonal; `?toolUpdates=tail`, the paged session list, `/api/agent/running/events` and
  `/api/sessions/[id]/subagents` are untouched. `proxy.ts` gates `/api/:path*` as a whole, so the new
  `POST /api/sessions/stats` and `GET /api/cache-miss-notices` inherit the web-password gate.
- **Session list**: upstream's `lib/session-list-scanner.ts` tail rescan (+513) sits under the fork's
  `lib/session-list-page.ts` paging and `getSessionListVersion` path — the scanner and page tests pass.
- **i18n ×3**: the 45 new keys landed in `en`, `zh-CN` and `zh-TW`; `lib/i18n/registry.test.mjs` and
  `format.test.mjs` pass.
- **Safari 16.2**: no lookbehind is added anywhere in the range; the only matches in the tree are
  `lib/markdown.ts`'s comment about its emulation and `lib/mcp-test.ts`'s runtime-built regex in `try`.
- **Upstream's auto-merged tests that asserted upstream's subagent rules**: `lib/subagent-runtime.test.mjs`
  resumed isolated children and expected "Directory does not exist". Under the fork's rule resuming an
  isolated run is refused whether or not the checkout survived, so those three tests now assert the
  fork's refusal message — with the #1202 read-back wording still checked for the runs whose result
  entry carries `worktreeExists`, and nothing queued or recorded.

## Verification

| Check | Before merge | After merge |
|---|---|---|
| `tsc --noEmit` | clean | clean |
| `npm run lint` | clean | clean |
| `npm test` | 2779 / 2776 pass / 2 fail / 1 skipped | **2924 / 2923 pass / 0 fail / 1 skipped** |
| `android/gradlew test --offline --rerun-tasks` | rc=0, 51 tasks | rc=0, BUILD SUCCESSFUL, 51 tasks |
| `npm run test:e2e` | — | rc=0, 38 PASS, 0 FAIL (incl. the fork's inline-dialog fit checks at 1280px and 390px, chat appearance, theme switches) |

The two pre-merge failures and the six post-merge ones above were run with
`GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false`: the git fixtures in
`app/api/git/diff/route.test.mjs` and `lib/subagent-runtime.integration.test.mjs` commit with only
`-c user.name` / `-c user.email`, so this machine's `commit.gpgsign=true` makes them ask gpg for a
passphrase with no tty. Environment, not code — with that override both baselines and the merged
suite are clean.

e2e ran in a detached `git worktree` at `/tmp/e2e-v0122` with the merge applied and
`npm ci --include=dev`, so `:30141` and its `.next/dev/lock` stayed untouched. `next build` was never
run in this checkout. Upstream's new specs `e2e/model-context.mjs` and `e2e/project-navigation.mjs`
are imported by `e2e/run.mjs` and ran; the fork's inline-dialog sizing adaptations needed no change.

## Fork-only files

`android/` shows no diff against `4d8ebb1` — the app's code and tests are untouched by this merge.
`demo/` and `lib/subagent-input.ts` remain deleted. The fork-only routes and libs (`agent-state-lite`,
`session-list-page`, `run-status-stream`, `cold-context`, `word-diff`, `edit-preview`, `tool-display`,
`session-fork*`, `session-delete`, `session-list-scanner` additions) survived the merge; the ones
upstream also touched are listed above.

## Docs touched

`AGENTS.md` (file map + topic notes + the `settings.css` count), `docs/agents/subagents.md` (fork's
rewrite plus #1202 and the agent switcher's usage), `docs/agents/chat-ui.md` (where the turn features
land in the fork's memoized list), `docs/agents/sessions.md` (the fork's context route carries both
sides), `docs/agents/files-and-access.md` (drop targets and a changed file's own repository),
`docs/agents/android.md` (what the app must expect: stripped signatures, untouched fork-only contracts,
the two web-only routes). Upstream's own `CONTRIBUTING.md` and `docs/CONTRIBUTING.zh-CN.md` land here —
the docs move skipped at v0.12.1 is inside this range.

## Leftovers

- The three pending cleanups in `AGENTS.md` are unchanged: `lib/worktree.ts` still makes `baseDir`
  before `git worktree add`, the Android new-session sheet still ignores `SessionsViewModel.validateCwd`'s
  reason, and `components/SystemPromptPanel.tsx` is still dead-but-kept. `app/settings.css`'s token
  retune remains a separate choice (now 16 rules).
- Upstream's #1199 `input_files` sizing is moot on this fork — there is no copy to size. If the fork
  ever restores per-call options, that work has to be re-read from upstream rather than replayed.
- `lib/subagent-runtime.ts` now carries two resume guards; upstream's applies to any run whose folder
  is gone, the fork's to any run that was isolated. Worth collapsing if upstream ever drops the
  per-call isolation option too.
- Dev server on `:30141` (pid 465575) was left running through the merge; restart it to pick the
  merged tree up.
