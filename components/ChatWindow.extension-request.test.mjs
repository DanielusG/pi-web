import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./ChatWindow.tsx", import.meta.url), "utf8");
const dialogStart = source.indexOf("function ExtensionDialog");
const customStart = source.indexOf("function ExtensionCustomPanel");
const dialogSource = source.slice(dialogStart, customStart);
const customSource = source.slice(customStart);

test("renders the extension dialog inline in the composer slot (fork layout)", () => {
  // While a blocking extension request is open, the dialog replaces the input bar.
  assert.match(source, /const extensionDialogElement = extensionDialog \?/);
  assert.match(source, /\{extensionDialogElement \?\? chatInputElement\}/);
  // The dialog is not an overlay: no absolute positioning in the dialog component.
  assert.doesNotMatch(dialogSource, /position: "absolute"/);
  assert.doesNotMatch(dialogSource, /inset: 0/);
  // The custom panel remains an overlay confined to the content region above the composer.
  assert.match(customSource, /position: "absolute"[\s\S]*?inset: 0/);
  assert.match(customSource, /pointerEvents: "none"/);
  assert.doesNotMatch(source, /z-\[100\]|zIndex: 100/);
  assert.match(customSource, /maxHeight: "min\(760px, 100%\)"/);
});

test("adds collapse without replacing cancel", () => {
  assert.match(dialogSource, /setCollapsed\(true\)/);
  assert.match(dialogSource, /chat\.extensionCollapse/);
  assert.match(dialogSource, /chat\.cancel/);
  assert.doesNotMatch(dialogSource, /chat\.extensionSkip/);
});

test("renders extension confirmation and options as markdown", () => {
  assert.match(source, /import \{ MarkdownBody \} from "\.\/MarkdownBody"/);
  assert.match(dialogSource, /<MarkdownBody>\{request\.message\}<\/MarkdownBody>/);
  assert.match(dialogSource, /role="button"[\s\S]*?data-extension-option[\s\S]*?<div inert>[\s\S]*?<MarkdownBody>\{option\}<\/MarkdownBody>/);
  assert.match(dialogSource, /ref=\{index === 0 \? focusFirstOption : undefined\}/);
});

test("preserves title newlines like pi's TUI and keeps long titles from hiding the body", () => {
  const header = dialogSource.slice(dialogSource.indexOf('role="dialog"'), dialogSource.indexOf("{request.method === \"confirm\""));
  assert.match(header, /whiteSpace: "pre-wrap", overflowWrap: "anywhere" \}\}>\{request\.title\}/);
  // Fork layout: the dialog renders inline in the composer slot (it replaces the input bar),
  // so the card carries a viewport-based max-height and the title scrolls inside an absolute
  // cap derived from that limit — never a percentage, which resolves to none in a
  // content-sized card.
  assert.match(source, /const EXTENSION_DIALOG_MAX_HEIGHT = "min\(45vh, 360px\)";/);
  assert.match(source, /const EXTENSION_DIALOG_TITLE_MAX_HEIGHT = `calc\(\$\{EXTENSION_DIALOG_MAX_HEIGHT\} \* 0\.4\)`;/);
  assert.match(header, /maxHeight: full \? "min\(80vh, 760px\)" : EXTENSION_DIALOG_MAX_HEIGHT,/);
  // Upstream #890 applies to the fork card too: the header has to shrink and scroll, or a
  // long title pushes the option list past the card's overflow edge.
  assert.match(header, /flexShrink: 1, minHeight: 0/);
  // Keyed by request so the next question of a sequence starts scrolled to the top.
  assert.match(header, /key=\{request\.id\} id=\{dialogTitleId\} tabIndex=\{0\} style=\{\{ maxHeight: EXTENSION_DIALOG_TITLE_MAX_HEIGHT, overflowY: "auto",[^}]*\}\}>\{request\.title\}/);
  assert.doesNotMatch(dialogSource, /maxHeight: "50%"/);
  assert.doesNotMatch(dialogSource, /maxHeight: "50vh"/);
});

test("resets collapse state when a new extension request arrives", () => {
  // A new request also drops the previous dialog's grown width and maximize state.
  assert.match(dialogSource, /setCollapsed\(false\);[\s\S]*?setFull\(false\);\s*setFitWidth\(null\);\s*}, \[request\]\)/);
  assert.match(source, /<ExtensionCustomPanel key=\{extensionCustomUi\.id\}/);
  assert.match(customSource, /if \(!collapsed\) inputRef\.current\?\.focus\(\);\s*}, \[collapsed\]\)/);
});

test("shows how many extension requests wait behind the one on screen", () => {
  const expandedHeader = dialogSource.slice(dialogSource.indexOf('role="dialog"'), dialogSource.indexOf("{request.method === \"confirm\""));
  const collapsedButton = dialogSource.slice(dialogSource.indexOf("if (collapsed) {"), dialogSource.indexOf('role="dialog"'));
  const customCollapsed = customSource.slice(customSource.indexOf("{collapsed ? ("), customSource.indexOf('role="dialog"'));
  const customExpanded = customSource.slice(customSource.indexOf('role="dialog"'));
  const waitingSource = source.slice(source.indexOf("function ExtensionWaitingCount"), source.indexOf("function ExtensionDialog("));

  assert.match(source, /<ExtensionDialog key=\{extensionDialog.id\} request=\{extensionDialog\} waitingCount=\{waitingExtensionDialogCount\}/);
  assert.match(source, /<ExtensionCustomPanel key=\{extensionCustomUi.id\} request=\{extensionCustomUi\} waitingCount=\{waitingExtensionCustomUiCount\}/);
  assert.match(waitingSource, /if \(count <= 0\) return null;[\s\S]*?t\("chat\.extensionMoreWaiting", \{ count \}\)/);
  assert.match(expandedHeader, /chat\.extensionRequest"\)\}<\/span>\s+<ExtensionWaitingCount count=\{waitingCount\} \/>\s+\{countdown\}/);
  assert.match(collapsedButton, /<ExtensionWaitingCount count=\{waitingCount\} \/>\s+\{countdown\}/);
  assert.match(customCollapsed, /<ExtensionWaitingCount count=\{waitingCount\} \/>\s+<span[^>]*>\s+\{t\("chat\.extensionExpand"\)\}/);
  assert.match(customExpanded, /chat\.extensionPanel"\)\}<\/div>\s+<div[^>]*>\s+<ExtensionWaitingCount count=\{waitingCount\} \/>/);
});

test("fits dialogs to their code blocks and lets the user maximize them (#947)", () => {
  const dialogOnly = dialogSource.slice(0, dialogSource.indexOf("function ExtensionCustomPanel"));
  // Plain pi compatibility: nothing about size travels in the request or comes from an extension.
  assert.doesNotMatch(source, /dialogSize/);

  // Fork layout: the inline card keeps the chat column's width and grows past it only
  // through the measured fit, or to the whole row when the user maximizes it.
  assert.ok(dialogOnly.includes("maxWidth: full"), "the card's width follows the maximize toggle");
  assert.ok(
    dialogOnly.includes("`max(var(--chat-content-max-width, 820px), ${fitWidth}px)`"),
    "a grown dialog stays at least as wide as the chat column",
  );
  assert.ok(
    dialogOnly.includes('maxHeight: full ? "min(80vh, 760px)" : EXTENSION_DIALOG_MAX_HEIGHT'),
    "the card's height follows the maximize toggle inside the composer slot",
  );
  // Only blocks that scroll sideways count, and the fit never shrinks again while it is read.
  assert.match(dialogOnly, /querySelectorAll<HTMLElement>\("pre, \.markdown-table-wrap"\)/);
  assert.match(dialogOnly, /block\.scrollWidth - block\.clientWidth/);
  assert.match(dialogOnly, /prev !== null && prev >= needed \? prev : needed/);
  // Highlighted code swaps in after the first paint, so the fit watches the body.
  assert.match(dialogOnly, /new MutationObserver\(fit\)[\s\S]*?observe\(body, \{ childList: true, subtree: true, characterData: true \}\)/);

  // The maximize/restore button sits next to the collapse chevron and only affects this dialog.
  const header = dialogOnly.slice(dialogOnly.indexOf('role="dialog"'), dialogOnly.indexOf("{request.method === \"confirm\""));
  assert.match(header, /onClick=\{toggleFull\}[\s\S]*?t\("chat\.extensionMaximize"\)[\s\S]*?t\("chat\.extensionRestoreSize"\)[\s\S]*?<ExtensionSizeIcon expanded=\{full\} \/>[\s\S]*?onClick=\{\(\) => setCollapsed\(true\)\}/);
  assert.doesNotMatch(source, /localStorage|pi-extension-/);
});

test("shows a custom panel's lines whole instead of scrolling when they are wider than 920px (#947)", () => {
  // The extension wraps its lines to the width it asked for, so the panel only has to be
  // as wide as the widest of them, capped to the content region.
  assert.match(customSource, /width: "max-content",\s+minWidth: "min\(920px, 100%\)",\s+maxWidth: "100%"/);
  assert.doesNotMatch(customSource.slice(0, customSource.indexOf("\n}\n")), /toggleFull|extensionMaximize/);
});
