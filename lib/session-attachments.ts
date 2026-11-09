import { randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { getAgentDir } from "@earendil-works/pi-coding-agent";
import { isValidSessionId } from "./session-file-references-core";

// Documents a client attaches to a prompt (fork addition, Android client): saved
// under `<agentDir>/pi-web-attachments/<session id>/` as `<6 hex>_<safe name>`,
// outside the project and its git tree, and named to the model by the prompt's
// prefix (lib/attachment-prefix.ts). The folder goes with its session
// (lib/session-delete.ts).

export const ATTACHMENTS_DIR_NAME = "pi-web-attachments";
export const MAX_ATTACHMENT_FILE_BYTES = 25 * 1024 * 1024;
export const MAX_ATTACHMENT_TOTAL_BYTES = 100 * 1024 * 1024;
export const MAX_ATTACHMENT_FILES = 20;

const MAX_SAFE_NAME_CHARS = 100;

export interface SavedAttachment {
  path: string;
  name: string;
  size: number;
}

export function getSessionAttachmentsDir(sessionId: string, agentDir = getAgentDir()): string {
  if (!isValidSessionId(sessionId)) throw new Error("Invalid session id");
  return path.join(agentDir, ATTACHMENTS_DIR_NAME, sessionId);
}

/**
 * [name] as a file name every shell and tool reads without quoting: letters,
 * digits, `.`, `_` and `-`, every other run of characters one `_`, at most 100
 * characters with the extension kept.
 */
export function safeAttachmentName(name: string): string {
  const base = name.normalize("NFC").split(/[\\/]/).pop() ?? "";
  // The 6-hex prefix keeps a leading dot (`.env`) from hiding the file or naming `..`.
  let safe = base.replace(/[^\p{L}\p{N}._-]+/gu, "_");
  if (!safe) safe = "file";
  if (safe.length > MAX_SAFE_NAME_CHARS) {
    const dot = safe.lastIndexOf(".");
    const ext = dot > 0 && safe.length - dot <= 16 ? safe.slice(dot) : "";
    safe = safe.slice(0, MAX_SAFE_NAME_CHARS - ext.length) + ext;
  }
  return safe;
}

/**
 * Writes [files] into the session's folder, each under a fresh 6-hex prefix, and
 * answers with their absolute paths. Never replaces a file: a prefix already
 * taken draws another.
 */
export function saveSessionAttachments(
  sessionId: string,
  files: Array<{ name: string; bytes: Buffer }>,
  agentDir = getAgentDir(),
): { dir: string; files: SavedAttachment[] } {
  const dir = getSessionAttachmentsDir(sessionId, agentDir);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const saved: SavedAttachment[] = [];
  for (const file of files) {
    const safe = safeAttachmentName(file.name);
    for (let attempt = 0; ; attempt++) {
      const destination = path.join(dir, `${randomUUID().slice(0, 6)}_${safe}`);
      try {
        fs.writeFileSync(destination, file.bytes, { flag: "wx", mode: 0o600 });
        saved.push({ path: destination, name: file.name, size: file.bytes.length });
        break;
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "EEXIST" || attempt >= 4) throw error;
      }
    }
  }
  return { dir: dir + path.sep, files: saved };
}

/** Removes a session's attachment folder; nothing to remove is not an error. */
export function deleteSessionAttachments(sessionId: string, agentDir = getAgentDir()): void {
  if (!isValidSessionId(sessionId)) return;
  fs.rmSync(getSessionAttachmentsDir(sessionId, agentDir), { recursive: true, force: true });
}
