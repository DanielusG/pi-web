# Pi Mobile — native Android client for pi-web

A native Kotlin + Jetpack Compose app that talks to a running pi-web backend on
another machine over its existing HTTP + SSE API. It needs no backend changes.

**Status: first working version.** It covers one complete flow and is meant to decide
the direction before building a full-featured app. It has been tested on an Android 15
emulator.
It has **not yet been tested against a real pi-web with a real model.**

## What works

- **Server setup:** address + `PI_WEB_PASSWORD` (Basic auth), with a connection test
  and clear 401/403 messages.
- **Sessions:** grouped by project, pull to refresh, a live "running" dot (polls
  `/api/agent/running` every 3 s, reloads when `sessionListVersion` changes),
  "show all" for long projects, and long-press on a session for rename/delete
  (bottom sheet: pre-filled rename field with the web's no-op check, delete with
  inline confirmation).
- **New session:** pick a recent working directory or type a path (validated by the
  server). The session is created on the first message.
- **Chat:**
  - Markdown (headings, lists, code blocks, tables, quotes, links) and selectable text.
  - Collapsible thinking; deferred thinking loads its full text on tap.
  - Tool call cards with a spinner, check or error state. Tap for input and output;
    bash output streams live.
  - `!bash` executions and compaction notices.
  - "Load earlier messages" paging.
- **Live runs:**
  - Token streaming, throttled to about 20 UI updates per second.
  - Stop (`abort`). Typing while the agent runs sends a steering message.
  - Model picker, thinking level, context % indicator.
- **Extension dialogs:** select / confirm / input / editor, so an extension waiting for
  an answer doesn't hang the agent.
- **Image attachments:**
  - The image button in the composer opens the system photo picker, which needs no
    storage permission.
  - Limits and compression follow pi-web's composer: at most 10 images of 10 MB each.
    Files over 1 MB (except GIF) are re-encoded as JPEG q85, long side 1024 px, and the
    original is kept when that isn't smaller.
  - EXIF orientation is applied. HEIC is always converted to JPEG, because model APIs
    don't accept it.
  - Thumbnails with a remove badge. Images are sent with `prompt`, also while steering.
  - A dismissible warning appears when the selected model doesn't list `image` input.
  - User messages show their images; tap one for a full-screen preview.
- **Slash commands:**
  - Typing `/` opens pi-web's command palette above the input: built-in, extension,
    prompt and skill commands from `get_commands`, grouped with counts and filtered as
    you type, ranked the way pi-web ranks them.
  - Skills with `disable-model-invocation` are listed last, marked `dormant`
    (from `/api/skills`).
  - Tapping a command inserts `/name `; Back closes the palette. On a hardware
    keyboard, the arrow keys move the highlight, Tab inserts and Escape closes.
  - A new chat gets an idle runtime (`ensure_session`) to load commands; it stays out of
    the session list until a prompt is sent.
  - Extension, prompt and skill commands are sent as prompts. A skill message shows
    as a collapsed `/skill:<name> args` chip that expands to the full skill text.
  - Built-ins, as in pi-web:
    - `/compact [instructions]` shows "Compacted 47k -> 12k tokens (35k saved)" above
      the input for 6 s. Auto-compaction results show there too.
    - `/reload` reloads the runtime, then history, models and commands.
    - `/name <name>` renames the session.
    - `/session` opens the context indicator's stats, fetched with `get_session_stats`.
    - `/copy` copies the last assistant message.
    - `/clone` copies the current branch into a new session and switches to it.
  - While the agent runs, only `/session` and `/copy` run locally; other `/` commands
    are sent as a steering prompt, as on the web. The input is disabled while a
    built-in runs.
- **Resilience on mobile networks:**
  - SSE read timeout of 75 s (the server heartbeat is 30 s), so half-open connections
    are detected.
  - Automatic reconnect with backoff; after a reconnect, history is reloaded to cover
    missed events.
  - State polling every 15 s while running, plus a check when the app returns to the
    foreground.
  - SSE lease renewal. The run ends on `prompt_done` / `agent_settled`, never on the
    first `agent_end`.

- **Tool views:**
  - Every tool gets pi-web's header summary: path, command, `path · N edits`…
  - `edit` shows the SDK's `details.patch` as a unified diff, with line numbers, word-level highlights, a `+N -M` count and the run time.
  - An edit that has no result yet shows a server-computed preview from `/api/edit-preview`.
- **Context indicator:**
  - A ring in the chat header shows the % of the context window used.
  - Tapping it shows context tokens, input/output, cache read/write, cache hit rate, cost, message counts and active time.
- **Files:**
  - The folder button in the chat header opens the session's project. Each project on the session list has a "Files" button too; from there mentions are off, since there is no chat to insert into.
  - Explorer:
    - Folder drill-down with a breadcrumb; Back goes up one level.
    - Search via `/api/file-index`, and a "Recent" list at the root.
    - A "Changes" tab from `git status`, with M/A/D/R/U/C badges and dots on folders that contain changes. Tapping a change opens its diff.
    - Pull to refresh; it also refreshes when you return to the screen.
    - Long-press a row for "Mention in chat", "Copy path", "Share" and "Open with".
  - Viewer:
    - Source view with line numbers, wrap toggle and "Load more" for files over 256 KB.
    - Markdown and HTML preview; HTML runs with scripts and network off.
    - A git diff view. The default view follows pi-web: preview for markdown/html, diff when opened from a change, diff for deleted files.
    - Images (pinch zoom), PDF pages, docx preview, audio and video streaming.
    - Live reload through the `watch` SSE, shown as a green dot.
  - Mentions: long-press a line, tap another to extend the range, then "Mention" inserts `@path:12-18` into the composer. The @ button mentions the selection or the whole file.
  - In chat, the path in read/write/edit tool cards opens the file (edits open on the diff). Local markdown links open in the viewer, and chips under a turn's last message list the files it wrote.
- **Notifications:**
  - pi-web's own push is Web Push, which a native app can't receive. Instead, `notify/RunWatcherService` runs as a foreground service only while a session is running.
  - It polls `/api/agent/running` every 3 s and posts "Task finished." when a session goes idle.
  - It stays silent for subagents (pi-web's suppression list) and for the session already on screen.
  - Tapping the notification opens that session.

## Install on a phone

APK files:

- `app/build/outputs/apk/release/app-release.apk`: recommended, small and smooth.
  Signed with the local debug key.
- `app/build/outputs/apk/debug/app-debug.apk`

To install:

- **USB:** `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk`
- **Without USB:** copy the APK to the phone and open it (allow "install unknown apps").

## Server side (pi-web machine)

1. Listen on the network: `npm run start:lan`, or `dev:lan` in development.
2. Set `PI_WEB_PASSWORD=...`.
3. In the app, use the **IP address**, e.g. `192.168.1.20:30141` or a Tailscale
   `100.x.y.z:30141`. A hostname is rejected with 403 unless it is listed in
   `PI_WEB_ALLOWED_HOSTS`.
4. Traffic is plain HTTP with Basic auth. That is fine on a LAN or over Tailscale; put
   an HTTPS reverse proxy in front for anything else.

## Build

Requirements: JDK 17 and the Android SDK at `~/Android/Sdk` (`local.properties` points to it).

```bash
cd android
./gradlew assembleRelease      # or assembleDebug
```

## Code map

```
app/src/main/java/app/pimobile/
  PiApp.kt                 process-wide PiApi + SettingsStore
  MainActivity.kt          navigation: settings → sessions → chat
  data/PiApi.kt            OkHttp REST + SSE flow, Basic auth, error mapping
  data/ChatModel.kt        message → ChatItem parsing (both tool-call spellings),
                           StreamingAssembler (applies message_update deltas)
  data/Settings.kt         DataStore server config
  data/Json.kt             lenient JsonObject accessors
  ui/sessions/             session list + new-session sheet
  ui/chat/ChatViewModel.kt run lifecycle, SSE loop, reconnect, reconcile, commands
  ui/chat/ChatScreen.kt    list, composer, model sheet, extension dialogs
  ui/chat/ChatItems.kt     bubbles, thinking, tool cards, bash, notices
  ui/chat/SlashCommands.kt slash palette: filter, ranking, groups, menu
  data/FilePaths.kt        pi-web's path, link and @mention helpers
  ui/files/                explorer, viewer (source/preview/diff), media views, share
  ui/markdown/Markdown.kt  small markdown renderer
```

The API contract this client relies on (endpoints, SSE events, delta rules) was
mapped from pi-web `main` at df32731. JSON is handled as lenient trees rather than
strict DTOs, because pi-web passes pi SDK objects through and their shapes change
between SDK releases.

## Not done yet (road to a full app)

| Area | Notes |
|---|---|
| Notifications, part 2 | "Needs your input" (blocking extension dialog) requires holding SSE from the service. Real push (FCM) would need a backend addition. |
| Tool views, part 2 | write (content + preview), read, bash, grep/find/ls, Agent/subagent views, following the tested edit pattern. |
| Images, part 2 | Camera capture and pasting from the keyboard; assistant image blocks and tool-result images (URL form needs auth). |
| Branches & forks | Tree view, `navigate_tree`, fork from a message. |
| Session management | Search. (Rename and delete ship via the long-press sheet.) |
| Files, part 2 | Syntax highlighting in the source view, upload, file tabs, git diff, worktrees (`/api/worktrees`). |
| Terminal | SSE + POST exist server-side; needs a terminal emulator view. |
| Extension widgets/status/custom panels | Only blocking dialogs and notify are handled. |
| `!bash`, tool presets, compaction button | Commands exist (`bash`, `set_tools`, `compact`). |
| Security | The password is stored in plain DataStore; move to the Android Keystore. Release signing key. |
| Tests | Unit tests for `StreamingAssembler`, markdown parser, and run-state transitions. |
