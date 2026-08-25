import { NextResponse } from "next/server";
import { stat as fsStat } from "fs/promises";
import path from "path";
import { getAllowedFileRoots, isExistingFilePathAllowed, isFilePathAllowed } from "@/lib/file-access";
import { isApiRequestAllowed } from "@/lib/request-security";
import { isEditToolName, isWriteToolName } from "@/lib/tool-names";
import { PreviewError, previewEdit, previewWrite } from "@/lib/edit-preview";

// POST /api/edit-preview
// Computes a read-only preview of a pending `edit` / `write` tool call so the
// chat can show the proposed diff (with surrounding file context) before the
// tool is approved and executed. The target file is never mutated.

const MAX_EDITS = 200;
/** Cap on the proposed content: the generated patch scales with it, so anything bigger is a denial of service. */
const MAX_PREVIEW_INPUT_BYTES = 10 * 1024 * 1024;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

type ValidatedRequest =
  | { error: string; status: number }
  | {
      cwd: string;
      filePath: string;
      kind: "edit";
      edits: Array<{ oldText: string; newText: string }>;
    }
  | {
      cwd: string;
      filePath: string;
      kind: "write";
      content: string;
    };

function badRequest(error: string, status = 400): { error: string; status: number } {
  return { error, status };
}

function validateBody(body: unknown): ValidatedRequest {
  if (!isRecord(body)) return badRequest("Invalid request body");
  const toolName = typeof body.toolName === "string" ? body.toolName : "";
  const cwd = typeof body.cwd === "string" ? body.cwd.trim() : "";
  const input = body.input;
  if (!cwd || !path.isAbsolute(cwd)) return badRequest("cwd must be an absolute path");
  if (!isEditToolName(toolName) && !isWriteToolName(toolName)) {
    return badRequest("Only edit and write tool previews are supported");
  }
  if (!isRecord(input) || typeof input.path !== "string" || !input.path) {
    return badRequest("input.path is required");
  }
  const filePath = input.path;

  if (isEditToolName(toolName)) {
    if (!Array.isArray(input.edits) || input.edits.length === 0) {
      return badRequest("input.edits must be a non-empty array");
    }
    if (input.edits.length > MAX_EDITS) return badRequest("Too many edits");
    const edits: Array<{ oldText: string; newText: string }> = [];
    for (const edit of input.edits) {
      if (!isRecord(edit) || typeof edit.oldText !== "string" || typeof edit.newText !== "string") {
        return badRequest("Each edit needs string oldText and newText");
      }
      edits.push({ oldText: edit.oldText, newText: edit.newText });
    }
    const totalBytes = edits.reduce((sum, e) => sum + Buffer.byteLength(e.oldText) + Buffer.byteLength(e.newText), 0);
    if (totalBytes > MAX_PREVIEW_INPUT_BYTES) return badRequest("Edit content is too large", 413);
    return { cwd, filePath, kind: "edit", edits };
  }

  if (typeof input.content !== "string") return badRequest("input.content is required");
  if (Buffer.byteLength(input.content) > MAX_PREVIEW_INPUT_BYTES) return badRequest("input.content is too large", 413);
  return { cwd, filePath, kind: "write", content: input.content };
}

export async function POST(request: Request) {
  if (!isApiRequestAllowed(request)) {
    return NextResponse.json({ error: "Not allowed" }, { status: 403 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const parsed = validateBody(body);
  if ("error" in parsed) {
    return NextResponse.json({ error: parsed.error }, { status: parsed.status });
  }

  // Authorize the target the same way /api/files does: resolve the (possibly
  // relative) path against the session cwd, check the allowed roots
  // lexically, then re-check after resolving symbolic links so a link inside
  // an allowed root cannot redirect the read outside it.
  const target = path.resolve(parsed.cwd, parsed.filePath);
  const allowedRoots = await getAllowedFileRoots();
  if (!isFilePathAllowed(target, allowedRoots)) {
    return NextResponse.json({ error: "Path is not allowed" }, { status: 403 });
  }
  const targetExists = await fsStat(target).then(() => true, () => false);
  const authorizationPath = targetExists ? target : path.dirname(target);
  if (!isExistingFilePathAllowed(authorizationPath, allowedRoots)) {
    return NextResponse.json({ error: "Path is not allowed" }, { status: 403 });
  }

  try {
    const result = parsed.kind === "edit"
      ? await previewEdit({ cwd: parsed.cwd, filePath: parsed.filePath, edits: parsed.edits }, request.signal)
      : await previewWrite({ cwd: parsed.cwd, filePath: parsed.filePath, content: parsed.content });
    return NextResponse.json(result);
  } catch (error) {
    if (request.signal.aborted) {
      return NextResponse.json({ error: "Request aborted" }, { status: 400 });
    }
    if (error instanceof PreviewError) {
      return NextResponse.json({ error: error.message }, { status: error.status });
    }
    return NextResponse.json(
      { error: error instanceof Error ? error.message : String(error) },
      { status: 500 },
    );
  }
}
