import { NextResponse } from "next/server";
import { spawnWetty, killWetty, isWettyProxyActive } from "@/lib/wetty-manager";
import { getAllowedFileRoots, isExistingFilePathAllowed, isFilePathAllowed } from "@/lib/file-access";

/** Same gate as /api/files and /api/worktrees: only session cwds / project
 *  roots / explicitly allowed dirs may host a terminal. */
async function checkCwdAllowed(cwd: string): Promise<NextResponse | null> {
  const allowedRoots = await getAllowedFileRoots();
  if (!isFilePathAllowed(cwd, allowedRoots) || !isExistingFilePathAllowed(cwd, allowedRoots)) {
    return NextResponse.json({ error: "Access denied" }, { status: 403 });
  }
  return null;
}

// POST /api/terminal/[id]  body: { cwd }
//   → proxy mode:  { id, cwd, mode: "proxy", wsToken }
//   → direct mode: { id, cwd, mode: "direct", wsToken, port }
//
// The [id] segment is supplied by the client so it matches its own tab id;
// spawnWetty is idempotent (returns existing entry on repeat calls).
//
// `wsToken` is the per-tab secret the browser must present on the socket.io
// handshake — both to the same-origin proxy (proxy mode) and directly to
// pty-server (direct mode). `port` is only returned in direct mode, where
// the browser needs it to reach pty-server on 127.0.0.1. In proxy mode the
// port is an internal detail and never leaves the server.
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }) {
  try {
    const { id } = await ctx.params;
    const body = await req.json() as { cwd?: string };
    if (!body.cwd || typeof body.cwd !== "string") {
      return NextResponse.json({ error: "cwd is required" }, { status: 400 });
    }
    const denied = await checkCwdAllowed(body.cwd);
    if (denied) return denied;

    const handle = await spawnWetty(id, body.cwd);
    const mode = isWettyProxyActive() ? "proxy" : "direct";
    return NextResponse.json({
      id: handle.id,
      cwd: handle.cwd,
      mode,
      wsToken: handle.authToken,
      ...(mode === "direct" ? { port: handle.port } : {}),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

// DELETE /api/terminal/[id]  →  { ok: true }
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  try {
    const { id } = await ctx.params;
    const killed = killWetty(id);
    return NextResponse.json({ ok: killed });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
