# Plan — Android: user bubble missing after follow-up until turn end

## Symptom
In the Android app, sending a follow-up after the agent finished sometimes shows no
user bubble, even though the agent received the message. The bubble reappears only
when the agent's turn completes.

## Root cause
`ChatViewModel.refreshSession()` replaces `messages` with the server snapshot
unconditionally (`messages = fresh`). A snapshot **generated before the new user
message is persisted** can be **processed on the phone after the SSE `message_end`
already appended the delivered user message** — wiping both the optimistic bubble
and the delivered copy until the settle-time reload at turn end.

Snapshot-generating triggers that race an in-flight prompt:

1. **`connected` (reconnect) handler** — the main one. The SSE stream is closed
   30 s after a turn (`IDLE_CLOSE_MS`), so a follow-up sent after reading the
   answer always reconnects. The handler launches `refreshSession()`; that GET is
   sent **before** the prompt POST (ordering in `send()`), so the server reads the
   `.jsonl` before the user message is appended. The pi SDK emits the SSE
   `message_end` *before* persisting (`agent-session.js` `_handleAgentEvent`:
   `_emit` → `appendMessage`), so the snapshot and the SSE event are genuinely
   unordered from the phone's point of view. The web client does **not** reload
   history on `connected` — this is an Android deviation.
2. **Stale turn-tail events** (`agent_end`, `prompt_done`, `agent_settled` →
   `settle`) when the user sends while the client still thinks the previous turn
   is running (also the steering path, which adds no optimistic bubble at all).
3. **`onForeground()`** refresh around send time.

The bug is visible when the stale GET response is processed after the `message_end`
append (large session → slow server file read, Wi-Fi jitter, busy main dispatcher);
otherwise the bubble only flickers for <200 ms.

## Changes (all in `android/`)

### 1. `app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt` — fix A
`onEvent` `"connected"` handler: remove `refreshSession(id)` from the reconnect
branch, keep `reconcile(id)`. History recovery stays covered by turn-boundary
reloads (`agent_end` / `prompt_done` / `agent_settled` → `settle`) and the 15 s
state polling — same posture as the web. Update the comment and the class doc
("state polling and a refetch after every reconnect…" → new wording).

### 2. `app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt` — fix B
`refreshSession()`: make the replace merge-aware.

- Snapshot `localAtRequest = messages` before the GET.
- After parsing `fresh`:
  - `messages === localAtRequest` (no local mutation while in flight) →
    `messages = fresh` (today's behavior).
  - current list has `localAtRequest` as an exact prefix (tail grew: optimistic
    bubble / SSE appends) → `messages = fresh + tail.filterNot { alreadyIn(fresh, it) }`.
  - otherwise (structural change: `loadEarlier` prepend, optimistic removal on
    send failure) → `messages = fresh` (status quo, no regression).
- `alreadyIn` matching:
  - **user**: content key = joined text blocks + image signatures (port of web
    `userMessageKey`, `lib/prompt-recovery.ts`).
  - **assistant**: fingerprint = stopReason + provider + model + joined text +
    ordered toolCall ids (thinking/images excluded — the GET defers them).
  - other roles: never matched (kept).
- Replace the stale "Server history wins" comment with the merge rationale.

Merge helper lives in `app/src/main/java/app/pimobile/data/ChatModel.kt` next to
`Messages` (e.g. `Messages.mergeSnapshot(fresh, localAtRequest, current)` plus the
key/fingerprint helpers) so it is unit-testable without the ViewModel.

### 3. Unit tests (proposed — confirm)
`app/src/test/java/app/pimobile/data/ChatModelTest.kt`:
- unchanged list → fresh wins;
- tail grew with pending optimistic user not in fresh → kept;
- tail grew with delivered user already in fresh → deduped (no duplicate);
- assistant tail already in fresh (fingerprint) → deduped;
- structural change (prepend) → full replace.

## Not touched
- `send()` steering behavior (sending while running stays a feature; with A+B the
  delivered message can no longer be wiped).
- Server/SDK event-vs-persist ordering (pi SDK behavior, out of scope).
- Web client (not affected: no reload on `connected`).

## Verification
- `cd android && ./gradlew assembleDebug` (+ `:app:testDebugUnitTest` if tests added).
- Manual: session → wait >30 s after a turn → send follow-up → bubble appears
  immediately and stays through the turn. Also: fast follow-up at the exact turn
  end (stale-running/steering path).

## Open questions
- Include the unit tests (recommended) or keep the change minimal?
