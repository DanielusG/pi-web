# Android client and its fork-only server routes

The native client (`android/`, Kotlin + Jetpack Compose) is a fork addition: a standalone app that
talks to the same HTTP + SSE API. `android/README.md` is the source of truth for its features, build
and install steps. The web client is unchanged by these routes — every fork-only parameter is
opt-in, so an older server simply ignores it and a newer one behaves as before without them.

## Running-state stream (`lib/run-status-stream.ts`)
- `GET /api/agent/running/events` sends the `/api/agent/running` snapshot plus `waitingSessionIds`
  (sessions with an open select/confirm/input/editor dialog) on connect, then again **only when it
  changes**, detected by an in-memory check once a second. The app does not poll; the web client
  keeps its own 2.5 s polling of `/api/agent/running`.
- The app refuses to start against a server without this route, so keep it when merging upstream.

## Per-request trimming for runs
- `GET /api/agent/[id]?lite=1` (`lib/agent-state-lite.ts`) leaves out `systemPrompt`: it carries
  every context file (43 KB on this repository), the app never shows it, and it reconciles around
  every turn.
- `GET /api/agent/[id]/events?toolUpdates=tail` (`ClientAgentEventOptions` in
  `lib/agent-event-wire.ts`) cuts the text of each `tool_execution_update` to its last 16 lines,
  8000 characters at most: bash resends its whole accumulated output with every update (3.7 MB for
  one verbose command). `tool_execution_end` still carries the complete result, so the app's tool
  rows stay correct — its `ChatViewModel` reads only `toolCallId` / `toolName` from the end event.
  The tail is applied inside upstream's `toClientToolExecutionEvent()` after the nested-call drop,
  the codemode cap and the coalescing, so upstream's slimming rules and the tail compose.

## Material 3 in this app (`compose-bom 2025.10.01`)
- `OutlinedTextField(singleLine = true)` constrains the typed value only: a long placeholder still
  wraps and makes the field two lines tall. Keep placeholders short and give the placeholder `Text`
  its style — this version has no `placeholderTextStyle` argument.
- `ModalBottomSheet` has no `skipPartiallyExpanded` argument: pass
  `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`. A sheet whose footer
  must stay above the keyboard needs the list weighted (`weight(1f, fill = false)` so it shrinks to
  its rows when few match), not a fixed `heightIn` — a fixed height gets clipped once the keyboard
  takes its space.

## Session list paging (`lib/session-list-page.ts`)
A response of ~7 MB on a machine with 2 000 sessions, most of it first messages.
- `GET /api/sessions?perProject=N` answers with `projects` (key, root, total, most recent activity,
  and a window of N top-level sessions each; subagent runs are left out) plus `recentCwds`, instead
  of `sessions`. `project=<key>&offset=K` pages one project.
- Every project comes back in one response, only the session windows are cut — so the app's
  new-session sheet lists `projects` roots with its own filter (`SessionsScreen.kt`) and needs no
  extra request. `recentCwds` is capped at 12 distinct session cwds and is what installs before the
  full-project sheet picked from: keep emitting it while such an APK is still in use.
- `recentHours=H` stops each first window at the sessions active in the last H hours (server clock)
  and adds their count as `recent`, which the app shows by default. Projects with `recent: 0` still
  come back, with an empty window, and the app folds them under "Older projects".
- `ids=a,b` keeps only those sessions (notification titles). `firstMessageChars=N` shortens first
  messages after collapsing a skill expansion to its `/skill:` command, which only works on the
  complete text.
- The app treats a response without `projects` as an outdated server.

## Subagent runs
`GET /api/sessions/[id]/subagents` lists a session's subagent runs for the app's session view.

## Extension dialog mirrors the fork's web layout
The app renders the extension dialog **inline in the composer slot**, replacing the input bar — not
upstream's non-blocking overlay (`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`).
See [chat-ui.md](chat-ui.md). Do not "fix" either side back to the upstream overlay when merging.

## Tests
`android/` has its own Gradle test task (105 tests on the v0.9.3 baseline). Run it after changing a
route contract the app depends on.
