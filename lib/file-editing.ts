/**
 * Whether manual file editing is enabled. Reads the NEXT_PUBLIC_PI_WEB_FILE_EDITING
 * env var so the same value is available on the server (route handlers) and in
 * the browser (inline at build time by Next.js). Default off; set to "1",
 * "true", or "yes" in .env to enable.
 */
export function isFileEditingEnabled(): boolean {
  const raw = process.env.NEXT_PUBLIC_PI_WEB_FILE_EDITING;
  if (!raw) return false;
  return ["1", "true", "yes", "on"].includes(raw.trim().toLowerCase());
}
