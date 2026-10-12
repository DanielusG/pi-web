# Plan: UI Refresh — "clean, professional, modern"

Goal: de-age the UI without making it flashy. Reference aesthetic: Linear / Raycast / Vercel —
tinted neutrals, one refined accent, soft depth, consistent geometry, calm micro-interactions.
No redesign of layout/behavior — visual layer only.

## Decisions (confirmed with user)

- **Font**: Geist (via `@fontsource-variable/geist`, local, offline). Noto Sans Mono stays for
  code/paths/technical labels.
- **Dark mode**: deep slate (tinted), not pure gray.
- **Scope**: full app — chat, composer, sidebar, tab bar, modals, file viewer.
- **Approach**: direct in code, hot-reload verification (no separate mockup).

## Diagnosis (why it reads "old & boxy")

1. System font stack (`Segoe UI`/`Roboto`) on everything + Noto Mono labels → 2018 admin-panel look.
2. Flat surfaces + hard 1px borders everywhere, almost no shadow layering.
3. Micro radii (5–8px) with no scale.
4. Achromatic pure gray in both themes.
5. ~67 hardcoded colors outside CSS tokens (`#ef4444`, `rgba(59,130,246,…)`, `#4ade80`…).

## Changes

### Phase 1 — Design tokens (`app/globals.css`, `app/layout.tsx`)

- Install `@fontsource-variable/geist`; import in `globals.css`.
- New palette, slate-tinted:
  - light: `--bg #ffffff`, `--bg-panel #f7f8fa`, `--bg-hover #eef0f4`, `--bg-selected #e7eaef`,
    `--border #e3e6eb`, text `#191c21 / #5f6672 / #9aa1ac`, user bubble `#eef3fd`.
  - dark: `--bg #0f1216`, `--bg-panel #151a20`, `--bg-hover #1c232b`, `--bg-selected #232c37`,
    `--border #273039`, text `#e5e9ef / #8f99a6 / #5c6672`, user bubble `#1a2432`.
- New semantic tokens: `--danger`, `--success`, `--border-strong`.
- New geometry/depth tokens: `--radius-sm 7 / -md 9 / -lg 12 / -xl 14`, `--shadow-sm/-md/-lg`
  (theme-aware), `--ease cubic-bezier(0.3,0.7,0.4,1)`.
- Base font: `"Geist Variable"` + system fallbacks; `-webkit-font-smoothing: antialiased`.
- Scrollbars 4→6px, pill-shaped thumb, alpha-tinted.
- `::selection` with accent tint.
- `themeColor` meta updated (dark → `#0f1216`).

### Phase 2 — Typography pass

- Body 14px Geist; keep Noto Mono only where `var(--font-mono)` is already used.
- No size inflation (density is a feature here); better face + smoothing + token palette does the work.

### Phase 3 — Key surfaces

- **Composer** (`ChatInput.tsx`): card with `--radius-lg`, soft border, `--shadow-md`; send button
  radius 10; control pills radius 10; anchored dropdowns radius 10 + `--shadow-md`.
- **User bubble** (`MessageView.tsx`): asymmetric radius `14 14 4 14`, accent-mixed border via
  `color-mix`, softer fill.
- **Tool blocks**: radius 10, `--tool-bg`, soft border.
- **Sidebar** (`SessionSidebar.tsx`): row radius 9–10, hover/selected using refined tokens.
- **Tab bar** (`TabBar.tsx`): active tab blends into content (`--bg`), inactive muted; close-button
  hover states.
- **Modals/dialogs** (`ModelsConfig`, `PluginsConfig`, `SkillsConfig`, `DirectoryPicker`,
  `ProjectTrustDialog`): radius 12, `--shadow-lg`, border-soft.
- **File viewer toolbar** buttons: radius 9.

### Phase 4 — Micro-interactions + token hygiene

- Unify hover/active transitions (~150ms, `--ease`) on primary interactive elements.
- Replace hardcoded hex/rgba with tokens (`--danger`, `--success`, `color-mix(var(--accent) …)`).
- `npm run lint` + `tsc --noEmit` clean at the end.

## Non-goals

- No layout restructuring, no behavior changes, no Tailwind-class migration of inline styles.
- No glassmorphism/gradients/animation flourishes.
