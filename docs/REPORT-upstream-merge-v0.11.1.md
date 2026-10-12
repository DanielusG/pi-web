# Report: upstream v0.11.1 merged into main

Merged on 2026-10-11 with `git merge --no-commit --no-ff v0.11.1` (upstream `agegr/pi-web`, tag
`v0.11.1` → `e783721`, npm `@agegr/pi-web@0.11.1` published 2026-10-10). Plan:
`docs/PLAN-upstream-merge-v0.11.1.md`. Backup branch: `backup/main-pre-v0.11.1`.

Upstream range: 46 commits, 193 files, +12195 −1625. The fork was 746 commits ahead of `v0.11.0`,
0 behind. **No dependency bumps**: `package.json` and `package-lock.json` changed only the `version`
line, so there was no pi SDK or `next` upgrade this time (the v0.11.0 merge's main risk).

## Conflicts and how they went

`git merge-tree` predicted 25 conflicts / 17 hunks before the merge and the real merge matched it:
13 in `demo/` (modify/delete — kept the deletion, `git rm -r -f demo`) and 12 real files.

| File | Resolution |
|---|---|
| `lib/rpc-manager.ts` | upstream's `isAwaitingInput()` + `getAwaitingInputRpcSessionIds()` adopted; the fork's `BLOCKING_DIALOG_METHODS`, `hasPendingBlockingDialog()` and `getWaitingRpcSessionIds()` deleted |
| `app/api/agent/running/events/route.ts`, `lib/run-status-stream.ts` | fork-only, clean merge; only the import changed. The Android field name `waitingSessionIds` stays |
| `app/api/sessions/route.ts` | fork's `sessionListing()` body kept, `awaitingInputSessionIds` added inside it; `recentCwds` removed |
| `lib/session-list-page.ts` + test, Kotlin fixture, `docs/agents/android.md`, `AGENTS.md` | `recentCwds` dropped everywhere (the pending cleanup) |
| `app/globals.css` | upstream's two `@source not` lines kept (`../demo` now inert, `../.claude` matters) |
| `app/sidebar.css` | upstream's geometry with the fork's tokens: row radius `--radius-sm`, group `--radius-md`, footer `--radius-md`, fork's `color-mix` selection tint kept alongside upstream's accent bar, `gap` removed from the row |
| `components/ChatWindow.tsx` | fork's memoized `messageListNode` kept, upstream's new logic moved into it (`isHiddenCustomMessage`, `getFinalAnswerViews` + `finalAnswerViewCache`, `collapsesProcessDetails`, `keepWrittenFiles`, `ref={setSentinel}`); fork's inline `EXTENSION_DIALOG_BASE_WIDTH` import and inline-styled update link dropped in favour of upstream's classes |
| `components/MessageView.tsx` | `ToolCallBlock` keeps the fork's `cwd` (pending preview) and takes upstream's `onOpenSubagent`; fork's preview cache restored; upstream's `getToolPreview` and the fork's `previewText` deleted as dead code |
| `hooks/useAgentSession.ts` | union: fork's `flushPendingDeltas()` + upstream's `setRetryInfo(null)` retry-wait fix, deps unioned |
| `components/ChatInput.tsx` | upstream's `onDismissCompactError` padding branch with the fork's `color-mix(var(--danger))` |
| `MessageView.test.mjs`, `NewSessionContextBar.test.mjs`, `SessionTree.test.mjs` | upstream's assertions taken, then the fork's forms re-added (fixture `>Find parser<`, hero slice ends at the fork's composer slot, `color-mix` selection tint, `--radius-sm`/`--radius-md` token assertions resolved through `app/globals.css`, `var(--danger)` added to the text-colour whitelist) |
| `AGENTS.md` | union of the file map: upstream's `rename-project` and `awaiting-input` wording, fork-only routes and `?lite=1` / `?toolUpdates=tail` annotations kept |

`onOpenSession` → `onOpenSubagent` everywhere: `AppShell`'s fork-only `handleOpenSession` (opening a
sub-agent as the main chat) was replaced by upstream's `handleOpenSubagentTab` (right-panel tab),
which renders the fork's own `SubagentViewer`.

## Semantics the Android client should expect

`waitingSessionIds` is now upstream's classification: a session counts when its run is **live** and a
dialog is open — `select`/`confirm`/`input`/`editor`, **or a `custom` overlay not yet closed**. The
fork's list excluded `custom` and accepted alive-but-not-running wrappers. `docs/agents/android.md`
records this.

## Fork deviations that changed by decision

- Sidebar restyle adopted (Daniele's choice): upstream's `app/sidebar.css` (1143 → 1618 lines) with
  the fork's token retune re-applied; the selection mark is now upstream's accent bar **plus** the
  fork's tint, so the row radius moved from `--radius-md` (9px) to `--radius-sm` (7px) — upstream's
  geometry test requires ≤ 8px.
- Awaiting-input classification delegated to upstream.
- `recentCwds` removed inside this merge.

## Verification

| Check | Before merge | After merge |
|---|---|---|
| `tsc --noEmit` | clean | clean |
| `npm run lint` | clean | clean |
| `npm test` | 2731 tests, 2727 pass, 3 fail | 2979 tests, 2975 pass, 3 fail (a second full run confirmed the same 3) |
| `android/gradlew test --offline` | not recorded | rc=0, all Kotlin unit tests pass |

The 3 failures are the pre-existing environment ones, unchanged: two `app/api/git/diff` tests that
cannot commit without a gpg TTY, and `lib/web-auth.test.mjs` "enables password authentication only
for a non-empty configured password". +248 tests came from upstream.

Four JS tests needed fork-side fixes after the merge: `hooks/retry-banner.test.mjs` and
`hooks/context-usage.test.mjs` run extracted case clauses and lacked the fork's `flushPendingDeltas`
stub; `components/SessionTree.test.mjs` needed the token-aware radius checks and the `--danger`
whitelist entry; `components/MessageView.test.mjs` needed its assertion aligned to the fork's fixture.

The Kotlin side needed one: when `recentCwds` was dropped from `SessionPagesTest.kt` the fixture JSON
lost its two closing brackets, so `parsesProjectsWithTheirTotals` failed with a `JsonDecodingException`
at offset 374 until the ending became `}]}]}`.

`lib/mcp-host.integration.test.mjs` "a stdio server that cannot start is failed with what it wrote to
stderr, masked" failed once inside one full parallel run, then passed in a second full run and twice
standalone — it spawns real servers, so it reads as load-sensitive timing, not a merge break.

Not run: `npm run test:e2e` (Playwright browsers are not installed here). **Never `next build`** was
run during dev, per AGENTS.md; the dev server on :30141 was left untouched.

## Review pass (3 read-only subagents, after resolution before commit)

**Code correctness** — no real problems. The fork's `messageListNode` memo (:932-1166) carries every
line upstream's v0.11.1 IIFE had (`isHiddenCustomMessage` :960/:1073, `getFinalAnswerViews` + cache
:799/:1063, `collapsesProcessDetails`→`answered` :1090/:1097, `keepWrittenFiles` :1117,
`ref={setSentinel}` :1133, anchors :933-956) and its deps array is complete; `MessageView`'s preview
machinery is intact and used (:1149/:1162/:1166) with `ToolCallBlock` at :1179 taking `cwd` +
`onOpenSubagent`; the sessions route keeps all fork params (:39-:55) plus `awaitingInputSessionIds`
(:82); no dangling `sentinelRef`/`withAssistantBlocks`/`onOpenSession`/`recentCwds` anywhere.
Repo sanity: no conflict markers (docs and `android/` included), no `.rej`/`.orig`, `git ls-files -u`
empty, no duplicate selectors in any stylesheet, every upstream file present except
`.github/workflows/demo-pages.yml` (dropped with `demo/`) and one unreferenced screenshot.

**Silent-loss audit** (34 auto-merged files where both sides had changes) — no new silent loss: all
121 fork-only files exist, the merge deleted no file versus fork HEAD, every fork-intent line still
present, and the 7 files touched only by hand match this report. i18n: the 7 keys upstream deleted
(`subagent.open`, `sidebar.gitRepoRootOnly(+Title)`, `chat.attachImage`,
`i18n.hidden/showExtensionMessage`) lost their consumers in lockstep; fork-only keys survive.

**Android contract** — no breakage: every endpoint and field Kotlin reads is still emitted with the
same name/type (`?lite=1`, `?toolUpdates=tail`, `deferThinking/deferMedia`, paging params,
`/subagents`, `/edit-preview`, `lease` → `renewed`, `info.relation`); upstream's added
`awaitingInputSessionIds` is safe under `Json { ignoreUnknownKeys = true }`; the three
`SessionPagesTest.kt` JSON literals parse (verified with `json.loads`).

## Decisions taken after the review (Daniele, 2026-10-11)

1. **`waitingSessionIds` scope, both directions.** Upstream's `isAwaitingInput()` gates on
   `isRunning()` where the fork used `isAlive()`, so a dialog pending on an alive-but-idle wrapper
   drops out — and `custom` overlays now count, which the Android client cannot show or settle
   (`ChatViewModel.kt:1339` ignores custom panels) yet `RunWatcherService.kt:109-115` will notify for
   them. `docs/agents/android.md:11-16` records the change but understates the first half. No Kotlin
   test covers the waiting semantics today (`RunSnapshotTest.kt` checks field names only).
   **Chosen: keep upstream's semantics.** `docs/agents/android.md` was widened to state both halves
   plainly, and `app/api/agent/running-events-route.test.mjs` now pins
   `waitingSessionIds: getAwaitingInputRpcSessionIds()` so a future merge cannot silently swap the
   producer while installed APKs still read the old name.
2. **Model list order.** Not a loss of this merge: `HEAD:app/api/models/route.ts` was identical to
   `v0.11.0` — the alphabetical collators were shared upstream code and upstream itself removed them
   (`9aa8752`, `a3a158a`, "list models in pi's /model order" #783/#1111). Staged `docs/agents/models.md`
   now forbids sorting by name; `lib/enabled-models-runtime.ts:30` is still alphabetical (upstream left
   it). **Chosen: adopt upstream's order** — no fork edit, no docs rewrite.
3. **30 untracked docs had been staged** (`docs/PLAN-*.md`, `docs/PERF-REVIEW*.md`, this report).
   **Chosen: only `docs/PLAN-upstream-merge-v0.11.1.md` and this report stay staged**, as at the
   v0.11.0 merge; the other 28 went back to untracked with `git restore --staged`.

Dead code the review noted: `lib/extension-dialog-fit.ts:2` `EXTENSION_DIALOG_BASE_WIDTH` is now an
unused export (the fork renders the dialog inline); `app/globals.css:2386` `.chat-stats-center` and
`components/ChatInput.tsx:2245` `className="composer-card"` are pre-existing orphans, not merge
fallout. `lib/web-auth.test.mjs` fails because `PI_WEB_PASSWORD` is exported in this shell.

## Left for Daniele

- The merge commit itself, the `v0.11.1-fork` tag and the push: `commit.gpgsign=true` with key
  `96FBC4F2284E3272`, and gpg cannot ask for the passphrase without a TTY ("batchmode - can't get
  input"), so the commit needs your unlocked key or `--no-gpg-sign`.
- Optional follow-up: `a952ed5` (perf: streaming markdown, session list payload, SSE image echo
  #1176) sits on `upstream/main` **after** the `v0.11.1` tag and is not in this merge.
- Optional follow-up: no lookbehind guard test exists for upstream's new client files (AGENTS.md rule;
  the only `(?<` in the range is a JSDoc comment in `lib/worktree.ts`, server-side).
