import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, tsconfigPaths: true });
const React = await jiti.import("react");
const { renderToStaticMarkup } = await jiti.import("react-dom/server");
const { I18nProvider } = await jiti.import("@/hooks/useI18n");
const { SessionSidebar, nearestEnabledKey, handOffDisabledKeyFocus, sameIdsOr } = await jiti.import("./SessionSidebar.tsx");
const { buildSessionTree, getRowOffsets, getVisibleRowIndices } = await jiti.import("@/lib/session-tree.ts");
const { SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH } = await jiti.import("@/lib/panel-layout.ts");

const source = await readFile(new URL("./SessionSidebar.tsx", import.meta.url), "utf8");
const globalStyles = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
const sidebarStyles = await readFile(new URL("../app/sidebar.css", import.meta.url), "utf8");
const explorerSource = await readFile(new URL("./FileExplorer.tsx", import.meta.url), "utf8");
const appShellSource = await readFile(new URL("./AppShell.tsx", import.meta.url), "utf8");

const h = React.createElement;
const noop = () => {};
const BASE = Date.parse("2026-10-01T00:00:00.000Z");

function between(startAnchor, endAnchor) {
  const start = source.indexOf(startAnchor);
  const end = source.indexOf(endAnchor, start + startAnchor.length);
  assert.notEqual(start, -1, `${startAnchor} not found`);
  assert.notEqual(end, -1, `${endAnchor} not found after ${startAnchor}`);
  return source.slice(start, end);
}

/** A sidebar callback's body: from its declaration to the next `const` of the component. */
function callbackBody(name) {
  return between(`const ${name} = `, "\n  const ");
}

function session(id, { project = "/work/alpha", modified = BASE, ...rest } = {}) {
  const time = new Date(modified).toISOString();
  return {
    path: `${project}/${id}.jsonl`,
    id,
    cwd: project,
    projectRoot: project,
    projectKey: project,
    created: time,
    modified: time,
    messageCount: 1,
    firstMessage: id,
    ...rest,
  };
}

function treeInput(overrides = {}) {
  return {
    sessions: [],
    uiState: { version: 1, revision: 0, sessions: {}, projects: {} },
    runningIds: new Set(),
    unreadIds: new Set(),
    selectedSessionId: null,
    currentProject: null,
    groupExpansion: {},
    moreShown: {},
    pinnedCollapsed: false,
    ...overrides,
  };
}

function render(props = {}) {
  return renderToStaticMarkup(h(I18nProvider, null, h(SessionSidebar, {
    selectedSessionId: null,
    onSelectSession: noop,
    ...props,
  })));
}

/** The opening tag of the element with this id. */
function openingTag(html, id) {
  const match = html.match(new RegExp(`<[a-z]+ [^>]*id="${id}"[^>]*>`));
  assert.ok(match, `#${id} not rendered`);
  return match[0];
}

test("scrolling keeps the focused session and the viewport mounted without expanding the whole tree", () => {
  const sessions = Array.from({ length: 2000 }, (_, index) => session(`s${index}`, { modified: BASE - index * 60_000 }));
  const { rows } = buildSessionTree(treeInput({ sessions, currentProject: { key: "/work/alpha", root: "/work/alpha" }, moreShown: { "/work/alpha": 5000 } }));
  const offsets = getRowOffsets(rows, "desktop");
  const lastSessionIndex = rows.findLastIndex((row) => row.kind === "session");
  for (const [scrollTop, focusedIndex] of [[0, lastSessionIndex], [30000, 1]]) {
    const indices = getVisibleRowIndices(offsets, scrollTop, 335, 240, [focusedIndex]);
    for (let index = 0; index < rows.length; index++) {
      const intersects = offsets[index + 1] > scrollTop && offsets[index] < scrollTop + 335;
      if (intersects) assert.ok(indices.includes(index), `row ${index} in the viewport is mounted`);
    }
    assert.ok(indices.includes(focusedIndex), "an inline rename survives scrolling");
    assert.ok(indices.length < 60, "only a window of the tree is mounted");
    assert.equal(new Set(indices).size, indices.length);
    assert.deepEqual(indices, [...indices].sort((a, b) => a - b));
  }
});

test("subagents fold into their main session row, which carries their running, unread and selected state", () => {
  const child = session("child", { relation: { kind: "subagent", parentSessionId: "main", profile: "explore", description: "", status: "running" } });
  const { rows } = buildSessionTree(treeInput({
    sessions: [session("main"), child],
    runningIds: new Set(["child"]),
    unreadIds: new Set(["child"]),
    selectedSessionId: "child",
    currentProject: { key: "/work/alpha", root: "/work/alpha" },
  }));
  const sessionRows = rows.filter((row) => row.kind === "session");
  assert.deepEqual(sessionRows.map((row) => row.family.root.id), ["main"]);
  assert.deepEqual(sessionRows[0].status, { running: true, awaiting: false, unread: true, selected: true, transient: false });
});

test("only Shift skips the session deletion confirmation", () => {
  assert.match(
    callbackBody("requestDelete"),
    /if \(shiftKey\) \{\s*void performDelete\(family\);\s*\} else \{[\s\S]*?setConfirmDeleteRootId\(family\.root\.id\);/,
  );
});

test("on a desktop the files sit below the sessions by default, both in view", () => {
  // The server renders the default placement on a desktop (useFilesPlacement
  // and useIsMobile's server snapshots): the stacked layout.
  const html = render({ selectedCwd: "/work/alpha", onOpenTerminal: noop });
  assert.match(html, /^<div class="session-sidebar is-files-below" style="--sidebar-files-height:320px">/);
  // No tabs: the brand (its name says the versions a click shows), then New
  // and the sessions' search. No view options.
  assert.match(html, /<div class="sidebar-header"><button type="button" class="sidebar-brand" aria-label="Pi Web, versions: web [^",]+, pi [^",]+">Pi Web<\/button><span class="sidebar-header-spacer"><\/span><button type="button" class="sidebar-new-button"/);
  assert.doesNotMatch(html, /role="tablist"|role="tab"|role="tabpanel"|View options|sidebar-title/);
  assert.match(html, /<span class="sidebar-new-label">New<\/span><\/button><button type="button" title="Search conversations" aria-label="Search conversations" aria-expanded="false" aria-controls="session-search-input" class="sidebar-search-toggle">/);
  // Two regions, both shown: the sessions, the separator, the files.
  const sessions = openingTag(html, "session-sidebar-panel-sessions");
  const files = openingTag(html, "session-sidebar-panel-files");
  assert.equal(sessions, '<div id="session-sidebar-panel-sessions" role="region" aria-label="Sessions" class="sidebar-panel sidebar-sessions-panel">');
  assert.equal(files, '<div id="session-sidebar-panel-files" role="region" aria-label="Files" class="sidebar-panel sidebar-files-panel">');
  const separator = html.slice(html.indexOf('role="separator"') - 200, html.indexOf(files));
  assert.ok(html.indexOf(sessions) < html.indexOf('role="separator"') && html.indexOf('role="separator"') < html.indexOf(files), "sessions, separator, files");
  assert.match(separator, /<div aria-label="Resize file browser" aria-orientation="horizontal" aria-valuemax="2000" aria-valuemin="220" aria-valuenow="320" aria-valuetext="320 px" role="separator" tabindex="0" aria-controls="session-sidebar-panel-files" class="panel-resize-handle sidebar-files-resize-handle" data-resize-handle="sidebar-files" title="Resize file browser: Drag to resize\. Double-click or press Enter to reset\."><\/div>$/);
  // The files section: one header row (folds it; the files' keys at its
  // end in the files tab's order, the file search last), then its body: the
  // picker's one row and the tree. No row of keys under the picker; nothing
  // changed, so no count after the label.
  const filesPanel = html.slice(html.indexOf(files) + files.length);
  assert.match(filesPanel, /^<div class="sidebar-files-section"><button type="button" class="sidebar-files-section-toggle" aria-expanded="true" aria-controls="session-sidebar-files-body"><svg width="12" height="12"[^>]*stroke-width="3"[^>]*class="session-tree-chevron is-open"[^>]*>[\s\S]*?<\/svg><span class="sidebar-files-section-label">Files<\/span><\/button><div class="sidebar-files-keys" role="group" aria-label="File actions"><button type="button" title="Open workspace terminal" aria-label="Open workspace terminal" class="sidebar-tool-button">/);
  assert.match(filesPanel, /<div id="session-sidebar-files-body" class="sidebar-files-body"><div class="sidebar-files-head"><div class="project-picker is-row" role="group"/);
  assert.match(filesPanel, /<div class="sidebar-files-scroll scrollbar-subtle">/);
  assert.doesNotMatch(filesPanel, /sidebar-files-toolbar|sidebar-files-title|sidebar-files-actions|sidebar-files-section-search|sidebar-files-changes/);
  assert.doesNotMatch(filesPanel, /sidebar-files-section-count/);
  // Pins and archive not loaded yet: the tree waits instead of flashing archived rows.
  assert.match(html, /<div class="session-tree-message">Loading\.\.\.<\/div>/);
  // Without a folder the files' search has nothing to search.
  assert.match(render(), /<button type="button" disabled="" title="Search files" aria-label="Search files" aria-expanded="false" aria-controls="file-search-input" class="sidebar-tool-button">/);
});

test("the fold's and the archive view's motions in the CSS last as long as the hook's phase", async () => {
  const { SIDEBAR_MOTION_MS } = await jiti.import("@/hooks/useSidebarMotion.ts");
  // A phase ends on its element's own end event, else a little after
  // SIDEBAR_MOTION_MS (hooks/useSidebarMotion.ts): every motion it waits for
  // lasts that long.
  for (const [selector, property] of [
    [".session-sidebar.is-files-below.is-files-folding .sidebar-files-panel", "transition"],
    [".session-sidebar.is-files-below.is-files-folding .sidebar-toast-region", "transition"],
    [".sidebar-archive-view.is-entering", "animation"],
    [".sidebar-archive-view.is-leaving", "animation"],
  ]) {
    assert.match(sidebarRule(selector), new RegExp(`(?:^|\\s)${property}: [\\w-]+ ${SIDEBAR_MOTION_MS}ms `), selector);
  }
});

test("still, the sidebar renders as it did before it moved: nothing inert, no fold under way, no archive view", () => {
  const html = render({ selectedCwd: "/work/alpha" });
  assert.doesNotMatch(html, / inert[ =>]|is-files-folding|sidebar-archive-view|is-entering|is-leaving/);
  assert.match(html, /<div class="sidebar-sessions-views"><div class="sidebar-sessions-view">/);
  assert.equal(openingTag(html, "session-sidebar-files-body"), '<div id="session-sidebar-files-body" class="sidebar-files-body">');
  assert.match(html, /role="separator"/, "the open section's separator");
});

/** The files section header row's keys group, as rendered below the sessions. */
function sectionKeys(html) {
  const row = html.slice(html.indexOf('<div class="sidebar-files-section">'), html.indexOf('<div id="session-sidebar-files-body"'));
  return row.slice(row.indexOf('<div class="sidebar-files-keys"'));
}

/** Each key's name and state, in order. */
function keyLabels(markup) {
  return [...markup.matchAll(/<button type="button"( disabled="")? title="([^"]+)" aria-label="\2"( aria-pressed="(true|false)")?( aria-expanded="(true|false)" aria-controls="[^"]+")? class="[^"]+"/g)]
    .map((match) => `${match[2]}${match[1] ? " (disabled)" : ""}${match[4] ? ` pressed=${match[4]}` : ""}${match[6] ? ` expanded=${match[6]}` : ""}`);
}

/** A rule's declarations in app/sidebar.css (its first rule for the selector). */
function sidebarRule(selector) {
  const start = sidebarStyles.indexOf(`\n${selector} {`);
  assert.notEqual(start, -1, `${selector} not found`);
  return sidebarStyles.slice(sidebarStyles.indexOf("{", start) + 1, sidebarStyles.indexOf("}", start));
}

function px(rule, property) {
  const match = rule.match(new RegExp(`(?:^|\\s)${property}: ([^;]+);`));
  assert.ok(match, `${property} not set`);
  return match[1].split(/\s+/).map((value) => Number.parseFloat(value));
}

/**
 * The files' right inset below the sessions (where the header row's keys and
 * the picker's boxes end) beside a session tree scrollbar this wide.
 */
function filesInsetRight(scrollbar) {
  const match = sidebarRule(".session-sidebar.is-files-below").match(/(?:^|\s)--sidebar-files-inset-right: max\((\d+)px, calc\(var\(--session-tree-scrollbar, 0px\) \+ (\d+)px\)\);/);
  assert.ok(match, "the files' right inset follows the session tree's scrollbar");
  return Math.max(Number(match[1]), scrollbar + Number(match[2]));
}

test("below the sessions the section's header row holds the files' keys in the files tab's order, the search last", () => {
  const html = render({ selectedCwd: "/work/alpha", onOpenTerminal: noop });
  const keys = sectionKeys(html);
  // A group of their own (the picker's names only the project and
  // worktree), in the files tab's order: the folder's actions, then the
  // tree's two views (what it lists, its changes), then the file search,
  // which the files tab leaves to the toolbar row. The search is the
  // files' (the toolbar row's is the sessions'), an expandable control,
  // not a toggle. Seven keys always there, so none ever moves.
  assert.match(keys, /^<div class="sidebar-files-keys" role="group" aria-label="File actions">/);
  assert.deepEqual(keyLabels(keys), [
    "Open workspace terminal",
    "Open in file manager",
    "Upload files to project root",
    "Refresh file list",
    "Show ignored files pressed=false",
    "0 changed files (disabled) pressed=false",
    "Search files expanded=false",
  ]);
  assert.equal((keys.match(/<button /g) ?? []).length, 7);
  // Their icons are 13px, a size under the files tab's 14px, in the sidebar
  // icons' one stroke (the files tab's keys keep 14px:
  // SessionSidebar.tabs.test.mjs).
  assert.deepEqual([...keys.matchAll(/<svg width="(\d+)" height="(\d+)"[^>]*stroke-width="(\d+)"/g)].map((match) => match.slice(1).join(",")), Array(7).fill("13,13,2"));
  // The head under the row is the picker alone: no second row of keys, no
  // search in the toolbar row's place.
  const head = html.slice(html.indexOf('<div class="sidebar-files-head">'), html.indexOf('<div class="sidebar-files-scroll'));
  assert.doesNotMatch(head, /sidebar-tool-button|sidebar-files-actions/);
  // Without a folder they wait, disabled, in the same places; so does the
  // terminal without a handler, where the files tab leaves it out.
  assert.deepEqual(keyLabels(sectionKeys(render())), [
    "Open workspace terminal (disabled)",
    "Open in file manager (disabled)",
    "Upload files to project root (disabled)",
    "Refresh file list (disabled)",
    "Show ignored files (disabled) pressed=false",
    "0 changed files (disabled) pressed=false",
    "Search files (disabled) expanded=false",
  ]);
  assert.deepEqual(keyLabels(sectionKeys(render({ selectedCwd: "/work/alpha" }))).slice(0, 3), [
    "Open workspace terminal (disabled)",
    "Open in file manager",
    "Upload files to project root",
  ]);

  // The keys share their group's width alike, 18 to 26px each. The group
  // never shrinks under its seven keys' minimum nor grows past their
  // maximum, and its grow factor, far above the toggle's (1), gives it the
  // room the toggle's content leaves before the toggle takes any.
  const keyRule = sidebarRule(".sidebar-files-keys .sidebar-tool-button");
  assert.match(keyRule, /(?:^|\s)flex: 1 1 0;/);
  const [keyMin] = px(keyRule, "min-width");
  const [keyMax] = px(keyRule, "max-width");
  const keysGroup = sidebarRule(".sidebar-files-keys").match(/(?:^|\s)flex: (\d+) 0 calc\((\d+) \* (\d+)px\);[\s\S]*?max-width: calc\((\d+) \* (\d+)px\);/);
  assert.ok(keysGroup, "the keys' group: a grow factor, no shrink, its keys' minimum and maximum");
  const [, groupGrow, minCount, groupKeyMin, maxCount, groupKeyMax] = keysGroup.map(Number);
  assert.ok(groupGrow >= 1000, "the toggle gets a fraction of a pixel until the keys are at their widest");
  assert.deepEqual([minCount, groupKeyMin, maxCount, groupKeyMax], [7, keyMin, 7, keyMax]);
  assert.match(sidebarRule(".sidebar-files-section-toggle"), /(?:^|\s)flex: 1 1 auto;/);

  // The fit, added up from the CSS: the sidebar's width less its line, the
  // row's padding (on the right the files' inset beside the session tree's
  // classic scrollbar, Chromium's 10px) and the gap before the keys; the
  // toggle's content in Chromium's 11px system font (its 5px on either
  // side, the 12px chevron, a 3px gap, the label, then a 3px gap and the
  // count): "Files" 34px, "文件" 24px, "· 123" 26px, "· 2" 14px.
  const [scrollbar] = px(globalStyles.slice(globalStyles.indexOf("::-webkit-scrollbar {")), "width");
  const right = filesInsetRight(scrollbar);
  const [, , , left] = px(sidebarRule(".sidebar-files-section"), "padding");
  const [gap] = px(sidebarRule(".sidebar-files-section"), "gap");
  const [toggleMin] = px(sidebarRule(".sidebar-files-section-toggle"), "min-width");
  const [, toggleInset] = px(sidebarRule(".sidebar-files-section-toggle"), "padding");
  assert.equal(toggleMin, toggleInset + 12, "the toggle keeps its chevron");
  const content = (label, count = 0) => 2 * toggleInset + 12 + 3 + label + (count ? 3 + count : 0);
  const fit = (width, toggleContent) => {
    const room = width - 1 - left - right - gap;
    const key = Math.min(keyMax, Math.max(keyMin, (room - toggleContent) / 7));
    return { key, toggle: room - 7 * key };
  };
  // At the default 260px the toggle keeps all of its content, the keys
  // wider than at their minimum: about 21px beside "Files · 123", 24px
  // beside "文件 · 2".
  const filesCount = fit(SIDEBAR_DEFAULT_WIDTH, content(34, 26));
  assert.ok(filesCount.key > keyMin && filesCount.toggle >= content(34, 26), `"Files · 123" leaves ${filesCount.key}px keys`);
  assert.ok(fit(SIDEBAR_DEFAULT_WIDTH, content(24, 14)).key >= 24);
  // From 300px they are at their widest, whatever the label and count.
  assert.equal(fit(300, content(34, 26)).key, keyMax);
  // At the sidebar's 180px minimum they are at their narrowest and nothing
  // overflows: the toggle keeps more than its chevron (what does not fit
  // in it wraps out of view, whole).
  const narrowest = fit(SIDEBAR_MIN_WIDTH, content(34, 26));
  assert.equal(narrowest.key, keyMin);
  assert.ok(narrowest.toggle >= toggleMin, `${toggleMin} + ${gap} + 7 × ${keyMin} fits ${SIDEBAR_MIN_WIDTH - 1 - left - right}`);
});

test("a key disabled under the focus hands it to the nearest key still enabled, the next one first", () => {
  const keys = ["terminal", "files", "changes", "search"].map((name) => ({ name, tagName: "BUTTON", disabled: false }));
  keys.forEach((key, index) => {
    key.previousElementSibling = keys[index - 1] ?? null;
    key.nextElementSibling = keys[index + 1] ?? null;
  });
  keys[2].disabled = true;
  assert.equal(nearestEnabledKey(keys[2]).name, "search");
  keys[3].disabled = true;
  assert.equal(nearestEnabledKey(keys[2]).name, "files", "the file search disabled too: the one before");
  keys[1].disabled = true;
  keys[0].disabled = true;
  assert.equal(nearestEnabledKey(keys[2]), null, "none left: the caller falls back to the toolbar row's search");
  // Only buttons count.
  keys[3].disabled = false;
  keys[3].tagName = "DIV";
  assert.equal(nearestEnabledKey(keys[2]), null);
});

test("a key just disabled hands its focus on while the focus is still on it, or fell to the page, and never takes the user's", () => {
  const focused = [];
  const key = (name) => ({ name, tagName: "BUTTON", disabled: false, focus: (options) => focused.push([name, options]) });
  const keys = ["ignored", "changes", "search"].map(key);
  keys.forEach((item, index) => {
    item.previousElementSibling = keys[index - 1] ?? null;
    item.nextElementSibling = keys[index + 1] ?? null;
  });
  const [, changes] = keys;
  const fallback = key("toolbar search");
  const body = { name: "body" };
  changes.disabled = true;

  // Current Chromium: the disabled key keeps the focus until the next
  // rendering update, after the layout effect. That counts as lost.
  assert.equal(handOffDisabledKeyFocus({ activeElement: changes, body }, changes, fallback), true);
  assert.deepEqual(focused.splice(0), [["search", { preventScroll: true }]]);
  // A browser that blurred it at once: the focus fell to the page (or nowhere).
  assert.equal(handOffDisabledKeyFocus({ activeElement: body, body }, changes, fallback), true);
  assert.equal(handOffDisabledKeyFocus({ activeElement: null, body }, changes, fallback), true);
  assert.deepEqual(focused.splice(0).map(([name]) => name), ["search", "search"]);
  // Focus the user put elsewhere stays.
  const elsewhere = { name: "a session row" };
  assert.equal(handOffDisabledKeyFocus({ activeElement: elsewhere, body }, changes, fallback), false);
  assert.deepEqual(focused, []);
  // No key left enabled: the fallback, still on the key or from the page.
  keys.forEach((item) => { item.disabled = true; });
  assert.equal(handOffDisabledKeyFocus({ activeElement: changes, body }, changes, fallback), true);
  assert.equal(handOffDisabledKeyFocus({ activeElement: body, body }, changes, fallback), true);
  assert.deepEqual(focused.splice(0).map(([name]) => name), ["toolbar search", "toolbar search"]);
  // Nothing to hand it to: nothing moves.
  assert.equal(handOffDisabledKeyFocus({ activeElement: changes, body }, changes, null), false);
  assert.deepEqual(focused, []);
});

test("below the sessions the picker is one row under the header row, lined up with it and with the session rows", () => {
  // The tree's first row 4px under the boxes: the head's 2px, none in the
  // scroll box, the tree's own 2px (FileExplorer's list).
  const belowHead = sidebarRule(".session-sidebar.is-files-below .sidebar-files-head");
  const [, , headBottom, headLeft] = px(belowHead, "padding");
  const [scrollTop] = px(sidebarRule(".session-sidebar.is-files-below .sidebar-files-scroll"), "padding-top");
  const treeTop = Number(explorerSource.match(/\(changesCollapsed \|\| gitFiles\.length === 0\) && \(!fileSearchOpen \|\| !hasSearchQuery\) && \(\s*<div style=\{\{ padding: "(\d+)px \d+px" \}\}>/)[1]);
  assert.equal(headBottom + scrollTop + treeTop, 4, "the tree's first row 4px under the boxes");
  // The paths start in the text column (the chevron column's 6px + 5px, the
  // 12px chevron, a 3px gap), after the box's 1px line and 10px padding.
  const [boxLine] = px(globalStyles.slice(globalStyles.indexOf("\n.project-picker-button {")), "border");
  const [, boxPadding] = px(globalStyles.slice(globalStyles.indexOf("\n.project-picker-button {")), "padding");
  const textColumn = 6 + 5 + 12 + 3;
  assert.equal(headLeft + boxLine + boxPadding, textColumn, "the paths in the text column");
  // The boxes end where the keys end and where a session row's time does,
  // whatever room the session tree's scrollbar takes (none for an overlay
  // one, 6px on a coarse pointer, 10px in Chromium, more elsewhere): a row
  // ends 6px in or on the scrollbar's gutter, its time 4px inside it.
  const treeInset = Number(sidebarStyles.match(/--session-tree-inset-right: max\(0px, calc\((\d+)px - var\(--session-tree-scrollbar, 0px\)\)\);/)[1]);
  const [, sessionRight] = px(sidebarRule(".session-tree-session"), "padding");
  for (const scrollbar of [0, 6, 10, 17]) {
    assert.equal(filesInsetRight(scrollbar), Math.max(treeInset, scrollbar) + sessionRight, `beside a ${scrollbar}px scrollbar`);
  }
});

test("the files tab's head holds the picker and its six buttons, always the same ones in the same places", () => {
  // Only in the tabs layout (below the sessions the keys are the header
  // row's), after the picker: the picker's group names only the project and
  // worktree, the buttons are a group of their own. The folder's actions,
  // then the tree's two views: what it lists and its changes (its search is
  // the toolbar row's). The changes view is there without changes too,
  // disabled, so nothing moves as an agent edits files and commits; its
  // count is the tab's. Without a terminal (no onOpenTerminal) the other
  // five stay.
  assert.match(source, /\{!stacked && explorerCwd && \(\s*<div className="sidebar-files-actions" role="group" aria-label=\{t\("sidebar\.fileActions"\)\}>\s*\{onOpenTerminal && terminalButton\}\s*\{fileManagerButton\}\s*\{uploadButton\}\s*\{refreshButton\}\s*\{ignoredFilesButton\("sidebar-files-views-start"\)\}\s*\{changesButton\}\s*<\/div>\s*\)\}/);
  assert.doesNotMatch(source, /changesCount > 0 && \(\s*<ToolbarIconButton/);
  // The ignored-files switch is the browser's, restored after hydration like
  // the tab, and it is what the explorer lists.
  assert.match(source, /if \(loadShowIgnoredFiles\(\)\) setShowIgnoredFiles\(true\);/);
  assert.match(source, /const next = !showIgnoredFiles;\s*setShowIgnoredFiles\(next\);\s*saveShowIgnoredFiles\(next\);/);
  assert.match(source, /title=\{t\("sidebar\.showIgnoredFiles"\)\}\s*pressed=\{showIgnoredFiles\}\s*className=\{className\}/);
  assert.match(source, /showHidden=\{showIgnoredFiles\}/);
  // In the tabs, the toolbar row's search button is the tab's: the files
  // tab's searches its files; elsewhere, and on a files tab without a
  // folder, the sessions. Below the sessions it is always theirs.
  assert.match(source, /const searchesFiles = !stacked && sidebarTab === "files" && explorerCwd !== null;/);
  assert.match(source, /if \(searchesFiles\) \{\s*setFileSearchOpen\(\(open\) => !open\);\s*return;\s*\}\s*if \(!sessionsShown\) \{\s*switchTab\("sessions"\);\s*setSessionSearchOpen\(true\);\s*return;\s*\}\s*setSessionSearchOpen\(\(open\) => !open\);/);
  assert.match(source, /title=\{searchesFiles \? t\("sidebar\.searchFiles"\) : t\("sidebar\.toggleSessionSearch"\)\}\s*aria-label=\{searchesFiles \? t\("sidebar\.searchFiles"\) : t\("sidebar\.toggleSessionSearch"\)\}\s*aria-expanded=\{searchesFiles \? fileSearchOpen : sessionSearchOpen\}\s*aria-controls=\{searchesFiles \? "file-search-input" : "session-search-input"\}/);
  assert.match(source, /className=\{`sidebar-search-toggle\$\{\(searchesFiles \? fileSearchOpen : sessionSearchOpen\) \? " is-active" : ""\}`\}/);
  assert.match(explorerSource, /ref=\{searchInputRef\}\s*id="file-search-input"/);
  // The sessions' search, as the server renders it (below the sessions).
  const html = render({ selectedCwd: "/work/alpha", onOpenTerminal: noop });
  assert.match(html, /title="Search conversations" aria-label="Search conversations" aria-expanded="false" aria-controls="session-search-input" class="sidebar-search-toggle"/);
  assert.doesNotMatch(source, /kind: "files"|filesMenuItems|TabRowToggle/);
  // No box of its own: the head sits flat under the toolbar row's line and
  // a line of its own parts it from the tree. None between the boxes and the
  // keys: square, borderless and dim, the folder's four from the left, the
  // tree's two views at the right end, the row's edges on the boxes'. At the
  // sidebar's 180px minimum (160px inside the head's padding) all six fit:
  // six 21px keys and five 6px gaps.
  assert.match(sidebarStyles, /\.sidebar-files-head \{\s*display: flex;\s*flex: none;\s*flex-direction: column;\s*padding: 10px 10px 4px;\s*border-bottom: 1px solid var\(--border\);\s*\}/);
  assert.match(source, /<div className="sidebar-files-head">/);
  assert.doesNotMatch(source, /explorerScrolled|is-scrolled/);
  assert.doesNotMatch(sidebarStyles, /sidebar-files-card|sidebar-files-actions::before|sidebar-tabs\.is-files|sidebar-icon-button|is-scrolled/);
  assert.match(sidebarStyles, /\.sidebar-files-actions \{\s*display: flex;\s*align-items: center;\s*gap: 6px;\s*margin-top: 4px;\s*\}/);
  assert.match(sidebarStyles, /\.sidebar-files-views-start \{\s*margin-left: auto;\s*\}/);
  assert.doesNotMatch(sidebarStyles, /\.sidebar-tool-button:last-child/);
  assert.match(sidebarStyles, /\.sidebar-tool-button \{\s*display: flex;\s*flex: 0 1 32px;\s*align-items: center;\s*justify-content: center;\s*min-width: 21px;\s*height: 32px;\s*padding: 0;\s*border: 0;\s*border-radius: var\(--radius-md\);\s*background: transparent;\s*color: var\(--text-dim\);/);
  assert.match(sidebarStyles, /\.sidebar-tool-button:not\(:disabled\):hover \{\s*background: var\(--bg-selected\);\s*color: var\(--text\);/);
  assert.match(sidebarStyles, /@media \(pointer: coarse\) \{[\s\S]*?\.sidebar-tool-button \{\s*flex-basis: 36px;\s*height: 36px;\s*\}/);
  // Little room above and below the keys: 4px to the boxes, 4px to the
  // line; the tree's first row 4px under the line (2px here, the tree's own
  // 2px), as close as it sits under the boxes below the sessions.
  assert.match(sidebarStyles, /\.sidebar-files-scroll \{[^}]*padding-top: 2px;/);
  assert.doesNotMatch(globalStyles.slice(globalStyles.indexOf(".project-picker.is-stacked {")), /^\.project-picker\.is-stacked \{[^}]*(border|background|margin)/);
  assert.doesNotMatch(sidebarStyles, /sidebar-files-toolbar|sidebar-files-title|is-pressed/);
});

test("the toolbar row is the chat bar's cells, and gives up labels only where they do not fit", () => {
  // 36px with its line, as the chat's top bar beside it (AppShell, border-box
  // from the global reset), so the two read as one bar; the chosen tab and
  // the open search take its accent line.
  assert.match(sidebarStyles, /\.sidebar-header \{\s*display: flex;\s*flex: none;\s*align-items: stretch;\s*height: 36px;\s*box-sizing: border-box;\s*border-bottom: 1px solid var\(--border\);\s*\}/);
  assert.match(appShellSource, /borderBottom: "1px solid var\(--border\)", height: "calc\(36px \+ env\(safe-area-inset-top\)\)", paddingTop: "env\(safe-area-inset-top\)"/);
  assert.match(globalStyles, /^\* \{\s*box-sizing: border-box;/m);
  assert.match(sidebarStyles, /\.sidebar-tab,\s*\.sidebar-new-button,\s*\.sidebar-search-toggle \{[^}]*padding: 0 12px;\s*border: 0;\s*border-top: 2px solid transparent;\s*border-radius: 0;/);
  assert.match(sidebarStyles, /\.sidebar-tab,\s*\.sidebar-new-button \{\s*border-right: 1px solid var\(--border\);\s*\}/);
  // New draws its own left line in both layouts: the brand before it is no
  // cell, and in the tabs layout the spacer leaves a gap after the last tab
  // at most widths. So New and the search read as two cells. The -1px margin
  // lays it over the last tab's line where the spacer has collapsed and keeps
  // New's footprint; border-box keeps the fit levels' 36px cells 36px.
  assert.match(sidebarStyles, /\n\.sidebar-new-button \{\s*margin-left: -1px;\s*border-left: 1px solid var\(--border\);\s*\}/);
  assert.equal((sidebarStyles.match(/border-left:/g) ?? []).length, 1, "one left line, not per layout");
  assert.doesNotMatch(sidebarStyles, /\.is-files-below \.sidebar-new-button/);
  assert.match(sidebarStyles, /\.sidebar-tab\.is-selected,\s*\.sidebar-search-toggle\.is-active \{\s*border-top-color: var\(--accent\);\s*background: var\(--bg-selected\);\s*color: var\(--text\);\s*\}/);
  assert.match(sidebarStyles, /\.sidebar-search-toggle \{\s*width: 36px;\s*padding: 0;\s*\}/);
  // Measured, since the labels' widths change with the language: New keeps
  // its +, then the tabs their icons, or the brand's versions a smaller
  // type. A hidden label is still the name.
  const fit = between("function useHeaderFit(", "\n/** A menu placed below");
  assert.match(fit, /useLayoutEffect\(\(\) => \{/);
  assert.match(fit, /for \(const level of \["0", "1", "2"\]\) \{\s*header\.dataset\.fit = level;\s*if \(header\.scrollWidth <= header\.clientWidth\) return;\s*\}/);
  assert.match(fit, /fit\(\);\s*const observer = new ResizeObserver\(fit\);\s*observer\.observe\(header\);\s*return \(\) => observer\.disconnect\(\);\s*\}, \[ref, fit, labels\]\);\s*return fit;/);
  // Fed the labels shown: New's, or the tabs (and the layout, which changes
  // the row's cells). The brand's text changes its width without the row
  // resizing: it calls the fit itself, after the commit and before paint.
  assert.match(source, /const fitHeader = useHeaderFit\(headerRef, \(stacked\s*\? \["below", t\("sidebar\.new"\)\]\s*: \["tabs", t\("sidebar\.tabSessions"\), t\("sidebar\.tabFiles"\), t\("sidebar\.new"\), explorerCwd && changesCount > 0 \? changesCount : "", sidebarTab\]\s*\)\.join\("\\n"\)\);/);
  assert.match(source, /<div ref=\{headerRef\} className="sidebar-header">/);
  assert.match(sidebarStyles, /\.sidebar-header\[data-fit="1"\] \.sidebar-new-button,\s*\.sidebar-header\[data-fit="2"\] \.sidebar-new-button \{\s*width: 36px;\s*padding: 0;\s*\}/);
  assert.match(sidebarStyles, /\.sidebar-header\[data-fit="1"\] \.sidebar-new-label,\s*\.sidebar-header\[data-fit="2"\] \.sidebar-new-label,\s*\.sidebar-header\[data-fit="2"\] \.sidebar-tab-label \{\s*position: absolute;\s*width: 1px;\s*height: 1px;\s*overflow: hidden;\s*clip: rect\(0 0 0 0\);/);
  assert.match(sidebarStyles, /\.sidebar-header\[data-fit="2"\] \.sidebar-tab \{\s*padding: 0 11px;\s*\}/);
  assert.match(sidebarStyles, /\.sidebar-header\[data-fit="2"\] \.sidebar-brand \{\s*font-size: 11px;\s*\}/);
  // Below the sessions the brand stands where the tabs are (the empty
  // new-session page has its own); the tabs layout shows none. No sessions'
  // title any more.
  assert.match(source, /<div ref=\{headerRef\} className="sidebar-header">\s*\{stacked && <PiWebTitle onWidthChange=\{fitHeader\} \/>\}\s*\{!stacked && \(\s*<div className="sidebar-tabs-list"/);
  assert.doesNotMatch(source, /sidebar-title|useScramble|SCRAMBLE_CHARS/);
  assert.doesNotMatch(sidebarStyles, /\.sidebar-title/);
});

test("the layout, the tab and the fold chosen last are shown again after hydration, not in the first render", () => {
  const serverHtml = render();
  const saved = {
    "pi-web:sidebar-files-placement": "tab",
    "pi-web:sidebar-tab": "files",
    "pi-web:sidebar-files-collapsed": "true",
    "pi-web:sidebar-groups-v2": JSON.stringify({ "/work/alpha": false }),
    "pi-web:sidebar-pins-collapsed": "true",
    "pi-web:sidebar-files-show-ignored": "true",
  };
  const previous = globalThis.window;
  globalThis.window = {
    localStorage: { getItem: (key) => saved[key] ?? null, setItem() {}, removeItem() {} },
  };
  let clientHtml;
  try {
    clientHtml = render();
  } finally {
    if (previous === undefined) delete globalThis.window;
    else globalThis.window = previous;
  }
  // The hydrating render must match the server's HTML: the files open below
  // the sessions, whatever the browser saved.
  assert.equal(clientHtml, serverHtml);
  assert.match(clientHtml, /^<div class="session-sidebar is-files-below" style="--sidebar-files-height:320px">/);
  assert.doesNotMatch(openingTag(clientHtml, "session-sidebar-panel-files"), /hidden/);
  assert.equal(openingTag(clientHtml, "session-sidebar-files-body"), '<div id="session-sidebar-files-body" class="sidebar-files-body">');
  assert.doesNotMatch(clientHtml, /role="tablist"/);
});

test("the files show the composer bar's picker in one row below the sessions, asking for a project until it has one", () => {
  const html = render({ selectedCwd: "/work/alpha" });
  const filesPanel = html.slice(html.indexOf('id="session-sidebar-panel-files"'));
  // Before the sidebar has a cwd: the project box asks for one.
  assert.match(filesPanel, /<div class="project-picker is-row" role="group" aria-label="Project and worktree"><button type="button" class="project-picker-button is-project is-empty" title="" aria-haspopup="menu" aria-expanded="false">/);
  assert.match(filesPanel, /<span class="project-picker-label">Select project…<\/span>/);
});

test("the running poll keeps the same Set while the running ids stay the same", () => {
  const previous = new Set(["a", "b"]);
  assert.equal(sameIdsOr(previous, ["b", "a"]), previous);
  assert.equal(sameIdsOr(previous, ["a", "b", "b"]), previous);
  assert.deepEqual([...sameIdsOr(previous, ["a"])], ["a"]);
  assert.deepEqual([...sameIdsOr(previous, ["a", "c"])], ["a", "c"]);
  const empty = new Set();
  assert.equal(sameIdsOr(empty, []), empty);
});
