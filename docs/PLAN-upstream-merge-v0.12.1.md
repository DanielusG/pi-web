# Plan: merge upstream v0.12.1 → main, preserving fork deviations

Date: 2026-10-14. Approved by Daniele before any edit ("procedi"), with the three open questions
taken as the recommendations below: add `lib/pi-sdk-paths.test.mjs`, push at the end, skip the
commit after the tag.

## Starting point (verified)

| | |
|---|---|
| fork `main` HEAD | `54dd79c` (`fix(android): start the subagents bar collapsed`) |
| merge-base | `894e1a2` = upstream `v0.12.0` (peeled) — the fork is **752 commits** ahead of it |
| upstream `v0.12.1` (peeled) | `f83b1a5` |
| upstream range | **2 commits**, 18 files, +143 −572 (541 of the deletions are `package-lock.json` dedup) |
| commits in range | `35067b6` `chore(deps)`: pi SDK 1.1.0 → 1.2.0 + pin every pi package · `f83b1a5` Release v0.12.1 |
| after the tag | `7cc1803` `docs`: CONTRIBUTING guide + `README.{ja,ru,zh-CN}.md` moved into `docs/` — **out of scope** |
| dry-run `git merge-tree --write-tree --merge-base=v0.12.0 main v0.12.1` | tree `08dba65`: **2 conflicts**, both content — `package.json`, `package-lock.json`. Everything else auto-merges |
| fork-modified files inside the range | `AGENTS.md`, `lib/i18n/messages/{en,zh-CN,zh-TW}.ts`, `package.json`, `package-lock.json`. **None of the MCP files** (`lib/mcp-host.ts`, `lib/mcp-test.ts`, `lib/api-types.ts`, `components/McpConfig.tsx`, `lib/pi-sdk-internals.ts`) — upstream's adaptation lands clean |
| JS baselines (before merge) | `tsc --noEmit` clean · `npm run lint` clean · `npm test` **2775 tests, 2774 pass, 0 fail, 1 skipped** (the old `lib/web-auth.test.mjs` environment failure does not reproduce in this shell) |
| dev server | `next-server` pid 650475 listening on :30141 with **pi 1.1.0 loaded in memory**; Daniele restarts it after the merge |

## What upstream brings

This is the first merge here with a **real SDK upgrade**, so the risk is not git but the runtime.

- **pi SDK 1.1.0 → 1.2.0**, and every `@earendil-works/*` package in the tree becomes a direct
  dependency pinned exactly: `pi-agent-core`, `pi-ai`, `pi-codemode` (new), `pi-coding-agent`,
  `pi-mcp` (new), `pi-telemetry` (new), `pi-tui`. Reason: pi's packages depend on each other with
  `^` ranges and the published pi-web ships no lockfile, so 0.12.0's four pins let an install pair
  `pi-coding-agent` 1.1.0 with `pi-codemode` 1.2.0, which it cannot load.
- New `lib/pi-packages.test.mjs` fails on any `@earendil-works/*` package in the lock that is not a
  direct dependency at its locked version, or that appears twice in the tree.
- **The code changes are all one thing**: pi 1.2 lists MCP resources only when a resource tool asks.
  `lib/mcp-host.ts` `LIST_METHODS` narrows to `tools/list`; `lib/pi-sdk-internals.ts` drops
  `McpResource` / `McpResourceTemplate` / `McpContentAnnotations` and the connection's `resources` /
  `resourceTemplates` arrays (keeps `hasResources`); `lib/mcp-test.ts` → `lib/api-types.ts` →
  `components/McpConfig.tsx` + i18n turn the count line into "Offers resources."; the MCP tests and
  `lib/__fixtures__/mcp-env-server.mjs` follow.
- `AGENTS.md` gains an "Upstream: upgrading pi" section; `docs/agents/mcp-test-sign-in.md` gets the
  matching note.

## Decisions

1. **Lockfile**: hand-merge `package.json`, then `npm install` regenerates `package-lock.json`
   (Daniele) — guarantees `lib/pi-packages.test.mjs` passes and no nested pi copies remain.
2. **Verification depth**: full (Daniele) — JS suite + the SDK path check + Android + e2e in a
   throwaway worktree.
3. **Dev server**: left running on :30141 (Daniele restarts it). Replacing `node_modules` under it
   may glitch it mid-merge; that is expected and not a merge regression.
4. **`lib/pi-sdk-paths.test.mjs` added** — the one-off path check becomes fork-only coverage, so a
   future pi bump fails in `npm test` instead of at runtime.
5. **`upstream/main` after the tag** (`7cc1803`): skipped. The fork's README edits are one line each.

## Step 0 — backup

`git branch backup/main-pre-v0.12.1` (created) + this plan, committed with the merge as at v0.12.0.

## Step 1 — merge

`git merge --no-commit --no-ff v0.12.1` on `main`, so the tree can be inspected before committing.

## Step 2 — the two conflicts

| # | File | Resolution |
|---|---|---|
| 1 | `package.json` | union by hand: upstream's seven pinned pi deps at `1.2.0`, `version: 0.12.1`, plus the fork's `@fontsource-variable/geist ^5.3.0`, `ansi_up ^6.0.6`, `diff ^8.0.4` in their alphabetical places and the fork's `allowScripts` block |
| 2 | `package-lock.json` | take theirs, then `npm install` rewrites it |

Expected install deltas, verified rather than assumed:

- `@earendil-works/chord` is **pruned** — pi 1.2 dropped it from the graph (`pi-coding-agent` 1.2.0
  and `pi-tui` 1.2.0 do not require it) and nothing in the fork references it (grepped).
- `diff` hoists to `node_modules/diff` and shares one copy with `pi-coding-agent`'s exact `8.0.4`;
  the fork's `^8.0.4` resolves to the same version, so `lib/word-diff.ts` is unaffected. Upstream's
  own lock nests it (`pi-coding-agent/node_modules/diff`) because upstream's root has none.
- `node-pty` stays `1.2.0-beta.15` → no native rebuild, `allowScripts` entry still correct.

## Step 3 — SDK path check (what `tsc` cannot catch)

The fork loads non-exported pi modules by path. After install, import each module and confirm it and
its named exports still exist:

| Fork file | Path loaded | Exports the fork uses |
|---|---|---|
| `lib/pi-sdk-internals.ts` | `dist/extensions/mcp/{index,runtime,config,oauth}.js`, `dist/core/mcp-servers.js`, `dist/core/resolve-config-value.js` | `createMcpConnection`-side surfaces, `getMcpToolExposure`, `signInMcpServer`, `loadMcpConfig`, `resolveConfigValue` |
| `lib/project-trust.ts` | `dist/core/trust-manager.js` | `hasTrustRequiringProjectResources` |
| `lib/mcp-config-file.ts` | `dist/extensions/mcp/config.js` | `add/update/removeMcpServerConfig` |
| `lib/mcp-import-cli.ts` | `dist/extensions/mcp/cli.js` | the login/config helpers |
| `lib/jsonc.ts` | `dist/utils/json.js` | `stripJsonComments` |

Plus the public surfaces the fork uses: `createAgentSessionFromServices`, `SessionManager` helpers,
`createModelRuntimeWithExtensions`, `ModelRegistry.getApiKeyForProvider`. Whatever the check finds
goes into the report; a missing path is a stop-and-discuss, not a silent workaround.

## Step 4 — verification gate (must pass before any push)

`tsc --noEmit` → `npm run lint` → `npm test` (baseline 2775 / 2774 / 0 / 1, plus the new
`lib/pi-packages.test.mjs` and `lib/pi-sdk-paths.test.mjs`) → `android/gradlew test --offline` →
`npm run test:e2e` in a `git worktree` under `/tmp` with `npm ci --include=dev` and
`bin/prepare-terminal.js` run by hand, so :30141 and its `.next/dev/lock` stay untouched. **Never
`next build`** in this checkout. Record e2e in the report.

Note: `e2e/README.md` says `npm config get omit` is `dev` on this machine; it reads empty now
(npm 12.0.2), so `--include=dev` is harmless rather than required. Verify and correct the README if
the claim is stale.

## Step 5 — docs

- `AGENTS.md`: verify the auto-merge put upstream's "Upstream: upgrading pi" section cleanly beside
  the fork's sections and kept the fork's file map, topic-note links and pending cleanups; keep the
  `next dev` generated block out of the commit.
- `docs/agents/mcp-runtime.md`: one clause — pi 1.2 lists resources only when a resource tool asks,
  so `LIST_METHODS` is `tools/list` only. Upstream updated only `docs/agents/mcp-test-sign-in.md`,
  which lands clean.
- i18n ×3: verify `mcp.test.resources` lost its placeholder consumers in lockstep.

## Step 6 — close

Commit the merge (gpg `96FBC4F2284E3272`, `commit.gpgsign=true`; unlock or `--no-gpg-sign`), tag
`v0.12.1-fork`, write `docs/REPORT-upstream-merge-v0.12.1.md`, push `origin main` and the tag. Only
this plan and the report get staged among the untracked `docs/` files.

## Risks

1. **The fork's non-exported SDK loads** — a renamed dist path or export fails at runtime, not at
   compile time. Step 3 and the new test are the defence.
2. **Behaviour changes inside pi 1.2** beyond the resources rule upstream adapted for: the fork's
   `lib/rpc-manager.ts`, `lib/builtin-extensions.ts` (codemode), `lib/subagent-*.ts` and
   `lib/session-reader.ts` lean on SDK surfaces. The integration tests in `npm test`
   (`lib/mcp-host.integration.test.mjs`, `lib/subagent-mcp.integration.test.mjs`) spawn real
   fixtures and are the closest thing to a runtime smoke here.
3. **`npm install` over a live dev server** may glitch :30141 mid-merge; expected, and not to be
   chased as a merge failure.
4. **`lib/pi-packages.test.mjs` reads the lockfile**, so it only passes once `npm install` has
   rewritten it — resolve `package.json` first, install, then run the suite.
