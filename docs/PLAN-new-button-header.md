# Plan: Move "New" button from sidebar to top bar (plus icon only)

Follow-up to `docs/PLAN-header-cleanup.md`. Surface-level move: the session
composer action lives in the main header on both viewports so it is always
reachable, even with the sidebar closed. Icon-only (plus), no text label.

## Resulting layout

- Desktop top bar: sidebar toggle | **+** | theme | [trust warning] | Branches
  | session stats | file toggle
- Mobile top bar: sidebar toggle | **+** | more (…) | session stats | file
  toggle. The "+" is a direct toolbar button, *outside* the floating "more"
  layer, so it is always visible (never covered by the overlay).

## Changes

### `components/AppShell.tsx`
- `handleHeaderNewSession`: guard on `activeCwd`, generate a temp UUID
  (same `crypto.randomUUID` fallback pattern the sidebar used), then call the
  existing `handleNewSession(tempId, activeCwd)` — the same code path as the
  Ctrl+Alt+N shortcut (`useGlobalKeyboardShortcuts`).
- `renderNewSessionButton()`: icon-only plus button styled like the other
  top-bar icon buttons (`TOP_BAR_ICON_BUTTON_SIZE`, `borderRight`,
  muted → accent hover). `disabled` when no cwd is selected.
  Tooltip/aria-label: `sidebar.newSessionTitle` (with the path) when a
  project is active, `sidebar.selectProject` otherwise — same semantics as
  the old sidebar button. Marker: `data-topbar-new-session="true"`.
- Placement: desktop — after the sidebar toggle, before the theme button;
  mobile — after the sidebar toggle, before the `mobileToolbarRef` container.
- Drop `onNewSession={handleNewSession}` from the `<SessionSidebar>` props.

### `components/SessionSidebar.tsx`
- Remove the "New" button from the sidebar header (leaves `PiWebTitle` +
  refresh button).
- Remove the now-dead `handleNewSession` callback and the `onNewSession`
  prop (type + destructure).

### i18n (`lib/i18n/messages/en.ts`, `zh-CN.ts`)
- Remove the now-unused `sidebar.new` key. The header button reuses the
  existing `sidebar.newSessionTitle` / `sidebar.selectProject` keys.

### `components/AppShell.mobile-toolbar.test.mjs`
- Two tests pre-dated this change and were already failing because they still
  asserted controls removed by the header-cleanup plan
  (`history` / `name` / `system` / `language` actions,
  `handleViewFullHistory` / `handleAutoName` handlers). Updated them to the
  current toolbar: overlay actions are `branches` + `theme`, and the new
  assertions cover the always-visible `renderNewSessionButton` placement on
  both viewports.

## Decisions
- Cwd source is `activeCwd` (identical to the sidebar's `selectedCwd` in all
  paths — project click, session select, new-session cwd — and to what
  Ctrl+Alt+N already uses). Disabled state matches: no cwd selected →
  button dimmed, tooltip "Select project…".
- "+" is placed left (after the sidebar toggle) on both viewports, not next
  to the right-hand context controls.
- Other pre-existing test failures (auto-name ×2, ImagePreview safe-area,
  useAgentSession System) are unrelated and left untouched.

## Verification
- `node_modules/.bin/tsc --noEmit` — clean
- `npm run lint` — clean
- `npm test` — mobile-toolbar file 6/6 green; overall 594 pass / 4 fail
  (all 4 pre-existing, unrelated)
- Manual: desktop and mobile top bars show the plus after the sidebar
  toggle; click creates a new session in the active cwd; disabled without a
  project; mobile "more" overlay does not cover it.
