// Client-side helper for POST /api/git/revert.
//
// The route returns the RevertResult shape from lib/git-revert.ts on both 200
// (full success) and 207 (partial failure — some paths unsupported or errored
// while others were reverted). A plain 4xx/5xx returns { error: string }.
//
// This helper normalizes those cases:
//  - 2xx with a result object  → returned to the caller as-is
//  - 4xx/5xx, or 2xx with top-level error → thrown
// Per-path unsupported/errors stay in the returned object so batch callers can
// surface summaries; single-file callers can use `firstRevertErrorMessage` to
// surface a specific reason.

export interface RevertResult {
  reverted?: string[];
  // Paths whose files were actually removed from disk by the server (added/
  // untracked entries). Used by callers to close stale tabs without relying
  // on their potentially-stale cached git status.
  deleted?: string[];
  unsupported?: Array<{ path: string; reason: string }>;
  errors?: Array<{ path: string; error: string }>;
  error?: string;
}

export async function revertGitChangesClient(
  cwd: string,
  paths: string[],
): Promise<RevertResult> {
  const res = await fetch("/api/git/revert", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cwd, paths }),
  });
  const data = (await res.json().catch(() => ({}))) as RevertResult;

  // Hard failure: nothing was reverted. Treat 207-with-reverted as a soft
  // success so batch callers can read the partial result.
  if (!res.ok && !(data.reverted && data.reverted.length > 0)) {
    throw new Error(data.error ?? `Revert failed (HTTP ${res.status})`);
  }
  return data;
}

/** Surface the first per-path error/unsupported reason as an Error, if any. */
export function firstRevertErrorMessage(result: RevertResult): string | null {
  if (result.errors && result.errors.length > 0) return result.errors[0].error;
  if (result.unsupported && result.unsupported.length > 0) return result.unsupported[0].reason;
  return null;
}
