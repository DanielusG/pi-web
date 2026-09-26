# Pi Mobile — native Android client for pi-web

A native Kotlin + Jetpack Compose app that talks to a running pi-web backend on
another machine over its HTTP + SSE API. It needs this fork's pi-web: it relies on
`GET /api/agent/running/events`, and against a server without it the app shows an
"Update pi-web" screen instead of starting (there is no polling fallback).

**Status: first working version.** It covers one complete flow and is meant to decide
the direction before building a full-featured app. It has been tested on an Android 15
emulator.
It has **not yet been tested against a real pi-web with a real model.**

## What works

- **Server setup:** address + `PI_WEB_PASSWORD` (Basic auth), with a connection test
  and clear 401/403 messages. The test also refuses a pi-web without the run-state
  stream.
- **Sessions:** grouped by project, pull to refresh, a live "running" dot (pushed by
  the run-state stream while the list is visible, reloads when `sessionListVersion`
  changes),
  an "Active" box at the top of the list, styled like a project group and
  scrolling with it, that lists every running or waiting session across
  projects (tap to open, long-press for the same rename/delete sheet; hidden
  when nothing is active, capped at a few rows with internal scroll),
  each project's sessions of the last 24 hours with "Show 10 more" and "Show less",
  projects with nothing in 24 hours folded under "Older projects" at the bottom (open
  when nothing is recent): the list arrives a page at a time
  (`GET /api/sessions?perProject=&recentHours=`), first messages cut to what a row
  shows, so a project with thousands of sessions is never downloaded whole; and
  long-press on a session for rename/delete
  (bottom sheet: pre-filled rename field with the web's no-op check, delete with
  inline confirmation).
- **New session:** pick a recent working directory or type a path (validated by the
  server). The session is created on the first message.
- **System assistant trigger:** the app declares `android.intent.action.ASSIST`, so on
  devices where the user can choose the default assistant (Settings > Apps > Default
  apps > Assistant app) Pi Mobile can be picked. The system triggers — swipe up from
  the bottom corner (gesture nav), long-press power (if enabled), long-press home
  (3-button nav) — then open a **fresh session** with the composer focused and the
  keyboard up. The session opens in the project chosen under Settings > "Assistant
  project" (a project from the session list or a typed directory, saved on pick);
  left unset, it follows the last used working directory. With neither, the
  new-session sheet opens instead. Back returns to the session list; chats opened
  before the trigger are dropped. Note: on OnePlus the Plus Key (AI key) has fixed
  options and cannot be mapped to a third-party app.
- **Chat:**
  - Markdown (headings, lists, code blocks, tables, quotes, links) and selectable text.
  - LaTeX math: inline formulas in text style, display formulas shrunk to fit (down to 75 %)
    and then scrolled behind a faded edge, tables that wrap their text to fit the screen.
  - Collapsible thinking; deferred thinking loads its full text on tap.
  - Tool call cards with a running dot, check or error state. Tap for input and output;
    bash output streams live as its last lines (`?toolUpdates=tail`), the whole output
    arrives when the tool finishes.
  - `!bash` executions and compaction notices.
  - "Load earlier messages" paging.
- **Live runs:**
  - Token streaming at about 10 UI updates per second, each re-rendering only the
    message's last block and scrolling in the same frame.
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
- **Voice dictation:** long-press the send button to dictate (WhatsApp-style); while
  dictating, a soft red halo glows around the composer (the send button itself stays
  unchanged). Tokens from the Nemotron ASR server stream into the input live at the
  cursor position. Release to stop and flush the trailing tokens; the text stays
  editable. Tap sends as usual; a quick tap while dictating stops the dictation.
  - Server URL is a setting ("Voice input (ASR)", default `ws://192.168.1.56:8000/ws`,
    empty disables the feature). Language is `it-IT`.
  - Needs the `RECORD_AUDIO` permission, requested on first use.
- **Text to speech ("Listen"):**
  - Final assistant messages show a "Listen" action with a speaker icon in the footer.
  - Streaming synthesis against an OpenAI-compatible TTS server (`POST /v1/audio/speech`
    with `stream: true`). Default: Kokoro-FastAPI on the laptop (`http://192.168.1.56:8880`,
    model `kokoro`, voice `if_sara`). Audio playback starts in ~0.5 s even for long
    responses because Kokoro chunks and streams mp3 frames incrementally.
  - While active, a floating Telegram-style bar appears below the header with play/pause,
    preview title, speed selector (0.5x, 1x, 1.2x, 1.5x, 1.7x, 2x), and a stop/close button.
  - Changing playback speed happens client-side without re-synthesizing, and the last chosen
    speed is persisted across sessions.
  - Background and screen-off playback: uses Android's native Media3 stack (`ExoPlayer` +
    `MediaSession` hosted in a foreground `MediaSessionService`), showing the standard system
    media notification with lockscreen controls and handling audio focus automatically
    (e.g., auto-pause during phone calls or voice dictation).
  - Settings: URL, model, and voice configured in Settings > "Text to speech (TTS)"; empty
    URL disables the feature and hides the button.
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
  - 10 s after the chat leaves the screen it closes its event stream (the notification
    service keeps watching); on return it reloads and reconnects, and the server replays
    the message in progress.
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
  - It follows `GET /api/agent/running/events`, one SSE connection shared with the session
    list: the server sends the running and waiting session ids on connect and then only
    when they change, so nothing is polled.
  - It posts "Task finished." when a session goes idle, and "Waiting for your input." when a
    session blocks on an extension dialog (permission gates included).
  - It stays silent for subagents (pi-web's suppression list) and for the session already on screen.
  - The ongoing notification is re-posted only when its content changes.
  - Tapping the notification opens that session. Each tap is a one-shot deep link, consumed
    exactly once (a unique token per notification survives activity recreation), and the
    finished/waiting notifications of a session are removed as soon as that session is on screen.

## Install on a phone

APK files:

- `app/build/outputs/apk/release/app-release.apk`: recommended, small and smooth.
  Signed with the local debug key.
- `app/build/outputs/apk/debug/app-debug.apk`

To install:

- **USB:** `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk`,
  then `adb shell cmd package bg-dexopt-job app.pimobile` to compile it now. A sideloaded app
  otherwise runs interpreted until the system's overnight compilation (charging and idle):
  about 15 % more CPU while streaming. On OnePlus `cmd package compile` is refused from the
  shell; the background job works.
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

The build patches two classes of the LaTeX renderer (`LatexRendererPatches` in
`app/build.gradle.kts`, see `ui/markdown/LatexGlyphBounds.kt`) and fails if a library update
moved the code they patch: check them when updating `latex-renderer`.

## Code map

```
app/src/main/java/app/pimobile/
  PiApp.kt                 process-wide PiApi + SettingsStore
  MainActivity.kt          navigation: settings → sessions → chat
  data/PiApi.kt            OkHttp REST + SSE flow, Basic auth, gzip, error mapping
  data/ChatModel.kt        message → ChatItem parsing (both tool-call spellings),
                           StreamingAssembler (applies message_update deltas)
  data/Settings.kt         DataStore server config (incl. ASR URL for dictation,
                           TTS config), last cwd and assistant project
  data/AsrClient.kt        voice dictation: OkHttp WebSocket + AudioRecord 16 kHz mono
                           to the Nemotron ASR server (start/audio/stop protocol)
  data/TtsClient.kt        TTS streaming request builder (OpenAI-compatible /v1/audio/speech)
  data/TtsText.kt          markdown sanitizer for speech (drops code fences, markers)
  data/TtsPlayer.kt        app-level TTS coordinator, MediaController binding, UI state
  media/TtsDataSource.kt   Media3 DataSource streaming POST /v1/audio/speech directly to ExoPlayer
  service/TtsPlaybackService.kt foreground MediaSessionService + ExoPlayer
  data/Json.kt             lenient JsonObject accessors
  notify/RunStatus.kt      the one run-state SSE connection (list + notification service)
  notify/RunWatcherService.kt foreground service: finished / waiting notifications
  ui/sessions/             session list + new-session sheet
  ui/settings/             server settings, assistant project picker, "Update pi-web" screen
  ui/chat/ChatViewModel.kt run lifecycle, SSE loop, reconnect, reconcile, commands
  ui/chat/ChatScreen.kt    list, composer, model sheet, extension dialogs
  ui/chat/ChatItems.kt     bubbles, thinking, tool cards, bash, notices
  ui/chat/SlashCommands.kt slash palette: filter, ranking, groups, menu
  data/FilePaths.kt        pi-web's path, link and @mention helpers
  ui/files/                explorer, viewer (source/preview/diff), media views, share
  ui/markdown/Markdown.kt  small markdown renderer
  ui/markdown/LatexSource.kt formula rewrites for the LaTeX renderer (spaces, minus, inline style)
  ui/markdown/LatexGlyphBounds.kt precise glyph bounds with each font loaded once
  ui/markdown/TableLayout.kt column widths like a browser's automatic table layout
  ui/markdown/StreamingMarkdown.kt streaming message: closed segments cached, only the tail re-parsed
```

The API contract this client relies on (endpoints, SSE events, delta rules) was
mapped from pi-web `main` at df32731. JSON is handled as lenient trees rather than
strict DTOs, because pi-web passes pi SDK objects through and their shapes change
between SDK releases.

## Not done yet (road to a full app)

| Area | Notes |
|---|---|
| Notifications, part 2 | Real push (FCM), so runs started while the app is closed notify too, would need a backend addition. |
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
