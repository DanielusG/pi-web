import { access, constants, readFile, stat } from "fs/promises";
import path from "path";
import {
  createEditTool,
  generateUnifiedPatch,
  type EditToolInput,
} from "@earendil-works/pi-coding-agent";

/** Cap on the target file size: the diff output is small, so anything bigger is just a denial of service. */
export const PREVIEW_SOURCE_MAX_BYTES = 5 * 1024 * 1024;

/** Error carrying the HTTP status the API route should report. */
export class PreviewError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = "PreviewError";
    this.status = status;
  }
}

export interface EditPreviewInput {
  cwd: string;
  filePath: string;
  edits: Array<{ oldText: string; newText: string }>;
}

export interface WritePreviewInput {
  cwd: string;
  filePath: string;
  content: string;
}

export interface PreviewResult {
  /** Standard unified patch of the proposed change, renderable by the chat's SplitPatchView. */
  patch: string;
}

/** Reject targets that would make the preview a denial of service. */
async function assertPreviewableTarget(target: string): Promise<void> {
  const info = await stat(target).catch(() => null);
  if (info?.isDirectory()) {
    throw new PreviewError("Target is a directory", 400);
  }
  if (info && info.size > PREVIEW_SOURCE_MAX_BYTES) {
    throw new PreviewError(`File is too large to preview (${info.size} bytes)`, 413);
  }
}

/**
 * Preview a pending `edit` tool call.
 *
 * Runs the SDK's real edit tool with a no-op write, so validation (exact /
 * fuzzy match, uniqueness, overlap, CRLF handling) and diff generation are
 * identical to what the executed tool would produce — but the file is never
 * mutated. Throws with the same user-facing error an execution would give
 * (e.g. "Could not find the exact text in the file...").
 */
export async function previewEdit(
  input: EditPreviewInput,
  signal?: AbortSignal,
): Promise<PreviewResult> {
  const target = path.resolve(input.cwd, input.filePath);
  if (!(await stat(target).catch(() => null))) {
    throw new PreviewError(`File not found: ${input.filePath}`, 404);
  }
  await assertPreviewableTarget(target);

  const tool = createEditTool(input.cwd, {
    operations: {
      readFile: (absolutePath) => readFile(absolutePath),
      access: (absolutePath) => access(absolutePath, constants.R_OK | constants.W_OK),
      writeFile: async () => {
        // Preview only: never mutate.
      },
    },
  });

  const toolInput: EditToolInput = { path: input.filePath, edits: input.edits };
  let result;
  try {
    result = await tool.execute("edit-preview", toolInput, signal);
  } catch (error) {
    // Aborts propagate as-is (the route maps them); anything else is a
    // validation failure of the proposed edit (not found, not unique, ...).
    if (signal?.aborted) throw error;
    throw new PreviewError(error instanceof Error ? error.message : String(error), 422);
  }
  const details = result.details as { patch?: string } | undefined;
  const patch = details?.patch ?? null;
  if (!patch) throw new PreviewError("Edit preview produced no diff", 422);
  return { patch };
}

/**
 * Preview a pending `write` tool call: unified patch of the existing content
 * (empty for a new file) against the proposed content, LF-normalized the same
 * way the edit tool does.
 */
export async function previewWrite(
  input: WritePreviewInput,
): Promise<PreviewResult> {
  const target = path.resolve(input.cwd, input.filePath);
  await assertPreviewableTarget(target);

  let existing = "";
  try {
    existing = (await readFile(target, "utf8")).replace(/\r\n/g, "\n");
  } catch {
    // New file: diff against empty content.
  }

  return { patch: generateUnifiedPatch(input.filePath, existing, input.content) };
}
