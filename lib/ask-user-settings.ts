import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { getAgentDir } from "@earendil-works/pi-coding-agent";
import { writePrivateFileAtomicSync } from "./atomic-file";

// The switch for pi-web's native `ask_user_question` (lib/ask-user-extension.ts). Off when the
// file or the field is absent, and when the file is malformed: turning it on suppresses an
// installed @juicesharp/rpiv-ask-user-question, so a damaged file must leave that package alone.

type StoredAskUserSettings = Record<string, unknown> & { enabled?: unknown };

export function getAskUserSettingsPath(agentDir = getAgentDir()): string {
  return join(agentDir, "pi-web-ask-user.json");
}

function readStoredSettings(settingsPath: string): StoredAskUserSettings {
  if (!existsSync(settingsPath)) return {};
  const parsed: unknown = JSON.parse(readFileSync(settingsPath, "utf8"));
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Invalid ask_user_question settings: expected an object");
  }
  return parsed as StoredAskUserSettings;
}

export function isNativeAskUserEnabled(settingsPath = getAskUserSettingsPath()): boolean {
  try {
    return readStoredSettings(settingsPath).enabled === true;
  } catch {
    return false;
  }
}

/** Keeps fields this build does not know, as a newer build may have written them. */
export function writeNativeAskUserEnabled(enabled: boolean, settingsPath = getAskUserSettingsPath()): boolean {
  const stored = readStoredSettings(settingsPath);
  mkdirSync(dirname(settingsPath), { recursive: true });
  writePrivateFileAtomicSync(settingsPath, JSON.stringify({ ...stored, version: 1, enabled }, null, 2));
  return isNativeAskUserEnabled(settingsPath);
}
