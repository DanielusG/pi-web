# Plan: merge upstream v0.12.2 → main, preserving fork deviations

Date: 2026-10-16. Approved by Daniele ("proceed") with the three decisions taken as the
recommendations: keep the fork's subagent deletion, full verification depth, take upstream's new UI
as shipped and retune afterwards. The two open questions went unanswered, so the merge takes the
conservative form: **push is left to Daniele** and the pending cleanups are **reported, not fixed**.

## Starting point (verified)

| | |
|---|---|
| fork `main` HEAD | `4d8ebb1` (`refactor(subagents): drop input_files, model, and isolation from the Agent tool`), clean tree |
| merge-base | `f83b1a5` = upstream `v0.12.1` (peeled) — the fork is **756 commits** ahead of it |
| upstream `v0.12.2` (peeled) | `b017cd4` (2026-10-16 `Release v0.12.2`) |
| after the tag | nothing — `upstream/main` **is** the tag, so there is no post-tag commit to skip |
| upstream range | **51 commits**, 181 files, **+8708 −811** |
| overlap | 51 files touched by both sides (the fork's deviation surface against the merge-base is 505 files) |
| dependencies | `package.json` moves **only `version`**; `package-lock.json` 4 lines. No SDK bump → no install step, `lib/pi-packages.test.mjs` untouched |
| dry-run `git merge-tree --write-tree --merge-base=v0.12.1 main v0.12.2` | tree `d801838`: **23 conflicts** — 13 content, 10 modify/delete |
| JS baselines (before merge) | `tsc --noEmit` clean · `npm run lint` clean · `npm test` **2779 / 2776 pass / 2 fail / 1 skipped** |
| the 2 pre-merge failures | both `app/api/git/diff/route.test.mjs`: the fixture's `git commit` inherits the machine's `commit.gpgsign=true` and gpg cannot open `/dev/tty`. Environment, not code — with `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=commit.gpgsign GIT_CONFIG_VALUE_0=false` the suite is **2779 / 2778 / 0 / 1**. Same env for the post-merge run |
| Android baseline | `android/gradlew test --offline --rerun-tasks` rc=0, BUILD SUCCESSFUL, 51 tasks executed |
| dev server | `next-server` pid 465575 listening on :30141 — left running, Daniele restarts it |

## What upstream brings

51 commits, most of them UI and performance. New non-test files: `app/api/cache-miss-notices/route.ts`,
`app/api/sessions/stats/route.ts`, `components/ModelContextWindow.tsx`, `components/TranscriptNotices.tsx`,
`hooks/useTerminalTheme.ts`, `lib/message-keys.ts`, `lib/message-signatures.ts`, `lib/model-context.ts`,
`lib/session-stats-format.ts`, `lib/terminal-theme.ts`, `lib/transcript-notices-server.ts`,
`lib/transcript-notices.ts`, `lib/turn-duration.ts`; deleted `components/ThemeIcon.tsx`; plus
`CONTRIBUTING.md` and `docs/CONTRIBUTING.zh-CN.md` (the docs move skipped at v0.12.1 is inside this
range, so it lands).

- **Subagents**: #1199 sizes `input_files` by the child model's window, #1202 tells the parent where
  an isolated run's worktree went and refuses to resume one whose folder is gone, #1198 keeps a
  never-prompted run findable by writing its session file at dispatch, #1199/#1202 also add
  `worktreeExists` to the result entry. Our HEAD commit deleted exactly the surface #1199 fixes.
- **Session files**: `lib/session-list-scanner.ts` rescan only the appended tail (+513), provider
  signatures omitted from browser payloads, stable message keys, a session name an extension sets
  after its run.
- **UI**: more themes picked by swatch, terminal dark/light toggle, cache/cost notices, turn duration
  and the running turn's elapsed time, agent-switcher time/tokens/cost with a subagent tab toggle,
  folded archive projects with bulk actions, simplified process fold and project menu.
- **Perf**: long expanded thinking rendered in pieces, Settings stops reloading every extension per
  provider/skills request.
- **Files**: drag-and-drop upload with subdirectory targets, a changed file's own repository for its
  diff, more highlighted file types, panel tabs not clipped, pinned line-number gutter, skills'
  relative Markdown references, projects opened in a new tab, worktree form focus.
- **Models**: managed-model context windows editable in Settings, a new session starts on a deferred
  default model, connect attempts get the CLI's two-second window.

## Decisions

1. **Subagent cluster** (Daniele): keep the fork's deletion. `lib/subagent-input.ts` and its test stay
   deleted, the profile owns model/thinking/isolation, the fork's blanket resume-refusal on
   `worktreePath` wins. Port upstream's two orthogonal pieces only — `persistChildSession` (#1198) and
   `worktreeExists` in `recordResult` (#1202). Upstream's #1199 sizing is documented as moot here.
2. **Verification depth** (Daniele): full — JS suite + Android + e2e in a throwaway `/tmp` worktree.
3. **New UI** (Daniele): take upstream's versions as shipped in the auto-merged regions, then retune
   only what the fork's own tokens and tests require; report as fork deviations.
4. **`demo/*` modify/delete**: keep the fork's deletion of the demo app (8 paths).
5. **Push**: left to Daniele. **Pending cleanups** (`lib/worktree.ts` mkdir, `app/settings.css`
   hardcoded colours): report only.

## Step 0 — baselines and backup

Baselines above. `git branch backup/main-pre-v0.12.2` at `4d8ebb1`.

## Step 1 — merge

`git merge --no-commit --no-ff v0.12.2` on `main`, so the tree can be inspected before committing.

## Step 2 — the 10 modify/delete conflicts

`git rm` all of them, keeping the fork's deletions:
`demo/components/MessageView.tsx`, `demo/components/TabBar.tsx`, `demo/hooks/useAudio.ts`,
`demo/lib/message-display.ts`, `demo/lib/subagent-input.ts`, `demo/lib/thinking-expansion-preference.ts`,
`demo/mock/captured/tools.json`, `demo/mock/files.ts`, `lib/subagent-input.ts`, `lib/subagent-input.test.mjs`.

## Step 3 — the 13 content conflicts

| File | Resolution |
|---|---|
| `lib/subagent-runtime.ts` | Fork base (profile owns model/thinking/isolation, no `input_files`, blanket resume-refusal). Port `persistChildSession` and `worktreeExists` in `recordResult`; drop `expectedSubagentModel`, `subagentInputLimits`, `loadSubagentInputFiles`, `appendSubagentInputFiles` |
| `lib/subagent-extension.ts` | Fork's Agent schema (no `input_files`/`model`/`isolation`); upstream's text only where it is not one of those three |
| `lib/subagent-runtime.integration.test.mjs` | Fork's cases + upstream's read-back-from-file case, adapted to the fork's refusal rule |
| `lib/subagent-extension.test.mjs` | Fork's stale-option cases + upstream's cases minus the `input_files` sizing ones |
| `app/api/sessions/[id]/context/route.ts` | Union: upstream's `readSessionTranscriptNotices` + `attachTranscriptNotices` around `buildSessionContext`, plus the fork's `jsonResponse` and `contextUsage` (skipped on `before` pages). Android reads both shapes |
| `components/AppShell.tsx` | Upstream's theme swatches, terminal theme, agent switcher, notices — on the fork's header cleanup (no `systemPrompt` state), tab handling, inline dialog slot |
| `components/ChatWindow.tsx` | Upstream's notices + turn duration + thinking pieces — on the fork's inline extension-dialog composer slot, scroll rules, written-files row |
| `components/MessageView.tsx` | Upstream's notices/turn-duration/elapsed rendering — on the fork's user bubble and tool header geometry, edit-preview and tool summary line, thinking preview |
| `components/FileExplorer.tsx` | Upstream's drag-drop upload targets + changed-file repo lookup — on the fork's sidebar file section and tree |
| `hooks/useAgentSession.ts` | Upstream's stable message keys, turn duration, extension-set session name — on the fork's fork/navigate, reconciliation, slash commands |
| `components/SidebarMenu.test.mjs` | Both sides' assertions, keeping the fork's token-form checks |
| `AGENTS.md` | Union by hand: upstream's new sections beside the fork's file map, topic notes and pending cleanups; the `next dev` generated block stays out of the commit |
| `docs/agents/subagents.md` | The fork's rewrite from `4d8ebb1` wins, plus one line that upstream's #1199 sizing is moot here |

## Step 4 — semantics git cannot see (auto-merged is not the same as correct)

- **CSS**: `app/globals.css` (+328), `app/settings.css` (+134), `app/sidebar.css` (+52) auto-merge with
  upstream's theme blocks; the fork retuned these files to `--danger`/`--radius-*`/`--shadow-*`/`--ease`.
  Grep the merged files for reintroduced hardcoded colours and shadows, then run the four suites that
  assert the token forms (`SessionTree`, `SidebarMenu`, `SessionSidebar`, `ProjectWorktreePicker`).
- **Wire vs fork-only routes**: `lib/message-signatures.ts` + `lib/agent-event-wire.ts` strip signatures
  from payloads; check against the fork's `?lite=1` (`lib/agent-state-lite.ts`) and `?toolUpdates=tail`.
  New `/api/sessions/stats` and `/api/cache-miss-notices` must stay reachable under `proxy.ts`'s
  password gating.
- **Session list**: `lib/session-list-scanner.ts`'s tail rescan against the fork's
  `lib/session-list-page.ts` paging and `getSessionListVersion` path.
- **i18n ×3**: the 45 new keys land in `en`, `zh-CN`, `zh-TW` with placeholder parity.
- **Safari 16.2**: no lookbehind is added anywhere in the range (verified against the diff before the
  merge); re-check after conflict resolution.
- **Android**: nothing in the range should move the SSE wire, the paged session list, `?lite=1`,
  `?toolUpdates=tail`, `/api/agent/running/events`, `/api/sessions/[id]/subagents` or
  `waitingSessionIds`; verify and write the "semantics the Android client should expect" section.

## Step 5 — verification gate (must pass before the commit)

`tsc --noEmit` → `npm run lint` → `npm test` (same gpg env override as the baseline) →
`android/gradlew test --offline --rerun-tasks` → `npm run test:e2e` in a `git worktree` under `/tmp`
with `npm ci --include=dev`, so :30141 and its `.next/dev/lock` stay untouched. **Never `next build`**
in this checkout. Upstream adds `e2e/model-context.mjs` and `e2e/project-navigation.mjs`; the fork's
inline-dialog sizing adaptations may need follow-up — reported, not hidden.

## Step 6 — docs

- `AGENTS.md`: the 13 new non-test files into the file map, the topic notes updated
  (`docs/agents/{sessions,chat-ui,files-and-access,client-platform,settings-ui,subagents}.md`),
  pending cleanups kept.
- `docs/agents/*`: fold upstream's notes into the fork's topic files where they describe the same code.
- `docs/REPORT-upstream-merge-v0.12.2.md`: conflicts, semantic checks, verification table, Android
  semantics, leftovers.

## Step 7 — close

Commit the merge (`commit.gpgsign=true`, key `96FBC4F2284E3272`; unlock the key or `--no-gpg-sign`),
tag `v0.12.2-fork`, commit this plan and the report with the merge. **Push left to Daniele.**

## Risks

1. **The subagent cluster** — both sides rewrote `lib/subagent-runtime.ts` and the Agent schema in
   opposite directions; a careless hunk merge reintroduces the per-call options `4d8ebb1` deleted.
2. **The three big components** — `ChatWindow.tsx` (fork ±1014 since the merge-base), `MessageView.tsx`
   (fork ±524), `AppShell.tsx` (fork ±340) against upstream's notices, turn duration and switcher.
   Re-check whole files, not only the conflict hunks.
3. **CSS auto-merge** — clean merge, possibly non-fork look; the four CSS-asserting suites are the net.
4. **`lib/session-list-scanner.ts`** rewrite under the fork's paging and SSE additions.
5. **The merge commit's own `git commit`** needs the signing key unlocked, same as at v0.12.1.
