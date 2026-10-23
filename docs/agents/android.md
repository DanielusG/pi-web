# Android client and its fork-only server routes

The native client (`android/`, Kotlin + Jetpack Compose) is a fork addition: a standalone app that
talks to the same HTTP + SSE API. `android/README.md` is the source of truth for its features, build
and install steps. The web client is unchanged by these routes — every fork-only parameter is
opt-in, so an older server simply ignores it and a newer one behaves as before without them.

## Running-state stream (`lib/run-status-stream.ts`)
- `GET /api/agent/running/events` sends the `/api/agent/running` snapshot plus `waitingSessionIds`
  on connect, then again **only when it changes**, detected by an in-memory check once a second. The
  app does not poll; the web client keeps its own 2.5 s polling of `/api/agent/running`.
- Since the v0.11.1 merge the field comes from upstream's `getAwaitingInputRpcSessionIds()` (the name
  stays for installed APKs): a session counts only while its run is **live** and a dialog is open —
  select/confirm/input/editor, or a `custom` overlay not yet closed. The fork's own
  `BLOCKING_DIALOG_METHODS` list was dropped, so two things changed at once:
  - `custom` overlays count now. The app cannot show or settle one (`ChatViewModel.kt` reads only
    select/confirm/input/editor), yet `RunWatcherService` still notifies for them — accepted on
    purpose: the notification says "go to the desktop/web client to answer", which is true.
  - `isAwaitingInput()` gates on `isRunning()` where the fork's `getWaitingRpcSessionIds()` used
    `isAlive()`, so **any** dialog parked on an alive-but-idle wrapper (not just `custom`) drops out
    of the set, and the app cancels the notification for it. Upstream's own
    `lib/rpc-manager-extension-ui.test.mjs` asserts this; `app/api/agent/running-events-route.test.mjs`
    pins the field name to upstream's producer.
- The app refuses to start against a server without this route, so keep it when merging upstream.
- `RunStatus.snapshot` has three collectors: the session list, `RunWatcherService`, and the chat's
  subagent bar. The chat subscribes only while its bar has an **active** row
  (`ChatViewModel.watchRunState`, gated with `distinctUntilChanged` + `collectLatest`), so a bar of
  finished runs does not hold the stream open and the server's 1 s in-memory check with it. One
  connection still serves all of them: a second collector is no second request.

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
- Upstream's own wire slimming (v0.12.0, #1176) sends a non-assistant `message_start` as
  `{ type, message: { role } }` only — an image `read` used to go out twice, once in the start
  snapshot and again in `message_end`. The app is unaffected: `ChatViewModel` opens the streaming
  assembler only when `message.role == "assistant"`, and an assistant start still carries the
  whole snapshot. `lib/agent-event-wire.test.mjs` pins both forms next to the fork's tail tests.

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
  and a window of N top-level sessions each; subagent runs are left out) instead of `sessions`.
  `project=<key>&offset=K` pages one project.
- Every project comes back in one response, only the session windows are cut — so the app's
  new-session sheet lists `projects` roots with its own filter (`SessionsScreen.kt`) and needs no
  extra request. `recentCwds` was dropped at the v0.11.1 merge: no installed APK read it any more.
- `recentHours=H` stops each first window at the sessions active in the last H hours (server clock)
  and adds their count as `recent`, which the app shows by default. Projects with `recent: 0` still
  come back, with an empty window, and the app folds them under "Older projects".
- `ids=a,b` keeps only those sessions (notification titles). `firstMessageChars=N` shortens first
  messages after collapsing a skill expansion to its `/skill:` command — but since v0.12.0 upstream
  pre-cuts every catalog row's first message to **500 code units** in `catalogFirstMessage()`
  (`lib/session-reader.ts`, called at :254 and by `lib/rpc-manager.ts:2326`) *before* the fork's
  trim runs, so `N` above 500 cannot recover text the catalog dropped. The app asks 200 for windows
  and 80 for notification titles, so nothing is lost today; raising a request past 500 needs
  upstream's cut lifted first. `app/api/sessions/detail-route-fork.test.mjs` pins the ceiling.
- The app treats a response without `projects` as an outdated server.

## Subagent runs
`GET /api/sessions/[id]/subagents` lists a session's subagent runs for the app's session view.

On the client the bar above the composer (`SubagentBar` in `ui/chat/ChatScreen.kt`) starts **closed**
and never expands itself when a run starts — `rememberSaveable { mutableStateOf(false) }`, so the
header's toggle survives Back from a child's chat and a new session entry starts closed again. The
web opens `AgentSessionPanel` from a button, so closed-by-default is parity: do not "restore" the
old always-expanded bar when merging upstream. The header carries the count, `N running` and
`N waiting`, which is what makes a collapsed bar enough.

A row whose run is parked on a blocking extension dialog — the permission gate asks in the child's
own chat, which the row opens on tap — shows the orange dot and `waiting` where it would say the
accent dot and `running` (`Subagents.isWaiting` / `Subagents.statusLabel`, `t.warning`, like a
waiting row in the session list). Nothing is fetched for it: the set is the same `waitingSessionIds`
the notifications use, `getAwaitingInputRpcSessionIds()` of `lib/rpc-manager.ts`, and a subagent's
wrapper is registered under its **own** session id (`registerRpcWrapper`, from the subagent
controller's `registerSession`), so the child's dialog lands in the server-wide set. Only an active
row can turn orange — that set is a subset of the running wrappers, so a stale id on a finished row
keeps its done icon. `Subagents.order` puts a waiting row above the running ones (newest first
within each group), and the header counts a parked run as waiting rather than running, so its words
match the rows' words.

## Extension dialog mirrors the fork's web layout
The app renders the extension dialog **inline in the composer slot**, replacing the input bar — not
upstream's non-blocking overlay (`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`).
See [chat-ui.md](chat-ui.md). Do not "fix" either side back to the upstream overlay when merging.

## Native `ask_user_question` dialog
The app answers pi-web's own `ask_user_question` (see [tools.md](tools.md)). It has its own
dialog, not the extension dialog's generic select and input requests.
- **Pending:** the questions come from the call's `tool_execution_update`:
  `details: { kind: "pi-web-ask-user-pending", questions }`. `ChatViewModel` keeps
  `questions` (oldest first) and an `AskForm` per tool call id. The wrapper replays the latest
  update on reconnect, so going to the background clears only the list and the forms survive.
  `tool_execution_end` removes both. An idle run (`markIdle`) clears both.
- **Answering:** `answer_question` goes through `api.command`. A refusal (non-2xx) unlocks the form
  and shows the server's message. Success keeps the form locked ("Sending…") until the end event
  closes it. `data/AskUserQuestion.kt` builds the command. On a single-choice question own text
  replaces the option, because the server takes exactly one of the two. Questions left blank are
  left out of it (the model reads them as `unanswered:`), so Submit works with gaps when there are
  several questions; a lone question still needs its answer, as on the web.
- **Placement:** the dialog takes the composer slot before an extension dialog
  (`ChatScreen.kt` bottom bar).
- **Back collapses the dialog to its pill.** It does not decline: a phone's Back gesture is too
  easy to make by accident with a half-filled questionnaire. The web declines on Esc, as its
  extension dialog does.
- **Monochrome, like the rest of the app** (admin's call after the first version used the accent
  for chips, tabs, radio buttons and chosen rows). The tabs are the files screen's `TabLabel` (a
  label over a 2 dp line in `text`), headers are neutral mono chips (`muted` fill), a chosen row
  takes `muted` with a `borderStrong` line and a filled `primary` mark (circle or box) instead of
  Material's `RadioButton`/`Checkbox`. Only the collapsed pill keeps the accent dot and border,
  because the extension dialog's pill has them.
- **Previews:** single-choice only. A preview with a code fence goes through `Markdown`; plain
  text goes through `CodeBlock` and keeps its spacing, scrolling sideways for ASCII mockups.
- **History:** a finished call with questionnaire `details` renders as `AskUserSummaryCard`.
  Results without them (a refused call's `error`, a Stop) keep the generic tool card.
- `AskUserQuestionTest` pins the contract on the JVM.

## Tests
## Semantics since v0.12.2
- Provider signature fields (`thinkingSignature`, `textSignature`, `thoughtSignature`) are stripped
  from what reaches the client (`lib/message-signatures.ts`, on the SSE wire, in `lib/session-reader.ts`
  and `lib/project-tree.ts`). A Kotlin model that read them optionally keeps working; nothing sends
  them again.
- The fork-only contracts are untouched by this range: `?lite=1`, `?toolUpdates=tail`, the paged
  session list, `/api/agent/running/events`, `/api/sessions/[id]/subagents`, and `waitingSessionIds`
  still meaning "awaiting an extension answer".
- `POST /api/sessions/stats` and `GET /api/cache-miss-notices` are the web UI's own (the agent
  switcher's totals and the transcript notices). The app reads neither; its usage numbers stay on
  the context pages it already fetches.

`android/` has its own Gradle test task (105 tests on the v0.9.3 baseline). Run it after changing a
route contract the app depends on.
