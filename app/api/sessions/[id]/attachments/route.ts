import { NextResponse } from "next/server";
import { parseFormDataWithinLimit, RequestBodyTooLargeError } from "@/lib/bounded-form-data";
import { isApiRequestAllowed } from "@/lib/request-security";
import { getRpcSession } from "@/lib/rpc-manager";
import {
  MAX_ATTACHMENT_FILE_BYTES,
  MAX_ATTACHMENT_FILES,
  MAX_ATTACHMENT_TOTAL_BYTES,
  saveSessionAttachments,
} from "@/lib/session-attachments";
import { isValidSessionId } from "@/lib/session-file-references-core";
import { resolveSessionPath } from "@/lib/session-reader";

export const dynamic = "force-dynamic";

// Fork addition (Android client): the documents attached to a prompt. Multipart
// `files` parts are saved under `<agentDir>/pi-web-attachments/<id>/`
// (lib/session-attachments.ts); the client then names them in the prompt's prefix
// (lib/attachment-prefix.ts). Photos still travel as prompt images.

const NO_STORE = { "Cache-Control": "no-store" };
const MAX_REQUEST_BYTES = MAX_ATTACHMENT_TOTAL_BYTES + 1024 * 1024;

function refusal(status: number, error: string) {
  return NextResponse.json({ error }, { status, headers: NO_STORE });
}

// POST /api/sessions/[id]/attachments - multipart `files`; answers
// { dir, files: [{ path, name, size }] } in the order sent.
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  if (!isApiRequestAllowed(req)) return refusal(403, "Untrusted API request");
  const { id } = await params;
  if (!isValidSessionId(id)) return refusal(400, "Invalid session id");
  // A session started a moment ago has a live wrapper and maybe no file yet.
  if (!getRpcSession(id)?.isAlive() && !await resolveSessionPath(id)) {
    return refusal(404, "Session not found");
  }

  let formData: FormData;
  try {
    formData = await parseFormDataWithinLimit(req, MAX_REQUEST_BYTES);
  } catch (error) {
    if (error instanceof RequestBodyTooLargeError) return refusal(413, "Attachments must total 100MB or less");
    return refusal(400, "Expected multipart form data");
  }
  const files = formData.getAll("files").filter((entry): entry is File => typeof entry !== "string");
  if (files.length === 0) return refusal(400, "No files attached");
  if (files.length > MAX_ATTACHMENT_FILES) return refusal(400, `At most ${MAX_ATTACHMENT_FILES} files per message`);
  if (files.some((file) => file.size > MAX_ATTACHMENT_FILE_BYTES)) {
    return refusal(413, "Each attachment must be 25MB or smaller");
  }
  if (files.reduce((total, file) => total + file.size, 0) > MAX_ATTACHMENT_TOTAL_BYTES) {
    return refusal(413, "Attachments must total 100MB or less");
  }

  try {
    const contents = await Promise.all(files.map(async (file) => ({
      name: file.name || "file",
      bytes: Buffer.from(await file.arrayBuffer()),
    })));
    return NextResponse.json(saveSessionAttachments(id, contents), { headers: NO_STORE });
  } catch (error) {
    return refusal(500, error instanceof Error ? error.message : String(error));
  }
}
