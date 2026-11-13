# Upstream merge report: v0.16.0

Merged on 2026-11-13. Upstream tag `v0.16.0` = `97e411f3 "Release v0.16.0"`. The fork's pre-merge
`main` was `8c4f5c2b "fix(android): open composer menus once the keyboard is down"` with a clean
tree, and the `merge-base` was `f130910a "Release v0.15.0"` — exactly the last integrated release,
so nothing had drifted. The fork was **785 commits ahead** of that base. Backup branch:
`backup/main-pre-v0.16.0` at `8c4f5c2b`. Merge commit: `9331cec4`, parents `8c4f5c2b` and
`97e411f3`. Plan: `docs/PLAN-upstream-merge-v0.16.0.md`.

All four commits of this round — the merge, the two fix commits under this one, and this docs
commit — are GPG-signed with the forwarded host key `96FBC4F2284E3272` and each verified with
`git log --show-signature`. Upstream's side was not rewritten: the 55 commits `f130910a..97e411f3`
keep their hashes and their GitHub signatures.

**55 commits, 189 files, +8242 −736** upstream-side (`v0.15.0..v0.16.0`). The merge commit itself
carries **174 files, +7762 −685** — the file short is `lib/edit-preview.test.mjs`, where the fork's
version won the add/add (see below), and the numbers moved as the lockfile reconciled and `demo/`
stayed out. The dry run `git merge-tree --write-tree main v0.16.0` predicted 30 conflicted paths
(13 content, 2 add/add, 15 modify/delete) and `git status` during the merge showed exactly that:
13 `UU`, 2 `AA`, 15 `DU`.

## Dependencies

- `package.json`: all **eight** `@earendil-works/*` `1.5.0 → 1.5.2`, pinned exactly (`e911f7bc`
  "upgrade pi to 1.5.2", installed with `npm install --save-exact` as `lib/pi-packages.test.mjs`
  demands). Next, `eslint-config-next`, `typebox` and `undici` did not move in this range. The
  fork's own lines survived: `@fontsource-variable/geist`, `ansi_up`, `diff`, `allowScripts`, and
  `version` auto-merged to `0.16.0`.
- `package-lock.json`: took upstream's, then `npm install --include=dev` reconciled it with the
  fork's dependencies; every `@earendil-works/*` resolves to one hoisted `1.5.2` copy, so
  `lib/pi-packages.test.mjs` passes (1/1). This shell's `npm config get omit` was empty, so the
  v0.13.0 install trap did not recur; `--include=dev` was still passed for portability.

## The 15 modify/delete conflicts

All fifteen are `demo/*` files (globals.css, AnsiText, AppShell, MermaidBlock, MessageView,
ModelsConfig, ansi, clipboard, file-links, the three locale files, model-discovery, session-search,
mock/search). They were kept deleted with `git rm`, as at every earlier merge. `.github/` was
untouched by this range, so the pending-cleanup entry about `publish.yml` needed no change and no
workflow arrived.

## The 13 content conflicts and the 2 add/add

| File | Resolution |
| --- | --- |
| `package.json` | upstream's eight `1.5.2` pins and `version`, plus the fork's font and helper packages |
| `package-lock.json` | upstream's, then `npm install --include=dev` reconciled |
| `components/ChatWindow.tsx` | the deepest one — see "Chat surfaces" below |
| `components/MessageView.tsx` | fork's header `summary` (`toolSummaryLine`), `resultPatchPath` (#1460), ask_user branches and word-diff kept; upstream's `ToolClockTime`, `toolsNotRun` and live-`!` footer taken; upstream's browser-side preview hooks removed (see "Chat surfaces") |
| `components/ModelsConfig.tsx` | upstream's llama.cpp conditionals taken, the fork's save-button tokens kept: `var(--success)` / `var(--accent)` / `var(--bg-panel)` |
| `app/api/sessions/[id]/context/route.ts` | union: upstream's `branchLeafId` naming and the new `leafId` field in the reply, inside the fork's `jsonResponse` (gzip) with the fork's `excludeLeaf` paging kept; `contextUsage` from the cold-context path |
| `hooks/useAgentSession.ts` | union of both import lines (`mergeMessageIdentities` + `normalizeToolCalls`, `EXTENSION_REPORTED_CODE` beside the fork's agent-client set) and of the two dependency arrays (upstream's `flushPendingDeltas`/`scheduleDeltaFlush` beside the fork's `chatInputRef`/`onSessionEnded`/`translate`) |
| `lib/subagent-runtime.ts` | import resolved to `getProjectTrustStatus` alone (#1613); upstream's `CLOSED_BEFORE_RUN` (#1590) and the child's `SettingsManager.create(childCwd, …, { projectTrusted })` auto-merged beside the fork's profile-owned schema; `lib/subagent-input.ts` stays deleted (4d8ebb12) |
| `hooks/context-usage.test.mjs`, `hooks/retry-banner.test.mjs` | upstream's new cases taken whole, with the fork's `flushPendingDeltas` stub added to the sandbox contexts the merged hook code now needs |
| `docs/agents/subagents.md` | the fork's child-chat-tab wording beside upstream's #1590 queued-child and #1613 trust bullets |
| `docs/agents/tools.md` | upstream's `defaultTools` paragraph and the #1611 built-in-tools bullet taken; the fork's native `ask_user_question` section kept after them |
| `AGENTS.md` | route map: upstream's updated ui-state/running lines plus the fork's Android and fork-only rows; lib map: upstream's read-times lines plus the fork's `atomic-file.ts`, `message-signatures.ts`, `idle-message-read.ts`; topic-note file lists extended with the merge's new files |
| `lib/edit-preview.ts` (add/add) | **the fork's server-side file won** — see "Chat surfaces" |
| `lib/edit-preview.test.mjs` (add/add) | kept the fork's: upstream's version imports `editPreviewFile`/`editPreviewEdits`, which do not exist in the merged tree and have no consumer — keeping it would fail on import |

## Chat surfaces — where the merge was a design choice, not a text clash

- **Pending edit preview (#1625).** Upstream added a *browser-side* `lib/edit-preview.ts` that
  builds the diff from the arguments alone — its stated reason: the browser cannot read the file.
  pi's TUI (`computeEditsDiff()`) reads the file, and pi-web's server can too, so the fork's
  server-side module (which runs the SDK's own edit preparation on the real file, validates, and
  returns a unified patch) is what fits pi's behavior. The add/add kept the fork's content for
  both file and test; upstream's `editPreviewFile()` variant did not enter the tree. `MessageView`
  keeps its `POST /api/edit-preview` fetch, the per-`toolCallId` cache, and the
  `Computing preview…` / no-change / error states, rendered through `ProposedChangesPreview` with
  the fork's word-level highlight. The **never-ran rule** from #1625 was adopted with a fork
  shape: a call in an aborted or failed message (`notRun`) shows neither preview nor raw arguments
  — the header stands alone — including while its arguments were still streaming, where upstream
  leaves the partial input on the card.
- **Tool header.** The fork's `toolSummaryLine()` kept the header (#1620's grep/find
  pattern-first is inside it, `pattern in path`). Upstream's generic `toolInputPreview()`
  (#1535/#1620) came into `lib/tool-fields.ts` and stayed there unwired — deleting an upstream
  export would turn every later merge of that file into a conflict; it is recorded as a pending
  cleanup in `AGENTS.md`.
- **Tool clocks (#1579/#1606).** `lib/tool-clock.ts` and `ToolClockTime` landed as upstream wrote
  them; the fork wired them through its memoized `messageListNode` (the memo now also takes
  `toolClocks` — the lint pass caught the missing dependency and it was added before the merge
  commit).
- **Fork-draft (#1539).** Upstream's `forkFromMessage` wrapper (message text and images into the
  new composer) landed on top of the fork's `handleFork(entryId, draft?)`, keeping the fork's
  `bashRunning || isNew` gate; `handleFork`'s own signature already carried a draft from the
  fork's Edit-from-here.
- **Inline extension dialog.** The fork renders the dialog in the composer slot, so
  `EXTENSION_DIALOG_BASE_WIDTH` stayed out with a comment naming this note; upstream's
  `splitNoticeLinks` in the notice shelf landed (its sole consumer is the fork's shelf).

## Semantics git could not see

Auto-merged regions that carry real behavior, each re-verified after the merge:

- **#1538 idle extension messages.** `lib/idle-message-read.ts` + `appendedLeafRef`: a message an
  extension sends with no run goes reads once and lands after the window; a `!` result held for the
  run's end forces a second disk read (`loadSession(…, { force: true })`) — the fork's old test
  "only the session-mount load probes disk" became two forced probes and was re-aimed, not
  loosened; `/clone` now sends the appended leaf while it is still the branch tip.
- **#1564 shutdown requests.** `endedSessionRef.current !== sid` joined `wantsEventStream`, and
  `handleSessionEnded` is wired from AppShell to both `ChatWindow` render sites and the fork's
  `SessionChatPanel`.
- **#1618 unread across browsers.** `lib/unread-markers.ts` + `lib/user-attention.ts` landed as
  written; `readAt` in the ui-state file, `forgetSessionUiState()` pruning it, and the poll's new
  `serverNow` compose with the fork's server-clock markers and favicon badge. The fork's own SSE
  (`lib/run-status-stream.ts`) keeps its four-field snapshot; Android reads none of the new poll
  fields, so the contract held.
- **#1613 child trust / #1590 queued child.** The child's wrapper is created with the parent's
  project trust; a queued child keeps its session file until its run starts (`CLOSED_BEFORE_RUN`),
  and the idle timer lets queued children live.
- **#1610 MCP deferred tools** re-register after an idle release; **#1615 codemode** startup-stall
  self-test ticks were taken verbatim and their integration tests pass.
- **#1612 built-in tool switches** ride the fork's `defaultTools` storage through
  `lib/powershell-settings.ts` (`withBuiltinTools`) and the tools-settings route.
- **#1616 multi-term search** reached only the web's literal `lib/session-search.ts`; the fork's
  `/api/sessions/find` delegates to the SDK's `/resume` matcher, which already requires every
  token — no duplicated matcher, no drift. **#1582** project labels ride the existing full
  `SessionInfo` serialization.
- **Bash live output (#1541)** and the truncated-output footer (59d6034f) landed inside the
  expanded card, as pi's TUI; the footer's borders were retuned to the fork's tokens in
  `471bd985`.
- **#1626 export** now `mkdtemp`s a private directory (0700, verified empirically) and removes it
  recursively; **#1609** `git status` threads `request.signal` into `getGitStatus`; **#1594/#1592**
  FileViewer diff highlighting and preview-recovery heartbeat landed with the fork's token lines
  beside them; **#1598** drive paths, **#1578/#649f8956** clipboard refusal + `/copy` fallback,
  **#1568/#1556** composer scroll and focus-through-compaction, and the IME guards
  (#1622/#1623/#1624) all landed.

## SDK jump 1.5.0 → 1.5.2

A patch jump: no dist path this fork loads moved, `lib/pi-sdk-internals.ts` needed no edit, and
the SDK-backed integration suites (rpc-manager, subagents against a real `AgentSession`, mcp-host,
llama.cpp) pass against the pinned 1.5.2. `dist/extensions/mcp/config.js` remains the BOM-strip
path the pending cleanup cites. The Android contract was audited after the merge: every wire claim
in `docs/agents/android.md` re-verified against `lib/agent-event-wire.ts` and pi 1.5.2 (see
"Independent review").

## Verification

- `node_modules/.bin/tsc --noEmit`: exit 0 (before the merge commit, and again after the review
  fixes).
- `npm run lint`: exit 0. The one warning during conflict resolution — the `messageListNode` memo
  missing `toolClocks` — was fixed before staging.
- Full serial suite (`env -u PI_WEB_PASSWORD node --experimental-strip-types --test
  --test-concurrency=1`, app/components/hooks/lib/public globs): first run **3636 tests, 3633
  pass, 2 fail, 1 skip** — the two failures were `app/api/sessions/context-route-fork.test.mjs`
  pinning the pre-merge route shapes, re-aimed to `branchLeafId` and the new reply field. Earlier
  in the same phase, `hooks/useAgentSession.test.mjs` carried 5 and `components/MessageView.test.mjs`
  carried several failures, all pattern tests on pre-merge shapes (onFork name, forced-read count,
  clone leafId, wantsEventStream, grep card text, the #1625 preview flow); every one was retargeted
  to the merged shape with a comment, none loosened without one.
- After the review round: **3636 tests, 3635 pass, 0 fail, 1 skipped** (exit 0). The 1 skip is
  the pre-existing one (Windows-only). The suite also passes with an ambient
  `ANTHROPIC_AUTH_TOKEN` present (checked by the test-side reviewer).
- `lib/pi-packages.test.mjs`: 1/1.
- **Android**: no `java` and no `~/Android/Sdk` in this container, so `gradlew
  compileDebugKotlin` could not run. Upstream touched zero `android/` files
  (`git diff --stat backup/main-pre-v0.16.0 -- android` empty), and the app-side contracts were
  audited instead; the Gradle build and its ~200 tests stay for Daniele's SDK box.
- **e2e**: blocked here. Playwright's browsers are cached
  (`~/.cache/ms-playwright/chromium_headless_shell-1243`), but `ldd` on the headless shell reports
  14 missing shared libraries — `libnspr4`, `libnss3`/`libnssutil3`, `libgbm`, `libdbus`,
  `libxkbcommon`, `libasound`, libatk/libatk-bridge/libatspi, and libXcomposite/Xdamage/Xfixes/
  Xrandr. Installing them is a packages.txt/image decision for Daniele; until then
  `npm run test:e2e` stays unrun.
- No `next build`, no `--webpack`, dev-server port untouched (no `next dev` was running on 30141).

## Fork-only files

`git diff backup/main-pre-v0.16.0..9331cec4` for `android/` is empty, and the fork-only libs
(`lib/ask-user-*`, `lib/edit-preview.ts`, `lib/tool-display.ts`, `lib/tool-fields` fork shape,
`lib/session-find.ts`, `lib/session-list-page.ts`, `lib/run-status-stream.ts`,
`lib/agent-state-lite.ts`, `lib/session-attachments.ts`, `lib/attachment-prefix.ts`,
`lib/cold-context.ts`, `lib/word-diff.ts`) show the expected deltas only — the ones this report
names. `demo/`, `.github/` and `lib/subagent-input.ts` stay deleted; the fork-only Android routes
still serve their parameters (`?perProject`, `?recentHours`, `?ids`, `?lite=1`,
`?toolUpdates=tail`, `/find`, `/attachments`, `/subagents`, `/running/events`).

## Docs touched

In the merge commit: `AGENTS.md` (maps), `docs/agents/{sessions,subagents,tools,chat-ui,models,
files-and-access,mcp-runtime,mcp-test-sign-in,settings-ui,side-sessions,client-platform,android}.md`
as upstream rewrote them, with the fork's hunks unioned.

In this docs commit, the review round's stale claims were fixed:

- `sessions.md`: the edit-preview bullet restated for the fork's server-side mechanism (it had
  landed as upstream's `editPreviewFile()` prose), the header bullet re-attributed to
  `toolSummaryLine` + `resultPatchPath`, `deletingRunsRef` and the `performDelete`/
  `handleSessionDeleted` names corrected, the `message_end` flush order restated (append, then
  flush before the `end` dispatch), the demo/`partial-json` clause marked upstream-only.
- `chat-ui.md`: the `toolInputPreview` keep-unwired decision told where the old delete-at-every-
  merge story stood; the inline card's `aria-expanded` claim corrected (it carries it; the test
  still does not pin it).
- `tools.md`: `maxNaturalHeight` and `docs/keyboard.md` labelled as
  @juicesharp/rpiv-ask-user-question internals.
- `android.md`: `tool_execution_end` field list, the SSE snapshot's actual four fields,
  `hasOpenQuestion` counting as awaiting input, the notification's real text ("Waiting for your
  input."), the fork's prefix-strip inside `catalogFirstMessage()` attributed, the search hint
  order, the snippet-gap causes widened (id/cwd/cross-phrase hits have no snippet), the Gradle
  count refreshed, and the drifted line cites dropped.
- `files-and-access.md`: the diff's own-repository resolver named (`findFileRepositoryRoot()` in
  `lib/git-changes.ts`, not `lib/project-tree.ts`).
- `side-sessions.md`, `mcp-runtime.md`: `BUILTIN_SLASH_COMMANDS` and the `/mcp` streaming gates
  attributed to `components/ChatInput.tsx`.
- `settings-ui.md`: the Chat section closes with the fork's native `ask_user_question` switch.
- `AGENTS.md`: side tabs are `sessionStorage` (#1512), the `lib/edit-preview.ts` entry tells the
  conflict outcome, and two pending cleanups were added — dead `toolInputPreview`, and FileViewer's
  remaining literals (the added-row green, VideoViewer's `#4ade80`/`#f87171`).

## Independent review

Six independent agents ran over the finished merge commit. Each got raw facts only — parents, the
conflict list, fork-only file names — and none of my conclusions; each read the tree itself and ran
only targeted `node --test` files, read-only.

| Section | Verdict | What it changed here |
| --- | --- | --- |
| Chat surfaces (`MessageView`, `ChatWindow`, `useAgentSession`, tool clock, preview flow) | every fork deviation intact, every upstream feature wired; 132 targeted tests green. Four confirmed: two stale doc bullets, the duplicated/orphan comments at `ToolCallBlock`, the `toolInputPreview` keep story | `471bd985` (comments, footer tokens), doc fixes here; the streaming-stop edge of `neverRanEdit` confirmed as intended and now written down |
| Sidebar / unread / search / list plumbing | clean; 285/285 targeted tests; all four features (#1559/#1618, #1616, #1582, #1614) land and compose with the fork's Android variants; fork deviation patches byte-identical pre/post merge | one `android.md` line (snippet-gap causes); two notes kept informational |
| Models / providers / subagents / MCP / tool switches | clean; 439/439 targeted tests; all features landed, all eight area deviations preserved. One confirmed low: upstream's project-trust integration test landed verbatim but its request-level `isolation` is ignored by the fork | `9634c1c5` (fixture declares isolation in the profile, the request key dropped) |
| Test side | honest: 67/69 upstream test edits landed verbatim, the two exceptions are the justified edit-preview fork keeps and the profile-owned subagent options; no assertion hollowed out; 91/91 touched+fork-only files, 1093 assertions green, also with a fake provider credential | `9634c1c5` (ChatInput button assert, `model-runtime` full strip); edit-preview lenient-parse note recorded below |
| Files / export / i18n / misc UI | clean; 231/231 targeted tests; export temp dir 0700 verified; i18n 1806 keys ×3 identical, 19 added keys all used, 4 removed all unreferenced; no new hex colors beyond one merge-new pair | `471bd985` (the pair retuned); FileViewer pre-existing literals added to AGENTS.md pending cleanups |
| `AGENTS.md` + `docs/agents/*.md` against the tree | map clean (all listed paths exist, merge-added files mapped, pending-cleanup entries verified true); stale claims named across eight notes + `android.md`/X-findings; duplicated-artifact scan beyond MessageView: none | the doc fixes listed under "Docs touched" |

Re-run after the round: serial suite green (3636 / 3635 pass / 0 fail / 1 skipped).

## Leftovers and open decisions

- **Version for release**: npm already has `@agegr/pi-web@0.16.0` (upstream published it), so the
  fork's next published release needs `0.16.1` or a prerelease. `v0.16.0-fork` is annotated on
  this docs commit, like the earlier `-fork` tags.
- **The fork's pending preview has no lenient parse**: upstream's dropped code accepted `edits` as
  a JSON string, a single object, or legacy `oldText`/`newText`; the fork's route requires an
  array (`200`/`10 MiB` caps) and the card gates on `Array.isArray(input.edits)`. A provider
  sending stringified `edits` shows no preview silently. Matching upstream is a product choice,
  not a merge fix.
- **The fork SSE lacks the new poll fields** (`serverNow`, `sessionUiStateRevision`): harmless
  today — Android reads neither — but a future Android unread feature needs them in
  `lib/run-status-stream.ts`.
- **Two search-side asymmetries by design**: an unclosed quote parses as a phrase in the web's
  literal search but as tokens in pi's `/resume` matcher (the Android find route), and `locatorPatterns()`
  gives no snippet for id/cwd/cross-message hits (now written into `android.md`).
- **The failed-`git worktree add` pending cleanup** is more user-visible since #1613/#1612 made
  isolation errors readable in the child card: the stray empty `<repo>-worktrees` stays until
  Daniele moves the mkdir in `lib/worktree.ts`.
- **e2e is unrun here** (14 missing shared libraries for Playwright's chromium, listed in
  "Verification"). If Daniele wants the gate in the image, append the matching apt packages to
  `/opt/claude-ccd/packages.txt` and restart `ccd`; otherwise run `npm run test:e2e` on the host.
- **Android compile and Gradle tests** stay for the SDK box; zero `android/` files changed.
- **Scratch cleaned here**: `/tmp/merge-tree-v0160.txt`, `/tmp/{ep-fork.ts,ep-up.ts,cw-theirs.jsx,
  cw-memo.txt,mv-fork-header.txt,mv-upstream.diff,has-upstream.diff,merge-msg-v0160.txt,
  suite-v0160.log,suite-v0160-final.log}`, the reviewers' `/tmp/audit` and `/tmp/rev-i18n`
  directories, and the earlier `/tmp/mv-debug.mjs` were deleted. `git worktree list` is the one
  checkout.
- **Push is left to Daniele**, as always.
