# Plan: configurable chat font size (Android app)

Make the chat text size configurable in the native Android client, scoped to the chat
surface only. Settings live locally in the app (DataStore); no API changes.

## Decisions (agreed with Daniele)

- **Scope:** chat only — messages, code blocks, tool output, composer. App chrome
  (labels, timestamps, tool names, session list, file screens) stays fixed.
- **Control:** A− / A+ step buttons in Settings.
- **Range:** 0.5× – 1.5× of the base size, step 0.1×, default 1.0×
  (chat body 16sp → 8–24sp). Daniele explicitly wants to be able to shrink a lot.

## Mechanism

A `LocalChatFontScale` composition local (default `1f`) provided around `ChatScreen`.
Everything that should scale reads it and multiplies the base `TextUnit`; em-based
sizes (inline code 0.9em) and styles derived from the body font (headings, lists,
LaTeX) scale automatically.

## Files (in order)

1. **`android/app/src/main/java/app/pimobile/data/Settings.kt`**
   - New `floatPreferencesKey("chat_font_scale")` in `SettingsStore` (same store/pattern
     as `ttsSpeed`).
   - `val chatFontScale: Flow<Float>` — missing key → `1f`.
   - `suspend fun saveChatFontScale(scale: Float)` — clamps to 0.5f..1.5f before writing.
   - Constants `MIN_CHAT_FONT_SCALE = 0.5f`, `MAX_CHAT_FONT_SCALE = 1.5f`,
     `CHAT_FONT_SCALE_STEP = 0.1f` (companion or top-level).

2. **`android/app/src/main/java/app/pimobile/ui/theme/Theme.kt`**
   - `val LocalChatFontScale = staticCompositionLocalOf { 1f }`.

3. **`android/app/src/main/java/app/pimobile/MainActivity.kt`**
   - In the chat nav destination: collect `app.settings.chatFontScale`
     (`collectAsStateWithLifecycle`) and wrap the `ChatScreen(...)` call in
     `CompositionLocalProvider(LocalChatFontScale provides scale) { ... }`.
   - Other screens are untouched: the local defaults to 1f and they never read it.

4. **`android/app/src/main/java/app/pimobile/ui/markdown/Markdown.kt`**
   - Read `val scale = LocalChatFontScale.current`.
   - `body = typography.bodyLarge.copy(color = t.text, fontSize = typography.bodyLarge.fontSize * scale)`.
   - Headings, bullets, quote, lists derive from `body` → automatic.
   - LaTeX configs key on `body.fontSize` → automatic; `cellMath` switches to
     `typography.bodyMedium.fontSize * scale`.
   - `code = CodeTextStyle.copy(color = t.text, fontSize = CodeTextStyle.fontSize * scale)`.
   - `table = typography.bodyMedium.copy(color = t.text, fontSize = typography.bodyMedium.fontSize * scale)`.
   - `CodeSpanPainter` / `InlineCodeSize` (0.9em) → automatic, no change.
   - Add `scale` to the `remember(t, typography, headings)` key for `markdownTypography`.
   - `StreamingMarkdown` delegates to `Markdown` → covered with no change.
   - `FileViewerScreen` uses `LazyMarkdown` outside the provider → unaffected (1f).

5. **`android/app/src/main/java/app/pimobile/ui/chat/ChatItems.kt`**
   - Plain user-message text (~line 115) and tool-call args (~line 261):
     `bodyLarge.copy(fontSize = bodyLarge.fontSize * scale)`.
   - `MonoSmall` (13sp) and `MonoOutput` (12.5sp) are top-level `TextStyle` vals, so
     where they are used inside composables apply
     `.copy(fontSize = base.fontSize * LocalChatFontScale.current)` (or a small
     composable helper if that reads cleaner at the ~5 use sites).
   - Left fixed: `labelSmall`-based chrome (timestamps, tool names, file path labels,
     diff +/- counts stay readable at any scale).

6. **`android/app/src/main/java/app/pimobile/ui/chat/ToolViews.kt`**
   - `DiffText` (12sp): scale `fontSize` the same way (diff output is chat content).

7. **`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`**
   - Composer `BasicTextField` `textStyle` and its placeholder `Text`: × scale.
   - Extension-dialog option name (`bodyLarge`, ~line 1709): × scale; dialog
     `Markdown` bodies scale automatically.

8. **`android/app/src/main/java/app/pimobile/ui/settings/SettingsScreen.kt`**
   - Collect `app.settings.chatFontScale` with `collectAsStateWithLifecycle`.
   - New row after the TTS section: `FieldLabel("Chat font size")` + a
     `Row { TextButton("A−") · Text("120%") · TextButton("A+") }`.
   - Buttons clamp at 0.5/1.5 (disabled at the bounds); each tap persists via
     `scope.launch { app.settings.saveChatFontScale(...) }`.
   - Display as rounded percent of 16sp base (e.g. `120%`).

## Verification

- `./gradlew :app:compileDebugKotlin` (or `assembleDebug`) — compiles.
- `./gradlew :app:testDebugUnitTest` — existing pure-logic tests stay green
  (none touch the UI; a trivial clamp test for `saveChatFontScale` only if it
  lands in testable code — default: no new tests, the change is UI plumbing).
- Manual: open Settings, step down to 50% and up to 150%, check a session with
  long messages, code blocks, LaTeX, tool output, and the composer; confirm the
  session list / file screens / file viewer are unchanged.

## Out of scope

- Scaling app-wide chrome, file viewer, or labels.
- Server-side / sync of the preference (stays device-local like the rest of
  `chatStore`).
