"use client";

import { useEffect, useRef, useState, useCallback } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import { io } from "socket.io-client";
import "@xterm/xterm/css/xterm.css";
import { useI18n } from "@/hooks/useI18n";
import { useTheme, type EffectiveTheme } from "@/hooks/useTheme";

interface Props {
  /** Server-side id (the [id] segment used for POST/DELETE). */
  terminalId: string;
  cwd: string;
}

interface TerminalInfo {
  enabled: boolean;
  reason?: string | null;
}

interface SpawnResponse {
  id: string;
  cwd: string;
  mode: "proxy" | "direct";
  wsToken: string;
  /** Only present in direct mode. */
  port?: number;
}

type Status =
  | { kind: "loading" }
  | { kind: "spawning" }
  | { kind: "ready"; mode: "proxy" | "direct"; port?: number; wsToken: string }
  | { kind: "error"; message: string }
  | { kind: "disabled"; reason?: string | null };

/** xterm.js theme colors matching pi-web's light palette. */
const LIGHT_THEME = {
  background: "#ffffff",
  foreground: "#1a1a1a",
  cursor: "#1a1a1a",
  cursorAccent: "#ffffff",
  selection: "#e8e8e8aa",
  black: "#1a1a1a",
  red: "#cd3131",
  green: "#0dbc79",
  yellow: "#b58900",
  blue: "#2563eb",
  magenta: "#bc3fbc",
  cyan: "#11a8cd",
  white: "#e8e8e8",
  brightBlack: "#6b7280",
  brightRed: "#f14c4c",
  brightGreen: "#23d18b",
  brightYellow: "#e5e510",
  brightBlue: "#3b8eea",
  brightMagenta: "#d670d6",
  brightCyan: "#29b8db",
  brightWhite: "#1a1a1a",
};

/** xterm.js theme colors matching pi-web's dark palette. */
const DARK_THEME = {
  background: "#1a1a1a",
  foreground: "#e8e8e8",
  cursor: "#e8e8e8",
  cursorAccent: "#1a1a1a",
  selection: "#383838aa",
  black: "#1a1a1a",
  red: "#f14c4c",
  green: "#23d18b",
  yellow: "#e5e510",
  blue: "#60a5fa",
  magenta: "#d670d6",
  cyan: "#29b8db",
  white: "#e8e8e8",
  brightBlack: "#9ca3af",
  brightRed: "#f14c4c",
  brightGreen: "#23d18b",
  brightYellow: "#f5f543",
  brightBlue: "#93c5fd",
  brightMagenta: "#d670d6",
  brightCyan: "#29b8db",
  brightWhite: "#ffffff",
};

function themeFor(theme: EffectiveTheme) {
  return theme === "dark" ? DARK_THEME : LIGHT_THEME;
}

/**
 * Embeds an xterm.js terminal connected to a pi-web pty-server subprocess.
 * The subprocess is spawned on demand via POST /api/terminal/[id] and killed
 * on unmount via DELETE. The browser speaks socket.io directly to the
 * pty-server on 127.0.0.1 — no iframe, no external wetty frontend.
 *
 * Theme sync: pi-web's resolved theme is applied to the xterm instance at
 * creation, then updated live when the theme changes.
 */
export function TerminalView({ terminalId, cwd }: Props) {
  const { t } = useI18n();
  const { theme } = useTheme();
  const [status, setStatus] = useState<Status>({ kind: "loading" });
  const killedRef = useRef(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);

  const spawn = useCallback(async () => {
    setStatus({ kind: "spawning" });
    try {
      const resp = await fetch(`/api/terminal/${encodeURIComponent(terminalId)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cwd }),
      });
      const data = await resp.json() as SpawnResponse & { error?: string };
      if (!resp.ok || data.error) {
        throw new Error(data.error ?? `HTTP ${resp.status}`);
      }
      if (killedRef.current) {
        await fetch(`/api/terminal/${encodeURIComponent(terminalId)}`, { method: "DELETE" }).catch(() => {});
        return;
      }
      if (data.mode !== "proxy" && data.mode !== "direct") {
        throw new Error(`unexpected spawn mode: ${data.mode}`);
      }
      if (!data.wsToken) {
        throw new Error("spawn response missing wsToken");
      }
      if (data.mode === "direct" && typeof data.port !== "number") {
        throw new Error("direct mode requires port");
      }
      setStatus({
        kind: "ready",
        mode: data.mode,
        port: data.port,
        wsToken: data.wsToken,
      });
    } catch (error) {
      if (killedRef.current) return;
      setStatus({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    }
  }, [terminalId, cwd]);

  useEffect(() => {
    let cancelled = false;
    killedRef.current = false;

    fetch("/api/terminal")
      .then((r) => r.json() as Promise<TerminalInfo>)
      .then((info) => {
        if (cancelled || killedRef.current) return;
        if (!info.enabled) {
          setStatus({ kind: "disabled", reason: info.reason });
          return;
        }
        void spawn();
      })
      .catch((error) => {
        if (cancelled || killedRef.current) return;
        setStatus({ kind: "error", message: error instanceof Error ? error.message : String(error) });
      });

    return () => {
      cancelled = true;
      killedRef.current = true;
      fetch(`/api/terminal/${encodeURIComponent(terminalId)}`, { method: "DELETE" }).catch(() => {});
    };
  }, [terminalId, cwd, spawn]);

  // Stable scalar deps: only re-runs when transitioning into/out of "ready"
  // with a specific mode/port. Avoids re-running on unrelated status changes.
  const readyMode = status.kind === "ready" ? status.mode : null;
  const readyPort = status.kind === "ready" ? status.port ?? null : null;
  const readyToken = status.kind === "ready" ? status.wsToken : null;

  /**
   * Once the pty-server is ready, mount the xterm.js Terminal into the
   * container div and open a socket.io connection. Tears down both on
   * cleanup. The terminal is created with the current resolved theme; a
   * separate effect handles subsequent theme changes.
   *
   * Two connection modes:
   *  - proxy:  same-origin path `/api/terminal/socket.io` served by the
   *            pi-web custom server (server.mjs), which forwards events to
   *            the local pty-server. Works behind reverse proxies / frp.
   *  - direct: connect straight to `http://127.0.0.1:<port>` — used when
   *            the custom server is not running (`next dev` / plain
   *            `next start`), i.e. localhost-only access.
   *
   * In both modes the per-tab `wsToken` from the spawn response is sent as
   * `auth.token` on the socket.io handshake; pty-server rejects any client
   * that does not present the matching token.
   */
  useEffect(() => {
    if (readyMode === null || readyToken === null) return;
    if (readyMode === "direct" && readyPort === null) return;
    const container = containerRef.current;
    if (!container) return;

    const term = new Terminal({
      theme: themeFor(theme),
      fontFamily: "var(--font-mono), ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
      fontSize: 13,
      cursorBlink: true,
      allowProposedApi: true,
    });
    termRef.current = term;
    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(container);
    try { fitAddon.fit(); } catch { /* container not yet sized */ }

    const socket = readyMode === "proxy"
      ? io({
          path: `/api/terminal/socket.io`,
          transports: ["websocket"],
          reconnection: false,
          // server.mjs's io.use() middleware looks up the wetty registry by
          // id, then constant-time-compares the token. Both fields are
          // required — missing id → "missing id or token" rejection.
          auth: { id: terminalId, token: readyToken },
        })
      : io(`http://127.0.0.1:${readyPort}`, {
          path: "/socket.io",
          transports: ["websocket"],
          reconnection: false,
          // pty-server.js only validates `token`, not `id`.
          auth: { token: readyToken },
        });

    const disposeData = term.onData((d: string) => socket.emit("input", d));
    const onData = (d: unknown) => {
      if (killedRef.current) return;
      try { term.write(typeof d === "string" ? d : String(d)); } catch { /* disposed */ }
    };
    socket.on("data", onData);

    const onResize = () => {
      if (killedRef.current) return;
      try {
        fitAddon.fit();
        socket.emit("resize", { cols: term.cols, rows: term.rows });
      } catch { /* ignore */ }
    };
    const observer = new ResizeObserver(onResize);
    observer.observe(container);

    const onEnded = (label: string) => () => {
      if (killedRef.current) return;
      try { term.write(`\r\n\x1b[90m[${label}]\x1b[0m\r\n`); } catch { /* disposed */ }
    };
    socket.on("logout", onEnded("session ended"));
    socket.on("disconnect", onEnded("disconnected"));
    // With reconnection:false, a failed WS upgrade (auth error, pty-server
    // unreachable) fires connect_error but NOT disconnect — without this
    // handler the user would see a blank terminal with no explanation.
    socket.on("connect_error", (err: unknown) => {
      if (killedRef.current) return;
      const msg = err instanceof Error ? err.message : String(err);
      try { term.write(`\r\n\x1b[31m[connection failed: ${msg}]\x1b[0m\r\n`); } catch { /* disposed */ }
    });

    return () => {
      disposeData.dispose();
      socket.off("data", onData);
      socket.off("logout");
      socket.off("disconnect");
      socket.off("connect_error");
      socket.close();
      observer.disconnect();
      try { term.dispose(); } catch { /* ignore */ }
      termRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readyMode, readyPort, readyToken]);

  /**
   * Apply theme changes to the live terminal without rebuilding it.
   */
  useEffect(() => {
    const term = termRef.current;
    if (!term) return;
    term.options.theme = themeFor(theme);
  }, [theme]);

  if (status.kind === "loading" || status.kind === "spawning") {
    return (
      <div style={{ height: "100%", display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-muted)", fontSize: 12 }}>
        {t("terminal.starting")}
      </div>
    );
  }
  if (status.kind === "disabled") {
    return (
      <div style={{ height: "100%", display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-dim)", fontSize: 12, padding: 16, textAlign: "center" }}>
        {status.reason === "disabled"
          ? t("terminal.disabledByConfig")
          : t("terminal.notInstalled")}
      </div>
    );
  }
  if (status.kind === "error") {
    return (
      <div style={{ height: "100%", display: "flex", alignItems: "center", justifyContent: "center", color: "#f87171", fontSize: 12, padding: 16, textAlign: "center" }}>
        {t("terminal.error")}: {status.message}
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      style={{ width: "100%", height: "100%", background: theme === "dark" ? "#1a1a1a" : "#ffffff" }}
    />
  );
}
