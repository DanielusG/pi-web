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

## Chat size (`ScaledUi` in `ui/theme/Theme.kt`)
Settings › "Chat size" (DataStore key still `chat_font_scale`, 0.5–1.5) scales the whole chat
screen: `MainActivity` wraps `ChatScreen` in `ScaledUi(scale)`, which provides a `LocalDensity`
with `density × scale`, so every dp and sp inside shrinks together. Write chat sizes as plain
dp/sp; never multiply them by the scale. sp→dp is delegated to the display density, so Android 14's
nonlinear font scaling still applies.
- `Dialog`, `ModalBottomSheet`, `DropdownMenu` and `Popup` compose in a window of their own, which
  provides the display density again. Wrap their content in `KeepUiScale` (it reads `LocalUiScale`,
  which does cross into the window). What is set on the component itself — shape, container
  padding, `Modifier.width` — stays at display size, so a fixed menu width goes on a `Box` inside.
- `LocalConfiguration.screenWidthDp/HeightDp` are display dp and no longer match the scaled dp:
  read `screenSizeDp()`.
- At 150 % the screen is about 274 dp wide: rows that must fit one line (the message footer) are
  `FlowRow`s.

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

## New-session folder browser (`ui/sessions/FolderBrowser*.kt`)
A folder that is no project yet is picked on the new-session sheet's second page ("Other
folder"), which browses the server through upstream's own `/api/cwd/browse` — the route the web's
`components/DirectoryPicker.tsx` uses; nothing fork-only is involved, so a merge that changes the
route's shape (`path`, `parentPath`, `directories`, `drives` on a Windows server asked for no
path) changes the app too. `FolderBrowserTest` pins the parsing and the breadcrumb.
- **The commit point is "Start here"**, as the web's "Select this folder": it posts the folder on
  screen to `/api/cwd/validate`, which also calls `allowFileRoot()` — a cwd that skipped it would
  not open in the files screen. "New folder" opens the created folder rather than choosing it,
  as on the web. While the path field is open both are disabled, the web's `hasUncommittedPath`
  rule: the folder on screen is not the one typed, and a refused path stays in the field with the
  reason under the list. The field uses the URI keyboard (`/` on its first layer).
- **The breadcrumb is built in the app** (`FolderBrowser.crumbs`) from the path alone: the route
  answers only the immediate `parentPath`. On a Windows server it starts at "Drives" (the route
  without `path`), then the drive; a UNC `\\server\share` is its own root.
- **Dot folders are folded** under "Show N hidden folders": the route lists them, sorted first
  by Node's `localeCompare`, and in a home directory they pushed the real folders off the first
  screen. The web picker lists everything; this is the app's deviation.
- **The state lives in `SessionsViewModel.folders`**, so the next "Other folder" opens where the
  last one was left (fetched again; the server's home when that folder is gone), the way the web
  starts its picker from the last custom path. Memory only, unlike the web's localStorage: a
  restarted app opens at the home again. The sheet's own `browsing` flag is
  `rememberSaveable`, so a reopened sheet starts on the projects page.
- **Back inside the sheet** goes through a `BackHandler` composed inside `ModalBottomSheet`'s
  content: it closes the path or new-folder field first, then returns to the projects page;
  only Back on the projects page dismisses the sheet.

## Session search (`lib/session-find.ts`)
`GET /api/sessions/find?q=&project=&sort=recent|relevance&limit=` searches every session the way
pi's `/resume` selector does; the app's list has a search mode on top of it
(`ui/sessions/SessionSearch*.kt`). The web sidebar keeps upstream's literal
`/api/sessions/search`.
- **pi's matcher, not a copy.** `parseSearchQuery` / `matchSession` live in the SDK's
  `dist/modes/interactive/components/session-selector-search.js`, which the package does not
  export; it is imported by file URL from the SDK pi-web resolves (as `lib/pi-sdk-internals.ts`
  does for MCP). A query means what it means in the TUI and follows pi's changes (1.5 already
  replaced 1.2's fuzzy-over-everything with tiers: name, then exact text, then scattered letters of
  the name or cwd only). An upgrade that moves or renames the module turns the route into a 503
  with the reason; `lib/session-find.test.mjs` pins the exports and compares the order with pi's own
  `filterAndSortSessions` over `SessionManager.listAll()` of the same files.
- **The text** is what pi's `SessionManager` puts in `allMessagesText`: the user and assistant
  text blocks of every entry of the file (all branches, no thinking, tool calls or tool
  results), blocks and messages joined with a space; the test compares the two. The name comes
  from the catalogue. Side chats are left out (each copies its parent's text); subagent runs are
  searched and labelled `subagent · <profile>` in the app.
- **Sort:** `relevance` is the default, because the TUI's default Threaded sort ranks by score
  as soon as there is a query; `recent` keeps the catalogue's newest-first order and stops
  reading after `limit + 1` hits. `limit` is 50 by default, 200 at most; `hasMore` says there
  are others. The app pages 50 at a time up to 200, then says to refine the query.
- **Cost:** files are read 8 at a time with a 10 s budget (`truncated` with `scanned` / `total`
  when it runs out). The extracted text stays in a process-wide cache of 32 M characters, keyed
  by path, size and mtime, so typing a longer query does not reread every file; on 76 fixture
  sessions a cold search took ~0.5 s and a warm one ~20 ms.
- **`re:` runs the user's pattern on the server's event loop.** JavaScript has no regex
  timeout, so a catastrophic pattern over a long transcript stalls pi-web until it finishes.
  Accepted: only someone who can already use the server can search, as the TUI runs the same
  pattern on the user's own machine; the query is capped at 200 characters. An invalid pattern is
  `queryError` with pi's message, and nothing matches, as in the TUI.
- **Where the hit is** is pi-web's own addition: the first message block where a phrase, a
  literal word or the regex occurs, in query order (`snippet`: entry id, block index, 80
  characters each side), and the hit in the name (`nameMatch`). A word pi matched only as
  scattered letters has no snippet. The app marks the title instead of repeating a snippet that
  is the title (an unnamed session's first message).
- **Opening at the hit** (web: ChatWindow's `searchTarget`, which loads one extra page of 200 and
  then gives up): the chat route takes `entry`, and `ChatViewModel.locate()` keeps the loading row
  up while it pages `context?before=&tail=200` until the entry is a message of the list (a message's
  item key is its entry id), at most 10 pages. It then publishes the list, the end of loading and
  `jumpTo` in one update; `ChatScreen` hands the index to `BottomFollow.jumpTo()`, which detaches
  the follow (or it would pull the list back to its end) and scrolls, and the row is marked with
  the muted fill for about 2.5 s. Not found (another branch, before a compaction, further back than
  10 pages) opens at the end with a notice. The jump scrolls to the message's top, not to the text
  block inside it.
- **A reload keeps the older pages** (`Messages.olderThanSnapshot`): `refreshSession` replaced the
  list with the branch's last 50 entries, so coming back to the screen (`onForeground` reloads) cut
  away a jump's pages, and "Load earlier"'s, with the message on screen. When the snapshot's first
  entry is already loaded, everything before it is one of its ancestors and stays, with the old
  `oldestEntryId` and `hasMore`; a snapshot starting elsewhere (a compaction summary, another
  branch) keeps nothing, as before.
- **In the app** the search lives in `SessionsViewModel` (`SessionSearch`), so Back from a result's
  chat finds it as it was left; Back from the search closes and forgets it. The project chip lists
  the paged list's projects (its `key` is the route's `project`), the sort switch is the file
  viewer's segmented control, the hint line is the TUI's (`"phrase" exact · re:<pattern> regex`),
  and the mark is the muted fill, never the accent.

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
- **Previews:** single-choice only, for the chosen row (the first option before a choice; no hover
  on a phone); a row without one shows "No preview available", never another option's. A preview with a code fence goes through `Markdown`; plain
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
