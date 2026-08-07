import { execFile } from "child_process";
import fs from "fs";
import path from "path";
import { promisify } from "util";
import type { GitFileStatusKind } from "./git-types";
import { classifyGitStatus, type GitPorcelainEntry } from "./git-status";
import {
  findRepositoryRoot,
  isWithinPath,
  readStatusEntries,
  toGitPath,
} from "./git-changes";

const execFileAsync = promisify(execFile);
const GIT_TIMEOUT_MS = 10_000;

export interface RevertResult {
  reverted: string[];
  // Subset of `reverted` whose files were actually removed from disk by this
  // operation (added/untracked entries). Clients use this to close stale tabs
  // instead of predicting deletion from their cached git status, which may be
  // stale by the time the request reaches the server.
  deleted: string[];
  unsupported: Array<{ path: string; status: GitFileStatusKind; reason: string }>;
  errors: Array<{ path: string; error: string }>;
}

async function git(cwd: string, args: string[]): Promise<string> {
  const { stdout } = await execFileAsync("git", ["-C", cwd, ...args], {
    timeout: GIT_TIMEOUT_MS,
    maxBuffer: 1024 * 1024,
    env: { ...process.env, LC_ALL: "C" },
  });
  return stdout;
}

function extractGitError(error: unknown): string {
  const stderr = (error as { stderr?: string }).stderr;
  if (typeof stderr === "string" && stderr.trim()) return stderr.trim();
  return error instanceof Error ? error.message : String(error);
}

/**
 * After removing a file from disk (added/untracked revert), walk up the parent
 * directories and remove any that are now empty. Stops at the repository root
 * or the request cwd (so the file explorer's current view never disappears),
 * and at the first non-empty directory or filesystem root. Mirrors the
 * directory cleanup `git clean -d` would do for the reverted path's parents.
 */
function cleanupEmptyParentDirs(startPath: string, stopAt: string[]): void {
  const stopSet = new Set(stopAt.map((p) => path.resolve(p)));
  let dir = path.dirname(path.resolve(startPath));
  while (!stopSet.has(path.resolve(dir))) {
    try {
      const entries = fs.readdirSync(dir);
      if (entries.length > 0) break;
      fs.rmdirSync(dir);
    } catch {
      break;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break; // reached filesystem root
    dir = parent;
  }
}

/**
 * Revert working-tree changes for the given paths back to their HEAD state.
 *
 * Per-status strategy:
 *  - modified / deleted  → git restore -- <path>            (restore HEAD content)
 *  - added (staged new)  → git rm -f -- <path>              (remove the new file)
 *  - untracked           → fs.unlinkSync                    (no git record to clear)
 *  - renamed / conflict  → unsupported, reported back without mutating
 *
 * Returns a per-path result so the caller can surface partial failures
 * (mirrors the upload route's 207 pattern).
 */
export async function revertGitChanges(cwd: string, paths: string[]): Promise<RevertResult> {
  const repositoryRoot = await findRepositoryRoot(cwd);
  if (!repositoryRoot) {
    throw new Error("Not a git repository");
  }

  // Verify every target is inside the repository before mutating anything.
  for (const target of paths) {
    if (!isWithinPath(repositoryRoot, target)) {
      throw new Error(`Path is not inside the repository: ${target}`);
    }
  }

  // Read the current porcelain status once. This is the race-safety check:
  // if the UI is stale (file already reverted), we skip it instead of
  // running git commands that would fail.
  const entries = await readStatusEntries(repositoryRoot);
  const entryByPath = new Map<string, GitPorcelainEntry>();
  for (const entry of entries) {
    const absolute = path.resolve(repositoryRoot, entry.path);
    entryByPath.set(absolute, entry);
  }

  const result: RevertResult = { reverted: [], deleted: [], unsupported: [], errors: [] };

  for (const target of paths) {
    const resolved = path.resolve(target);
    const entry = entryByPath.get(resolved);
    if (!entry) {
      // No git status entry — either already clean or untracked-but-filtered.
      // Treat as already-reverted so the UI refreshes without error. We do
      // NOT push to `deleted` because we didn't touch the disk; the client
      // shouldn't close the tab based on a stale cache prediction.
      result.reverted.push(target);
      continue;
    }

    const { status } = classifyGitStatus(entry);
    const gitPath = toGitPath(path.relative(repositoryRoot, resolved));

    try {
      if (status === "modified" || status === "deleted") {
        // Restore both index and working tree to HEAD. `--staged --worktree`
        // ensures staged changes are also discarded — `git restore` without
        // these flags only touches the working tree, leaving the index dirty.
        await git(repositoryRoot, ["restore", "--staged", "--worktree", "--", gitPath]);
        result.reverted.push(target);
      } else if (status === "added") {
        // A staged new file: remove it from both index and working tree so
        // HEAD (which never had it) is the resulting state. `-f` is needed
        // because the file has unmerged/staged content.
        await git(repositoryRoot, ["rm", "-f", "--", gitPath]);
        result.reverted.push(target);
        result.deleted.push(target);
        cleanupEmptyParentDirs(resolved, [repositoryRoot, cwd]);
      } else if (status === "untracked") {
        // Not in the index or HEAD — just remove the file from disk.
        try {
          fs.unlinkSync(resolved);
        } catch (error) {
          const code = (error as NodeJS.ErrnoException).code;
          if (code !== "ENOENT") throw error;
        }
        result.reverted.push(target);
        result.deleted.push(target);
        cleanupEmptyParentDirs(resolved, [repositoryRoot, cwd]);
      } else {
        // renamed / conflict — require manual handling.
        result.unsupported.push({
          path: target,
          status,
          reason: status === "renamed"
            ? "Renamed files must be reverted manually"
            : "Conflicted files must be resolved manually",
        });
      }
    } catch (error) {
      result.errors.push({ path: target, error: extractGitError(error) });
    }
  }

  return result;
}
