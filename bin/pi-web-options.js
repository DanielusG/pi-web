"use strict";

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { parseArgs } = require("util");

const TRUE_VALUES = new Set(["1", "true", "yes", "on"]);

function isEnabled(value) {
  return typeof value === "string" && TRUE_VALUES.has(value.trim().toLowerCase());
}

function parseLaunchOptions(args = process.argv.slice(2), env = process.env) {
  const { values: cliArgs } = parseArgs({
    args,
    options: {
      port:      { type: "string", short: "p" },
      hostname:  { type: "string", short: "H" },
      "no-open": { type: "boolean" },
      "detach":  { type: "boolean" },
      "stop":    { type: "boolean" },
      "restart": { type: "boolean" },
      "status":  { type: "boolean" },
      "logs":    { type: "boolean" },
      "install": { type: "boolean" },
      "uninstall": { type: "boolean" },
      "pm2":     { type: "boolean" },
      "version": { type: "boolean", short: "v" },
      "help":    { type: "boolean", short: "h" },
    },
    strict: false,
  });

  return {
    port: cliArgs.port ?? env.PORT ?? "30141",
    hostname: cliArgs.hostname ?? env.PI_WEB_HOSTNAME ?? "127.0.0.1",
    openBrowser: !cliArgs["no-open"] && !isEnabled(env.PI_WEB_NO_OPEN),
    detach: Boolean(cliArgs.detach),
    stop: Boolean(cliArgs.stop),
    restart: Boolean(cliArgs.restart),
    status: Boolean(cliArgs.status),
    logs: Boolean(cliArgs.logs),
    install: Boolean(cliArgs.install),
    uninstall: Boolean(cliArgs.uninstall),
    pm2: Boolean(cliArgs.pm2),
    version: Boolean(cliArgs.version),
    help: Boolean(cliArgs.help),
  };
}

module.exports = { parseLaunchOptions };
