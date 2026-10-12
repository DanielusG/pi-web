# Plan: Android "waiting for input" state + notification

## Goal

When a session blocks on a permission gate (or any blocking extension UI request),
the Android client must:

1. show the session as **waiting for input** instead of "running" (session list),
2. deliver a **one-shot notification** when the session enters the waiting state,
3. reflect the waiting state in the **ongoing foreground-service notification**.

No server changes: the client listens to the per-session SSE stream, which the
server already re-plays open dialogs to new subscribers.

## Background

- The permission gate arrives as an `extension_ui_request` with method
  `select` / `confirm` / `input` / `editor` (see `requestExtensionUi()` in
  `lib/rpc-manager.ts`). While it is open, the wrapper's `isRunning()` stays
  true (prompt still pending), so `GET /api/agent/running` keeps listing the
  session and no running → idle transition ever happens.
- `AgentSessionWrapper.onEvent()` (rpc-manager.ts, ~line 508) re-emits
  `pendingUiRequests` to every new listener → a client that opens the SSE
  stream *after* the gate opened receives the pending dialog immediately.
- `PiApi.events(sessionId)` (`data/PiApi.kt`) is a cold `Flow<JsonObject>` that
  completes when the server ends the stream and fails on network errors;
  reconnect policy belongs to the caller.
- Today `RunWatcherService` is polling-only (`GET /api/agent/running` every
  3 s, `POLL_MS`); the only SSE in the app is `ChatViewModel`'s, open while the
  chat screen is visible. The dialog is already rendered inline in the composer
  in the foreground (`ChatScreen.kt`, fork layout) — no chat changes needed.

## Changes

### 1. `android/app/src/main/java/app/pimobile/notify/RunWatcherService.kt`

Per-session SSE tracking, reconciled on every existing 3 s poll tick:

- New state: `sseJobs: MutableMap<String, Job>` and
  `waiting: MutableMap<String, String>` (sessionId → open dialog id).
- Each tick, after computing the `running` set:
  - desired SSE set = `running` − (if `AppVisibility.isForeground`:
    `AppVisibility.viewingSessionId` — that session's events are already covered
    by `ChatViewModel`'s SSE, and notifications for it are suppressed anyway);
  - open a Job for each id in desired − `sseJobs.keys`
    (`scope.launch { api.events(id).collect { onWaitingEvent(id, it) } }`);
  - cancel Jobs for ids no longer in the desired set (covers: session finished,
    navigation to/from the chat screen, foreground change — the next tick
    re-opens and the server replay restores any still-open dialog);
  - a flow that completed/failed while its session is still desired is simply
    re-opened on the next tick (natural reconnect, no backoff needed at 3 s).
- `onWaitingEvent(sessionId, event)`:
  - `type == "extension_ui_request"` and `method ∈ {select, confirm, input,
    editor}` → `waiting[sessionId] = id`. Only on transition (was not waiting):
    post the one-shot notification (see 2), guarded like `finished()`:
    skip when `AppVisibility.isForeground && viewingSessionId == sessionId`.
    Other methods (`notify`, `custom`, `setWidget`, `status`) are non-blocking
    and ignored.
  - `type == "extension_ui_closed"` and `id == waiting[sessionId]` → clear
    `waiting[sessionId]`, cancel the one-shot notification (tag, see 2).
- When a session leaves the `running` set (existing `finished` handling):
  also clear `waiting[sessionId]` and cancel its one-shot notification (covers
  timeout-deny and Stop, where the session goes idle right after the gate).
- Ongoing notification: build per-session `(title, waiting)` pairs from the
  existing `sessions` map + `waiting` and pass them to `Notifications.ongoing()`
  (see 2).
- `onDestroy()`: cancel all SSE Jobs, clear `PiApp.waitingSessionIds` (see 3).
- Publish the waiting set: `PiApp.waitingSessionIds.value = waiting.keys` after
  every change (main thread; the poll loop already runs on
  `Dispatchers.Main.immediate`).

### 2. `android/app/src/main/java/app/pimobile/notify/Notifications.kt`

- `waitingForInput(context, sessionId, cwd, title)`: one-shot modeled on
  `finished()` — channel `CHANNEL_FINISHED` (high importance, same UX class),
  title = session title, text = "Waiting for your input.", `autoCancel`,
  content intent opens the session (dialog renders on arrival). Tag
  `pi-session-waiting:$sessionId` (replaces itself on repeat gates, never
  collides with the `pi-session-complete:$sessionId` tag).
- `ongoing(context, items: List<Pair<String, Boolean>>)` (title, waiting)
  replacing `titles: List<String>`:
  - no waiting → exactly today's rendering ("pi is working" / title /
    "N sessions running" + "Working…");
  - any waiting → contentTitle = "Waiting for input" (or the single waiting
    title when it is the only session), contentText = waiting titles joined
    with " · ".
- No new channel (keeps the notification settings surface unchanged).

### 3. `android/app/src/main/java/app/pimobile/PiApp.kt`

- Expose `val waitingSessionIds = MutableStateFlow<Set<String>>(emptySet())`
  (as `StateFlow` publicly), updated by `RunWatcherService` (only main thread,
  matching the service's dispatcher).

### 4. `android/app/src/main/java/app/pimobile/ui/sessions/SessionsViewModel.kt`

- State: add `waiting: Set<String> = emptySet()` next to `running`.
- Constructor: take the waiting `StateFlow` (pass
  `app.waitingSessionIds` from `MainActivity`, same pattern as `app::onRunActive`);
  collect it into `_state` in the existing `viewModelScope`.
- The existing running poll is untouched.

### 5. `android/app/src/main/java/app/pimobile/ui/sessions/SessionsScreen.kt`

- `SessionRowView(row, running, waiting, …)`: when `waiting` (takes precedence
  over `running`), render `StatusDot(t.warning, pulsing = false)` + "Waiting"
  label instead of the green pulsing "Running" (tokens: `t.warning` exists in
  `PiTokens`).
- `SessionsHeader`: when `waitingCount > 0`, extend the chip text, e.g.
  "1 running · 1 waiting".

## Deliberately unchanged

- Server (`/api/agent/running`, rpc-manager) and the web client.
- `ChatViewModel` / `ChatScreen`: the foreground dialog already renders inline
  in the composer slot (fork layout, web parity); its SSE keeps working as is.
- SSE connection count never exceeds today's: at most one SSE per active
  session, and the viewed session's SSE is never doubled (service skips it).

## Edge cases

- **Gate timeout**: pi settles the request with its default (confirm → deny)
  and emits `extension_ui_closed` → waiting clears, one-shot cancelled,
  session continues or finishes as normal.
- **Stop/abort**: the wrapper aborts the extension UI (`extensionUiAbortController`)
  → `extension_ui_closed`; session also leaves the running set → both cleanup
  paths fire, notification cancelled exactly once (tag-based).
- **Multiple concurrent sessions**: one SSE per non-viewed active session;
  ongoing notification aggregates waiting titles.
- **App backgrounded mid-gate**: the service's SSE (or its next-tick reopen +
  server replay) catches the state → notification posted.
- **Process death**: same limitation as today's completion notification —
  nothing runs without the foreground service.

## Out of scope

- Responding to the dialog directly from the notification (no action buttons
  in v1; tapping opens the session with the dialog).
- `custom` panels, `notify`, status/widget methods (non-blocking).
- Android i18n (plain strings, consistent with the rest of the app).

## Verification

- `cd android && ./gradlew :app:assembleDebug` compiles.
- Manual, against a live pi-web:
  1. Start a session that hits a permission gate (e.g. a bash command needing
     approval), background the app → one-shot "Waiting for your input."
     notification arrives; ongoing notification shows the waiting text; tap
     opens the session with the dialog.
  2. Approve from the chat → one-shot dismissed, ongoing back to "Working…".
  3. Session list while a gate is open → row shows amber dot + "Waiting"
     instead of the green pulsing "Running"; header shows the waiting count.
  4. Let the gate time out (or press Stop) → waiting notification removed,
     completion flow unchanged.
  5. Two concurrent sessions, one gated one running → ongoing text lists the
     waiting title; both notifications behave independently.
  6. Open the gated session's chat, then navigate to the list → service opens
     the SSE on the next tick, replay restores the waiting state immediately.
