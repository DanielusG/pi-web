# Plan: Android voice dictation (Nemotron ASR)

Voice input for the Android client via long-press on the send button (WhatsApp-style),
streaming tokens from the Nemotron 3.5 ASR server into the composer draft live.

**ASR server**: already running at `ws://192.168.1.56:8000/ws` (laptop, RTX 2060).
Protocol (unchanged): `start{language}` → `ready`; `audio{base64 int16 mono 16kHz}` →
`text{token}` stream; `stop` → `done{full_text}`. No server-side changes.

**Scope**: Android only. Web voice input is deferred (separate plan later).

## UX

- **Quick tap** on send: send (or stop agent) — unchanged.
- **Long-press** (>300 ms) on send: dictation starts. Button turns into a pulsing mic.
  Transcribed tokens are inserted **live at the cursor position** with a leading space
  (if the char before the cursor is not whitespace). User watches the text appear.
- **Release**: `stop` is sent, server flushes held-back RNNT tokens (~1 s), text stays
  in the draft, editable. Send is manual.
- Long-press while agent running (stop-button mode): tap = stop agent, long-press =
  dictation (steering text).
- `asrUrl` empty → long-press does nothing (button unchanged).
- Server unreachable / error → toast, dictation ends, partial text kept.
- Language: `it-IT` (server default), hardcoded for now.

## Files (in order)

1. **`android/app/src/main/AndroidManifest.xml`**
   Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.

2. **`android/app/src/main/java/app/pimobile/data/Settings.kt`**
   - `ServerConfig(baseUrl, password, asrUrl = "ws://192.168.1.56:8000/ws")`.
   - `normalizeAsrUrl(raw)`: trim, strip trailing `/`; bare `host:port` → `ws://host:port`;
     `ws://`/`wss://` pass through; empty stays empty (dictation disabled).
   - `SettingsStore`: new `asr_url` key, persisted in `save()`.
   - Update `ServerConfig(...)` constructor call sites (SettingsScreen uses positional args).

3. **`android/app/src/main/java/app/pimobile/ui/settings/SettingsScreen.kt`**
   New "Voice input (ASR)" field after the password field: `OutlinedTextField`,
   placeholder `ws://192.168.1.56:8000/ws`, helper text "WebSocket ASR server for voice
   dictation. Leave empty to disable." Saved with the same Connect flow.

4. **New `android/app/src/main/java/app/pimobile/data/AsrClient.kt`**
   - OkHttp WebSocket to `$asrBaseUrl/ws` (OkHttp 4.12 already a dependency).
   - `start(onToken, onDone, onError)`: send `start{language:"it-IT"}`, await `ready`
     (3 s timeout), open `AudioRecord` (16 kHz, MONO, PCM_16BIT), read loop on
     `Dispatchers.IO` sending 100 ms blocks (3200 bytes) base64-encoded as `audio`.
   - `text` → `onToken` (main thread); `done` → `onDone(full_text)`; `error` → `onError`.
   - `stop()`: send `stop`, keep reading until `done` (15 s timeout), close, release mic.
   - `cancel()`: abort without stop (release-before-ready, permission denied).
   - Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT` on start, abandon on stop.

5. **`android/app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt`**
   - `private var asr: AsrClient? = null`, `val dictating: StateFlow<Boolean>`.
   - `hasAsr(): Boolean` from `api.config.asrUrl`.
   - `startDictation(onToken: (String) -> Unit)` / `stopDictation()`.
   - `onCleared()`: cancel any live session.

6. **`android/app/src/main/java/app/pimobile/ui/theme/Icons.kt`**
   Add `Mic` line icon.

7. **`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`**
   - `ActivityResultContracts.RequestPermission()` launcher for `RECORD_AUDIO`.
   - Composer send button: replace `.clickable` with a custom `pointerInput`
     (`awaitEachGesture`): press-down starts a 300 ms timer; release before timer →
     original action; timer fires → (asrUrl set? permission granted? → start dictation;
     else request permission, start when granted if still pressed, cancel on release).
     Release while dictating → `stopDictation()`.
   - While dictating: mic icon in the button (accent tint, pulsing), send arrow hidden.
   - Draft insertion: on start, `anchor = draft.selection.min`; each token inserts at
     `anchor + accumulatedLen` (first token prefixed with a space when needed); cursor
     kept at the end of the segment.

## Not in scope (now)

- Web voice input (browser AudioWorklet capture) — later.
- Language picker (it-IT hardcoded), silence auto-stop, saving audio.

## Verification

- `cd android && ./gradlew compileDebugKotlin` (fast) / `assembleDebug`.
- Manual on phone: tap vs long-press, live text at cursor, release flush, permission
  flow on first use, server-down toast, empty asrUrl.
