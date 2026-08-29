import { diffChars } from "diff";

export interface InlineSegment {
  text: string;
  changed: boolean;
}

export interface InlineDiff {
  old: InlineSegment[];
  new: InlineSegment[];
}

// Beyond this length char-diffing a line pair gets expensive (worst case is
// O(len_old * len_new)) and the line is usually minified anyway — the
// row-level highlight is enough. VS Code likewise skips intra-line diffing
// on very long lines.
const MAX_LINE_CHARS = 500;

// If the average fraction of changed characters is above this, the inline
// highlight would cover almost the whole row and add nothing over the
// row-level background.
const MAX_CHANGED_FRACTION = 0.5;

/**
 * Compute VS Code-style intra-line highlighting for a removed/added line
 * pair. Uses char-level diff (like VS Code's editor inline diff): for
 * typical edits the changed regions come out word-shaped, e.g.
 * `const value = "old"` → `const value = "new"` highlights only `old`/`new`.
 *
 * Returns per-side segments with a `changed` flag, or `null` when inline
 * highlighting would be noise (identical/empty/too long/too divergent
 * lines) — callers then fall back to plain row rendering.
 */
export function inlineDiff(oldText: string, newText: string): InlineDiff | null {
  if (!oldText || !newText || oldText === newText) return null;
  if (oldText.length > MAX_LINE_CHARS || newText.length > MAX_LINE_CHARS) return null;

  const parts = diffChars(oldText, newText);
  if (parts.length <= 1) return null;

  const oldSegments: InlineSegment[] = [];
  const newSegments: InlineSegment[] = [];
  let changedOld = 0;
  let changedNew = 0;

  for (const part of parts) {
    if (part.added) {
      changedNew += part.value.length;
      pushSegment(newSegments, part.value, true);
    } else if (part.removed) {
      changedOld += part.value.length;
      pushSegment(oldSegments, part.value, true);
    } else {
      pushSegment(oldSegments, part.value, false);
      pushSegment(newSegments, part.value, false);
    }
  }

  if (changedOld === 0 && changedNew === 0) return null;

  // Safety net: never render segments that do not reconstruct the original
  // line exactly (with diffChars' exact per-char equality this should not
  // happen, but a library upgrade could change the contract).
  if (joinSegments(oldSegments) !== oldText || joinSegments(newSegments) !== newText) {
    return null;
  }

  const fraction = (changedOld / oldText.length + changedNew / newText.length) / 2;
  if (fraction > MAX_CHANGED_FRACTION) return null;

  return { old: oldSegments, new: newSegments };
}

function pushSegment(segments: InlineSegment[], text: string, changed: boolean) {
  if (!text) return;
  const last = segments[segments.length - 1];
  if (last && last.changed === changed) {
    last.text += text;
  } else {
    segments.push({ text, changed });
  }
}

function joinSegments(segments: InlineSegment[]): string {
  let out = "";
  for (const segment of segments) out += segment.text;
  return out;
}
