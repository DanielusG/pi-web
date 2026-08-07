import { NextRequest, NextResponse } from "next/server";
import { getAllowedFileRoots, isExistingFilePathAllowed, isFilePathAllowed, isWindowsAbsolutePath } from "@/lib/file-access";
import { isApiRequestAllowed } from "@/lib/request-security";
import { isFileEditingEnabled } from "@/lib/file-editing";
import { revertGitChanges } from "@/lib/git-revert";

export async function POST(request: NextRequest) {
  if (!isApiRequestAllowed(request)) {
    return NextResponse.json({ error: "Untrusted API request" }, { status: 403 });
  }

  if (!isFileEditingEnabled()) {
    return NextResponse.json({ error: "File editing is disabled" }, { status: 403 });
  }

  try {
    const body = await request.json().catch(() => null) as {
      cwd?: unknown;
      paths?: unknown;
    } | null;
    if (!body || typeof body.cwd !== "string" || !Array.isArray(body.paths)) {
      return NextResponse.json({ error: "Request body must be { cwd: string, paths: string[] }" }, { status: 400 });
    }

    const cwd = body.cwd.trim();
    if (!cwd || (!cwd.startsWith("/") && !isWindowsAbsolutePath(cwd))) {
      return NextResponse.json({ error: "cwd must be an absolute path" }, { status: 400 });
    }

    const paths = body.paths;
    if (paths.length === 0 || !paths.every((p): p is string => typeof p === "string" && p.length > 0)) {
      return NextResponse.json({ error: "paths must be a non-empty array of strings" }, { status: 400 });
    }
    for (const p of paths) {
      if (!p.startsWith("/") && !isWindowsAbsolutePath(p)) {
        return NextResponse.json({ error: "each path must be an absolute path" }, { status: 400 });
      }
    }

    const allowedRoots = await getAllowedFileRoots();
    if (!isFilePathAllowed(cwd, allowedRoots)) {
      return NextResponse.json({ error: "Access denied" }, { status: 403 });
    }
    // cwd must exist; reverted file paths may not (deleted files), so only
    // the string-prefix allow-list check applies to them — repo membership
    // is verified inside revertGitChanges via findRepositoryRoot + isWithinPath.
    if (!isExistingFilePathAllowed(cwd, allowedRoots)) {
      return NextResponse.json({ error: "Access denied" }, { status: 403 });
    }
    for (const p of paths) {
      if (!isFilePathAllowed(p, allowedRoots)) {
        return NextResponse.json({ error: "Access denied" }, { status: 403 });
      }
    }

    const result = await revertGitChanges(cwd, paths);
    // 207 if any path was unsupported or errored, mirroring the upload route.
    const hasPartialFailure = result.unsupported.length > 0 || result.errors.length > 0;
    return NextResponse.json(result, { status: hasPartialFailure ? 207 : 200 });
  } catch (error) {
    return NextResponse.json({ error: error instanceof Error ? error.message : String(error) }, { status: 500 });
  }
}
