import { NextResponse } from "next/server";
import { isNativeAskUserEnabled, writeNativeAskUserEnabled } from "@/lib/ask-user-settings";
import { hasJsonContentType, isApiRequestAllowed } from "@/lib/request-security";

export const dynamic = "force-dynamic";

// Settings › General's switch for the native ask_user_question card (lib/ask-user-extension.ts).
// A session picks the change up on its next reload; Settings reloads the open one.

function errorResponse(error: unknown) {
  return NextResponse.json({ error: error instanceof Error ? error.message : String(error) }, { status: 500 });
}

export async function GET() {
  return NextResponse.json({ enabled: isNativeAskUserEnabled() });
}

export async function PUT(req: Request) {
  if (!isApiRequestAllowed(req)) {
    return NextResponse.json({ error: "Untrusted API request" }, { status: 403 });
  }
  if (!hasJsonContentType(req)) {
    return NextResponse.json({ error: "Content-Type must be application/json" }, { status: 415 });
  }
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }
  const enabled = (body as { enabled?: unknown } | null)?.enabled;
  if (typeof enabled !== "boolean") {
    return NextResponse.json({ error: "enabled must be a boolean" }, { status: 400 });
  }
  try {
    return NextResponse.json({ enabled: writeNativeAskUserEnabled(enabled) });
  } catch (error) {
    return errorResponse(error);
  }
}
