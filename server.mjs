/**
 * Pi-Web custom server.
 *
 * Replaces `next start` for the published `bin/pi-web.js` entry point. We
 * need a custom server because Next.js App Router route handlers do not
 * reliably support WebSocket upgrades, and the terminal integration needs
 * a socket.io endpoint that:
 *
 *   1. Is reachable from the browser on the SAME origin as the rest of
 *      pi-web. The original design had the browser connect directly to
 *      `http://127.0.0.1:<port>` — that breaks as soon as pi-web is
 *      reached through a reverse proxy (frp, nginx, Caddy, SSH forwards,
 *      …), because the browser's `127.0.0.1` is its own machine, not the
 *      pi-web host. It also breaks under HTTPS front-ends because the
 *      direct `http://` URL is mixed content.
 *
 *   2. Inherits the same authentication that protects the rest of pi-web.
 *      The original pty-server subprocess had no auth at all and relied
 *      entirely on loopback isolation — a serious footgun the moment
 *      someone tried to "fix" the reverse-proxy problem by binding
 *      pty-server to 0.0.0.0.
 *
 * Design:
 *  - One `http.Server`. Next.js handles every non-socket.io request via
 *    `app.getRequestHandler()`.
 *  - A single socket.io server is attached with `path: "/api/terminal/socket.io"`.
 *    The terminal id and per-tab token are passed in `handshake.auth`
 *    (not in the URL), so all tabs share the same path and we avoid
 *    parameterised routes.
 *  - `io.use()` middleware validates `auth.id` + `auth.token` against
 *    the wetty registry. A valid token is the proof that the client went
 *    through the authenticated `POST /api/terminal/[id]` endpoint (which
 *    itself is gated by `proxy.ts` Basic Auth + host allow-list). We do
 *    NOT re-run Basic Auth on the WS upgrade: socket.io-client from a
 *    browser does not reliably send cached Basic credentials on WS
 *    upgrade requests, and the per-tab token is a strictly stronger
 *    credential anyway.
 *  - On `connection`, the proxy opens a server-side socket.io-client to
 *    `http://127.0.0.1:<port>` (the pty-server subprocess), passing the
 *    same `auth.token` onward. pty-server validates the token a second
 *    time, so even a misconfigured `PTY_HOST=0.0.0.0` does not expose an
 *    unauthenticated shell.
 *  - Events `data` / `input` / `resize` / `logout` / `disconnect` are
 *    forwarded in both directions. The proxy is purely a bridge — it
 *    does not interpret the stream.
 *
 * Dev mode (`next dev`) does NOT use this file. In dev the spawn endpoint
 * returns `mode: "direct"` and the browser connects to 127.0.0.1:<port>
 * directly, which works because dev is always local.
 *
 * Why .mjs and not .ts? This file runs as a plain Node entry point
 * outside the Next.js build. Loading TS at runtime would require jiti
 * (an extra runtime dep) or --experimental-strip-types (still flaky on
 * interop). Plain ESM works natively. The trade-off is that we access
 * the wetty registry via `globalThis` directly instead of importing
 * `@/lib/wetty-manager` — the registry is just a Map on globalThis, and
 * since this process IS the Next.js server (Next's handler runs inside
 * our http.Server), both code paths share the same globalThis. The
 * `WettyEntry` shape duplicated below MUST stay in sync with
 * `lib/wetty-manager.ts`.
 */

import { createServer } from "node:http";
import { parse } from "node:url";
import { timingSafeEqual } from "node:crypto";
import next from "next";
import { Server as IoServer } from "socket.io";
import { io as ioClient } from "socket.io-client";

const hostname = process.env.PI_WEB_HOSTNAME || "127.0.0.1";
const port = Number(process.env.PORT || "30141");
const dev = process.env.NODE_ENV !== "production";

// --- wetty registry access (mirrors lib/wetty-manager.ts) ---------------
// WettyEntry shape MUST match lib/wetty-manager.ts. We read it directly
// from globalThis instead of importing the TS module — see file header.
function getWettyHandle(id) {
  const registry = globalThis.__piWettyRegistry;
  if (!registry) return null;
  const entry = registry.get(id);
  if (!entry) return null;
  return { id: entry.id, port: entry.port, cwd: entry.cwd, authToken: entry.authToken };
}

function markWettyProxyActive() {
  globalThis.__piWettyProxyActive = true;
}

function constantTimeEqual(a, b) {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ba.length !== bb.length) return false;
  return timingSafeEqual(ba, bb);
}

// --- main ---------------------------------------------------------------
const app = next({ dev, hostname, port });
const handle = app.getRequestHandler();
await app.prepare();

const server = createServer((req, res) => {
  try {
    const parsedUrl = parse(req.url || "/", true);
    handle(req, res, parsedUrl);
  } catch (err) {
    res.statusCode = 500;
    res.end(`Internal Server Error: ${err instanceof Error ? err.message : String(err)}`);
  }
});

const io = new IoServer(server, {
  path: "/api/terminal/socket.io",
  // WebSocket only — polling would route through Next's handler and
  // re-trigger proxy.ts middleware, which we don't want. WebSockets are
  // also dramatically better for terminal latency.
  transports: ["websocket"],
  // Per-connection ping keeps NAT/proxy idle timeouts from killing the
  // socket behind frp / nginx. pty-server uses 3s/7s; match it.
  pingInterval: 3000,
  pingTimeout: 7000,
  // Do not serve the socket.io client bundle — the browser already has
  // socket.io-client from pi-web's own JS bundle.
  serveClient: false,
  cors: { origin: false },
});

// Auth middleware: validate per-tab token before any pty traffic flows.
// The token was generated by spawnWetty() and handed to the browser via
// the spawn endpoint response; the browser sends it back as `auth.token`.
io.use((socket, next) => {
  const auth = (socket.handshake?.auth ?? {});
  const id = typeof auth.id === "string" ? auth.id : null;
  const token = typeof auth.token === "string" ? auth.token : null;
  if (!id || !token) {
    return next(new Error("missing id or token"));
  }
  const entry = getWettyHandle(id);
  if (!entry) {
    return next(new Error("unknown terminal id"));
  }
  if (!constantTimeEqual(token, entry.authToken)) {
    return next(new Error("invalid token"));
  }
  socket.data = { handle: entry };
  next();
});

io.on("connection", (downstream) => {
  const handle = downstream.data.handle;
  const upstream = ioClient(`http://127.0.0.1:${handle.port}`, {
    path: "/socket.io",
    transports: ["websocket"],
    reconnection: false,
    // Pass the same per-tab token onward; pty-server validates it again.
    auth: { token: handle.authToken },
    // Give up quickly if pty-server is gone (race with killWetty).
    timeout: 5000,
  });

  // Browser → pty
  downstream.on("input", (input) => upstream.emit("input", input));
  downstream.on("resize", (size) => upstream.emit("resize", size));

  // pty → browser
  upstream.on("data", (d) => downstream.emit("data", d));
  upstream.on("logout", () => downstream.emit("logout"));
  upstream.on("disconnect", () => downstream.disconnect(true));

  // Tear down the other side whenever either end goes away.
  downstream.on("disconnect", () => {
    try { upstream.close(); } catch { /* already closed */ }
  });
  upstream.on("connect_error", () => {
    try { downstream.emit("data", "\r\n\x1b[31m[pty-server unreachable]\x1b[0m\r\n"); } catch { /* gone */ }
    downstream.disconnect(true);
  });
});

// Tell the spawn endpoint that the proxy is live, so it returns
// `mode: "proxy"` instead of `mode: "direct"` + `port`. This MUST be set
// before the server starts listening, so the first spawn request after
// boot never sees a stale "direct" response.
markWettyProxyActive();

server.listen(port, hostname, () => {
  // bin/pi-web.js watches stdout for "Ready" to know when to open the
  // browser. Keep that substring in the log line.
  console.log(`  ▲ Next.js ${dev ? "dev" : "start"}    Ready in`);
  console.log(`        - Local:        http://${hostname}:${port}`);
});

// Forward signals so `pi-web --stop` and Ctrl-C in detach mode shut the
// server down gracefully. We close the socket.io server first so no new
// terminal connections are accepted during shutdown, then close the HTTP
// server and exit. We deliberately do NOT re-raise the signal — that
// would re-enter this handler.
let shuttingDown = false;
const shutdown = () => {
  if (shuttingDown) return;
  shuttingDown = true;
  try { io.close(); } catch { /* ignore */ }
  server.close(() => process.exit(0));
  // Hard exit if graceful close hangs (e.g. stuck keep-alive sockets).
  setTimeout(() => process.exit(0), 2000).unref();
};
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
