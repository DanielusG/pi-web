// Client-safe: the text a client puts before a prompt to hand the model the
// documents the user attached (fork addition, Android client). The files live
// in `<agentDir>/pi-web-attachments/<session id>/` (lib/session-attachments.ts);
// the prompt only names them. The Android client builds and parses the same
// format (android/.../data/AttachmentPrefix.kt): keep both in step.
//
//   Attachments from the user (you should probably read them; Pi saved them in /…/<id>/):
//   - /…/<id>/a1b2c3_notes.txt (original name: notes.txt)
//
//   ---
//
//   <what the user typed, absent when they typed nothing>

export interface AttachmentRef {
  /** Absolute path of the saved copy. */
  path: string;
  /** The file's name on the user's device. */
  name: string;
}

export interface SplitAttachmentPrefix {
  dir: string;
  attachments: AttachmentRef[];
  /** The message after the prefix: what the user typed, "" for attachments alone. */
  body: string;
}

const HEADER_START = "Attachments from the user (you should probably read them; Pi saved them in ";
const HEADER_END = "):";
const LINE_START = "- ";
const ORIGINAL_NAME = " (original name: ";

/** One line per name: a newline or control character in a device file name becomes a space. */
function oneLine(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f]+/g, " ");
}

export function formatAttachmentPrefix(dir: string, attachments: AttachmentRef[]): string {
  const lines = attachments.map((file) => `${LINE_START}${file.path}${ORIGINAL_NAME}${oneLine(file.name)})`);
  return [`${HEADER_START}${dir}${HEADER_END}`, ...lines].join("\n");
}

/** [body] after the attachment list, or the list alone when the user typed nothing. */
export function withAttachmentPrefix(dir: string, attachments: AttachmentRef[], body: string): string {
  if (attachments.length === 0) return body;
  const prefix = formatAttachmentPrefix(dir, attachments);
  return body ? `${prefix}\n\n---\n\n${body}` : prefix;
}

/** The prefix and the body apart, or null when [text] does not start with a complete prefix. */
export function splitAttachmentPrefix(text: string): SplitAttachmentPrefix | null {
  if (!text.startsWith(HEADER_START)) return null;
  const lines = text.split("\n");
  const header = lines[0];
  if (!header.endsWith(HEADER_END)) return null;
  const dir = header.slice(HEADER_START.length, header.length - HEADER_END.length);
  if (!dir) return null;

  const attachments: AttachmentRef[] = [];
  let index = 1;
  for (; index < lines.length && lines[index].startsWith(LINE_START); index++) {
    const line = lines[index];
    const at = line.indexOf(ORIGINAL_NAME);
    if (at <= LINE_START.length || !line.endsWith(")")) return null;
    attachments.push({
      path: line.slice(LINE_START.length, at),
      name: line.slice(at + ORIGINAL_NAME.length, -1),
    });
  }
  if (attachments.length === 0) return null;

  if (index === lines.length) return { dir, attachments, body: "" };
  if (lines[index] !== "" || lines[index + 1] !== "---" || lines[index + 2] !== "") return null;
  return { dir, attachments, body: lines.slice(index + 3).join("\n") };
}

/**
 * [text] as a list shows it: the body without the prefix, or the attached names
 * when the user sent files alone. Text without a prefix is returned unchanged.
 */
export function attachmentDisplayText(text: string): string {
  const split = splitAttachmentPrefix(text);
  if (!split) return text;
  return split.body || split.attachments.map((file) => file.name).join(", ");
}
