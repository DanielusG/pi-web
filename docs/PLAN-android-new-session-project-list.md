# New-session sheet: every project, with a filter

## Problem

The Android "New session" sheet showed only a short list of directories, with no way to reach the
others.

## Diagnosis

The limit was server-side, not in the app:

- `lib/session-list-page.ts` — `RECENT_CWDS_LIMIT = 12` cuts `recentCwds` to 12 distinct session cwds,
  newest activity first.
- `app/api/sessions/route.ts` sends `recentCwds` only in the `perProject` branch, so the web never
  receives it (its sidebar calls `/api/sessions` without `perProject`, and its cwd picker browses the
  filesystem through `/api/cwd/browse`).
- The app rendered the field verbatim (`SessionsScreen.kt` `NewSessionSheet`, `SessionsViewModel.kt`
  parsed `recentCwds` into state) — no app-side filter, and the sheet did scroll.

Measured on this machine: 151 distinct cwds over 2 439 top-level sessions, 12 shown.

Key finding: `pageSessionsByProject` windows only the sessions **inside** each group, so the response
already carries **every** project (`key`, `root`, `total`, `modified`, `recent`) — including projects
with `recent: 0`, which the list folds under "Older projects". `recentCwds` is therefore redundant
for a project picker, and building one from `projects` costs no extra bytes and no server work.

Call frequency of `/api/sessions` from the app: no polling — once when the session list is visible,
again whenever the `/api/agent/running/events` SSE pushes a changed `sessionListVersion`, on
pull-to-refresh (`force=1`), and after rename/delete. Server-side `listAllSessions()` is cached for
30 s with generation dedupe and in-flight coalescing, so a reload inside the window costs no disk
scan; only `force=1` forces a rescan.

## Decisions

- **Source of the list:** project roots from `state.groups` only (one row per project, not one per
  session cwd). Subdirectory cwds of a repository collapse into its git root — the typed path field
  covers those cases.
- **Filter:** yes, by name or path — with 151 projects a complete list is still hard to scroll.
- **Server:** unchanged. `recentCwds` keeps being emitted at 12 so installs predating this sheet keep
  their quick picks; the app ignores the field.

## Changes

1. `android/app/src/main/java/app/pimobile/ui/sessions/SessionsScreen.kt`
   - `NewSessionSheet(projects: List<ProjectGroup>, loading: Boolean, …)` instead of `recentCwds`;
     the call site passes `state.groups`
   - header, filter field and the typed-path row pinned; project rows in a `LazyColumn` bounded at
     420 dp — only the rows scroll
   - one row per project: folder icon, `baseName(root)`, `shortPath(root)`, `total` session count;
     tap → `onStart(group.root)` (server path, no validation round-trip)
   - filter: trimmed case-insensitive `contains` on basename, full path and `~`-shortened path;
     blank query = all; clear button when non-empty
   - rows for loading, "no project matches", and "no project yet"
2. `android/app/src/main/java/app/pimobile/ui/sessions/SessionsViewModel.kt` — `recentCwds` dropped
   from `SessionsUiState` and from the response parsing (dead field)
3. `android/README.md`, `docs/agents/android.md` — what the sheet lists now, and why `recentCwds`
   stays on the wire

## Verification

`./gradlew test` (155 tests, 0 failures) and `./gradlew assembleRelease` (APK built).

## Follow-up: sheet layout fixes (checked on the phone)

The first on-device look at the sheet showed four problems, all fixed:

1. **The two text fields were tall.** M3 lets a placeholder wrap even in a `singleLine` field, so
   "Filter projects by name or path" and "Other directory, e.g. ~/projects/app" ran to two lines
   (`uiautomator`: the placeholder node was 156 px = 52 dp). Placeholders are now short and styled
   on the placeholder `Text` (this Compose version has no `placeholderTextStyle` argument): the
   field is one line, 56 dp.
2. **With the keyboard open the typed-path row was gone** — no `Start` node in the hierarchy, the
   sheet's content column is not scrollable so anything past the available height is drawn under the
   keyboard. The sheet now opens fully expanded
   (`sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`, no
   `skipPartiallyExpanded` argument in this version) and the list is weighted, so the list shrinks
   and the row stays above the keyboard.
3. **The session count touched long paths** (`…/@agegr/pi-web` ended at x=963 where the number
   started) — an 8 dp gap before the count, as `ProjectGroupView` has.
4. **`AssistProjectSheet` had the same wrapping placeholder** — same fix.

Plus: the list box used to stay full height with one match, showing a big empty rectangle; the
`LazyColumn` is now `weight(1f, fill = false)` inside a weighted column, so it fills the space with
many matches and hugs its rows with few.

Measured on the OnePlus (480 dpi, system font scale 1.0, app chat font size 90 %): filter field and
the typed-path row both 54 px text height = one line; `Start` visible at y=1332 with the keyboard
open and at y=2181 without; the filter narrowed 151 projects to 5 for "pi-web" and to 1 for
"y-bench"; "No project matches "…"" shown for a query with no hit; the clear button empties the
query and the full list returns; tapping a row opens the chat for that cwd without creating a session
file (2 475 before and after). No crash in `logcat`.
