# Plan: Android extension dialog inline (web parity)

## Goal

Replace the full-screen `BasicAlertDialog` extension dialog in the Android client
(`android/`) with the same inline implementation the web uses: the dialog lives in
the composer slot, **replacing the input bar**, with an expanded card + collapsed
pill. Back button (expanded state) = cancel, mirroring web's Esc.

## Web reference (components/ChatWindow.tsx)

- Composer slot: `{extensionDialogElement ?? chatInputElement}` (line 1420) — input bar unmounted while dialog open.
- Expanded card: width 100%, `maxHeight: min(45vh, 360px)`, radius 10, border, shadow.
  - Header: title (14px/650) + mono dim badges ("extension request", expiry countdown) + 26px collapse chevron button.
  - Body (scrollable): confirm → markdown message; select → bordered option buttons (markdown content); input → single-line field (Enter submits, Esc cancels); editor → mono textarea min 220px (Cmd/Ctrl+Enter submits, Esc cancels).
  - Footer: Cancel + Confirm/Submit (select has no submit button).
- Collapsed pill: full-width, radius 10, accent-mixed border, panel bg; pulsing 8px accent dot, title (13px/650), summary (first option / first non-empty message line), countdown, mono "request" label. Tap expands.
- State resets per request (value + collapsed reset on `[request]`).

## Changes

### 1. `android/app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt`

- `ExtensionDialog` data class (line 63): add `expiresAt: Long?` (epoch ms, as the web type uses).
- `onExtensionRequest` (line ~756): parse `expiresAt = event.long("expiresAt")` (helper exists in `data/Json.kt`).

### 2. `android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`

- `ChatScreen` (line ~187): `bottomBar = { if (state.dialog != null) ExtensionDialogView(state.dialog, vm) else Composer(...) }`.
- Remove modal invocation at line 278 (`state.dialog?.let { dialog -> ExtensionDialogView(dialog, vm) }`).
- Rewrite `ExtensionDialogView` (lines 757–826) as an inline composable:
  - Outer frame identical to `Composer`'s: `background(t.background)`, `navigationBarsPadding()`, `imePadding()`, padding 12/12/4/10 dp.
  - **Collapsed pill**: full-width clickable Row, radius 10, border (accent-mixed), `t.surface` bg; `StatusDot(t.primary, pulsing = true)` (reuse from `ui/theme/Components.kt`), title 13sp medium, summary (first option for select / first non-empty line for confirm), countdown, mono "request" label. Click → expand.
  - **Expanded card**: radius 10, border, bg, shadow, `heightIn(max = min(360.dp, 0.45 × screen height))`.
    - Header Row: title 14sp w650 + mono dim badge row ("extension request" + countdown) + 26dp collapse button (chevron icon, bordered).
    - Body (verticalScroll, weighted):
      - confirm → `Markdown(message)` (reuse `ui/markdown/Markdown.kt`).
      - select → Column of option buttons (border, surface, radius 9, `Markdown` content, 13sp).
      - input → single-line `OutlinedTextField` (autofocus, IME action Done → submit).
      - editor → multi-line `OutlinedTextField` (autofocus, minLines 6, mono font, IME action Send → submit).
    - Footer Row (top border, surface bg, end-aligned): `PiSecondaryButton("Cancel")` + `PiPrimaryButton("Confirm"|"Submit")` (no submit for select).
  - State: `collapsed` via `rememberSaveable(dialog.id)` (default false); `text` already keyed by `dialog.id`; reset both when a new request arrives (key change handles it).
  - Countdown: `now` state + `LaunchedEffect(dialog.expiresAt)` ticking 1s while non-null; render "in Ns" next to the badge (web uses i18n; Android has no i18n layer yet → plain string).
  - Back: `BackHandler(enabled = dialog != null && !collapsed) { vm.respondDialog(cancelled = true) }` — expanded only, matching web (Esc handler exists only on the expanded card).
- `vm.respondDialog(value/confirmed/cancelled)` untouched; `extension_ui_closed` handling untouched.
- Drop `BasicAlertDialog` import if no longer used.

### 3. `android/app/src/main/java/app/pimobile/ui/theme/Components.kt`

- No changes expected — reuse `StatusDot`, `PiPrimaryButton`, `PiSecondaryButton`, `piTextFieldColors`. Add a chevron-down icon to `Icons.kt` if not present.

## Out of scope

- `custom` panel (web's `ExtensionCustomPanel`) — Android doesn't support it yet (VM comment: "not in this client yet").
- i18n strings for Android.

## Verification

- `cd android && ./gradlew :app:assembleDebug` compiles.
- Manual: run app against a live pi-web and trigger an extension dialog; check expanded/collapsed, countdown, back=cancel, composer restored after response/close.
