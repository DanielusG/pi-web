# Upstream merge report: v0.13.0

Merged on 2026-10-30. Upstream tag `v0.13.0` = `d128bae4 "Release v0.13.0"`. The fork's
pre-merge `main` was `cc46bb2 "fix(ask-user): preview box keeps the tallest preview's height"`
with a clean tree, and the `merge-base` was `b017cd4 "Release v0.12.2"` — exactly the last
integrated release, so nothing had drifted. The fork was **771 commits ahead** of that base
(the Android client, the native `ask_user_question`, the sidebar work). Backup branch:
`backup/main-pre-v0.13.0` at `cc46bb2`. Merge commit: `0999a3af`, parents `cc46bb2` and
`d128bae4`. Plan: `docs/PLAN-upstream-merge-v0.13.0.md`.

**99 commits, 267 files, +21494 −1625** — nearly twice the v0.12.2 range. The dry run
`git merge-tree --write-tree main v0.13.0` predicted 45 conflicted paths (19 content, 26
modify/delete) and the real merge produced exactly those: `git status` after
`git merge --no-ff --no-commit v0.13.0` showed 19 `UU` and 26 `DU`.

## Dependencies

- `package.json`: all seven `@earendil-works/*` `1.2.0 → 1.4.0`, `version` auto-merged to
  `0.13.0`, and upstream's `release` script became `npm version patch -m "Release v%s"`.
  The fork's own additions (`@fontsource-variable/geist`, `ansi_up`, `diff`, `allowScripts`)
  survived.
- **The install trap**: this machine has `npm config get omit` = `dev` and `NODE_ENV=production`
  exported, so the first plain `npm install` **pruned the devDependencies** — `node_modules` fell
  to 121 packages, `node_modules/.bin` lost `tsc` and `eslint`, and `react-markdown`/`mermaid`/
  `katex` disappeared under a running dev server. Recovered with `npm install --include=dev`
  (rc=0, TypeScript 5.9.3 back). `e2e/README.md` already warns about this for `npm ci`.
- Lockfile: the seven pi packages resolve to one hoisted copy each at `1.4.0`; no new
  `@earendil-works/*` appeared in the tree (only `marked-cjk-friendly@0.1.2` under `pi-tui`), so
  `lib/pi-packages.test.mjs` passes. pi 1.3+ pins its internal deps exactly instead of with `^`,
  which is why that rule now guards loudly.
- pi 1.4 caps codemode workers at 2 (`DEFAULT_MAX_CODEMODE_WORKERS`). Upstream's new
  `lib/codemode-workers.ts` raises pi-web's own default to 8 through `PI_CODEMODE_MAX_WORKERS`,
  and the merged `instrumentation-node.ts` kept **both** upstream's
  `applyCodemodeWorkerDefault()` call and the fork's `configureHttpDispatcher`, SIGINT/SIGTERM
  stream close and unawaited SDK prewarm — `git diff HEAD -- instrumentation-node.ts` shows only
  upstream's additions.

## The 26 modify/delete conflicts

All of them `demo/*`; kept deleted with `git rm -rf demo/` (upstream had also added
`demo/lib/tool-fields.ts`, which went with the rest). `.github/workflows/publish.yml` — npm
Trusted Publishing, new in v0.13.0 — arrived as a clean add and was removed as well: this fork
does not publish from GitHub Actions. The `AGENTS.md` pending cleanup about `.github/` now covers
that workflow too.

## The 19 content conflicts

| File | Resolution |
| --- | --- |
| `package.json` | upstream's `1.4.0` pins + the fork's `@fontsource-variable/geist` line |
| `package-lock.json` | upstream's, then `npm install` reconciled it with the fork's deps |
| `app/api/agent/[id]/route.ts` | both status-read forms: `?lite=1` (fork, `liteAgentState`) and `?systemPrompt=0` (upstream #1306); upstream's `missing-cwd` body kept |
| `app/api/agent/[id]/events/route.ts` | one call passing `{ toolUpdates, holder, ...describeStreamClient(req.headers) }`; `isValidLivenessHolder` kept |
| `app/api/sessions/route.ts` | fork's `sessionListing()` paging/trimming wrapped in upstream's `withSubagentDisplayStatus` |
| `app/globals.css` | fork's ask-user preview/tab rules + upstream's `.markdown-body > :last-child` margin fix |
| `components/AppShell.tsx` | top panels: fork's no-`system` (SystemPromptPanel stays dead) plus upstream's `debug`; Agents button takes upstream's badge gate (#1311); context tooltip = fork's numbers and percent with upstream's translated label (#1382) |
| `components/FileExplorer.tsx` | upstream's `t("files.uploadedCount"/"files.failedCount")` (#1379) with the fork's `var(--success)`/`var(--danger)` |
| `components/MessageView.tsx` | see "Tool cards" below — the deepest one |
| `components/ModelsConfig.tsx` | upstream's `? (` ternary form with the fork's `var(--danger)` |
| `hooks/useAgentSession.ts` | see "The hook" below |
| `lib/agent-event-stream.ts` | one options object carrying the fork's client choices and the debug panel's labels |
| `lib/subagent-extension.ts` | `wait_timeout` (#1448), `childAbort`/`attached`/`update`/`finally` and `waitForSubagent` taken; `model`, `isolation`, `input_files` stay out — the profile owns them |
| `lib/subagent-extension.integration.test.mjs` | parameter list = the fork's five + `wait_timeout` |
| `hooks/retry-banner.test.mjs` | stub keeps the fork's `flushPendingDeltas` and upstream's `markPromptLanded` (#1457) |
| `e2e/extension-dialog.mjs` | the fork's chat-column width checks, with #1360/#1369 wrapping noted and upstream's unconditional no-sideways assertion |
| `docs/agents/sessions.md` | the fork's collapse rule kept (code still has `CUSTOM_MESSAGE_COLLAPSE_CHARS`), upstream's #1318 search note folded in |
| `docs/agents/subagents.md` | the fork's six bullets kept, `wait_timeout` added to the offered list and to the resume bullet |
| `AGENTS.md` | route map keeps the fork's `?lite=1`/`?toolUpdates=tail` notes and gains upstream's `lease` and `debug`; topic notes take upstream's richer text with the fork's `lib/message-signatures.ts` and `ask_user_question` files re-added; the fork's `android.md`/`chat-ui.md` lines and Pending cleanups section kept |

## Tool cards — where the merge was a design choice, not a text clash

- Header label: the fork's `toolSummaryLine()` wins. `resultPatchPath` (#1460) labels a call that
  names **no** file; upstream's generic `getToolPreview()` was deleted again, as at v0.11.1, because
  the fork's line has its own first-key fallback that would otherwise shadow the patch path.
- Arguments: **upstream's flat rows (#1324) are the argument view**, `getToolArgsView()`
  (`lib/tool-display.ts`) is the fallback for a nested subtree, an empty object, a write shown as
  its file text, or an unparsable stream. Recorded in `docs/agents/chat-ui.md`.
- Patch view: upstream's `PatchDiffFiles` (#1325, two columns, one on a phone) and `DiffGapRow`
  (#1304) are taken as written, with the fork's word-diff `inline` segments painted on top. That
  necessarily splits a changed line over spans, so `MessageView.test.mjs` reads the rendered text
  before checking a line shows whole.
- `components/MessageView.test.mjs` after these: 4 failures → 0.

## The hook

Upstream extracted `applyLoadedAgentState()` and folded the fork's `applyContextUsage` into its own
`applyLiveUsage` (which sets `contextUsage` and `liveTotalActiveMs`). The fork's
`flushPendingDeltas()` before `dispatch({ type: "resume" })` was folded **into** the extracted
callback, with its comment and its dependency; `applyContextUsage` left the other dependency array —
that was the only `tsc` error of the merge. `markPromptLanded` (#1457), `releaseUnconfirmedSend` and
`withoutMessage`/`countUserMessages` came in beside the fork's `stableStringArray`.

Ten assertions in `hooks/useAgentSession.test.mjs` match the hook's **source text** and stopped
matching after upstream's refactor (`agentStatusUrl()`, `sessionLeaseUrl()`, `closeEvents(reason)`,
`shouldMaintain: wantsEventStream`, the new `AG_EVENT_HEARTBEAT_MS` import, the extracted restore
block). They were re-aimed to the merged shapes — patterns and slices only, no production change —
and three slices that had silently gone empty were re-pointed. The file is 45/45 green.

## Semantics git could not see

- `lib/agent-event-stream.ts` and the events route: taking both parameters as two positional
  arguments looked right and **broke upstream's own debug test**, which passes the labels as the 4th
  argument — the merged file hung for 240s under `node --test` until the two were unioned into one
  object. `app/api/agent/events-route.test.mjs` and `lib/stream-client.test.mjs` were re-aimed.
- `components/AppShell.mobile-toolbar.test.mjs` expected `{hasSubagentSessions && (`; the badge gate
  is behaviourally identical because `getAgentsBadge()` never returns null for a family with
  subagents, so the pattern was updated and the badge pin added.
- `components/ChatWindow.quote-caret.test.mjs` (new upstream file) read `demo/components/…` and died
  with ENOENT: it now reads only the real file. It is the **only** test in the merged tree that
  referenced `demo/`.
- Colour tokens: the merge added just two hardcoded colours, `app/sidebar.css` `#d97706` for the
  unread/awaiting dots, already in `SessionTree.test.mjs`'s whitelist. `app/settings.css` still
  carries its 16 `#ef4444` (unchanged).
- i18n: the fork's 28 `askUser.` keys are present in `en.ts`, `zh-CN.ts` and `zh-TW.ts` (28 each).
- `android/` was touched by **zero** upstream files.

## SDK jump 1.2.0 → 1.4.0, audited apart from the merge

Verdict: safe, with two checks — and both turned out to be already handled in this tree.

- **Multi-provider patterns** (pi 1.4 `resolveModelScopeFromModels` now returns *several* matches for
  a bare id and an `ambiguous` diagnostic): the fork already adopted pi 1.3's semantics —
  `lib/enabled-models-runtime.test.mjs` has "a bare id shared by two providers resolves to both
  owners". Verified under 1.4: `enabled-models-runtime` + `model-scope` = **31/31**.
- **Codemode worker cap**: wired, as above.
- Proven unchanged: `lib/pi-sdk-internals.ts`'s six unexported paths, `pi-mcp` dist byte-identical,
  `AgentSession` prototype/arity, `sdk.d.ts` (`createAgentSessionFromServices`), session manager and
  `CURRENT_SESSION_VERSION = 3`, the extension/tool API types (`defineTool`, `ToolDefinition`,
  `InlineExtension`, `ctx`) so `ask-user-extension`, `subagent-extension`, the built-ins and
  `mcp-host` are safe, `SettingsManager` (only `sessionShareDestination`), `ModelRuntime`,
  `ModelRegistry`, `ProjectTrustStore`, `DefaultResourceLoader`, `DefaultPackageManager`, the pi-ai
  subpath exports, the HTML exporter, `pi-tui` keybindings. No native `ask_user` tool exists in pi
  1.4, so nothing collides with the fork's `pi-web-ask-user`.
- Silent changes to keep in mind: `_restoreToolsFromTranscript` replays every branch system message,
  so a resumed or pinned session can come back with more active tools than before (it feeds
  `lib/rpc-manager.ts`'s carry set); compaction's `reserveTokens` is now unused; pi-ai adds
  `argumentsError`; `provider-attribution.js` is gone in favour of `client-identity.js` + pi-ai's
  `getAttributionHeaders` (third-party extensions using `before_provider_headers` see less
  attribution); telemetry was reworked (the fork imports nothing); some `DEFAULT_MODEL` ids moved
  (nvidia, github-copilot, xiaomi); pi's own `mcp.json` reader strips a BOM while pi-web's do not —
  recorded as a new pending cleanup in `AGENTS.md`; the `grep` tool runs `rg --no-config`.

## Verification

| Check | Before | After |
| --- | --- | --- |
| `tsc --noEmit` | clean | **clean** (one error mid-merge, the stale `applyContextUsage` dep) |
| `npm run lint` | clean | **clean** |
| `npm test` | last recorded 2924 at the v0.12.2 merge; not re-measured on the pre-merge tree | **3352 tests / 3351 pass / 0 fail / 1 skipped**, rc=0 |
| `android/gradlew --offline compileDebugKotlin` | rc=0 | **rc=0** |
| `npm run test:e2e` | rc=0, 38 PASS at v0.12.2 | **rc=0, 40 PASS / 0 FAIL** |

Environment notes, all of them pre-existing rather than merge damage:

- `lib/web-auth.test.mjs` fails ("true !== false") in any shell that exports `PI_WEB_PASSWORD`,
  because `isWebPasswordEnabled(undefined)` then reads the real value. With the variable unset the
  file is 6/6, and the whole suite is green — this is how the gate was measured.
- `gradle` needs `ANDROID_HOME=$HOME/Android/Sdk` in a non-login shell.
- e2e cannot share a checkout with a running dev server (`.next/dev/lock`), and Turbopack panics on a
  `node_modules` symlinked out of the filesystem root ("Symlink [project]/node_modules is invalid"),
  so it runs in a detached worktree with its own `npm ci --include=dev`. The fork's live dev server
  on :30141 was left untouched, and `next build` was never run in this checkout.

## Fork-only files

`android/` — zero changed files. Verified unchanged by the merge: `lib/ask-user-extension.ts`,
`lib/ask-user-question.ts`, `lib/run-status-stream.ts`, `lib/agent-state-lite.ts`,
`lib/session-list-page.ts`, `lib/session-fork.ts`, `lib/word-diff.ts`, `lib/tool-display.ts`,
`lib/pi-sdk-internals.ts`. Still deleted: `demo/`, `.github/`, `lib/subagent-input.ts`.

## Docs touched

- `AGENTS.md` — route map (`?lite=1` note now names upstream's `?systemPrompt=0`, new `lease` and
  `debug` lines), topic notes (upstream's richer sessions/tools text with the fork's
  `lib/message-signatures.ts` and `ask_user_question` files re-added), and Pending cleanups: the
  `.github/` entry covers `publish.yml`, and a new entry records the `mcp.json` BOM asymmetry.
- `docs/agents/chat-ui.md` — the #1460 labelling rule, upstream's rows as the argument view with
  `getToolArgsView` as fallback, and the patch layout (#1325/#1304) over the fork's inline segments
  with the reason the test strips tags.
- `docs/agents/sessions.md` — the custom-message collapse kept, #1318's search rule folded in.
- `docs/agents/subagents.md` — `wait_timeout` as a wait budget beside the profile-owned schema.
- `docs/PLAN-upstream-merge-v0.13.0.md`, this report.

## Leftovers and open decisions

- **#1432 send recovery**: `sendStreamingPrompt` now puts the queued message back on *any* failure
  and splits the notice, while `handleSend` parks an unconfirmed send. The fork had deliberately
  avoided re-adding a message whose turn may have landed. Adopted as upstream wrote it — say the
  word if you want the fork's asymmetry back.
- **Tool carry set**: pi 1.4's `_restoreToolsFromTranscript` activates extension tools the branch
  never mentioned, so a resumed or pinned session can start with more tools. Worth a look before it
  bites; no code change made.
- **Version for release**: npm already has `@agegr/pi-web@0.13.0` (upstream published it), so the
  fork's next published release needs `0.13.1` or a prerelease. `v0.13.0-fork` marks this merge.
- `feat/server-status-card` (two commits) stays shelved, not landed.
- Scratch to clean once you are back and approve the `rm`s (destructive commands are blocked while
  you are away): the `npm test` process hung before the stream fix, the two `/tmp/e2e-v0130*`
  worktrees, the SDK audit's `/tmp/sdk-audit` harnesses **and the symlink it left inside the repo at
  `node_modules/marked-cjk-friendly/marked-cjk-friendly` → `/tmp/sdk-audit/…`**, and the test-agent's
  `/tmp/{check-copy,fake-assert,old-useAgentSession,up-useAgentSession,final-run.log}` files.
