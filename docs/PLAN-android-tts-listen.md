# Plan: TTS "Listen" for assistant messages (Android client)

Add a "Listen" action to final assistant messages that synthesizes the text with an
OpenAI-compatible TTS server (default: Kokoro on the laptop, `http://192.168.1.56:8880`)
and plays it with a Telegram-style floating bar (play/pause, speed 0.5x–2x, close),
using the native Media3 stack so playback survives background and screen-off.

## Server chunking + streaming (verified in the container)

Daniele's questions — does Kokoro chunk internally, and is there a streaming endpoint?
**Yes to both.** Verified in the container source (`/app/api/src` on
`hungry_dijkstra`, `remsky/kokoro-fastapi-gpu`) and measured live:

- `services/text_processing/text_processor.py::smart_split` splits the text into
  sentences and groups them into chunks targeting 175–250 tokens (absolute max 450,
  `core/config.py`); supports `[pause:0.5s]` tags.
- `services/tts_service.py` processes chunks with `asyncio.Semaphore(4)` — up to 4
  chunks in parallel.
- `POST /v1/audio/speech` with `"stream": true` (`routers/openai_compatible.py`) is a
  `StreamingResponse` that yields each chunk's mp3 frames as soon as that chunk is
  generated; without `stream` it concatenates everything and returns one file.

Measured on the laptop with a 6000-char message:

| Mode | Time to first audio byte | Total |
|---|---|---|
| non-streaming | 11.3 s | 11.3 s |
| `stream: true` | **0.51 s** | 11.3 s |

The audio arrives ~10x faster than real time (590 KB ≈ 32 s of audio by t=1.85 s), so
playback never stalls. A typical 1–3k-char message starts in ~0.5 s instead of 2–6 s.

**Decision: use `stream: true`.** The client sends the whole sanitized message in one
request; the server chunking + streaming give Telegram-like TTFC without any
client-side chunking.

## Decisions (agreed)

- Playback stack: **Media3** (`media3-exoplayer` + `media3-session`, stable 1.11.1) with
  a `MediaSessionService` (foreground, type `mediaPlayback`) — the official pattern for
  background + screen-off playback, media notification, lockscreen controls, and audio
  focus (auto-pause during ASR dictation / calls, auto-resume after).
- Whole-message synthesis with `stream: true` (server chunks internally and streams
  the mp3 frames as they're generated — TTFC ≈ 0.5 s); client-side cache per
  (text, voice, model), tee'd from the stream, so a repeat play is instant.
- Speed is **client-side** (`setPlaybackParameters(speed, 1f)`, pitch preserved) — no
  re-synthesis, exactly Telegram's behavior. Last speed persisted.
- Settings: TTS URL (default `http://192.168.1.56:8880`, empty disables), model
  (default `kokoro`), voice (default `if_sara` — Italian female; server also has
  `im_nicola`). Blank model/voice = field omitted, server default applies.
- Voices available on the server: 50+ (`/v1/audio/voices`), Italian: `if_sara`,
  `im_nicola`.

## Files (in order)

1. **`android/app/build.gradle.kts`** — add `androidx.media3:media3-exoplayer:1.11.1`
   and `androidx.media3:media3-session:1.11.1`.

2. **`android/app/src/main/AndroidManifest.xml`** — add
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission (required on Android 14+ for the
   `mediaPlayback` type) and the `TtsPlaybackService` declaration
   (`exported=false`, `foregroundServiceType="mediaPlayback"`).

3. **`data/Settings.kt`** — `ServerConfig` gains `ttsUrl` (default
   `http://192.168.1.56:8880`), `ttsModel` (default `kokoro`), `ttsVoice` (default
   `if_sara`); `normalizeTtsUrl()` accepts bare `host:port` (mirrors the ASR pattern);
   `SettingsStore` persists the three keys plus `ttsSpeed` (last speed, default 1.0).
   Empty URL = feature off.

4. **`data/TtsText.kt`** (new) — `sanitizeForSpeech(markdown: String): String`:
   drops fenced code blocks, strips inline-code backticks, images, link markup (keep
   label), heading/bold/italic/list markers; collapses whitespace.
   **`data/TtsTextTest.kt`** (new) — unit tests, house style.

5. **`data/TtsClient.kt`** (new) — request builder + cache logic (no streaming plumbing;
   the DataSource below owns the HTTP call):
   - cache hit: `cacheDir/tts/<sha1(model|voice|text)>.mp3` exists → play the file Uri
     directly, no network;
   - builds the POST body `{model, input, voice, response_format:"mp3", stream:true}`
     (blank fields omitted) + endpoint URL + OkHttp client (call timeout 30 s, read
     timeout 60 s — between-chunk gaps are ~2 s max).
   - Friendly errors: "TTS server unreachable", "TTS server error".

6. **`media/TtsDataSource.kt`** (new, ~80 lines) — a Media3 `DataSource<MediaItem>`
   (extends `BaseDataSource`) for the live-stream path. This is the documented
   customization point (Media3 "Customization" page: inject a custom
   `DataSource.Factory` into `ProgressiveMediaSource`), and the only way to play a
   POST-streamed audio — verified in the Media3 source that
   `ProgressiveMediaPeriod.buildDataSpec()` rebuilds the DataSpec from the URI alone,
   so a POST `DataSpec` set on the `MediaItem` is ignored by progressive playback
   (the official `OkHttpDataSource` POST support only helps `SingleSampleMediaSource`,
   which buffers the whole stream first — no TTFC gain):
   - `open(dataSpec)`: issues the OkHttp POST (synchronously, on the player's IO
     thread) and returns unknown length; with `dataSpec.position > 0` (retry after a
     mid-stream drop) it re-issues the POST and fast-forwards by discarding exactly
     `position` bytes — the mp3 frame stream resumes cleanly;
   - `read()`: reads the response body and tees the bytes to `<hash>.mp3.partial`;
   - `close()`: on clean end-of-stream renames `.partial` → final cache file; on
     error/cancel deletes it.

7. **`service/TtsPlaybackService.kt`** (new) — `MediaSessionService`:
   - ExoPlayer with `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)`;
   - `MediaSession` with standard callback (play/pause/stop/seek); session token
     exposed via companion object (single-process app);
   - two item kinds: cached file (plain `MediaItem.fromUri`, default data source)
     or live stream (`ProgressiveMediaSource.Factory(TtsDataSource.Factory)`);
   - started with intent extras `title` (message preview), `speed`, plus either the
     file `uri` or the request (endpoint, body) for the live path;
   - metadata: title = preview, artist = "Pi Mobile" (feeds the auto media
     notification);
   - a new play replaces the current item; player + session released in `onDestroy`.

8. **`data/TtsPlayer.kt`** (new) — app-level holder (created in `PiApp`):
   - `state: StateFlow<TtsUiState>`: `Idle | Loading(key, title) | Active(key, title,
     isPlaying, speed)`;
   - `play(key, text, title)`: no-op when TTS is not configured; sanitize → cache hit?
     plain file item : live stream item (Loading) → `startForegroundService` → wait
     for the session token (5 s timeout, small poll) → bind `MediaController`
     (`buildAsync`) → apply persisted speed → play. On failure: error callback
     (snackbar) back to Idle.
   - `toggle()`, `setSpeed(x)` (persist + `controller.setPlaybackParameters(x, 1f)`),
     `stop()` (cancel stream if live, release controller, `stopSelf`).
   - Starting a new message stops the current one.

9. **`ui/theme/Icons.kt`** — add a `VolumeUp` speaker vector (same custom style as the
   other `PiIcons`).

10. **`ui/chat/ChatItems.kt`** — `MessageFooter` gains an optional `onListen` →
    speaker action (label "Listen"). `AssistantMessage` gains `ttsState` + `onListen`:
    the action shows only on final assistant messages (not streaming,
    `stopReason != "toolUse"`) with non-empty text and TTS configured; while
    `Loading(key)` for that message the icon becomes a spinner.

11. **`ui/chat/ChatScreen.kt`** —
    - `ChatTopBar` wrapped in a `Column` with the new **`TtsBar`** directly below it,
      visible while `Loading` or `Active`:
      `[⏸/▶] [title preview …] [1.2x ▾] [✕]` (Telegram layout from the screenshot);
      while `Loading`: spinner + "Synthesizing…" + ✕ (cancels the stream).
    - Speed dropdown anchored to the chip: 0.5 / 1 / 1.2 / 1.5 / 1.7 / 2 with labels
      (Slow / Normal / Medium / Fast / Very fast / Super fast — English, the app's UI
      language; the screenshot is a UX reference only).
    - Receives `tts: TtsPlayer` and wires bar + footer callbacks.

12. **`MainActivity.kt`** — pass `app.tts` into `ChatScreen`.

13. **`PiApp.kt`** — create the `TtsPlayer` in `onCreate`.

14. **`android/README.md`** — document the feature (settings, defaults, streaming +
    TTFC, background behavior, speed, cache).

## Behavior notes

- TTS text = the same text "Copy" uses (joined text blocks), sanitized.
- The bar lives in the chat screen; navigating away keeps playback going through the
  media notification — that is the point of the foreground service.
- Live-stream items have unknown duration until EOF; the bar shows no progress (as in
  the Telegram screenshot), so that is fine.
- Cache key excludes speed, so changing speed never re-synthesizes.
- ExoPlayer's default audio-focus manager pauses playback during ASR dictation or a
  phone call and resumes after (transient focus loss).
- Risks: (1) the MediaController must be built after the service exposes its token
  (`startService` is async) — mitigated by the token wait with timeout before
  `buildAsync`; (2) the custom `TtsDataSource` is the least standard piece — it is a
  documented Media3 extension point ("Customization" docs), and failures surface as
  player errors → snackbar + stop; (3) a mid-stream network drop triggers a retry that
  re-synthesizes from the start and fast-forwards — LAN drops are rare, acceptable.

## Open questions

- Confirm the streaming plan is good to implement.
