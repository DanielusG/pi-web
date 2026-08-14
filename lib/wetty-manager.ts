import { spawn, type ChildProcess } from "child_process";
import { existsSync } from "fs";
import path from "path";
import { randomUUID } from "node:crypto";

declare global {
  var __piWettyRegistry: Map<string, WettyEntry> | undefined;
  // Set by server.mjs once the same-origin socket.io proxy is listening.
  // When absent (e.g. `next dev` / `next start` without the custom server),
  // the spawn endpoint falls back to "direct" mode and returns the
  // pty-server port so the browser can connect on the loopback interface.
  var __piWettyProxyActive: boolean | undefined;
}

interface WettyEntry {
  id: string;
  cwd: string;
  port: number;
  authToken: string;
  process: ChildProcess;
  lastError?: string;
}

interface WettyAvailability {
  available: boolean;
  reason?: "not-installed" | "disabled";
}

function getRegistry(): Map<string, WettyEntry> {
  if (!globalThis.__piWettyRegistry) {
    globalThis.__piWettyRegistry = new Map();
  }
  return globalThis.__piWettyRegistry;
}

/**
 * Detect whether the bundled node-pty native module is usable. We must not
 * actually `require("node-pty")` here — this module is part of the Next.js
 * server bundle (via the terminal API routes), and loading a native module
 * into the server process would break the build. Instead we resolve the
 * package.json path and verify the prebuilt binary (or a from-source build
 * output) is present on disk. The real `require("node-pty")` happens only
 * inside the bin/pty-server.js subprocess, where native modules are safe.
 *
 * `process.getBuiltinModule("module")` (not `import { createRequire }` and not
 * plain `require.resolve`) is essential here: webpack rewrites both of those
 * in the server bundle — `require.resolve` becomes an always-throwing stub for
 * packages it did not bundle (node-pty is in serverExternalPackages), and the
 * `createRequire` import gets constant-folded to undefined. getBuiltinModule is
 * an ordinary method call on the process global, so webpack leaves it alone
 * and we get the genuine Node.js resolver at runtime. Available since Node
 * 22.3.0; pi-web requires >= 22.19.0.
 */
function checkPtyAvailable(): boolean {
  try {
    // process.cwd() is the pi-web package root both in dev and in the
    // published package (bin/pi-web.js spawns `next start` with cwd: pkgDir).
    const nodeModule = process.getBuiltinModule("module") as typeof import("module");
    const nodeRequire = nodeModule.createRequire(path.join(process.cwd(), "package.json"));
    const pkgJsonPath = nodeRequire.resolve("node-pty/package.json");
    const pkgRoot = path.dirname(pkgJsonPath);
    // node-pty 1.1.0 ships prebuilds under prebuilds/<plat>-<arch>/.
    const prebuildDir = path.join(pkgRoot, "prebuilds", `${process.platform}-${process.arch}`);
    if (existsSync(prebuildDir)) return true;
    // From-source build output (Linux without a matching prebuild).
    if (existsSync(path.join(pkgRoot, "build", "Release"))) return true;
    return false;
  } catch {
    return false;
  }
}

export function getWettyAvailability(): WettyAvailability {
  if (process.env.PI_WEB_TERMINAL === "0") {
    return { available: false, reason: "disabled" };
  }
  if (!checkPtyAvailable()) return { available: false, reason: "not-installed" };
  return { available: true };
}

export interface WettyHandle {
  id: string;
  port: number;
  cwd: string;
  authToken: string;
}

/**
 * Look up a registered pty-server by id. Used by the same-origin socket.io
 * proxy (server.mjs) to find the upstream port + auth token for a given tab.
 * Returns null if the entry has been evicted (subprocess exited or never
 * spawned). The caller is responsible for validating any client-supplied
 * auth token against `handle.authToken` before bridging traffic.
 */
export function getWettyHandle(id: string): WettyHandle | null {
  const entry = getRegistry().get(id);
  if (!entry) return null;
  return { id: entry.id, port: entry.port, cwd: entry.cwd, authToken: entry.authToken };
}

/**
 * True when the same-origin socket.io proxy (server.mjs) is mounted on the
 * HTTP server. The spawn endpoint uses this to decide whether to instruct
 * the browser to connect through `/api/terminal/socket.io` (proxy mode,
 * works behind reverse proxies / frp) or directly to `127.0.0.1:<port>`
 * (direct mode, localhost-only fallback for `next dev` / plain
 * `next start`).
 */
export function isWettyProxyActive(): boolean {
  return globalThis.__piWettyProxyActive === true;
}

export function markWettyProxyActive(): void {
  globalThis.__piWettyProxyActive = true;
}

function pickPort(): number {
  const base = Number(process.env.PI_WEB_TERMINAL_PORT_BASE ?? "30142");
  const used = new Set<number>();
  for (const entry of getRegistry().values()) used.add(entry.port);
  for (let offset = 0; offset < 200; offset++) {
    const candidate = base + offset;
    if (!used.has(candidate)) return candidate;
  }
  return base + Math.floor(Math.random() * 1000);
}

/**
 * Wait for the pty-server subprocess to confirm it is listening. We watch
 * stdout for the JSON "pty-server started" log line that `app.listen`'s
 * callback emits — TCP-connecting to the port is not enough because another
 * process may already be bound there, in which case the browser would silently
 * attach to the wrong server. Only stdout from our own subprocess proves it
 * bound successfully.
 *
 * Resolves true on ready, false on timeout or early exit (in which case the
 * caller inspects `entry.lastError` on the registry entry for the cause).
 */
function waitForReady(child: ChildProcess, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    let resolved = false;
    const finish = (ok: boolean) => {
      if (resolved) return;
      resolved = true;
      cleanup();
      resolve(ok);
    };
    const onStdout = (chunk: Buffer) => {
      try {
        const text = chunk.toString("utf8");
        if (text.includes('"pty-server started"')) finish(true);
      } catch { /* ignore decode errors */ }
    };
    const onExit = () => finish(false);
    const timer = setTimeout(() => finish(false), timeoutMs);
    function cleanup() {
      clearTimeout(timer);
      child.stdout?.removeListener("data", onStdout);
      child.removeListener("exit", onExit);
    }
    child.stdout?.on("data", onStdout);
    child.on("exit", onExit);
    // If the process already exited synchronously, onExit will not fire —
    // guard with a synchronous check.
    if (child.exitCode !== null || child.signalCode !== null) finish(false);
  });
}

/**
 * Spawn a pty-server subprocess bound to 127.0.0.1 with the given cwd.
 * The subprocess uses the bundled node-pty + socket.io to spawn a local
 * shell directly — no SSH, no external wetty CLI, no password prompt.
 *
 * The caller supplies `id` so the same id can be used for DELETE without a
 * server→client id remapping step.
 */
export async function spawnWetty(id: string, cwd: string): Promise<WettyHandle> {
  if (getRegistry().has(id)) {
    const existing = getRegistry().get(id)!;
    return { id: existing.id, port: existing.port, cwd: existing.cwd, authToken: existing.authToken };
  }
  if (!checkPtyAvailable()) {
    throw new Error("node-pty native module not available");
  }
  const port = pickPort();
  const host = "127.0.0.1";
  const authToken = randomUUID();
  const ptyServerPath = path.join(process.cwd(), "bin", "pty-server.js");

  const child = spawn(process.execPath, [ptyServerPath], {
    cwd,
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      PTY_PORT: String(port),
      PTY_HOST: host,
      PTY_CWD: cwd,
      PTY_SHELL: process.env.PTY_SHELL || process.env.SHELL || "bash",
      PTY_AUTH_TOKEN: authToken,
    },
    detached: false,
  });
  const entry: WettyEntry = { id, cwd, port, authToken, process: child, lastError: undefined };

  // Capture early stderr for error reporting.
  const earlyErrorChunks: Buffer[] = [];
  const earlyErrorTimer = setTimeout(() => {
    child.stderr?.removeListener("data", onEarlyStderr);
  }, 5_000);
  const onEarlyStderr = (chunk: Buffer) => {
    earlyErrorChunks.push(chunk);
  };
  child.stderr?.on("data", onEarlyStderr);
  child.on("exit", (code, signal) => {
    clearTimeout(earlyErrorTimer);
    if (code !== 0 && code !== null) {
      const msg = Buffer.concat(earlyErrorChunks).toString("utf8").trim();
      entry.lastError = msg || `pty-server exited with code ${code} (signal ${signal})`;
    }
    // Only delete if this entry is still the registered one — otherwise a
    // newer spawn for the same id (e.g. kill→spawn race) has already replaced
    // it and we must not evict the new subprocess from the registry.
    if (getRegistry().get(id) === entry) {
      getRegistry().delete(id);
    }
  });
  getRegistry().set(id, entry);

  // Wait for pty-server to confirm it is listening by watching stdout for the
  // startup log line emitted from inside `app.listen`'s callback. A plain TCP
  // connect would silently succeed against some other process already bound to
  // the same port and hand the browser to the wrong server.
  const ready = await waitForReady(child, 5_000);
  if (!ready) {
    const lastError = entry.lastError;
    killWetty(id);
    if (lastError) throw new Error(`pty-server failed to start: ${lastError}`);
    throw new Error("pty-server did not start listening within 5 seconds");
  }

  return { id, port, cwd, authToken };
}

export function killWetty(id: string): boolean {
  const registry = getRegistry();
  const entry = registry.get(id);
  if (!entry) return false;
  try {
    if (entry.process.killed === false) {
      entry.process.kill("SIGTERM");
      setTimeout(() => {
        if (entry.process.killed === false) {
          try { entry.process.kill("SIGKILL"); } catch { /* already gone */ }
        }
      }, 1_500).unref();
    }
  } catch {
    // ignore
  }
  registry.delete(id);
  return true;
}
