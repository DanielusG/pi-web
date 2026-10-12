# Plan: Top-header polish — remove System, Full history, Generate title, translate buttons

Follow-up to `docs/PLAN-ui-refresh.md`. Visual/surface-only change: declutter the top bar.
No layout restructuring of the remaining controls.

## Resulting top bar

- Desktop: sidebar toggle | theme | [trust warning] | Branches | session stats | file toggle
- Mobile: sidebar toggle | more (…) | session stats | file toggle; the expanded "more"
  overlay will contain only the Branches button.

## Changes

### `components/AppShell.tsx`
- `renderChatToolbarActions`: remove Full history button, Generate title IIFE button,
  System button (desktop + mobile). BranchNavigator (desktop inline / mobile button) stays.
- Remove `renderLanguageButton` + both call sites (desktop bar, mobile toolbar).
- Remove `activeTopPanel === "language"` and `activeTopPanel === "system"` dropdown blocks.
- Remove dead state/handlers:
  - `handleViewFullHistory`
  - `handleAutoName`, `autoNameStatus`, `autoNameTimerRef`, `activeSessionIdRef`,
    `AutoNameStatus` type, auto-name reset effect (session change), auto-name timer
    cleanup (keep sessionCopyTimerRef cleanup in unmount effect)
  - `systemPrompt`, `systemPromptLoading`, `systemPromptLoaderRef`, `systemPromptLoadIdRef`,
    `systemBtnRef`, `handleSystemPromptChange`, `handleSystemPromptLoaderChange`,
    `handleSystemPromptToggle`, and the `setSystemPrompt(null)/setSystemPromptLoading(false)`
    pairs in handleCwdChange / handleSelectSession / handleNewSession / handleSessionDeleted
  - `languageBtnRef`, `LANGUAGE_MENU_WIDTH`, language-specific branch in the top-panel
    position effect
- Narrow `activeTopPanel` / `toggleTopPanel` union to `"branches" | "session"`.
- `useI18n()` destructure → `{ locale, t: translate }`.
- Stop passing `onSystemPromptChange` / `onSystemPromptLoaderChange` to ChatWindow.

### `components/ChatWindow.tsx`
- Drop `onSystemPromptChange` / `onSystemPromptLoaderChange` props (type, destructure,
  passthrough to useAgentSession).

### `hooks/useAgentSession.ts`
- Drop the two callback options + the two effects invoking them (~L1843–1850).
- Internal `systemPrompt` state stays (synced across multiple state paths; only the
  outward plumbing is removed).

### i18n (`lib/i18n/messages/en.ts`, `zh-CN.ts`)
- Remove unused keys: `common.language`, `history.full/unsaved/label`,
  `title.*` (7), `system.*` (5).

## Out of scope / decisions
- `/api/sessions/[id]/export` (full-history HTML) — kept.
- `/api/sessions/[id]/auto-name` — kept (user decision: keep as potentially useful,
  e.g. for re-exposing title generation elsewhere later).
- `useAgentSession` keeps its internal `systemPrompt` state (synced in several state
  paths and returned); only the outward `onSystemPrompt*` plumbing was removed.
  `loadSystemPrompt` was deleted with it (its only consumer was the removed effect).

## Verification
- `node_modules/.bin/tsc --noEmit`
- `npm run lint`
- Manual: top bar renders Branches + stats + file toggle; mobile "more" overlay shows
  only Branches; no console errors on session switch / project switch.
