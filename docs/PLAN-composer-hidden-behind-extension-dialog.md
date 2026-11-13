# Plan — keep the composer mounted (hidden) behind the inline extension dialog

## Why

`npm run test:e2e` had two separate problems.

1. **Environment (already fixed, no code):** `.next/dev/lock` was stale — it held
   `{"pid":232913,"port":30141}` from a `next dev` killed on 2026-10-23, the pid was dead
   (`ESRCH`), nothing listened on 30141 and `flock -n` on the file succeeded, so nobody held it.
   `e2e/run.mjs:30` only checks `existsSync`, so it refused: `Use a checkout without an active
   dev server`. The lock was copied to `/tmp/tmp.wVqypekErd/dev-lock.stale` and removed; the suite
   then started and passed its first six groups.

2. **Fork deviation (this plan):** the upstream v0.16.0 block added by #1537
   (`e2e/extension-dialog.mjs:121-139`) reads the composer draft with
   `page.locator("textarea").last()` **while its dialog is open**. Upstream's overlay leaves the
   composer mounted; this fork renders `{askUserDialogElement ?? extensionDialogElement ??
   chatInputElement}` (`components/ChatWindow.tsx:1634`), so the composer unmounts and no
   `textarea` exists → `TimeoutError` after 30 s. `test-results/e2e/failure.png` shows only the
   inline card.

   The same unmount is a behaviour gap, not only a selector problem:
   `set_editor_text` (`hooks/useAgentSession.ts:1605`) and `editor_paste` (`:2289`) are imperative
   calls through `opts.chatInputRef.current`, which is `null` while the composer is unmounted —
   an extension writing pi's editor during its own dialog is silently dropped. pi's TUI keeps the
   editor alive under its dialog. AGENTS.md's compatibility principle says extensions written for
   the CLI must behave the same here.

## Facts measured before choosing

- `lib/draft-store.ts` keeps drafts in a module `Map`, not `localStorage`: the browser test cannot
  peek at the draft from outside, so a mid-dialog read needs a DOM element.
- Playwright against a `textarea` in various states: `hidden` / `visibility:hidden` / `hidden inert`
  → `inputValue()` OK, `evaluate()+setSelectionRange()` OK, `focus()` succeeds but
  `document.activeElement` stays outside (no keyboard can enter), `fill()` times out. An
  off-screen element → `fill()` OK, `focus()` really focuses it (would steal the dialog's focus).
- `ChatInput`'s `ResizeObserver` (`components/ChatInput.tsx:1127`) remeasures on **width** changes,
  so the textarea re-grows itself when it becomes visible again.
- `replaceTextareaRange` (`:191`) tries `document.execCommand("insertText")` and falls back to
  `setTextareaRange` — a hidden field cannot take focus, so a mid-dialog write lands through the
  plain fallback (text lands, no undo entry).
- Esc handling (`hooks/useKeyboardShortcuts.ts`) is focus/target based and deliberately ignores Esc
  inside `textarea`/`input`; an inert composer never originates events.
- DOM order after the change: dialog card first, then the hidden composer → `locator("textarea").last()`
  resolves to the composer, which is what upstream's block wants.

## Decision (agreed)

Option C: the composer stays **mounted but `hidden inert`** behind the card while a blocking dialog
owns the composer slot. `chatInputRef.current` stays alive, so `setEditorText()` / `pasteToEditor()`
land live mid-request; upstream's #1537 block passes unchanged; the visible layout is identical to
today's (a `hidden` wrapper is `display:none`, out of the accessibility tree and out of keyboard
reach). No new extension API surface.

## Changes

1. **`components/ChatWindow.tsx`** (~line 1634, the composer slot):
   `askUserDialogElement ?? extensionDialogElement ?? chatInputElement` becomes: when either
   blocking element exists, render it and then the composer inside
   `<div hidden inert={true}>`; otherwise the composer alone. Short comment naming pi's TUI editor
   parity. The native `ask_user_question` dialog shares the same treatment (it is part of the
   blocking element) and keeps its precedence over the extension dialog.

2. **`components/ChatWindow.extension-request.test.mjs`** (line ~15): re-aim the slot assertion to
   the new JSX and add that the wrapper carries **both** `hidden` and `inert={true}` — the pair is
   what keeps keyboard and screen readers out, so a future merge must not drop one of them.

3. **`docs/agents/chat-ui.md`** (first section): rewrite "The input bar unmounts while a blocking
   request is open" into the mounted-hidden-inert form, and record the measured facts (why
   `hidden`+`inert` rather than off-screen, `inputValue()` reading a hidden field, `focus()` being a
   no-op, the `ResizeObserver` re-growth, and `execCommand` falling back so mid-dialog writes land
   without an undo entry). Android keeps its own inline rendering and is untouched.

4. **`docs/agents/sessions.md`** (~line 251, "the composer is pi's editor for `ctx.ui`"): state that
   `setEditorText()` / `pasteToEditor()` reach the composer even while the slot holds a dialog.

5. **`e2e/extension-dialog.mjs`**: upstream's #1537 block stays as merged (no re-aim). Add one
   fork-only assertion pinning the deviation in the browser: while the `editor-text` dialog is
   open, the composer's textarea is present **and hidden** (`isHidden()`), so its
   `inputValue()` proves the fork keeps the editor alive behind the card.

6. **`e2e/README.md`** (coverage line ~77): extend the `setEditorText`/`pasteToEditor` sentence to
   say the check runs while the dialog holds the composer slot.

## Verification

`tsc --noEmit` → `npm run lint` → `npm test` → `npm run test:e2e` to the end, reporting every
PASS/FAIL group (composer-focus, chat-appearance, model*, directory-picker, project-navigation and
themes are still unverified past the dialog block).

## Open questions

- None blocking. If the full e2e run reveals further fork/upstream clashes, they come back here as
  separate items rather than being patched silently.
