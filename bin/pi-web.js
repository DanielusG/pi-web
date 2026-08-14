#!/usr/bin/env node
"use strict";

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { getUnsupportedNodeVersionMessage, isNodeVersionSupported } = require("./node-version");

if (!isNodeVersionSupported(process.versions.node)) {
  console.error(getUnsupportedNodeVersionMessage(process.versions.node));
  process.exit(1);
}

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { spawn, spawnSync, execSync } = require("child_process");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const path = require("path");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const fs = require("fs");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const os = require("os");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { parseLaunchOptions } = require("./pi-web-options");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const pkg = require("../package.json");

const pkgDir = path.join(__dirname, "..");
const nextDir = path.join(pkgDir, ".next");

const DATA_DIR = path.join(os.homedir(), ".pi-web");
const PID_FILE = path.join(DATA_DIR, "pi-web.pid");
const LOG_FILE = path.join(DATA_DIR, "pi-web.log");
const LABEL = "com.axello.pi-web";
const PLIST_PATH = path.join(os.homedir(), "Library", "LaunchAgents", `${LABEL}.plist`);

const options = parseLaunchOptions();

if (options.help) {
  printHelp();
  process.exit(0);
}

if (options.version) {
  console.log(pkg.version);
  process.exit(0);
}

// --- action flag dispatch (must exit before foreground startup) ---
if (options.stop) {
  handleStop();
  process.exit(0);
}
if (options.restart) {
  handleRestart();
  return;
}
if (options.logs) {
  handleLogs();
  return;
}
if (options.status) {
  handleStatus();
  process.exit(0);
}
if (options.uninstall) {
  handleUninstall();
  process.exit(0);
}
if (options.install) {
  handleInstall();
  process.exit(0);
}
if (options.detach) {
  handleDetach();
  return;
}
if (options.pm2) {
  handlePm2();
  process.exit(0);
}

// --- foreground startup (original behavior) ---
const { port, hostname, openBrowser } = options;
const loopbackHostnames = new Set(["127.0.0.1", "localhost", "::1", "[::1]"]);
const passwordEnabled = Boolean(process.env.PI_WEB_PASSWORD);

if (!fs.existsSync(nextDir)) {
  console.error("Build artifacts not found. Please report this issue.");
  process.exit(1);
}

if (!loopbackHostnames.has(hostname)) {
  if (passwordEnabled) {
    console.warn(
      `Warning: pi-web is listening on ${hostname} with Basic Auth over HTTP. Use HTTPS or a trusted VPN to protect the password in transport.`,
    );
  } else {
    console.warn(
      `Warning: pi-web is listening on ${hostname} without authentication. Only use this on a trusted network.`,
    );
  }
}

// Use the custom server (server.mjs) instead of `next start`. The custom
// server mounts a same-origin socket.io proxy at /api/terminal/socket.io
// so the terminal integration works behind reverse proxies (frp, nginx,
// …). `next start` would still work for everything else but would leave
// the terminal browser client trying to reach 127.0.0.1:<port> directly,
// which breaks the moment pi-web is not on the browser's own machine.
const serverEntry = path.join(pkgDir, "server.mjs");

const child = spawn(process.execPath, [serverEntry], {
  cwd: pkgDir,
  stdio: ["inherit", "pipe", "inherit"],
  env: {
    ...process.env,
    PI_WEB_HOSTNAME: hostname,
    PORT: port,
    // server.mjs reads NODE_ENV to pick dev vs production code paths.
    // For `pi-web` (the published binary) this is always production.
    NODE_ENV: process.env.NODE_ENV || "production",
  },
});

// Forward SIGTERM/SIGINT to the server.mjs child so that `pi-web --stop`
// and Ctrl-C in detach mode shut down the server gracefully.
const shutdown = (signal) => {
  try {
    child.kill(signal);
  } catch {}
  setTimeout(() => process.exit(0), 1000);
};
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

let browserOpened = false;
const url = `http://${hostname}:${port}`;

child.stdout.on("data", (chunk) => {
  const text = chunk.toString();
  process.stdout.write(text);
  if (openBrowser && !browserOpened && text.includes("Ready")) {
    browserOpened = true;
    const isWindows = process.platform === "win32";
    const isMac = process.platform === "darwin";
    const openCmd = isWindows ? "start" : isMac ? "open" : "xdg-open";
    const opener = spawn(openCmd, [url], {
      shell: isWindows,
      stdio: "ignore",
      detached: true,
    });

    opener.on("error", (error) => {
      console.warn(`Could not open browser automatically: ${error.message}`);
    });

    opener.unref();
  }
});

child.on("exit", (code) => process.exit(code ?? 0));

// --- action handlers ---

function ensureDataDir() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

function buildSpawnArgs() {
  const args = process.argv.slice(2).filter((a) => a !== "--detach" && a !== "--pm2");
  if (!args.includes("--no-open")) args.push("--no-open");
  return args;
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function handleDetach() {
  ensureDataDir();
  const logFd = fs.openSync(LOG_FILE, "a");
  const spawnArgs = buildSpawnArgs();
  const childProc = spawn(process.execPath, [__filename, ...spawnArgs], {
    detached: true,
    stdio: ["ignore", logFd, logFd],
    env: process.env,
  });

  setTimeout(() => {
    fs.closeSync(logFd);
    if (isProcessAlive(childProc.pid)) {
      fs.writeFileSync(PID_FILE, String(childProc.pid));
      console.log(`pi-web started in background (pid=${childProc.pid})`);
      console.log(`  log:  ${LOG_FILE}`);
      console.log(`  stop: pi-web --stop`);
    } else {
      console.error("pi-web failed to start. Recent log output:");
      try {
        const log = fs.readFileSync(LOG_FILE, "utf8");
        const lines = log.split("\n").filter(Boolean).slice(-20);
        console.error(lines.join("\n"));
      } catch {}
      process.exit(1);
    }
    childProc.unref();
    process.exit(0);
  }, 2000);
}

function handleStop() {
  if (!fs.existsSync(PID_FILE)) {
    console.log("pi-web background instance is not running (no PID file).");
    if (process.platform === "darwin") {
      try {
        execSync(`launchctl print gui/${process.getuid()}/${LABEL}`, { stdio: ["pipe", "pipe", "pipe"] });
        console.log("Note: launchd auto-start is installed. Use 'pi-web --uninstall' to remove it.");
      } catch {}
    }
    return;
  }

  let pid;
  try {
    pid = parseInt(fs.readFileSync(PID_FILE, "utf8").trim(), 10);
  } catch {
    console.error("Failed to read PID file. Removing it.");
    try { fs.unlinkSync(PID_FILE); } catch {}
    return;
  }

  if (!isProcessAlive(pid)) {
    console.log(`pi-web was not running (stale PID file for pid=${pid}). Removing.`);
    try { fs.unlinkSync(PID_FILE); } catch {}
    return;
  }

  try {
    process.kill(pid, "SIGTERM");
    console.log(`pi-web stopped (pid=${pid}).`);
  } catch (e) {
    console.error(`Failed to stop pid=${pid}: ${e.message}`);
    process.exit(1);
  }

  try { fs.unlinkSync(PID_FILE); } catch {}
}

function handleRestart() {
  handleStop();
  handleDetach();
}

function handleLogs() {
  if (!fs.existsSync(LOG_FILE)) {
    console.error(`No log file found at ${LOG_FILE}.`);
    console.error("Start pi-web in background first: pi-web --detach");
    process.exit(1);
  }

  const isWindows = process.platform === "win32";
  let tail;
  if (isWindows) {
    tail = spawn("powershell", ["-NoProfile", "-Command", `Get-Content -Wait -Tail 100 '${LOG_FILE}'`], {
      stdio: "inherit",
    });
  } else {
    tail = spawn("tail", ["-n", "100", "-f", LOG_FILE], {
      stdio: "inherit",
    });
  }

  const stop = (signal) => {
    try { tail.kill(signal); } catch {}
    setTimeout(() => process.exit(0), 200);
  };
  process.on("SIGINT", () => stop("SIGINT"));
  process.on("SIGTERM", () => stop("SIGTERM"));

  tail.on("exit", (code) => process.exit(code ?? 0));
}

function handleStatus() {
  console.log("pi-web status:");

  if (fs.existsSync(PID_FILE)) {
    try {
      const pid = parseInt(fs.readFileSync(PID_FILE, "utf8").trim(), 10);
      if (isProcessAlive(pid)) {
        console.log(`  background (--detach): running (pid=${pid})`);
      } else {
        console.log(`  background (--detach): stale PID file (pid=${pid} not alive)`);
      }
    } catch {
      console.log("  background (--detach): unreadable PID file");
    }
  } else {
    console.log("  background (--detach): not running");
  }

  const portNum = options.port;
  try {
    const out = execSync(`lsof -ti:${portNum}`, { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] }).trim();
    if (out) {
      console.log(`  port ${portNum}: in use (pid=${out.split("\n").join(", ")})`);
    } else {
      console.log(`  port ${portNum}: free`);
    }
  } catch {
    console.log(`  port ${portNum}: free`);
  }

  if (process.platform === "darwin") {
    try {
      execSync(`launchctl print gui/${process.getuid()}/${LABEL}`, { stdio: ["pipe", "pipe", "pipe"] });
      console.log(`  launchd: installed and loaded (${LABEL})`);
    } catch {
      if (fs.existsSync(PLIST_PATH)) {
        console.log(`  launchd: plist exists but not loaded (${LABEL})`);
      } else {
        console.log("  launchd: not installed");
      }
    }
  }

  try {
    const out = execSync("pm2 jlist", { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] });
    const list = JSON.parse(out);
    const entry = list.find((e) => e.name === "pi-web");
    if (entry) {
      console.log(`  pm2: managed (pid=${entry.pid || "n/a"}, status=${entry.pm2_env?.status || "unknown"})`);
    } else {
      console.log("  pm2: not managed");
    }
  } catch {
    console.log("  pm2: not available");
  }
}

function escapeXml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function buildPlist() {
  const programArgs = [process.execPath, __filename, "--no-open"];
  if (options.port !== "30141") programArgs.push("--port", options.port);
  if (options.hostname !== "127.0.0.1") programArgs.push("--hostname", options.hostname);

  const envKeys = [
    "PATH", "HOME", "LANG",
    "PI_WEB_PASSWORD", "PI_WEB_HOSTNAME", "PI_WEB_ALLOWED_HOSTS",
    "PI_CODING_AGENT_DIR", "PORT",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
  ];
  const envEntries = [];
  for (const key of envKeys) {
    if (process.env[key] !== undefined) {
      envEntries.push(`    <key>${escapeXml(key)}</key>\n    <string>${escapeXml(process.env[key])}</string>`);
    }
  }

  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
${programArgs.map((a) => `    <string>${escapeXml(a)}</string>`).join("\n")}
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${escapeXml(LOG_FILE)}</string>
  <key>StandardErrorPath</key>
  <string>${escapeXml(LOG_FILE)}</string>
  <key>EnvironmentVariables</key>
  <dict>
${envEntries.join("\n")}
  </dict>
</dict>
</plist>`;
}

function handleInstall() {
  if (process.platform !== "darwin") {
    console.error("Auto-start via launchd is only supported on macOS.");
    console.error("On this platform, use: pi-web --pm2");
    process.exit(1);
  }

  ensureDataDir();
  fs.mkdirSync(path.dirname(PLIST_PATH), { recursive: true });

  try {
    execSync(`launchctl bootout gui/${process.getuid()}/${LABEL}`, { stdio: ["pipe", "pipe", "pipe"] });
  } catch {}

  const plist = buildPlist();
  fs.writeFileSync(PLIST_PATH, plist, "utf8");

  try {
    execSync(`launchctl bootstrap gui/${process.getuid()} "${PLIST_PATH}"`, { stdio: ["pipe", "pipe", "pipe"] });
  } catch {
    try {
      execSync(`launchctl load -w "${PLIST_PATH}"`, { stdio: ["pipe", "pipe", "pipe"] });
    } catch (e) {
      console.error("Failed to load launchd job:", e.message);
      console.error(`Plist written to ${PLIST_PATH}.`);
      console.error(`Try loading manually: launchctl load -w "${PLIST_PATH}"`);
      process.exit(1);
    }
  }

  console.log("pi-web auto-start installed.");
  console.log(`  log:   ${LOG_FILE}`);
  console.log(`  plist: ${PLIST_PATH}`);
  console.log("  The service starts on login and restarts if it crashes.");
  console.log("  Stop and remove with: pi-web --uninstall");
}

function handleUninstall() {
  if (process.platform !== "darwin") {
    console.error("Auto-start via launchd is only supported on macOS.");
    console.error("On this platform, use: pm2 delete pi-web && pm2 save");
    process.exit(1);
  }

  let stopped = false;
  try {
    execSync(`launchctl bootout gui/${process.getuid()}/${LABEL}`, { stdio: ["pipe", "pipe", "pipe"] });
    stopped = true;
  } catch {
    try {
      execSync(`launchctl unload -w "${PLIST_PATH}"`, { stdio: ["pipe", "pipe", "pipe"] });
      stopped = true;
    } catch {}
  }

  try {
    fs.unlinkSync(PLIST_PATH);
    console.log("pi-web auto-start uninstalled (service stopped, plist removed).");
  } catch {
    if (stopped) {
      console.log("pi-web auto-start uninstalled (service stopped).");
    } else {
      console.log("pi-web auto-start was not installed.");
    }
  }
}

function handlePm2() {
  try {
    execSync("pm2 --version", { stdio: ["pipe", "pipe", "pipe"] });
  } catch {
    console.error("pm2 is not installed.");
    console.error("Install it with: npm install -g pm2");
    process.exit(1);
  }

  ensureDataDir();

  const spawnArgs = process.argv.slice(2).filter((a) => a !== "--pm2");
  if (!spawnArgs.includes("--no-open")) spawnArgs.push("--no-open");

  try {
    execSync("pm2 delete pi-web", { stdio: ["pipe", "pipe", "pipe"] });
  } catch {}

  const result = spawnSync("pm2", ["start", __filename, "--name", "pi-web", "--", ...spawnArgs], {
    stdio: "inherit",
  });

  if (result.status !== 0) {
    console.error("Failed to start pi-web via pm2.");
    process.exit(result.status ?? 1);
  }

  console.log("");
  console.log("pi-web started via pm2.");
  console.log("  logs:      pm2 logs pi-web");
  console.log("  stop:      pm2 stop pi-web");
  console.log("  restart:   pm2 restart pi-web");
  console.log("  auto-start: pm2 save && pm2 startup");
}

function printHelp() {
  console.log(`Usage: pi-web [options]

Local web UI for the pi coding agent.

Options:
  -p, --port <port>         custom port (default: 30141)
  -H, --hostname <addr>     bind address (default: 127.0.0.1)
      --no-open             do not open the browser automatically
      --detach              run in background (PID + log at ~/.pi-web/)
      --stop                stop the background instance
      --restart             restart the background instance
      --status              show running state, port usage, auto-start status
      --logs                tail background logs (Ctrl-C to exit)
      --install             macOS: install launchd auto-start (login + crash restart)
      --uninstall           macOS: remove launchd auto-start
      --pm2                 run via pm2 (requires: npm i -g pm2)
  -v, --version             print version and exit
  -h, --help                show this help message

Environment variables:
  PORT                      same as --port
  PI_WEB_HOSTNAME           same as --hostname
  PI_WEB_ALLOWED_HOSTS      comma-separated allowed proxy hostnames
  PI_WEB_PASSWORD           require Basic Auth (user: pi)
  PI_WEB_NO_OPEN=1          same as --no-open
  PI_CODING_AGENT_DIR       pi agent data directory (default: ~/.pi/agent)
  HTTP_PROXY / HTTPS_PROXY / NO_PROXY
                            proxy for server-side model/API requests

Examples:
  pi-web                    start in foreground
  pi-web -p 8080 -H 0.0.0.0 custom port and bind address
  pi-web --detach           run in background
  pi-web --restart          restart the background instance
  pi-web --logs             follow background logs
  pi-web --install          install auto-start on login (macOS)
  pi-web --status           check if running
  pi-web --version          print version

Documentation: https://github.com/AxelWT/pi-web#readme`);
}
