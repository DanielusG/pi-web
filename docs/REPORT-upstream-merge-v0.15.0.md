# Upstream merge report: v0.15.0

Merged on 2026-11-06. Upstream tag `v0.15.0` = `f130910a "Release v0.15.0"`. The fork's pre-merge
`main` was `e22bef40 "docs(merge): record the v0.13.0 merge outcome and its gates"` with a clean
tree, and the `merge-base` was `d128bae4 "Release v0.13.0"` — exactly the last integrated release,
so nothing had drifted. The fork was **773 commits ahead** of that base. Backup branch:
`backup/main-pre-v0.15.0` at `e22bef40`. Merge commit: `f17929eb`, parents `e22bef40` and
`f130910a`. Plan: `docs/PLAN-upstream-merge-v0.15.0.md`.

The merge and the test commit under this one were committed unsigned, with `commit.gpgsign=false`
because this environment's `git` has no tty for pinentry, and were re-signed on 2026-11-07 with
key `96FBC4F2284E3272` over the same trees, parents, authors and messages. So `accda73c` (merge)
and `2294e473` (test) are superseded by `f17929eb` and `c31e6e89`, and the tip before the
re-signing (`c2fa2587`) stays reachable as `backup/main-pre-v0.15.0-signing`. Upstream's side was
not rewritten: the 52 commits `d128bae4..f130910a` keep their hashes and their GitHub signatures.

**52 commits, 176 files, +6782 −1648** upstream-side (`d128bae4..v0.15.0`). The merge commit itself
carries **174 files, +6697 −1660** — the two files short are the `.github/workflows/*` pair kept
deleted, and the insertions/deletions moved as the lockfile reconciled and `demo/` stayed out. The
dry run `git merge-tree --write-tree main v0.15.0` predicted 25 conflicted paths (13 content, 12
modify/delete) and `git status` after `git merge --no-ff --no-commit v0.15.0` showed exactly 13 `UU`
and 12 `DU`.

## Dependencies

- `package.json`: all **eight** `@earendil-works/*` `1.4.0 → 1.5.0`, pinned exactly — the set grew by
  one, the new **`@earendil-works/pi-schema`**. Upstream added `typebox@1.3.27` as a direct
  dependency (pi 1.5 moved `Type` out of `@earendil-works/pi-ai`), and `next`/`eslint-config-next`
  went `16.3.8 → 16.4.4`, `undici` `8.11.0 → 8.12.3`. The fork's own lines survived:
  `@fontsource-variable/geist`, `ansi_up`, `diff`, `allowScripts`, and `version` auto-merged to
  `0.15.0`.
- `package-lock.json`: took upstream's, then `npm install` reconciled it with the fork's
  dependencies; every `@earendil-works/*` resolves to one hoisted `1.5.0` copy, so
  `lib/pi-packages.test.mjs` passes (1/1).
- The v0.13.0 **install trap did not recur here**: this shell has `npm config get omit` empty and no
  exported `NODE_ENV=production`, so plain installs kept devDependencies. `--include=dev` was still
  passed everywhere for the same reason the e2e README gives it — it costs nothing and a machine
  with `omit=dev` needs it.

## The 12 modify/delete conflicts

Ten `demo/*` files plus `.github/workflows/publish.yml` and `.github/workflows/demo-pages.yml`.
Unlike v0.13.0, where upstream's `publish.yml` arrived as a clean add, this range **edited** both
workflows (`2bf9c6e5` folded the demo deploy into one Release workflow per tag), so each came as a
`modify/delete` conflict; `container.yml`, added by `e97d00b1`, was removed again by `2bf9c6e5`
inside the same range and never reached the tree. All twelve were kept deleted with `git rm`; the `AGENTS.md` pending
cleanup about `.github/` now says the workflows arrive edited, not only new.

## The 13 content conflicts

| File | Resolution |
| --- | --- |
| `package.json` | upstream's eight `1.5.0` pins, `pi-schema`, `typebox`, Next/undici bumps, plus the fork's font and helper packages |
| `package-lock.json` | upstream's, then `npm install` reconciled |
| `components/ChatWindow.tsx` | the deepest one — see "Chat surfaces" below |
| `components/ChatInput.tsx` | upstream's shapes with the fork's icon-only run controls: `chat-composer-row` class, `controlSize` (44/32) and the `var(--danger)` tints on compact and Stop are upstream's v0.15.0 forms, the `chat-composer-run-label` spans are not |
| `components/TabBar.tsx` | union: the fork's `background 0.15s var(--ease)` transition beside upstream's `inset ±2px var(--accent)` drop-target marker (#1511) |
| `app/sidebar.css` | upstream dropped the `.session-tree` scope from the confirm-delete/confirm-cancel rules (AgentSessionPanel's side-chat Delete renders outside the tree, 48a158bc); the fork's `var(--danger)` fill stayed, so the unscoped selector now carries the token |
| `lib/i18n/messages/{en,zh-CN,zh-TW}.ts` | the fork's 28 `askUser.*` keys per locale kept; upstream's 17 `sideChat.*` / `sessionPanel.*` / `agentSwitcher.sideChat`+`openInMainChat` taken; four dead keys (`subagent.refresh`, `subagent.viewer.{starting,empty,error}`) dropped with `SubagentViewer` |
| `components/AppShell.tsx` | three hunks as "theirs minus the buttons this fork deleted": upstream's Agents-beside-More phone toolbar (#1507 delete-side-chat, #1512 side tabs restored, `TOP_BAR_ICON_BUTTON_SIZE`) landed, the System / Full-history / Generate-title buttons stayed out |
| `components/ChatWindow.quote-caret.test.mjs` | upstream's new test loops over `../components/ChatWindow.tsx` **and** `../demo/components/ChatWindow.tsx`, which this fork deleted; rewritten to read only the real file and assert `onClick={quoteSelectionHere}` |
| `docs/agents/subagents.md` | the fork's profile-owned schema kept beside upstream's #1473 settle-on-turn and #1491 latest-subagents answers |
| `AGENTS.md` | route map keeps the fork's Android and fork-only routes, takes upstream's side-chat lines; topic notes take upstream's `side-sessions.md` and the rewritten `subagents`/`client-platform` text with the fork's `android.md` and `chat-ui.md` beside them |

## Chat surfaces — where the merge was a design choice, not a text clash

- **The memoized list stayed.** Upstream v0.15.0 renders the message list as an inline IIFE inside
  the JSX; the fork keeps `messageListNode = useMemo(...)`. The merge took upstream's **content**
  into that memo: `renderMessage` now answers `msg.customType === SIDE_BOUNDARY_MESSAGE_TYPE` with
  `<SideChatDivider />` (ADR 0007's hidden boundary message), and `renderMessage`'s options read
  `recoverTruncation? / turnDurationMs? / withNotices?` in upstream's order.
- **`stripAnsi` was adopted, not ported.** Upstream's `da6d538a` wraps extension text before
  rendering it with pi's palette theme; the fork's inline card took the same wraps — the collapsed
  pill's option and first line, the header title in both places the fork draws it, the body
  `MarkdownBody`, and each option's `aria-label` and rendered label. Upstream has a third title
  render (the title duplicated for a hidden label node); the fork labels the dialog through
  `dialogTitleId` / `aria-labelledby`, so it has two. The `ExtensionCustomPanel` summary wrap came
  in as upstream wrote it.
- **Quote replaced the fork's selection action.** #1502's `quoteSelectionHere` owns the selected
  text; the fork's own "Ask in new chat" selection action (`onAskInNewChat`) is gone, and nothing in
  the tree references `askInNewChat` any more. A selection now offers Quote and Ask-in-side-chat.
- **Icon-only run controls held.** The header cleanup's steer/followUp buttons carry `title` and
  `aria-label` only, so upstream's `chat-composer-run-label` spans were dropped. The fork's
  `composer-card` class was a dead class (no stylesheet defined it); the row now uses upstream's
  `chat-composer-row`.

## Side chats replace the fork's viewer

Upstream deleted `components/SubagentViewer.tsx` and its test and added
`components/SessionChatPanel.tsx` — a full `ChatWindow` for a subagent's or a side chat's session in
the right-panel tab. The deletions applied cleanly (the fork had only token-coloured the viewer), so
there is no `SubagentViewer` anywhere in the tree now, and `docs/agents/subagents.md` talks about
"the child's chat tab" instead of a viewer. Side chats arrived whole from upstream, not from the
fork: `lib/side-session.ts`, `lib/side-chat-client.ts`, `lib/session-fork.ts`'s `createSideSession()`
and #1512's `components/side-tab-state.ts` / `lib/panel-tab-order.ts` are v0.15.0 additions (ADR
0007, #1469/#1512) — absent at the fork's pre-merge `e22bef40`, as `handleDeleteSideChat` was.
`lib/session-family.ts` and `lib/session-tool-selection.ts` existed in both parents, untouched by the
fork, so upstream's edits came in clean. `lib/session-fork.ts` changed in the merge (+55/−9) because
upstream extended it with the side copy, not because the fork reworked it.

## Semantics git could not see

- **`app/api/models/route.test.mjs` fails wherever a provider credential is ambient.** Its exact
  lists (`["acme:one"]`, then `["acme:one","acme:two"]`) hold only when the SDK's `getAvailable()`
  sees no other provider — and on a machine running Claude Code the proxy's `ANTHROPIC_AUTH_TOKEN`
  authenticates `anthropic`, whose whole catalog then rides along. The failure is not a pi 1.5
  semantics change: `getAvailable()` still filters to authenticated providers
  (`pi-ai/dist/models.js` → `getAuthenticatedProviders`), and #1529 is about `enabledModels`
  patterns of a signed-out provider counting as **unmatched** (kept by every toggle, dropped only
  by `prune`), which `docs/agents/models.md` already spells out. Upstream stripped five `*_API_KEY`
  names; the fork's version now strips pi's full key list plus Vertex ADC and the Anthropic proxy
  variables through the new test-only `lib/test-provider-env.mjs`, and keeps upstream's exact
  asserts. Verified both ways: green with the shell's real credentials, green with dummy
  `GEMINI/COPILOT/XIAOMI/MISTRAL` keys and a `GOOGLE_APPLICATION_CREDENTIALS` path exported.
- **Chat only (#804) meets pi 1.5's fallback.** pi 1.5 does not leave a session without a model: an
  unavailable settings default falls back to a model the runtime can answer for. On a machine
  running Claude Code itself, the proxy's `ANTHROPIC_*` variables make `anthropic` authenticated, and
  `lib/rpc-manager-chat-only.integration.test.mjs` saw the prompt quietly run on
  `anthropic/claude-opus-4-8` instead of rejecting. The test now strips pi's full provider-key list
  through `lib/test-provider-env.mjs` (restored in `after()`) and sets `PI_OFFLINE=1`, which is
  where the refusal the fork owns fires again. Worth watching in production: with a fallback
  available, Chat only picks it silently.
- **Two MCP tests died on this machine's process table, not on pi-mcp.** `pi-mcp` 1.5's stdio
  transport terminates correctly: its `close()` closes stdin, then SIGTERMs and finally SIGKILLs the
  **process group** (`detached: true`, `process.kill(-pid, …)`). What failed was the tests'
  `kill(pid, 0)` liveness check: here pid 1 is a `claude` process, not an init, so a killed orphan is
  never reaped and stays a zombie forever, and `kill(pid, 0)` answers true for a zombie. Both files'
  `isRunning()` now reads `ps -o state=` and calls `Z` exited. The same two tests fail on upstream's
  own tree in this environment, and a direct probe showed the signal had in fact landed (state `Z`).
- **Seed commits met a global `commit.gpgsign=true` with no usable key.** `lib/git-changes.test.mjs`,
  the `repo: true` fixture in `lib/subagent-runtime.integration.test.mjs` and the four commits in
  `app/api/git/diff/route.test.mjs` now set `commit.gpgsign=false` locally (or `-c` on the commit),
  as `lib/subagent-runtime.integration.test.mjs` already did for its `git: true` fixture.
- **Test patterns moved to the merged shapes**: `--top-bar-height` cells in
  `components/SessionSidebar.test.mjs` (the search cell and the fit-level New button are square in
  the bar's height now, upstream took them off the literal 36px); the `stripAnsi` forms in
  `components/ChatWindow.extension-request.test.mjs`; `onClick={quoteSelectionHere}` and the
  `isHiddenRelation` gates in `hooks/useAgentSession.test.mjs`; the Agents-beside-More gate and the
  `calc(2 * TOP_BAR_ICON_BUTTON_SIZE)` sheet inset in
  `components/AppShell.mobile-toolbar.test.mjs`. Each is a pattern or slice change: no production
  file was altered to make a test pass.

## SDK jump 1.4.0 → 1.5.0

- `Type` moved out of `@earendil-works/pi-ai` into `typebox`; the only `tsc` error of the merge was
  the fork's own `lib/ask-user-extension.ts` importing it from the old place, fixed to
  `import { Type } from "typebox";` — upstream's own `lib/subagent-extension.ts` already reads it
  that way. That import line is the file's only change in the merge.
- `lib/pi-sdk-internals.ts` took upstream's `McpOAuthSettings.clientRegistration?: "auto" | "dcr" |
  "cimd"` widening (pi 1.5's default is `"auto"`, CIMD when supported).
- Upstream dropped the string patch it used to apply to pi's HTML export (`1fbf6c66`), which pi 1.5
  rewrote to walk the tree iteratively; the export route now fails with "sortChildren expected 1
  match, found 0" if that patch is kept, so it went out as upstream wrote it.
- `pi-mcp`'s stdio termination was read rather than assumed (see the MCP note above).
- Codemode, the tool API types and the session format were not re-audited from scratch this time:
  nothing in the fork imports what the 1.5 notes flag, and the gate that would notice —
  `lib/pi-packages.test.mjs` plus the integration tests against a real `AgentSession` — is green.

## Verification

| Check | Before | After |
| --- | --- | --- |
| `tsc --noEmit` | clean | **clean** (one error mid-merge: the `Type` import) |
| `npm run lint` | clean | **clean** |
| `npm test` (serial, `env -u PI_WEB_PASSWORD`, `--test-concurrency=1`) | not re-measured on the pre-merge tree | **3440 / 3439 pass / 0 fail / 1 skipped** at the merge commit; **3441 / 3440 / 0 / 1** after the review round added the stand-in test |
| `lib/pi-packages.test.mjs` | — | **1 pass / 0 fail** |
| `android/…/gradlew --offline compileDebugKotlin` | rc=0 at v0.13.0 | **not run here** — this box has no `~/Android/Sdk` and no `java`; `android/` was touched by **zero** upstream files, so the compile is a formality, but it still needs running on the machine with the SDK (`ANDROID_HOME=$HOME/Android/Sdk`) |
| `npm run test:e2e` | rc=0, 40 PASS at v0.13.0 | **not run here** — the worktree was prepared (`/tmp/e2e-v0150` at the merge commit — `accda73c` before the re-signing, `f17929eb` now — with `npm ci --include=dev` clean, Chromium headless downloaded) but the browser cannot start on this box: `chrome-headless-shell: error while loading shared libraries: libnspr4.so`. It needs `npx playwright install-deps chromium` as root (also missing: `libnss3`, `libnssutil3`, `libatk-1.0`, `libatk-bridge-2.0`, `libdbus-1`, `libXcomposite`, `libXdamage`). Run it there before publishing |

Environment notes, none of them merge damage:

- The full suite was measured serially for deterministic attribution; the parallel reporter scrambles
  file↔name pairing.
- `env -u PI_WEB_PASSWORD` for every run, as before.
- **Commits here are unsigned**: this box has no GPG secret key (`gpg: no default secret key`), and
  the global `commit.gpgsign=true` makes a plain `git commit` fail. The merge and docs commits were
  made with `-c commit.gpgsign=false`. Before pushing from the signing machine, decide whether to
  keep them unsigned or rebuild the two commits there.
- No `next build` was run; the dev server on :30141 was untouched (it is not running here —
  `.next/dev/lock` is stale from a previous host).

## Fork-only files

`android/` — **zero** changed files, upstream or fork, across the whole range. Verified against the
pre-merge tree (`git diff e22bef40..HEAD`): `lib/ask-user-question.ts`, `lib/ask-user-settings.ts`,
`lib/run-status-stream.ts`, `lib/agent-state-lite.ts`, `lib/session-list-page.ts`,
`lib/word-diff.ts`, `lib/tool-display.ts`, `lib/session-liveness.ts` and the Android routes are
unchanged; `lib/ask-user-extension.ts` differs by its one `Type` import line. Still deleted:
`demo/`, `.github/`, `lib/subagent-input.ts`, and now `components/SubagentViewer.tsx` and its test —
the first deletion upstream itself performed, the second taken here with the docs following it.

## Docs touched

- `AGENTS.md` — File Map and topic notes as resolved in the merge; Pending cleanups: the `.github/`
  entry now covers upstream *editing* both workflows; the CSS token list names `--warning` and
  upstream's `--top-bar-height`.
- `docs/agents/chat-ui.md` — the `stripAnsi` adoption and its two-title-renders difference, the
  icon-only run controls and `chat-composer-row`, Quote beside Ask-in-side-chat, and the memo
  carrying the side-chat divider with upstream's option order.
- `docs/agents/client-platform.md` — a new "One top bar across the app" section for
  `--top-bar-height` / `TOP_BAR_ICON_BUTTON_SIZE` and what the phone toolbar keeps and leaves out;
  the PowerShell bullet now says upstream adopted it as #1508.
- `docs/agents/subagents.md` — two "viewer" wordings retargeted to the child's chat tab.
- `docs/PLAN-upstream-merge-v0.15.0.md`, this report.

Added after the review round, because the tree and these notes disagreed:

- `AGENTS.md` — File Map lines for the merge's live-but-unmapped files (`app/manifest.webmanifest/route.ts`,
  `lib/panel-tab-order.ts`, `components/side-tab-state.ts`, `lib/session-family.ts`,
  `lib/session-tool-selection.ts`) and for the Docker set and `docs/adr/`; the duplicated
  `file-upload-client.ts` / `mcp-override.ts` lines merged into one each; the `.github/` entry
  corrected — `docs/release.md` and `docs/docker.md` *do* describe `publish.yml`, so they now
  describe a pipeline this fork does not ship; `container.yml` (added then removed inside the range)
  recorded; the SystemPromptPanel mention list corrected; the BOM entry's path updated to pi 1.5's
  `dist/extensions/mcp/config.js`.
- `docs/agents/side-sessions.md` — upstream's "narrower than 480px shows icons by a container query"
  is dead CSS here (nothing renders `.chat-composer-run-label`): said so, with a pointer to
  chat-ui.md's icon-only rule.
- `docs/agents/client-platform.md` — the terminal bar does *not* keep hardcoded literals: it reads
  the `.terminal-panel` `--terminal-*` set and its `[data-terminal-theme="light"]` swap, the same set
  xterm takes; the keyboard rule drops padding on `.sidebar-sheet`, not the fixed layer.
- `docs/agents/sessions.md` — `?tail` / `?before` are upstream's own (#509/#555), not fork-only; the
  fork's part is `jsonResponse` and `contextUsage`.
- `docs/agents/chat-ui.md` — `ToolFieldRows` and `getWrittenFileText` live in
  `components/MessageView.tsx`, not in `lib/tool-fields.ts` / `lib/tool-display.ts`.

## Independent review

Seven independent agents ran over this merge: two audits while the merge was still open
(`AGENTS.md` against the tree, `docs/agents/*.md` against the tree), then five reviewers once the
merge commit existed — each got raw facts (parents, conflict list, file names) and no conclusions of
mine, and each checked the tree itself. All were read-only; the ones with tests ran targeted
`node --test` files only.

| Section | Verdict | What it changed here |
| --- | --- | --- |
| Chat surfaces (`chat-ui.md` files) | clean: no fork deviation lost, no upstream chat change dropped; 215/215 targeted tests | nothing |
| AppShell, sidebar, mobile toolbar, side-chat tabs | clean, no functional finding; 425/425 targeted tests. Two minors, both pre-existing fork shape | left as they are: the inner `!isMobile` at `AppShell.tsx:2347` is pinned by the mobile-toolbar test, and upstream's dead `title.*` / `history.*` keys stay with `SystemPromptPanel` |
| Models, providers, the SDK jump | one actionable: my models-route re-aim was **credential-dependent** (`env -u ANTHROPIC_AUTH_TOKEN` failed its added `some(provider === "anthropic")`) and its comment wrong — `getAvailable()` still means authenticated providers | upstream's exact asserts restored; stripping through the new `lib/test-provider-env.mjs` instead; PLAN/REPORT wording corrected |
| Test side (audited both `d128bae4..v0.15.0` upstream test edits and `e22bef40..HEAD` fork-side edits) | every fork deviation still asserted; `SubagentViewer.test.mjs`'s deletion is upstream's own adoption, not a weakening. Two actionable: the strip list was incomplete, and pi 1.5 lets Chat only land on a stand-in model silently | helper's key list extended to pi's whole list (XAI, ZAI, HF, AWS, Vertex ADC…); new test "Chat only starts on a stand-in model when another provider is in scope"; the mobile-toolbar Agents test also gained `disabled={!activeSessionFamily}`, since it pinned the greyed look without pinning the gate |
| i18n completeness | clean: 1791 keys in each of en / zh-CN / zh-TW, identical sets, no duplicates or empty values; all 41 merge-added keys referenced, all 11 removed keys unreferenced; `askUser.*` 28×3, all used; gates green: 9/9 format+registry, 262/262 locale-consuming tests, 21/21 SettingsUi and manifest | nothing |
| `AGENTS.md` against the tree (mid-merge audit) | all 253 mapped paths exist; the duplicate `file-upload-client.ts` / `mcp-override.ts` lines; map gaps for the merge's new files | deduped and extended, see "Docs touched" |
| `docs/agents/*.md` against the tree (mid-merge audit) | the merge's own retargets verified (PowerShell #1508, `--top-bar-height`, subagents' two wordings, side symbols); stale claims named — some introduced by v0.15.0, some older | fixed, see "Docs touched" |

Re-run after the round: serial suite green (3441 / 3440 pass / 0 fail / 1 skipped). The i18n audit
also noted this box's `grep` (ugrep 7.8.4) silently drops lines in `-oE '^\s+"[^"]+"'` pipelines —
locale scans here go through node or `git grep`.

## Leftovers and open decisions

- **Version for release**: npm already has `@agegr/pi-web@0.15.0` (upstream published it), so the
  fork's next published release needs `0.15.1` or a prerelease. `v0.15.0-fork` is annotated on this
  round's last commit, like the earlier `-fork` tags.
- **Chat only with an authenticated fallback** silently switches models (see above). The fork's
  refusal still covers the no-fallback case; whether Chat only should refuse even when another model
  is available is a product choice, not a merge fix.
- **Two upstream assertions are environment-sensitive as written**: the models route's exact lists
  fail in any shell that exports a provider credential (upstream's own tree at `f130910a` failed
  here for exactly that reason), and two MCP tests fail on a host whose pid 1 never reaps orphans.
  Both are fixed in the fork's test files (credential stripping, zombie-aware liveness); upstream
  ships them unchanged, so a later merge may bring the old forms back.
- **Scratch cleaned here**: the `/tmp/e2e-v0150` and `/tmp/wt-v0150` worktrees were removed with
  `git worktree remove --force` (the `node_modules` inside the second was a symlink into the repo,
  so only the link went), the `/tmp/{cw,ci,as,ed}-*` merge stage files,
  `/tmp/{merge-tree-v0150.txt,test-v0150*.log,tsc-v0150.log,fail-names.txt,e2e-v0150.log}`,
  `/tmp/repro-models.mjs` and the `/tmp/{mtest,grp-test,audit-v0150}` directories were deleted, and
  `git worktree list` is back to the one checkout. `/tmp/e2e-v0122` and `/tmp/tmp.hz1vAMW5ZK/wt`
  were already off disk; `git worktree prune` had nothing left to forget.
- **Push is left to Daniele**, as always.
