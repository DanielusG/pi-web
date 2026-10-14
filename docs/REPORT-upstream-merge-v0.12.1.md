# Report: upstream v0.12.1 merged into main

Merged on 2026-10-14 with `git merge --no-commit --no-ff v0.12.1` (upstream `agegr/pi-web`, tag
`v0.12.1` → `f83b1a5`), commit `0569ffd`. Plan: `docs/PLAN-upstream-merge-v0.12.1.md`. Backup
branch: `backup/main-pre-v0.12.1`. Fork tag: `v0.12.1-fork` on the merge commit.

Upstream range v0.12.0 → v0.12.1: **2 commits**, 18 files, +143 −572 (541 of the deletions are
`package-lock.json` dedup). The fork was 752 commits ahead of `v0.12.0` (`894e1a2`), the merge-base.
Unlike v0.12.0, this one **is a dependency upgrade**: pi SDK `1.1.0` → `1.2.0`, and every
`@earendil-works/*` package in the tree becomes a direct dependency pinned exactly.

`upstream/main` carries one commit after the tag (`7cc1803` docs: a CONTRIBUTING guide, READMEs
moved into `docs/`). Skipped, per the plan — the fork's README edits are one line each, and a
restructure of upstream's docs tree is not part of a patch merge.

## Conflicts and how they went

`git merge-tree` predicted 2 conflicts before the merge and the real merge matched it exactly — both
content, both in the dependency files. Nothing else conflicted, and **none of the MCP files the
range touches had fork changes** since v0.12.0, so upstream's adaptation landed clean.

| File | Resolution |
|---|---|
| `package.json` | hand-merged union: upstream's seven pi deps pinned at `1.2.0` (three new: `pi-codemode`, `pi-mcp`, `pi-telemetry`) and `version: 0.12.1`, with the fork's `@fontsource-variable/geist ^5.3.0`, `ansi_up ^6.0.6`, `diff ^8.0.4` in their alphabetical places and the fork's `allowScripts` block |
| `package-lock.json` | upstream's taken, then `npm install` rewrote it (Daniele's choice) — 21 root dependencies against upstream's 18 |

## What the install did, verified after the fact

- `@earendil-works/chord` **pruned** from `node_modules` and the lock: pi 1.2 dropped it from the
  graph (`pi-coding-agent` 1.2.0 and `pi-tui` 1.2.0 do not require it) and nothing in the fork
  references it.
- `diff` hoists to `node_modules/diff` at `8.0.4` and **shares that one copy** with
  `pi-coding-agent`, which requires `diff` at exactly `8.0.4`. Upstream's own lock nests it
  (`pi-coding-agent/node_modules/diff`) because upstream's root has none. The fork's `^8.0.4`
  resolves to the same version, so `lib/word-diff.ts` sees no change.
- `node-pty` stayed `1.2.0-beta.15` → no native rebuild, and the `allowScripts` entry is still
  correct. `npm install` ran `bin/prepare-terminal.js` through the `postinstall` script on its own.
- Three packages had install scripts **blocked** for not being in `allowScripts`: `@google/genai`
  (a no-op `preinstall`), `protobufjs` (`postinstall: node scripts/postinstall`) and `unrs-resolver`
  (a `napi-postinstall` check). `lib/mcp-*` and lint ran green with them blocked, so nothing was
  approved; `npm install-scripts ls` lists them if that ever needs revisiting.
- `npm audit` reported 13 vulnerabilities (5 low, 3 moderate, 5 high). Not compared against the
  pre-merge tree — pi 1.2 pulls more of pi-ai's provider SDKs than 1.1 did.

## How the SDK upgrade was checked

`tsc` cannot see the non-exported modules the fork loads by file URL, so each was imported against
the installed 1.2.0 tree and its exports listed:

| Fork file | Path loaded | Found in 1.2.0 |
|---|---|---|
| `lib/pi-sdk-internals.ts` | `dist/extensions/mcp/index.js` | `createMcpExtension` |
| | `dist/extensions/mcp/runtime.js` | `McpServerConnection`, `createDefaultTransport` (also `McpOAuthCredentialStore`, `McpSignInCancelledError`, `signInMcpServer`) |
| | `dist/extensions/mcp/config.js` | `loadMcpConfig`, `add/update/removeMcpServerConfig`, `getMcpToolExposure` |
| | `dist/extensions/mcp/oauth.js` | `signInMcpServer`, `McpOAuthCredentialStore`, `McpSignInCancelledError`, `createMcpAuthProvider` |
| | `dist/core/mcp-servers.js` | `validateMcpServerConfig`, `getMcpToolExposure`, plus the new `LIST_MCP_RESOURCES_TOOL` / `READ_MCP_RESOURCE_TOOL` names |
| | `dist/core/resolve-config-value.js` | `resolveConfigValueOrThrow`, `resolveHeadersOrThrow`, `getConfigValueEnvVarNames`, `isCommandConfigValue` |
| `lib/project-trust.ts` | `dist/core/trust-manager.js` | `hasTrustRequiringProjectResources`; `TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES` is still module-private but its eight entries are unchanged, which is what `lib/project-trust.test.mjs` compares against the source text |
| `lib/jsonc.ts` | `dist/utils/json.js` | `stripJsonComments` — the fork's own `stripJsonComments` extends it and is not a runtime load |
| `lib/mcp-import-cli.ts` | `dist/extensions/mcp/cli.js` | `runMcpCommand` only; `parseOptions` / `parsePairs` / `add` are still module-private, which is why the fork ports them |

Root exports the fork imports statically: `createAgentSessionServices`, `createAgentSessionFromServices`,
`SessionManager`, `ModelRegistry` (with `getApiKeyForProvider`), `getPackageDir`, `createMcpExtension`
— all present. `createModelRuntimeWithExtensions` is **not** an SDK export and never was: it is the
fork's own `lib/model-runtime.ts` wrapper over `createAgentSessionServices`.

Static named imports across `app/`, `components/`, `hooks/` and `lib/` are what `tsc --noEmit`
validates against the packages' declarations, so a throwaway script that re-checked them was dropped
rather than run — `tsc` clean is the same answer.

### Deviation from the plan: no `lib/pi-sdk-paths.test.mjs`

The plan's open question 1 was to turn the path check into fork-only coverage. While gathering
context for it I found the coverage **already exists**: `lib/pi-sdk-internals.test.mjs` loads every
MCP internal from the SDK pi-web runs and pins the shapes of the config, validation and value
helpers, and `lib/project-trust.test.mjs` compares the vendored trust list against the SDK's own.
Adding a second file would have restated them, against the fork's own practice and upstream's
`f364bc3` direction. Both suites were run against 1.2.0 and pass, so the check is covered without a
new file.

## Verification

| Check | Before merge | After merge |
|---|---|---|
| `tsc --noEmit` | clean | clean |
| `npm run lint` | clean | clean |
| `npm test` | 2775 tests, 2774 pass, 0 fail, 1 skipped | **2778 tests, 2777 pass, 0 fail, 1 skipped** (+3, counted: `lib/pi-packages.test.mjs` one test, `lib/mcp-host.integration.test.mjs` and `lib/mcp-test.test.mjs` one each; `components/McpConfig.test.mjs` and `lib/mcp-host.test.mjs` stayed at 52) |
| SDK-facing suites, run apart | — | `lib/pi-packages`, `lib/pi-sdk-internals`, `lib/project-trust`, `lib/mcp-host`, `lib/mcp-test`: 107 pass · integration `lib/mcp-host`, `lib/mcp-test`, `lib/subagent-mcp`, `lib/mcp-sign-in`: 31 pass |
| `android/gradlew test --offline` | rc=0 | rc=0, `BUILD SUCCESSFUL`, 51 tasks **executed** (`--rerun-tasks`, so not the Gradle cache) |
| `npm run test:e2e` | — | **27 checks PASS**, no failures, in a `git worktree` at `0569ffd` under `/tmp` with `npm ci --include=dev`; removed afterwards |
| dev server on :30141 | answering | **untouched**, still listening (pid 650475) — still running the **1.1.0** SDK in memory; Daniele restarts it |

The old `lib/web-auth.test.mjs` environment failure did not reproduce in this shell either before or
after, so the suite is at 0 failures. `test-results/e2e/` held only the evidence screenshots the
checks save on their success paths — no `failure.png`, no trace.

## Semantics the Android client should expect

Nothing in the range touches the SSE wire, the paged session list, `?lite=1`, `?toolUpdates=tail`,
`/api/agent/running/events`, `/api/sessions/[id]/subagents` or `waitingSessionIds`. One API type
changed shape: `McpTestResult.resources` went from `{ resources?: number, resourceTemplates?:
number }` to `resources?: true` (`lib/api-types.ts`). Settings › MCP is web-only and no Kotlin reads
that field, so the app is unaffected.

## Fork-only files

`git diff --name-only backup/main-pre-v0.12.1 HEAD` lists 21 files: the 18 upstream files, the plan,
and the two docs corrections below. **No fork-only file moved** — `lib/session-list-page.ts`,
`lib/run-status-stream.ts`, `lib/agent-state-lite.ts`, `lib/cold-context.ts`, `lib/edit-preview.ts`,
`lib/tool-display.ts`, `lib/extension-dialog-fit.ts`, `lib/session-fork*.ts`, `lib/session-delete.ts`,
`lib/word-diff.ts` and `app/api/agent/**` are byte-identical to the pre-merge branch. No conflict
markers anywhere in the tracked tree.

Safari 16.2: **zero lookbehinds** were added anywhere in the range, so no blank-page risk.

## Docs touched here

- `AGENTS.md`: upstream's new **Upstream: upgrading pi** section landed above the `---`, its own
  `### Dev server troubleshooting` bullets untouched, the fork's file map, topic notes, pending
  cleanups, CSS/Safari sections intact. The `next dev` generated block stayed out of the commit.
- `docs/agents/mcp-runtime.md`: the host's readiness bullet now says pi 1.2's watch waits on
  `tools/list` alone, because the SDK asks for resources only when a resource tool does. Upstream
  updated only `docs/agents/mcp-test-sign-in.md`, which landed clean.
- `e2e/README.md`: its claim that `npm config get omit` is `dev` on this machine went stale — it
  reads empty under npm 12.0.2, so a plain `npm ci` installs Playwright here. Reworded to keep the
  flag as the portable form rather than a machine requirement.

## Leftovers

- **Restart :30141** to run the 1.2.0 SDK: stop pid 650475 gracefully, move `.next` to a
  `mktemp -d` backup, `npm run dev` (AGENTS.md's order). Until then the running app serves 1.1.0
  against a 1.2.0 `node_modules`.
- The fork did **not** need `npm install --save-exact`, which `AGENTS.md`'s new section advises: the
  exact versions were already in `package.json` from upstream's file, so a plain `npm install` reused
  them for the SDK's `^` ranges. The advice matters when *bumping* pi by hand, not when taking an
  upstream bump.
- `docs/agents/tools.md` still describes the codemode built-in against pi 1.1's surface; nothing in
  the range contradicted it, but the next codemode-facing change should re-read it against 1.2.
- Unchanged in `AGENTS.md`'s pending cleanups: the Android refused-path message in `NewSessionSheet`,
  dead `components/SystemPromptPanel.tsx`, `app/settings.css` still hardcoded.
